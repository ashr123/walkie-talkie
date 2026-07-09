// Browser walkie-talkie client. Supports both transports the server exposes:
//   - relay : audio streams over a binary WebSocket and the server fans it out.
//   - webrtc: the server relays SDP/ICE only; audio flows peer-to-peer (mesh).
// ...and all three channel modes (multi-channel PTT, global PTT, full-duplex).
//
// Audio quality: 48 kHz fullband. On the relay path we encode Opus via WebCodecs (with in-band FEC)
// when available, falling back to raw 48 kHz PCM otherwise. Each relay frame is prefixed with a
// 1-byte codec tag so receivers can decode whatever the sender used. The WebRTC path uses the
// browser's native Opus, tuned up via SDP + sender bitrate.

import {
	decryptFrame,
	deriveKey,
	E2EE_SCHEME,
	encryptFrame,
	frameDisposition,
	rekeyAction,
	unwrapPassphrase,
	wrapPassphrase
} from './e2ee.js';

// Tiny alias for the regular DOM accessor — NOT jQuery (there is no jQuery in this project).
const byId = id => document.getElementById(id);
const STUN = {iceServers: [{urls: 'stun:stun.l.google.com:19302'}]};

const SAMPLE_RATE = 48000;
const FRAME_US = 20000;          // 20 ms frame, in microseconds
const MONO_BITRATE = 64000;      // fullband voice, high quality
const STEREO_BITRATE = 128000;   // stereo needs ~2x for equivalent quality
const CODEC_OPUS = 1;
const CODEC_PCM = 2;
const OPUS_SUPPORTED = typeof AudioEncoder !== 'undefined' && typeof AudioDecoder !== 'undefined';
const DISPLAY_NAME = /^[A-Za-z0-9_.-]{1,32}$/;   // must match the server's display-name validation
const CHANNEL_NAME = /^[A-Za-z0-9_-]{1,64}$/;    // must match the server's CHANNEL_NAME validation (no '.', unlike display names)
const SERVER_OWNER = 'server';   // ownerId the server stamps on the server-managed "global" room (matches ConnectionService.GLOBAL_CHANNEL_OWNER); no participant owns it
const MAX_ACTIVE_DECODERS = 8;   // cap on per-sender decoders we mix at once (O(N^2) fan-out guard); evict longest-silent
const SILENCE_TTL_MS = 4000;     // close a per-sender lane after this much silence (survives speech gaps + jitter)

const SPEAK_SILENCE_MS = 250;   // a relay speaker stays highlighted until this long passes with no frame (a few 20 ms frames of grace)
const VAD_RMS_THRESHOLD = 0.02; // full-duplex voice-activity gate: highlight a member only when their PCM RMS (normalized 0..1) exceeds this — a rough "actually talking" level (every mic is open in full-duplex). Tune per mic/AGC.

const state = {
	token: null,
	ws: null,
	transport: 'relay',
	mode: 'MULTI_CHANNEL_PTT',
	hifi: false,
	startMuted: false,
	selfId: null,
	ownerId: null,
	audioContext: null,
	captureNode: null,
	micStream: null,
	channels: 1,            // negotiated in setupAudio: 2 if the mic provides stereo, else 1
	transmitting: false,
	connecting: false,      // true while a connect() flow is in flight — guards against double-clicking Connect
	channel: null,          // the channel currently joined (server-confirmed), so a Switch can tell same vs new
	pendingReconnect: false, // set when a transport change requires tearing down + reconnecting (a new session)
	opusEncoder: null,
	captureTs: 0,
	warnedNoOpus: false,
	warnedChannels: false,
	cryptoKey: null,        // AES-256-GCM key for relay E2EE, or null when no passphrase
	keyCheck: null,         // key-check value sent in the join so the server can reject a mismatched passphrase
	passphrase: '',         // raw passphrase backing the current channel key (for the adaptive button's change-detection)
	channelKeyCheck: null,  // the channel's currently-announced key-check (null = unencrypted); a member re-keys to match it
	rekeyPending: false,    // owner changed the passphrase but our current passphrase doesn't match it yet
	txChain: null,          // serializes async frame encryption (send side) so it can't reorder our Opus stream
	warnedDecrypt: false,
	warnedEncryptedNoKey: false,  // warn once if encrypted frames arrive while no passphrase is set
	peers: new Map(),       // remoteId -> RTCPeerConnection (WebRTC)
	members: new Map(),     // id -> displayName
	mutedMembers: new Set(), // ids the owner has muted (server-authoritative; the server also DROPS their relay audio)
	locked: false,          // owner has locked the channel to new members (server-enforced; existing members unaffected)
	// Relay full-duplex: one decode/playback "lane" per sender, keyed by the server-assigned stream index,
	// mixed natively by ctx.destination. The maps relate stream indices to member ids for lifecycle/binding.
	lanes: new Map(),        // stream id (uint8) -> {node, decoder, decoderChannels, decodeTs, rxChain, memberId, lastSeen}
	streamOf: new Map(),     // member id -> stream id
	memberOfStream: new Map(), // stream id -> member id
	laneSweep: null,         // interval id for the silent-lane age-out sweep
	speaking: new Set(),     // ids currently highlighted as "speaking" in the roster
	memberLis: new Map(),    // id -> roster <li>, so speaking/owner state toggles without a full re-render
	speakTimers: new Map(),  // id -> timeout that clears a relay speaker after a short silence
	floorSpeaker: null,      // PTT/global floor holder — drives the highlight when there are no relay frames (WebRTC)
};

function log(message) {
	const el = byId('log');
	const time = new Date().toLocaleTimeString();
	el.textContent += `[${time}] ${message}\n`;
	el.scrollTop = el.scrollHeight;
}

function setStatus(connected, text) {
	byId('statusDot').classList.toggle('on', connected);
	byId('statusText').textContent = text;
}

function isOpen() {
	return state.ws && state.ws.readyState === WebSocket.OPEN;
}

// --- connection -----------------------------------------------------------------------------------

// Derive (or clear) the relay E2EE key + key-check for a (transport, passphrase, mode, channel) and store them
// in state. WebRTC media is already peer-to-peer encrypted, so it never uses a relay key. Shared by connect()
// and applyOrSwitch() so re-keying on a channel switch matches the initial-connect derivation exactly.
async function deriveJoinKey(transport, passphrase, mode, channel) {
	// Global is the server-managed, always-unencrypted room — the server rejects an encrypted global join
	// (ENCRYPTION_NOT_ALLOWED). So drop the key for GLOBAL_PTT regardless of any passphrase still sitting in the
	// field (e.g. when switching INTO global from an encrypted channel), mirroring the Java client's deriveCrypto;
	// otherwise the join would carry a non-null keyCheck and be refused, silently failing the switch.
	const derived = transport === 'relay' && passphrase && mode !== 'GLOBAL_PTT'
		? await deriveKey(passphrase, channel)
		: null;
	state.cryptoKey = derived ? derived.key : null;
	state.keyCheck = derived ? derived.keyCheck : null;
	state.passphrase = derived ? passphrase : '';   // remember what backs the current key, for the adaptive button
}

async function connect() {
	if (state.connecting || isOpen()) {
		return;   // a connect flow is already in progress (or we're connected) — ignore extra clicks
	}
	state.transport = byId('transport').value;
	state.mode = byId('mode').value;
	state.hifi = byId('hifi').checked;
	state.startMuted = byId('startMuted').checked;   // full-duplex only: join with the mic muted
	const display = byId('display').value.trim();
	const channel = byId('channel').value.trim();
	const passphrase = byId('passphrase').value;   // read once; used only on the relay path (E2EE)

	if (!DISPLAY_NAME.test(display)) {
		log('Display name must be 1-32 chars of letters, digits, _ . or - (no spaces).');
		return;
	}
	// A channel name is required (no silent default) — except in global mode, where the field is hidden and the
	// server forces the channel to "global".
	if (state.mode !== 'GLOBAL_PTT' && !CHANNEL_NAME.test(channel)) {
		log('Channel name must be 1-64 chars of letters, digits, _ or - (no spaces).');
		return;
	}

	// crypto.subtle is only exposed in a secure context (HTTPS or localhost). A second device reaching
	// this server over http://<LAN-IP> has no crypto.subtle, so catch that here — before we acquire the
	// mic — and explain it, rather than throwing a cryptic TypeError mid-connect.
	if (state.transport === 'relay' && passphrase
		&& !(window.isSecureContext && window.crypto && crypto.subtle)) {
		log('End-to-end encryption needs a secure context (HTTPS or localhost). '
			+ 'Clear the passphrase to connect without it, or serve the page over HTTPS.');
		return;
	}

	// Commit to a single connect flow: hold the button down for the whole async sequence (login -> mic -> WS),
	// not just from ws.onopen, so rapid double-clicks can't kick off a second login / mic grab / socket.
	state.connecting = true;
	// One button at a time: from the moment a session starts connecting (through being connected) show only
	// Disconnect; cleanup() flips back to Connect when the session ends or the attempt fails.
	byId('connectBtn').hidden = true;
	byId('disconnectBtn').hidden = false;
	try {
		// Login takes no input: it just mints a signed, short-lived token. Identity in a channel is the
		// server-assigned session id; the display name is sent with the join below.
		const res = await fetch('/api/auth/login', {method: 'POST'});
		if (!res.ok) {
			log('Login failed: HTTP ' + res.status);
			cleanup();   // reset the connecting state and flip back to the Connect button
			return;
		}
		const auth = await res.json();
		state.token = auth.token;
		log('Authenticated as ' + display);

		await setupAudio();
		log(state.transport === 'relay'
			? `Relay codec: ${OPUS_SUPPORTED ? 'Opus 48 kHz + FEC' : 'PCM 48 kHz (no WebCodecs)'}`
			: 'WebRTC: Opus 48 kHz (tuned)');

		// End-to-end encryption applies to the relay path only (WebRTC media is already peer-to-peer). The E2EE
		// status is logged once, uniformly, in onJoined (covering this initial join and any later switch).
		await deriveJoinKey(state.transport, passphrase, state.mode, channel);
		state.txChain = Promise.resolve();
		state.warnedDecrypt = false;

		const path = state.transport === 'webrtc' ? '/ws/signal' : '/ws/audio';
		const proto = location.protocol === 'https:' ? 'wss' : 'ws';
		const url = `${proto}://${location.host}${path}?token=${encodeURIComponent(state.token)}`;
		const ws = new WebSocket(url);
		ws.binaryType = 'arraybuffer';
		state.ws = ws;

		ws.onopen = () => {
			state.connecting = false;   // connect flow completed — now connected
			log('WebSocket open (' + state.transport + ')');
			sendCtrl({type: 'join', channel, mode: state.mode, displayName: display, keyCheck: state.keyCheck});
			setStatus(true, 'Connected — ' + state.transport);
			// The Connect/Disconnect buttons were already flipped to "Disconnect only" when the connect flow began.
			// The in-channel controls — Rename, the adaptive Apply/Switch button, the owner dropdown — appear only
			// once the server confirms the join (the Joined snapshot), via onJoined. Revealing them here on a mere
			// socket-open would flash them for a join the server then rejects (e.g. a wrong passphrase →
			// PASSPHRASE_MISMATCH closes the socket right after it opened).
		};
		ws.onmessage = onWsMessage;
		ws.onclose = ev => {
			log('WebSocket closed (' + ev.code + ')');
			const reconnecting = state.pendingReconnect;
			state.pendingReconnect = false;
			cleanup();
			if (reconnecting) {
				connect();   // transport changed — reopen as a NEW session with the current settings
			}
		};
		ws.onerror = () => log('WebSocket error');
	} catch (err) {
		log('Connect error: ' + err.message);
		state.connecting = false;
		if (!state.ws) {
			cleanup();  // tear down any mic / AudioContext acquired before the socket existed
		}
	}
}

function disconnect() {
	// Closing the socket ends the session — the bearer token is stateless and self-expiring, so there is
	// nothing to log out server-side.
	if (state.ws) {
		sendCtrl({type: 'leave'});
		state.ws.close();
	}
}

// One adaptive action for the connected form. SWITCH to a different channel when the channel name changed
// (carrying the chosen mode/passphrase), or APPLY the current channel's properties (mode / passphrase /
// transport) in one click when it hasn't — think of mode+passphrase+transport as the channel's properties.
// A switch is a fresh Join on the same socket ("leave old, join new"), so the session id (and the mic,
// AudioContext and socket) survive and the new Joined snapshot resets per-channel state (resetChannelState in
// onJoined). Changing the TRANSPORT can't be done in place (different socket endpoint + audio pipeline), so it
// reconnects as a new session; connect() re-reads the form, carrying any other pending change into the join.
async function applyOrSwitch() {
	if (state.connecting || !isOpen()) {
		return;
	}
	const transport = byId('transport').value;
	const mode = byId('mode').value;
	const channel = byId('channel').value.trim();
	const passphrase = byId('passphrase').value;
	const display = byId('display').value.trim();
	if (!DISPLAY_NAME.test(display)) {
		log('Display name must be 1-32 chars of letters, digits, _ . or - (no spaces).');
		return;
	}
	const effectiveChannel = mode === 'GLOBAL_PTT' ? 'global' : channel;

	if (transport !== state.transport) {
		log('Transport changed — reconnecting as a new session…');
		state.pendingReconnect = true;
		disconnect();   // ws.onclose -> cleanup -> connect() with the current form values
		return;
	}

	if (effectiveChannel !== state.channel) {
		// Different channel: switch (re-Join), carrying the chosen mode + passphrase. A channel name is required
		// (no silent default) — global is exempt (channel forced to "global" server-side; the field is hidden).
		if (mode !== 'GLOBAL_PTT' && !CHANNEL_NAME.test(channel)) {
			log('Channel name must be 1-64 chars of letters, digits, _ or - (no spaces).');
			return;
		}
		if (transport === 'relay' && passphrase
			&& !(window.isSecureContext && window.crypto && crypto.subtle)) {
			log('End-to-end encryption needs a secure context (HTTPS or localhost). Clear the passphrase to switch.');
			return;
		}
		await deriveJoinKey(transport, passphrase, mode, channel);
		sendCtrl({type: 'join', channel, mode, displayName: display, keyCheck: state.keyCheck});
		log(`Switching to "${effectiveChannel}" (${mode})…`);   // E2EE status follows in onJoined once confirmed
		updateApplyControls();
		return;
	}

	// Same channel & transport: apply this channel's property changes. Mode/passphrase are owner-only (the Mode
	// selector is disabled for non-owners, so a mode change here means we own the channel); a member uses the
	// passphrase path to adopt the owner's announced rotation.
	const iAmOwner = state.selfId === state.ownerId && state.ownerId !== SERVER_OWNER;
	let acted = false;
	if (mode !== state.mode) {
		sendCtrl({type: 'changeMode', mode});
		acted = true;
	}
	if (transport === 'relay' && (passphrase !== (state.passphrase || '') || state.rekeyPending)) {
		if (iAmOwner) {
			await initiatePassphraseChange();   // rotate for everyone; applied on the echo
		} else if (state.channelKeyCheck) {
			// Non-owner adopting the owner's announced rotation: re-derive and verify against the announced KCV.
			log(await applyAnnouncedPassphrase()
				? 'Applied the channel passphrase — you can be heard again.'
				: 'That passphrase does not match the channel — get the owner’s new passphrase and try again.');
		} else {
			// Non-owner on an unencrypted channel: only the owner can turn encryption on. Clear the field so the
			// button settles instead of looping on a silent no-op.
			log('Only the channel owner can change the passphrase.');
			byId('passphrase').value = '';
		}
		acted = true;
	}
	// Ownership transfer is an in-place change too — applied here (not live on dropdown selection), and LAST so any
	// mode/passphrase change above is processed while we still own the channel, before we hand it off.
	if (iAmOwner && byId('ownerSelect').value !== state.ownerId) {
		const newOwnerId = byId('ownerSelect').value;
		sendCtrl({type: 'transferOwnership', newOwnerId});
		log('Transferring ownership to ' + (state.members.get(newOwnerId) || newOwnerId) + '…');
		acted = true;
	}
	if (!acted) {
		log('Nothing to apply.');
	}
	updateApplyControls();
}

// Reset (cancel): restore every editable channel-property field — transport, mode, channel, passphrase, and the
// owner dropdown — to its current live value, so nothing is pending and the Apply/Switch + Reset buttons (and the
// hint) disappear together. Mirrors exactly the fields updateApplyControls compares against state.
function resetApplyControls() {
	if (!isOpen()) {
		return;
	}
	byId('transport').value = state.transport;
	byId('mode').value = state.mode;
	byId('ownerSelect').value = state.ownerId;
	// Restore the channel directly, discarding any stashed pre-global value so updateGlobalModeLocks' global lock
	// can't repopulate it with a stale name; then re-apply the restored mode's locks/visibility below.
	const channelInput = byId('channel');
	delete channelInput.dataset.userValue;
	channelInput.value = state.channel;
	byId('passphrase').value = state.passphrase || '';
	updateGlobalModeLocks();   // re-apply the restored mode's channel/passphrase locks + visibility
	updateApplyControls();     // nothing pending now → Apply/Switch + Reset + hint hide
}

// Reflects whether the adaptive button can act and what it does. Shown only while connected; labeled
// "Switch channel" when the channel name differs from the current one, else "Apply changes"; disabled when
// nothing changed (a pending member re-key keeps it enabled so the new passphrase can still be applied).
function updateApplyControls() {
	const btn = byId('applyBtn');
	const hint = byId('applyHint');
	if (!isOpen()) {
		btn.hidden = true;
		byId('resetBtn').hidden = true;
		hint.hidden = true;
		byId('shareRekeyRow').hidden = true;   // a rotation control — meaningless until connected
		byId('shareRekey').checked = true;     // back to the default-checked (auto-share) state
		return;
	}
	const transport = byId('transport').value;
	const mode = byId('mode').value;
	const channelField = byId('channel').value.trim();
	const effectiveChannel = mode === 'GLOBAL_PTT' ? 'global' : channelField;   // no silent "lobby" default
	const channelChanged = effectiveChannel !== state.channel;
	// A required, valid channel name (global is exempt — its channel is fixed/forced server-side). An empty or
	// malformed name has no valid target, so we never offer a switch to it (the button stays hidden below).
	const channelValid = mode === 'GLOBAL_PTT' || CHANNEL_NAME.test(channelField);
	const passphraseValue = byId('passphrase').value;
	const passphraseChanged = passphraseValue !== (state.passphrase || '');
	const iAmOwner = state.selfId === state.ownerId && state.ownerId !== SERVER_OWNER;
	// A pending OWNERSHIP transfer: the owner picked a different member in the dropdown. It's an in-place action
	// (you can't hand off a channel you're switching away from), so it counts only when the channel is unchanged;
	// it is applied — not sent live on selection — by the adaptive button, alongside any mode/passphrase change.
	const ownerSelectChanged = iAmOwner && !channelChanged && byId('ownerSelect').value !== state.ownerId;
	const changed = channelChanged
		|| transport !== state.transport
		|| mode !== state.mode
		|| transport === 'relay' && passphraseChanged
		|| ownerSelectChanged;
	// No disabled state: the button — and its explanatory hint — appear ONLY when there's something to do (a
	// pending property change, or a member re-key to adopt) AND the channel name is valid, and are hidden
	// otherwise. The label switches between "Switch channel" (the channel name changed → a fresh join) and
	// "Apply changes" (in-place edit).
	const actionable = (changed && channelValid) || state.rekeyPending;
	btn.hidden = !actionable;
	byId('resetBtn').hidden = !actionable;   // Reset (cancel) appears and disappears together with Apply/Switch
	hint.hidden = !actionable;
	btn.textContent = channelChanged ? 'Switch channel' : 'Apply changes';

	// Post-connect, the channel's properties — transport, mode, passphrase — are editable only by the channel
	// OWNER, or by anyone who has changed the channel NAME to switch to a different room (a fresh join, so you
	// pick its properties). A non-owner staying on the current channel can change none of them — the passphrase
	// is the one exception, re-enabling to ADOPT an owner's announced re-key (rekeyPending). In GLOBAL_PTT the
	// passphrase stays locked by updateGlobalModeLocks (encryption isn't allowed there), so leave it alone here.
	const inGlobalRoom = state.ownerId === SERVER_OWNER;
	const lockedForMember = !iAmOwner && !channelChanged;
	// The Mode selector is ALSO the only way to LEAVE the server-managed global room: its channel field is locked
	// to "global" and it has no owner to host an in-place mode change, so a member there could otherwise never
	// switch out (both channel and mode would be locked). Keep Mode editable in global — changing it away from
	// GLOBAL_PTT unlocks the channel field and turns the action into a switch to a regular channel.
	const modeLocked = lockedForMember && !inGlobalRoom;
	byId('transport').disabled = lockedForMember;
	byId('mode').disabled = modeLocked;
	if (lockedForMember) {
		// A locked member always sees the channel's LIVE transport (not a stale pending pick it can't apply).
		byId('transport').value = state.transport;
	}
	if (modeLocked) {
		byId('mode').value = state.mode;
	}
	if (mode !== 'GLOBAL_PTT') {
		byId('passphrase').disabled = lockedForMember && !state.rekeyPending;
	}

	// The "share new passphrase" control is shown to the OWNER setting a NEW, non-empty passphrase on the CURRENT
	// relay channel (a name change is a switch, not a rotation; clearing the field disables encryption — nothing
	// to share). Two cases:
	//  - encrypted→encrypted ROTATION (we hold an OLD key): the box is ENABLED — leave it checked to wrap the new
	//    passphrase under the old key so members auto-adopt, or uncheck for a revocation-style re-key;
	//  - plaintext→encrypted ENABLE (no old key to wrap under): auto-distribution is impossible, so the box is
	//    DISABLED + unchecked and the label tells the owner every member must enter the new passphrase themselves.
	const settingNewPassphrase = iAmOwner && !channelChanged && passphraseChanged && passphraseValue !== ''
		&& transport === 'relay' && mode !== 'GLOBAL_PTT';
	const canAutoShare = state.cryptoKey != null;   // need an OLD key to wrap the new passphrase under
	byId('shareRekeyRow').hidden = !settingNewPassphrase;
	byId('shareRekey').disabled = !canAutoShare;
	byId('shareRekeyText').textContent = canAutoShare
		? 'Share new passphrase with current members (uncheck to require everyone to re-enter it)'
		: 'No existing key to share under — every member must enter the new passphrase themselves';
	if (!settingNewPassphrase) {
		byId('shareRekey').checked = true;    // hidden → back to the auto-share default for the next rotation
	} else if (!canAutoShare) {
		byId('shareRekey').checked = false;   // enable: can't auto-share, so reflect "members must re-enter"
	}
	// (rotation + canAutoShare: leave .checked alone, so a manual uncheck isn't undone on the next keystroke)
}

// The owner dropdown: lists members and lets the current owner hand ownership to another. Selecting a different
// member is a PENDING change applied via "Apply changes" (not sent live); the echoed ownerChanged is what
// actually moves the controls. Shown ONLY to the current owner — everyone else already sees who owns the channel
// via the crown in the members list, so a disabled dropdown would just be confusing noise. Rebuilt with the
// roster (called from renderMembers), which resets the selection to the current owner.
function renderOwnerSelect() {
	const select = byId('ownerSelect');
	const iAmOwner = isOpen() && state.selfId === state.ownerId && state.ownerId !== SERVER_OWNER;
	select.hidden = !iAmOwner;
	byId('ownerLabel').hidden = !iAmOwner;
	if (!iAmOwner) {
		return;
	}
	const pendingPick = select.value;   // the owner's in-progress (un-Applied) selection, captured before the rebuild
	select.replaceChildren(...[...state.members.entries()]
		.sort(([, a], [, b]) => a.localeCompare(b, undefined, {sensitivity: 'base'}))
		.map(([id, name]) => {
			const opt = document.createElement('option');
			opt.value = id;
			// textContent (set via the option's text) avoids markup injection from a crafted display name.
			opt.textContent = `${name} (#${id.slice(0, 8)})` + (id === state.selfId ? ' (you)' : '');
			return opt;
		}));
	// Preserve a pending transfer target across a roster rebuild if that member is still present; fall back to the
	// current owner only when there was no pick yet or the picked member has left. Reverting unconditionally would
	// silently discard an in-progress transfer on any unrelated join/leave/rename. (renderMembers re-runs
	// updateApplyControls after this, so the Apply/Reset buttons re-settle if the pick was dropped.)
	select.value = state.members.has(pendingPick) ? pendingPick : state.ownerId;
	select.disabled = false;   // we are the owner
}


function sendCtrl(obj) {
	if (isOpen()) {
		state.ws.send(JSON.stringify(obj));
	}
}

// --- passphrase rotation (the owner changes the channel's E2EE key for everyone) -------------------

// Owner action (invoked by Apply changes): derive the key-check from the passphrase field for the CURRENT
// channel and ask the server to rotate it for everyone (a blank field clears encryption → a null key-check).
// We do NOT swap our own key here — we apply it when the server echoes passphraseChanged, so a rejected request
// changes nothing.
async function initiatePassphraseChange() {
	if (!isOpen() || byId('transport').value !== 'relay') {
		return;
	}
	const passphrase = byId('passphrase').value;
	if (passphrase && !(window.isSecureContext && window.crypto && crypto.subtle)) {
		log('End-to-end encryption needs a secure context (HTTPS or localhost). Clear the passphrase to turn it off.');
		return;
	}
	const effectiveChannel = state.mode === 'GLOBAL_PTT' ? 'global' : state.channel;
	const derived = passphrase ? await deriveKey(passphrase, effectiveChannel) : null;
	// Auto-distribution: when the owner opted in (the "Share with current members" box) AND we currently hold a
	// key (so this is a rotation, not the plaintext→encrypted enable) AND we're setting a new passphrase, wrap the
	// new passphrase under the OLD key so connected members adopt it automatically. The server relays the blob
	// without ever seeing the passphrase. Opting out (or no old key / disabling) sends no wrap, so members must
	// re-enter it out-of-band — use that for a revocation-style rotation that locks out the old key.
	const wrappedKey = (derived && state.cryptoKey && byId('shareRekey').checked)
		? await wrapPassphrase(passphrase, state.cryptoKey)
		: null;
	sendCtrl({type: 'changePassphrase', keyCheck: derived ? derived.keyCheck : null, wrappedKey});
	log(passphrase
		? wrappedKey
			? 'Requested a re-key — connected members will adopt it automatically…'
			: 'Requested a re-key for everyone (members must enter the new passphrase out-of-band)…'
		: 'Requested encryption OFF for everyone…');
}

// Re-derive the key from the passphrase field and apply it ONLY if it matches the channel's announced key-check.
// Used when a passphraseChanged arrives and when a member re-applies via Apply changes after typing the new
// secret. On a mismatch we keep the old key (or no key) and flag a pending re-key — sendTagged then stays SILENT
// rather than leak plaintext into the now-encrypted channel. Returns whether it applied.
async function applyAnnouncedPassphrase() {
	const passphrase = byId('passphrase').value;
	// Derive only when encryption IS announced and we have a passphrase in a secure context; otherwise leave
	// `derived` null and let rekeyAction decide ('disable' when nothing's announced, 'keep' when we can't match
	// yet). Decide against the LIVE state.channelKeyCheck read AFTER the await, so two rapid rotations can't apply
	// a key that only matched a stale announced value.
	let derived = null;
	if (state.channelKeyCheck != null && passphrase && window.isSecureContext && window.crypto && crypto.subtle) {
		derived = await deriveKey(passphrase, state.mode === 'GLOBAL_PTT' ? 'global' : state.channel);
	}
	const action = rekeyAction(state.channelKeyCheck, derived ? derived.keyCheck : null);
	// `derived` is non-null whenever rekeyAction returns 'apply' (it only does so for a non-null, matching
	// derived.keyCheck). Gate the dereference on `derived` anyway, so it is provably safe to a reader / static
	// analysis and stays safe if rekeyAction ever changes.
	if (action === 'apply' && derived) {
		state.cryptoKey = derived.key;
		state.keyCheck = derived.keyCheck;
		state.passphrase = passphrase;
		state.rekeyPending = false;
		updateApplyControls();
		return true;
	}
	if (action === 'disable') {
		state.cryptoKey = null;
		state.keyCheck = null;
		state.passphrase = '';
		state.rekeyPending = false;
		updateApplyControls();
		return true;
	}
	// 'keep' — we hold no matching key yet; stay muted (sendTagged drops) until the user re-keys.
	state.rekeyPending = true;
	updateApplyControls();
	return false;
}

// Server told us (and everyone) the channel's passphrase changed. Record the new key-check and try to apply it
// from whatever is in the passphrase field — seamless for the owner who just set it (and any member who
// pre-entered the new secret), a clear prompt for everyone else.
async function onPassphraseChanged(keyCheck, wrappedKey) {
	state.channelKeyCheck = keyCheck;
	// A new key era — re-arm the one-shot "could not decrypt" notice so a member who misses THIS rotation gets a
	// fresh cue (the flag is otherwise set on the first failure and never cleared until reconnect).
	state.warnedDecrypt = false;
	if (keyCheck == null) {
		state.cryptoKey = null;
		state.keyCheck = null;
		state.passphrase = '';
		state.rekeyPending = false;
		updateApplyControls();
		log('The owner turned end-to-end encryption OFF for this channel.');
		return;
	}
	// Auto-adopt: if the owner shared the new passphrase wrapped under the OLD key (which we still hold), unwrap
	// it, confirm it derives the announced key-check, and adopt silently — seamless for everyone who held the old
	// key (including the owner echoing their own rotation). Compare against the LIVE state.channelKeyCheck read
	// AFTER the awaits, so a second rotation racing in can't make us adopt a key that only matched a stale value.
	if (wrappedKey && state.cryptoKey) {
		try {
			const passphrase = await unwrapPassphrase(wrappedKey, state.cryptoKey);
			const derived = await deriveKey(passphrase, state.mode === 'GLOBAL_PTT' ? 'global' : state.channel);
			if (derived.keyCheck === state.channelKeyCheck) {
				state.cryptoKey = derived.key;
				state.keyCheck = derived.keyCheck;
				state.passphrase = passphrase;
				state.rekeyPending = false;
				byId('passphrase').value = passphrase;
				updateApplyControls();
				log('The channel passphrase changed — re-keyed automatically.');
				return;
			}
		} catch {
			// The blob wasn't wrapped under our (old) key, or was tampered/superseded — fall back to manual.
		}
	}
	const applied = await applyAnnouncedPassphrase();
	log(applied
		? 'The channel passphrase changed — re-keyed, E2EE updated.'
		: 'The owner changed the passphrase — enter the new one above and click "Apply changes" to keep talking (others won\'t hear you until you do).');
}

// Ask the server to change our display name to the current value of the Display name field. The server
// validates it and, on success, broadcasts memberRenamed (including back to us) — so our own roster label
// updates from that broadcast, not optimistically, and an invalid name surfaces as a server error instead.
function rename() {
	if (!isOpen()) {
		return;
	}
	const display = byId('display').value.trim();
	if (!DISPLAY_NAME.test(display)) {
		log('Display name must be 1-32 chars of letters, digits, _ . or - (no spaces).');
		return;
	}
	if (display === state.members.get(state.selfId)) {
		return;   // no-op: same as the current name (the button is disabled for this, and the server rejects it)
	}
	sendCtrl({type: 'rename', displayName: display});
}

// Enable Rename only when the Display name field holds a name DIFFERENT from the server-confirmed current one —
// a no-op rename is pointless (and the server rejects it). Called on every keystroke in the field, on join, and
// whenever our own name changes. Only meaningful while connected; the button is hidden (and this no-ops) until then.
function updateRenameButton() {
	const btn = byId('renameBtn');
	if (btn.hidden) {
		return;
	}
	btn.disabled = byId('display').value.trim() === state.members.get(state.selfId);
}

// --- incoming messages ----------------------------------------------------------------------------

function onWsMessage(ev) {
	if (typeof ev.data !== 'string') {
		handleAudioFrame(ev.data);
		return;
	}
	const msg = JSON.parse(ev.data);
	switch (msg.type) {
		case 'joined':
			onJoined(msg);
			break;
		case 'memberJoined':
			addMember(msg.member);
			log('+ ' + msg.member.displayName);
			break;
		case 'memberLeft':
			removeMember(msg.memberId);
			closePeer(msg.memberId);
			break;
		case 'memberRenamed':
			renameMember(msg.memberId, msg.displayName);
			break;
		case 'memberMuted':
			onMemberMuted(msg.memberId, msg.muted);
			break;
		case 'channelLocked':
			onChannelLocked(msg.locked);
			break;
		case 'floorGranted':
			log('Floor granted — you are live');
			beginTransmit();
			break;
		case 'floorDenied':
			log('Floor busy (held by ' + msg.currentHolderId + ')');
			break;
		case 'floorTaken':
			// PTT/global: the floor holder is the speaker. On the relay path the per-frame highlight already
			// covers this, so only drive it from the floor on WebRTC, where there are no relay frames to key off.
			if (state.transport === 'webrtc') {
				if (state.floorSpeaker && state.floorSpeaker !== msg.holderId) {
					setSpeaking(state.floorSpeaker, false);
				}
				state.floorSpeaker = msg.holderId;
				setSpeaking(msg.holderId, true);
			}
			log('Talking: ' + (state.members.get(msg.holderId) || msg.holderId));
			// If we were the PTT holder and the floor is now someone else's, the server reassigned it away from
			// us (idle auto-release) — stop our mic and reset the button so we're not talking into a closed floor.
			if (state.transmitting && state.mode !== 'FULL_DUPLEX' && msg.holderId !== state.selfId) {
				endTransmit();
				log('You were released from the floor (idle) — tap Talk to speak again');
			}
			break;
		case 'floorIdle':
			if (state.floorSpeaker) {
				setSpeaking(state.floorSpeaker, false);
				state.floorSpeaker = null;
			}
			log('Floor is free');
			// Server-initiated release while we held it (e.g. the max-hold talk timeout) — stop transmitting.
			if (state.transmitting && state.mode !== 'FULL_DUPLEX') {
				endTransmit();
				log('Your talk time was up — tap Talk to speak again');
			}
			break;
		case 'modeChanged':
			onModeChanged(msg.mode);
			break;
		case 'ownerChanged':
			onOwnerChanged(msg.ownerId);
			break;
		case 'passphraseChanged':
			onPassphraseChanged(msg.keyCheck, msg.wrappedKey).catch(err => log('Passphrase change error: ' + err.message));
			break;
		case 'signalOffer':
			onOffer(msg.from, msg.sdp).catch(err => log('Offer error: ' + err.message));
			break;
		case 'signalAnswer':
			onAnswer(msg.from, msg.sdp).catch(err => log('Answer error: ' + err.message));
			break;
		case 'signalIce':
			onIce(msg.from, msg.candidate, msg.sdpMid, msg.sdpMLineIndex).catch(err => log('ICE error: ' + err.message));
			break;
		case 'error':
			log('Server error [' + msg.code + ']: ' + msg.message);
			// Codes are the shared ErrorCode enum serialized as its constant names; an unrecognized code (a newer
			// server) simply falls through — the log line above already showed it.
			if (msg.code === 'PASSPHRASE_MISMATCH') {
				log('Disconnecting — this channel needs a different passphrase.');
				disconnect();
			} else if (msg.code === 'CHANNEL_LOCKED') {
				// The join was refused because the channel is locked to new members. Like PASSPHRASE_MISMATCH, the
				// join failed — on an initial connect nothing joined, and a locked switch drops us — so disconnect
				// cleanly rather than sit half-joined.
				log('This channel is locked by its owner — you can\'t join it right now.');
				disconnect();
			} else if (msg.code === 'CHANNEL_FULL') {
				// The channel is at its member limit — the join failed the same way as a locked/mismatched one.
				log('This channel is full — it has reached its member limit.');
				disconnect();
			} else if (msg.code === 'NOT_OWNER' || msg.code === 'UNKNOWN_TARGET') {
				// A rejected ownership transfer leaves the dropdown showing the failed target — snap it back to the
				// real owner (state.ownerId is unchanged on a rejection) and settle the Apply button (the pending
				// transfer is gone, so it should hide unless something else is still pending).
				renderOwnerSelect();
				updateApplyControls();
			}
			break;
		default:
			log('Unknown message: ' + msg.type);
	}
}

function onJoined(msg) {
	// A Joined snapshot — whether the initial join or an in-place channel switch — reassigns every stream index
	// and replaces the roster, so fully reset the current-channel state first (drops the previous channel's
	// peers, decode lanes, roster and floor/speaking highlights), then rebuild from this snapshot.
	const channelChanged = msg.channel !== state.channel;
	resetChannelState();
	state.selfId = msg.selfId;
	state.ownerId = msg.ownerId;
	state.mode = msg.mode;
	state.channel = msg.channel;
	state.locked = msg.locked;   // adopt the channel's lock state from the snapshot (covers an in-place re-join too)
	if (channelChanged) {
		// Baseline the announced key-check to what we joined with, and clear any pending rotation — ONLY on an
		// actual channel change. A same-channel idempotent re-snapshot must NOT reset these, or it would revert a
		// pending rotation's announced key-check (KCV) and silently hide the prompt to apply the new passphrase,
		// leaving us mis-keyed with no recovery.
		state.channelKeyCheck = state.keyCheck;
		state.rekeyPending = false;
	}
	msg.members.forEach(addMember);
	renderMembers();
	log(`Joined "${msg.channel}" (${msg.mode}) with ${msg.members.length} member(s)`);
	log(state.ownerId === SERVER_OWNER
		? 'Server-managed global room — everyone can talk (push-to-talk), no owner, no encryption.'
		: state.selfId === state.ownerId
			? 'You own this channel — change mode/passphrase and click Apply, or pick a new owner.'
			: 'Owner: ' + (state.members.get(state.ownerId) || state.ownerId));
	// Report the channel's E2EE status on every confirmed room entry — initial join AND in-place switch, whether
	// we created the channel or joined an existing one — reflecting the key we actually hold for it. (The global
	// room already states "no encryption" in the owner line above, so skip the redundant line there.)
	if (state.transport === 'relay' && state.ownerId !== SERVER_OWNER) {
		log(state.cryptoKey ? 'End-to-end encryption: ON (AES-256-GCM)' : 'End-to-end encryption: off');
	}

	if (state.transport === 'webrtc') {
		msg.members
			.filter(m => m.id !== state.selfId)
			.forEach(m => offerTo(m.id).catch(err => log('Offer error: ' + err.message)));
	}

	if (state.mode === 'FULL_DUPLEX' && !state.startMuted) {
		beginTransmit(); // full-duplex: mic is live as soon as you join (unless "Connect muted")
	} else {
		state.transmitting = false;
		enableLocalTracks(false);
	}
	enableTalkButton(true);
	updateTalkButton();
	updateModeControl();
	updateGlobalModeLocks();
	// Reveal the in-channel controls only now that the server has confirmed the join. Renaming makes sense only in
	// a channel, and showing it here (not on socket-open) avoids flashing it for a join the server rejects.
	byId('renameBtn').hidden = false;
	byId('renameHint').hidden = false;
	updateRenameButton();   // starts disabled — the field matches our just-joined name
	updateApplyControls();
	renderOwnerSelect();
}

function onModeChanged(mode) {
	state.mode = mode;
	state.transmitting = false;
	enableLocalTracks(false);
	if (mode === 'FULL_DUPLEX' && !state.startMuted) {
		beginTransmit(); // full-duplex: the mic goes live immediately (unless "Connect muted")
	}
	updateTalkButton();
	updateModeControl();
	updateGlobalModeLocks();
	updateApplyControls();   // the live mode now matches the selector again → the Apply button settles
	log('Mode changed to ' + mode);
}

function onOwnerChanged(ownerId) {
	const becameOwner = state.selfId === ownerId && state.ownerId !== ownerId;
	state.ownerId = ownerId;
	// Re-render the roster so the crown moves to the new owner AND the owner-only moderation controls (per-member
	// Mute/Unmute + "Mute all") appear for a just-promoted owner and vanish for the demoted one. An explicit
	// TransferOwnership broadcasts only OwnerChanged (no membership churn), so nothing else would refresh them.
	renderMembers();
	updateModeControl();
	// updateModeControl may have snapped the selector back to the live mode (e.g. ownership moved away while a
	// pending GLOBAL_PTT pick was open); re-run the global locks so the channel/passphrase show/hide can't lag the
	// selector and leave the passphrase stuck hidden. (onJoined/onModeChanged pair these two for the same reason.)
	updateGlobalModeLocks();
	renderOwnerSelect();
	updateApplyControls();
	log(state.selfId === ownerId
		? 'You are now the channel owner — you can change the mode/passphrase and pick the next owner.'
		: 'Channel owner is now ' + (state.members.get(ownerId) || ownerId));
	if (becameOwner && state.rekeyPending) {
		// We were promoted while still holding a stale key we never reconciled. As owner, Apply now ROTATES the
		// channel for everyone — so clear the leftover unmatched passphrase and the pending flag, otherwise a
		// click would rotate the whole channel to that stale/garbage text. To set a key, the new owner types a
		// passphrase they actually hold and clicks Apply.
		state.rekeyPending = false;
		byId('passphrase').value = '';
		updateApplyControls();
		log('You became owner while your passphrase was unmatched — it was cleared. Type a passphrase you hold and click Apply to re-key the channel.');
	}
}

// The owner muted or unmuted a member (server-authoritative — the server also DROPS a muted member's relay audio,
// so this is enforcement we merely reflect, not the enforcement itself). Update the roster mark; if WE are the one
// affected, stop transmitting immediately and lock/unlock our talk control so the UI matches what the relay does.
function onMemberMuted(memberId, muted) {
	if (muted) {
		state.mutedMembers.add(memberId);
	} else {
		state.mutedMembers.delete(memberId);
	}
	if (memberId === state.selfId) {
		if (muted) {
			// We were muted. Stop our mic right now (best-effort on WebRTC, whose media is peer-to-peer and can't be
			// relay-enforced; authoritative on the relay path, where onCapturedFrame also gates on state.transmitting).
			// This covers PTT/global (drop the floor locally) and full-duplex (close the always-open mic) alike.
			if (state.transmitting) {
				endTransmit();
				if (state.mode !== 'FULL_DUPLEX') {
					sendCtrl({type: 'releaseFloor'});   // let the floor go so it isn't held idle in our name
				}
			}
			log('You were muted by the channel owner — you cannot talk until unmuted.');
		} else {
			log('You were unmuted by the channel owner — tap Talk to speak again.');
		}
		updateTalkButton();   // re-render the talk control's disabled state + label for the new mute state
	} else {
		log((state.members.get(memberId) || memberId) + (muted ? ' was muted' : ' was unmuted') + ' by the owner');
	}
	// Re-render the roster: the muted mark and (for the owner) each row's Mute/Unmute label follow state.mutedMembers.
	renderMembers();
}

// The owner locked or unlocked the channel to new members (server-enforced at join; existing members, us included,
// are unaffected). Reflect it: everyone sees the 🔒 indicator, and the owner's toggle button relabels.
function onChannelLocked(locked) {
	state.locked = locked;
	log(locked
		? 'Channel locked by the owner — new members can\'t join (current members are unaffected).'
		: 'Channel unlocked by the owner — new members can join again.');
	updateLockControls();
}

// Reflects the live mode in the selector and lets only the owner change it while connected; when
// disconnected the selector is just the initial-mode chooser for the next Connect.
function updateModeControl() {
	const select = byId('mode');
	if (isOpen()) {
		select.value = state.mode;   // reflect the live mode; updateApplyControls owns the connected disabled state
	} else {
		select.disabled = false;     // pre-connect: the initial-mode chooser, always editable
	}
}

// Locks the channel + passphrase inputs in GLOBAL_PTT mode. That mode joins the server-managed "global"
// room, which forces the channel name to "global" and forbids end-to-end encryption (the server rejects an
// encrypted global join), so both fields are misleading if editable. Each field's typed value is stashed and
// restored when switching back. Driven by the mode SELECTOR (the pending pick), not the live mode.
function updateGlobalModeLocks() {
	// Key the locks off the mode SELECTOR (what you're about to connect or switch with), not the live state.mode.
	// This (a) disables the channel field the moment GLOBAL_PTT is picked — pre-connect AND while connected — and
	// (b) RE-enables it (restoring your saved channel name) the moment you pick a non-global mode, which is the only
	// way to LEAVE the server-managed global room (whose channel is fixed and whose mode you can't change in place).
	// The callers that fire on a server-confirmed mode (onJoined / onModeChanged) run updateModeControl first, which
	// syncs the selector to state.mode, so this stays correct there; only a deliberate pending pick diverges.
	const mode = byId('mode').value;
	const global = mode === 'GLOBAL_PTT';
	lockInGlobalMode(byId('channel'), byId('channelHint'), 'global', global);
	// The global room is always unencrypted, so the passphrase doesn't apply there: HIDE its label + input (not
	// just disable them) and DELETE any typed secret, leaving the hint to explain the absence. A non-global mode
	// shows the field again, empty, for the user to re-enter. (passphrase.disabled is managed by updateApplyControls
	// for the non-global connected case; here we only show/hide + clear.)
	byId('passphraseLabel').hidden = global;
	byId('passphrase').hidden = global;
	byId('passphraseHint').hidden = !global;
	if (global) {
		byId('passphrase').value = '';
	}
	// LEAVING the global room: when we're connected IN global (state.channel === 'global') but the selector has
	// moved to a non-global mode, CLEAR the channel field (instead of restoring the pre-global name) — global was
	// never a channel the user typed, so blank it to make clear they must enter the channel to switch to. The field
	// is already re-enabled by lockInGlobalMode above. (Doesn't fire pre-connect or in a regular channel.)
	if (!global && state.channel === 'global') {
		byId('channel').value = '';
	}
	// "Connect muted" is a connect-time-only choice for full-duplex (the sole mode whose mic auto-opens on join;
	// push-to-talk never auto-transmits). It is captured once at connect and can't take effect on a live session,
	// so show it only PRE-CONNECT when full-duplex is the selected mode — and hide it once connected (rather than
	// showing it greyed-out). Toggle display directly rather than the `hidden` attribute: this row's inline
	// `display:flex` would override the UA `[hidden]{display:none}` rule.
	byId('startMutedRow').style.display = (!isOpen() && mode === 'FULL_DUPLEX') ? 'flex' : 'none';
}

// Disables `input` and shows `hint` while in global mode, swapping in `lockedValue` and stashing the user's
// typed value in a data attribute so it is restored verbatim when the mode changes back.
function lockInGlobalMode(input, hint, lockedValue, global) {
	input.disabled = global;
	hint.hidden = !global;
	if (global) {
		if (input.dataset.userValue === undefined) {
			input.dataset.userValue = input.value;
		}
		input.value = lockedValue;
	} else if (input.dataset.userValue !== undefined) {
		input.value = input.dataset.userValue;
		delete input.dataset.userValue;
	}
}

// --- talk control ---------------------------------------------------------------------------------

function pressTalk() {
	if (state.mutedMembers.has(state.selfId)) {
		return;   // owner-muted — the button is disabled too; this also guards the hold-Space path
	}
	if (state.mode === 'FULL_DUPLEX') {
		if (state.transmitting) {
			endTransmit();
		} else {
			beginTransmit();
		}
		return;
	}
	// push-to-talk: ask the server for the floor; we go live on 'floorGranted'
	if (!state.transmitting) {
		sendCtrl({type: 'requestFloor'});
	}
}

function releaseTalk() {
	if (state.mode === 'FULL_DUPLEX') {
		return; // toggled via pressTalk
	}
	if (state.transmitting) {
		endTransmit();
	}
	sendCtrl({type: 'releaseFloor'});
}

function beginTransmit() {
	if (state.mutedMembers.has(state.selfId)) {
		// Owner-muted: never open the mic — guards the full-duplex auto-open on join and any stray floorGranted.
		state.transmitting = false;
		enableLocalTracks(false);
		updateTalkButton();
		return;
	}
	state.transmitting = true;
	enableLocalTracks(true);
	// PTT/global: holding the floor = "speaking". In full-duplex the mic is always open, so it's driven by
	// actual voice activity (see onCapturedFrame) rather than by the mic merely being unmuted.
	if (state.mode !== 'FULL_DUPLEX') {
		setSpeaking(state.selfId, true);
	}
	updateTalkButton();
}

function endTransmit() {
	state.transmitting = false;
	enableLocalTracks(false);
	setSpeaking(state.selfId, false);
	updateTalkButton();
}

function enableLocalTracks(on) {
	// WebRTC: gate the outgoing track. Relay: gating happens where frames are sent.
	if (state.micStream) {
		state.micStream.getAudioTracks().forEach(t => t.enabled = state.transport === 'webrtc' ? on : true);
	}
}

// --- audio plumbing -------------------------------------------------------------------------------

function captureConstraints() {
	// Hi-fi disables the voice DSP for faithful capture; otherwise keep it for clean speech.
	// Real stereo needs the voice DSP off (it downmixes to mono) AND a stereo input device, so only
	// request 2 channels in hi-fi mode — the default voice path stays unambiguously mono (no fake 128k
	// "stereo" from an upmixed mono mic).
	return state.hifi
		? {channelCount: 2, echoCancellation: false, noiseSuppression: false, autoGainControl: false}
		: {channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true};
}

async function setupAudio() {
	const ctx = new AudioContext({sampleRate: SAMPLE_RATE});
	await ctx.audioWorklet.addModule('/assets/audio-worklet.js');
	state.audioContext = ctx;
	// setupAudio() runs after awaits (login), so the synchronous user-gesture window is gone and Chrome
	// creates the context "suspended" under its autoplay policy. Resume it — the Connect click left a
	// sticky user activation — otherwise the capture/playback worklets never run, so there is no audio
	// in either direction even though control messages (floor, "talking") keep flowing.
	if (ctx.state === 'suspended') {
		await ctx.resume();
	}

	// Acquire the mic first so we can negotiate the real channel count before building the nodes.
	// Stereo only when the device actually reports 2 channels AND WebCodecs Opus is available — the raw
	// PCM fallback stays mono.
	state.micStream = await navigator.mediaDevices.getUserMedia({audio: captureConstraints()});
	const micSettings = state.micStream.getAudioTracks()[0].getSettings();
	state.channels = OPUS_SUPPORTED && micSettings.channelCount === 2 ? 2 : 1;
	log('Audio ready — context ' + ctx.state + ' @ ' + ctx.sampleRate + ' Hz, '
		+ (state.channels === 2 ? 'stereo' : 'mono') + ', transport ' + state.transport);

	if (state.transport === 'relay') {
		setupRelayCodec();
		// One decode/playback lane PER sender is created lazily in getLane; ctx.destination mixes them all.
		// Sweep idle lanes so a sender that has fallen silent has its decoder released.
		state.laneSweep = setInterval(sweepLanes, 1000);
		const source = ctx.createMediaStreamSource(state.micStream);
		// 'explicit' + 'speakers' so the worklet always gets exactly `channels` channels; if the source
		// turns out mono, 'speakers' duplicates it into both (no hard-left/dead-right) rather than
		// padding the second channel with silence the way 'discrete' would.
		const capture = new AudioWorkletNode(ctx, 'capture-processor', {
			channelCount: state.channels,
			channelCountMode: 'explicit',
			channelInterpretation: 'speakers',
			processorOptions: {channels: state.channels},
		});
		source.connect(capture);
		capture.port.onmessage = e => onCapturedFrame(e.data);
		state.captureNode = capture;
	}
}

function setupRelayCodec() {
	if (!OPUS_SUPPORTED) {
		return; // PCM fallback path; no codec objects needed
	}
	state.opusEncoder = new AudioEncoder({
		output(chunk) {
			return sendEncoded(chunk);
		},
		error(e) {
			return log('Opus encoder error: ' + e.message);
		},
	});
	state.opusEncoder.configure({
		codec: 'opus',
		sampleRate: SAMPLE_RATE,
		numberOfChannels: state.channels,
		bitrate: state.channels === 2 ? STEREO_BITRATE : MONO_BITRATE,
		opus: {format: 'opus', frameDuration: FRAME_US, complexity: 10, useinbandfec: true, packetlossperc: 10},
	});
	// Decoders are created PER sender (lazily, in getLane): each remote stream needs its own stateful Opus
	// decoder so simultaneous talkers don't garble, and ctx.destination mixes the per-sender playback nodes.
}

function onCapturedFrame(pcmBuffer) {
	// pcmBuffer is an ArrayBuffer of little-endian Int16 samples (20 ms, 960 samples).
	if (!state.transmitting || !isOpen()) {
		return;
	}
	const int16 = new Int16Array(pcmBuffer);
	// Full-duplex: the mic is always open, so highlight yourself only when actually talking (voice activity),
	// not merely because the mic is unmuted. markSpeaking's silence timer provides the hangover between words.
	if (state.mode === 'FULL_DUPLEX' && isVoiceActive(int16, 32768)) {
		markSpeaking(state.selfId);
	}
	if (state.opusEncoder) {
		const audioData = new AudioData({
			format: 's16',                                  // interleaved
			sampleRate: SAMPLE_RATE,
			numberOfFrames: int16.length / state.channels,  // frames are counted per channel
			numberOfChannels: state.channels,
			timestamp: state.captureTs,
			data: int16,
		});
		state.captureTs += FRAME_US;
		state.opusEncoder.encode(audioData);
		audioData.close();
	} else {
		sendTagged(CODEC_PCM, new Uint8Array(pcmBuffer));
	}
}

function sendEncoded(chunk) {
	const buf = new ArrayBuffer(chunk.byteLength);
	chunk.copyTo(buf);
	sendTagged(CODEC_OPUS, new Uint8Array(buf));
}

function sendTagged(tag, payloadBytes) {
	if (!isOpen()) {
		return;
	}
	// One pure decision (see e2ee.frameDisposition): 'drop' = the channel announces encryption but we hold no
	// matching key (e.g. the owner just turned encryption ON and we haven't applied the new passphrase) — stay
	// SILENT rather than leak cleartext to the relay; 'plaintext' = a genuinely unencrypted channel; 'encrypt' =
	// we hold a key.
	const disposition = frameDisposition(state.keyCheck, state.channelKeyCheck);
	if (disposition === 'drop') {
		return;
	}
	const out = new Uint8Array(payloadBytes.length + 1);
	out[0] = tag;
	out.set(payloadBytes, 1);
	if (disposition === 'plaintext') {
		state.ws.send(out.buffer);
		return;
	}
	// Serialize encryption so async WebCrypto can't reorder the stateful Opus stream. Read state.cryptoKey at
	// execution time (a concurrent re-key may swap it); a frame that started before a key change just encrypts
	// under whatever key is current when it runs.
	state.txChain = state.txChain
		.then(() => encryptFrame(out, state.cryptoKey))
		.then(enc => {
			if (isOpen()) {
				state.ws.send(enc.buffer);
			}
		})
		.catch(err => log('Encrypt error: ' + err.message));
}

// --- end-to-end encryption (relay path) -----------------------------------------------------------

// Decrypts (when E2EE is on) before decoding. Decryption is serialized through a promise chain so async
// WebCrypto can't hand frames to the stateful Opus decoder out of order.
function handleAudioFrame(arrayBuffer) {
	const bytes = new Uint8Array(arrayBuffer);
	if (bytes.length < 2) {
		return; // need at least [stream id][1 body byte]
	}
	// Demultiplex by the server-prepended stream index, then strip it unconditionally (before the E2EE
	// branch) so the no-key path can't mistake the stream id for a codec tag.
	const sid = bytes[0];
	const body = bytes.subarray(1);
	const lane = getLane(sid);
	lane.lastSeen = performance.now();
	// PTT/global: a sender's frames flow only while they hold the floor, so arrival = speaking. Full-duplex
	// keeps every mic open, so there it's driven by actual voice activity (see enqueuePlayback) instead.
	if (state.mode !== 'FULL_DUPLEX') {
		markSpeaking(lane.memberId);
	}
	if (!state.cryptoKey) {
		processFrame(lane, body);
		return;
	}
	// Decryption is serialized PER sender (one stateful decoder per lane), so a slow decrypt for one sender
	// can't reorder that sender's frames or head-of-line-block another.
	lane.rxChain = lane.rxChain
		.then(async () => processFrame(lane, new Uint8Array(await decryptFrame(body, state.cryptoKey))))
		.catch(() => {
			if (!state.warnedDecrypt) {
				state.warnedDecrypt = true;
				log('Could not decrypt audio — confirm everyone is using the same passphrase, channel, and mode.');
			}
		});
}

function processFrame(lane, body) {
	if (body.length < 2) {
		return;
	}
	const tag = body[0];
	if (tag === CODEC_OPUS) {
		if (lane.decoder) {
			const payload = body.subarray(1);
			// The Opus TOC byte's stereo flag (0x04) gives the stream's channel count, which may differ from
			// ours (e.g. a stereo Concentus/Java sender into a mono browser). Configure THIS lane's decoder to
			// match the stream — otherwise WebCodecs decodes to silence — and reconfigure if it changes.
			const streamChannels = payload[0] & 0x04 ? 2 : 1;
			if (lane.decoderChannels !== streamChannels) {
				lane.decoder.configure({codec: 'opus', sampleRate: SAMPLE_RATE, numberOfChannels: streamChannels});
				lane.decoderChannels = streamChannels;
				lane.decodeTs = 0;
			}
			const chunk = new EncodedAudioChunk({
				type: 'key',
				timestamp: lane.decodeTs,
				duration: FRAME_US,
				data: payload
			});
			lane.decodeTs += FRAME_US;
			lane.decoder.decode(chunk);
		} else if (!state.warnedNoOpus) {
			state.warnedNoOpus = true;
			log('Received Opus audio but this browser cannot decode it (no WebCodecs).');
		}
	} else if (tag === CODEC_PCM) {
		// Raw Int16 LE, mono (the PCM fallback is always mono), starting at body offset 1.
		const count = body.length - 1 >> 1;
		const view = new DataView(body.buffer, body.byteOffset + 1, body.length - 1);
		const f32 = new Float32Array(count);
		for (let i = 0; i < count; i++) {
			f32[i] = view.getInt16(i * 2, true) / 0x8000;
		}
		enqueuePlayback(lane, f32, count, 1);
	} else if (tag === E2EE_SCHEME && !state.warnedEncryptedNoKey) {
		// Encrypted frames arriving while we have no passphrase: drop cleanly and explain (don't decode noise).
		state.warnedEncryptedNoKey = true;
		log('Received end-to-end-encrypted audio but no passphrase is set — set the matching passphrase to hear it.');
	}
}

function playbackDecoded(lane, audioData) {
	const frames = audioData.numberOfFrames;
	// The lane's decoder is configured to match its stream (see processFrame), so numberOfChannels is the
	// stream's count. Copy each channel plane as f32-planar and interleave manually — the reliably supported
	// WebCodecs path; interleaved-'f32' copyTo can mis-extract a multi-channel AudioData.
	const srcChannels = audioData.numberOfChannels;
	const interleaved = new Float32Array(frames * srcChannels);
	const plane = new Float32Array(frames);
	for (let c = 0; c < srcChannels; c++) {
		audioData.copyTo(plane, {planeIndex: c, format: 'f32-planar'});
		for (let i = 0; i < frames; i++) {
			interleaved[i * srcChannels + c] = plane[i];
		}
	}
	audioData.close();
	enqueuePlayback(lane, interleaved, frames, srcChannels);
}

// Adapt an interleaved Float32 buffer (srcChannels) to the device's output channel count and queue it on
// this sender's playback node — so a mono sender plays on a stereo listener (and vice versa) regardless of
// how the Opus decoder reports channels. Mirrors the Java client's "decode to my own channel count".
// Takes ownership of `src`: the underlying buffer is transferred (detached) once queued.
function enqueuePlayback(lane, src, frames, srcChannels) {
	// Full-duplex: every sender's mic is open, so highlight a member only when their decoded audio is actually
	// loud (voice activity), not just because frames keep arriving. (PTT/global use frame arrival / the floor.)
	if (state.mode === 'FULL_DUPLEX' && isVoiceActive(src, 1)) {
		markSpeaking(lane.memberId);
	}
	const out = state.channels;
	let interleaved;
	if (srcChannels === out) {
		interleaved = src;
	} else if (srcChannels === 1 && out === 2) {
		interleaved = new Float32Array(frames * 2);
		for (let i = 0; i < frames; i++) {
			interleaved[i * 2] = src[i];
			interleaved[i * 2 + 1] = src[i];
		}
	} else if (srcChannels === 2 && out === 1) {
		interleaved = new Float32Array(frames);
		for (let i = 0; i < frames; i++) {
			interleaved[i] = 0.5 * (src[i * 2] + src[i * 2 + 1]);
		}
	} else {
		// Unexpected channel count (not 1 or 2) — drop rather than mis-stride the worklet de-interleave.
		if (!state.warnedChannels) {
			state.warnedChannels = true;
			log('Dropping audio with unexpected channel count: ' + srcChannels);
		}
		return;
	}
	lane.node.port.postMessage(interleaved.buffer, [interleaved.buffer]);
}

// --- per-sender lanes (relay full-duplex) ---------------------------------------------------------

// Returns the decode/playback lane for a stream index, creating it on first use. Each lane carries its own
// stateful Opus decoder and its own Web Audio playback node (ctx.destination mixes all lanes natively), so
// simultaneous talkers no longer collide in one decoder. If the index has been reassigned to a different
// member, the stale lane is torn down and rebuilt with a fresh decoder.
function getLane(sid) {
	const expectedMember = state.memberOfStream.get(sid);
	let lane = state.lanes.get(sid);
	if (lane) {
		if (expectedMember !== undefined && lane.memberId !== null && lane.memberId !== expectedMember) {
			closeLane(sid);   // index reassigned to a new member -> fresh decoder, no stale Opus state
			lane = undefined;
		} else if (lane.memberId === null && expectedMember !== undefined) {
			lane.memberId = expectedMember;   // late roster binding for a frame that arrived before MemberJoined
		}
	}
	if (!lane) {
		if (state.lanes.size >= MAX_ACTIVE_DECODERS) {
			evictOldestLane();
		}
		lane = createLane(expectedMember === undefined ? null : expectedMember);
		state.lanes.set(sid, lane);
	}
	return lane;
}

function createLane(memberId) {
	// Same node invariants as the original single playback node: no inputs and an explicit output channel
	// count, or Chrome can hand process() a zero-channel output and go permanently silent.
	const node = new AudioWorkletNode(state.audioContext, 'playback-processor', {
		numberOfInputs: 0,
		numberOfOutputs: 1,
		outputChannelCount: [state.channels],
		processorOptions: {channels: state.channels},
	});
	node.connect(state.audioContext.destination);
	const lane = {
		node,
		decoder: null,
		decoderChannels: null,
		decodeTs: 0,
		rxChain: Promise.resolve(),
		memberId,
		lastSeen: performance.now()
	};
	if (OPUS_SUPPORTED) {
		lane.decoder = new AudioDecoder({
			output(audioData) {
				return playbackDecoded(lane, audioData);
			},
			error(e) {
				return log('Opus decoder error: ' + e.message);
			},
		});
	}
	return lane;
}

function closeLane(sid) {
	const lane = state.lanes.get(sid);
	if (!lane) {
		return;
	}
	state.lanes.delete(sid);
	closeCodec(lane.decoder);
	try {
		lane.node.disconnect();
	} catch (err) {
		// already disconnected
	}
}

function closeAllLanes() {
	[...state.lanes.keys()].forEach(closeLane);
}

// Evicts the longest-silent lane to stay under MAX_ACTIVE_DECODERS (loudness isn't computable for an
// un-decoded sender). A dropped sender simply isn't audible until a slot frees.
function evictOldestLane() {
	let oldestSid = null;
	let oldest = Infinity;
	state.lanes.forEach((lane, sid) => {
		if (lane.lastSeen < oldest) {
			oldest = lane.lastSeen;
			oldestSid = sid;
		}
	});
	if (oldestSid !== null) {
		closeLane(oldestSid);
	}
}

function sweepLanes() {
	const now = performance.now();
	state.lanes.forEach((lane, sid) => {
		if (now - lane.lastSeen > SILENCE_TTL_MS) {
			closeLane(sid);
		}
	});
}

// --- WebRTC mesh ----------------------------------------------------------------------------------

function createPeer(remoteId) {
	const pc = new RTCPeerConnection(STUN);
	state.micStream.getAudioTracks().forEach(track => {
		track.enabled = state.mode === 'FULL_DUPLEX' || state.transmitting;
		pc.addTrack(track, state.micStream);
	});
	pc.onicecandidate = e => {
		if (e.candidate) {
			sendCtrl({
				type: 'ice',
				target: remoteId,
				candidate: e.candidate.candidate,
				sdpMid: e.candidate.sdpMid,
				sdpMLineIndex: e.candidate.sdpMLineIndex,
			});
		}
	};
	pc.ontrack = e => attachRemoteAudio(remoteId, e.streams[0]);
	state.peers.set(remoteId, pc);
	return pc;
}

async function offerTo(remoteId) {
	const pc = createPeer(remoteId);
	const offer = await pc.createOffer();
	await pc.setLocalDescription({type: offer.type, sdp: tuneOpusSdp(offer.sdp)});
	sendCtrl({type: 'offer', target: remoteId, sdp: pc.localDescription.sdp});
	setSenderBitrate(pc, MONO_BITRATE);
}

async function onOffer(from, sdp) {
	const pc = state.peers.get(from) || createPeer(from);
	await pc.setRemoteDescription({type: 'offer', sdp});
	const answer = await pc.createAnswer();
	await pc.setLocalDescription({type: answer.type, sdp: tuneOpusSdp(answer.sdp)});
	sendCtrl({type: 'answer', target: from, sdp: pc.localDescription.sdp});
	setSenderBitrate(pc, MONO_BITRATE);
}

async function onAnswer(from, sdp) {
	const pc = state.peers.get(from);
	if (pc) {
		await pc.setRemoteDescription({type: 'answer', sdp});
	}
}

async function onIce(from, candidate, sdpMid, sdpMLineIndex) {
	const pc = state.peers.get(from);
	if (pc && candidate) {
		await pc.addIceCandidate({candidate, sdpMid, sdpMLineIndex}); // rejection logged by the caller
	}
}

// Raise Opus quality in the SDP: fullband, high bitrate, in-band FEC, no DTX.
function tuneOpusSdp(sdp) {
	const rtpmap = sdp.match(/a=rtpmap:(\d+) opus\/48000/);
	if (!rtpmap) {
		return sdp;
	}
	const pt = rtpmap[1];
	const params = 'maxaveragebitrate=64000;maxplaybackrate=48000;stereo=0;useinbandfec=1;usedtx=0';
	const fmtp = new RegExp(`a=fmtp:${pt} ([^\\r\\n]*)`);
	if (fmtp.test(sdp)) {
		return sdp.replace(fmtp, (line, existing) => `a=fmtp:${pt} ${existing};${params}`);
	}
	return sdp.replace(rtpmap[0], `${rtpmap[0]}\r\na=fmtp:${pt} ${params}`);
}

function setSenderBitrate(pc, bitsPerSecond) {
	pc.getSenders()
		.filter(sender => sender.track && sender.track.kind === 'audio')
		.forEach(async sender => {
			const params = sender.getParameters();
			if (!params.encodings || params.encodings.length === 0) {
				params.encodings = [{}];
			}
			params.encodings[0].maxBitrate = bitsPerSecond;
			try {
				await sender.setParameters(params);
			} catch (err) {
				log('setParameters: ' + err.message);
			}
		});
}

function attachRemoteAudio(remoteId, stream) {
	let el = document.getElementById('audio-' + remoteId);
	if (!el) {
		el = document.createElement('audio');
		el.id = 'audio-' + remoteId;
		el.autoplay = true;
		document.body.appendChild(el);
	}
	el.srcObject = stream;
	// autoplay should start it, but call play() explicitly so a blocked autoplay surfaces a hint
	// instead of failing silently.
	el.play().catch(e => log('Browser blocked audio autoplay (click the page to enable): ' + e.message));
}

function closePeer(remoteId) {
	const pc = state.peers.get(remoteId);
	if (pc) {
		pc.close();
		state.peers.delete(remoteId);
	}
	const el = document.getElementById('audio-' + remoteId);
	if (el) {
		el.remove();
	}
}

// --- members + UI ---------------------------------------------------------------------------------

/**
 * One roster entry from the server — the wire shape of walkie-shared's `MemberInfo` record, as carried in
 * `joined.members[]` and `memberJoined.member`.
 *
 * @typedef {Object} MemberInfo
 * @property {string} id session id — the routing identity (floor, ownership, WebRTC, audio)
 * @property {string} displayName human label (not unique; rendered with a `#id` prefix)
 * @property {number} streamId per-channel uint8 stream index (0..254) prefixed onto this member's relayed audio
 * @property {boolean} muted owner-muted — the server drops this member's relay audio while true
 */

/** @param {MemberInfo} member */
function addMember(member) {
	state.members.set(member.id, member.displayName);
	// Seed the owner-mute state from the roster snapshot, so a member joining a channel where someone is already
	// muted renders that correctly (and, if it is us, our talk control starts disabled — see updateTalkButton).
	if (member.muted) {
		state.mutedMembers.add(member.id);
	} else {
		state.mutedMembers.delete(member.id);
	}
	if (member.streamId !== undefined && member.streamId !== null) {
		state.streamOf.set(member.id, member.streamId);
		state.memberOfStream.set(member.streamId, member.id);
	}
	renderMembers();
}

// A member changed its display name (id is unchanged — only the label moves). Update the roster map and
// re-render; the streamId / speaking state / decoder lanes are keyed by id, so they're untouched.
function renameMember(id, name) {
	const old = state.members.get(id);
	if (old === undefined) {
		return;   // not a member we're tracking (shouldn't happen for a channel rename)
	}
	state.members.set(id, name);
	renderMembers();
	updateRenameButton();   // if this was our own rename, the field now matches the new name → Rename re-disables
	log(id === state.selfId ? `You are now "${name}"` : `"${old}" is now "${name}"`);
}

function removeMember(id) {
	setSpeaking(id, false);
	clearTimeout(state.speakTimers.get(id));
	state.speakTimers.delete(id);
	if (state.floorSpeaker === id) {
		state.floorSpeaker = null;
	}
	state.members.delete(id);
	state.mutedMembers.delete(id);   // a mute never outlives the member (mirrors the server's Channel.remove)
	const sid = state.streamOf.get(id);
	if (sid !== undefined) {
		closeLane(sid);   // free the departed member's decoder immediately
		state.memberOfStream.delete(sid);
		state.streamOf.delete(id);
	}
	renderMembers();
}

// Toggles the "speaking" highlight on a roster entry. Operates on the cached <li> so a per-frame update is
// cheap (no full re-render); the state.speaking Set is the source of truth that renderMembers re-applies.
function setSpeaking(id, speaking) {
	if (!id) {
		return;
	}
	if (speaking) {
		state.speaking.add(id);
	} else {
		state.speaking.delete(id);
	}
	const li = state.memberLis.get(id);
	if (li) {
		li.classList.toggle('speaking', speaking);
	}
}

// A relay audio frame just arrived from `id`: highlight them and (re)arm a short silence timer that clears it.
// This keys off actual audio frames, so it works in every mode on the relay path (PTT, global, full-duplex).
function markSpeaking(id) {
	if (!id) {
		return;
	}
	setSpeaking(id, true);
	clearTimeout(state.speakTimers.get(id));
	state.speakTimers.set(id, setTimeout(() => {
		state.speakTimers.delete(id);
		setSpeaking(id, false);
	}, SPEAK_SILENCE_MS));
}

// Rough voice-activity gate: true when a PCM frame's RMS (after dividing by `scale` to normalize to [-1, 1])
// exceeds VAD_RMS_THRESHOLD. `scale` is 32768 for captured Int16, 1 for already-normalized decoded Float32.
function isVoiceActive(samples, scale) {
	let sum = 0;
	for (let i = 0; i < samples.length; i++) {
		const v = samples[i] / scale;
		sum += v * v;
	}
	return samples.length > 0 && Math.sqrt(sum / samples.length) > VAD_RMS_THRESHOLD;
}

function renderMembers() {
	const ul = byId('members');
	ul.replaceChildren();
	state.memberLis.clear();
	// Only the channel owner sees the moderation controls (per-member Mute/Unmute + "Mute all"); the server
	// enforces the same rule, so this is UI convenience, not the security boundary. Never for the ownerless global room.
	const iAmOwner = isOpen() && state.selfId === state.ownerId && state.ownerId !== SERVER_OWNER;
	// Always append a short session-id prefix after the display name (the session id is the real identity —
	// names aren't unique); the full id is on hover. Lexicographic (case-insensitive) by name, then by id.
	[...state.members.entries()]
		.sort(([idA, nameA], [idB, nameB]) => nameA.localeCompare(nameB, undefined, {sensitivity: 'base'}) || (idA < idB ? -1 : idA > idB ? 1 : 0))
		.forEach(([id, name]) => {
			let label = `${name} (#${id.slice(0, 8)})`;
			if (id === state.selfId) {
				label += ' (you)';
			}
			const li = document.createElement('li');
			li.title = id;
			// The channel owner gets a crown so it's clear at a glance who owns the channel (no need to scan the
			// log). The server-managed global room has no participant owner, so it gets no crown.
			if (id === state.ownerId && state.ownerId !== SERVER_OWNER) {
				li.classList.add('owner');
				const crown = document.createElement('span');
				crown.className = 'owner-badge';
				crown.textContent = '👑';
				crown.title = 'Channel owner';
				li.appendChild(crown);
			}
			const nameSpan = document.createElement('span');
			if (id === state.selfId) {
				nameSpan.className = 'self';
			}
			// textContent (not innerHTML) so a crafted display name can't inject markup.
			nameSpan.textContent = label;
			li.appendChild(nameSpan);
			// Muted members are dimmed and flagged with a speaker-off marker, so everyone (not just the owner) can
			// see who the owner has silenced. The badge's margin-left:auto right-aligns it (and the button after it).
			const muted = state.mutedMembers.has(id);
			if (muted) {
				li.classList.add('muted');
				const badge = document.createElement('span');
				badge.className = 'muted-badge';
				badge.textContent = '🔇';
				badge.title = 'Muted by the owner';
				li.appendChild(badge);
			}
			// The owner gets a per-member Mute/Unmute toggle (never on its own row — the owner can't mute itself).
			// It applies immediately, without the Apply/Reset flow the mode/passphrase/owner changes use.
			if (iAmOwner && id !== state.selfId) {
				const muteBtn = document.createElement('button');
				muteBtn.type = 'button';
				muteBtn.className = 'secondary member-mute';
				muteBtn.textContent = muted ? 'Unmute' : 'Mute';
				muteBtn.addEventListener('click', () => sendCtrl({type: 'muteMember', memberId: id, muted: !muted}));
				li.appendChild(muteBtn);
			}
			// Re-apply the live speaking highlight (state.speaking is authoritative across re-renders).
			li.classList.toggle('speaking', state.speaking.has(id));
			state.memberLis.set(id, li);
			ul.appendChild(li);
		});
	updateMuteAllButton(); // show/hide + relabel the owner's "Mute all" toggle to match the roster's mute state
	updateLockControls();  // show the 🔒 indicator to everyone + the owner's Lock/Unlock toggle (ownership may have changed)
	renderOwnerSelect();   // keep the owner dropdown in sync with the roster
	updateApplyControls(); // re-settle Apply/Reset: a rebuild may have dropped a now-departed pending owner pick
}

// Shows the owner's "Mute all" toggle (hidden for everyone else and in the ownerless global room) and labels it
// "Unmute all" when every other member is already muted, else "Mute all". Disabled when the owner is alone —
// there is no one to mute. The click handler recomputes the mute-vs-unmute intent from the live roster.
function updateMuteAllButton() {
	const btn = byId('muteAllBtn');
	const iAmOwner = isOpen() && state.selfId === state.ownerId && state.ownerId !== SERVER_OWNER;
	btn.hidden = !iAmOwner;
	if (!iAmOwner) {
		return;
	}
	const others = [...state.members.keys()].filter(id => id !== state.ownerId);
	const allMuted = others.length > 0 && others.every(id => state.mutedMembers.has(id));
	btn.textContent = allMuted ? 'Unmute all' : 'Mute all';
	btn.disabled = others.length === 0;
}

// Reflects the channel's lock state: the 🔒 indicator is shown to EVERYONE when locked (so non-owners, who don't
// see the toggle, still know newcomers are blocked); the "Lock/Unlock channel" toggle is owner-only (hidden for
// others and in the ownerless global room). The button label follows state.locked, recomputed at click time.
function updateLockControls() {
	const badge = byId('lockedBadge');
	const btn = byId('lockBtn');
	badge.hidden = !state.locked;
	const iAmOwner = isOpen() && state.selfId === state.ownerId && state.ownerId !== SERVER_OWNER;
	btn.hidden = !iAmOwner;
	if (iAmOwner) {
		btn.textContent = state.locked ? 'Unlock channel' : 'Lock channel';
	}
}

function enableTalkButton(enabled) {
	byId('talkBtn').disabled = !enabled;
}

function updateTalkButton() {
	const btn = byId('talkBtn');
	btn.classList.toggle('live', state.transmitting);
	// Owner-muted: the control is disabled and says why. The server drops our audio (and refuses us the floor)
	// regardless, but disabling here stops us talking into a closed door and makes the reason plain. This runs only
	// while connected (every caller is post-join), so it owns the connected disabled state alongside the mute.
	if (state.mutedMembers.has(state.selfId)) {
		btn.disabled = true;
		btn.textContent = 'Muted by owner';
		return;
	}
	btn.disabled = false;
	btn.textContent = state.mode === 'FULL_DUPLEX' ?
		state.transmitting ?
			'Mic ON (click to mute)' :
			'Mic OFF (click to talk)' :
		state.transmitting ?
			'LIVE — release to stop' :
			'Hold to talk';
}

// Resets everything tied to the CURRENT channel — peers, decode lanes, roster, and floor/speaking highlights —
// but NOT the connection (socket, mic, AudioContext, encoder). Used by cleanup() on disconnect AND by onJoined,
// so an in-place channel switch (a re-Join on the same session) starts the new channel from a clean slate and
// can't leak the old channel's peer connections, decoders, members, or "talking" state.
function resetChannelState() {
	state.peers.forEach((_, id) => closePeer(id));
	state.peers.clear();
	closeAllLanes();
	state.memberOfStream.clear();
	state.streamOf.clear();
	state.speakTimers.forEach(clearTimeout);
	state.speakTimers.clear();
	state.speaking.clear();
	state.floorSpeaker = null;
	state.members.clear();
	state.mutedMembers.clear();
	state.locked = false;   // onJoined re-seeds from the snapshot; this keeps a clean baseline for the disconnect path
}

function cleanup() {
	resetChannelState();
	if (state.laneSweep) {
		clearInterval(state.laneSweep);
		state.laneSweep = null;
	}
	renderMembers();
	state.channel = null;
	state.transmitting = false;
	state.connecting = false;
	closeCodec(state.opusEncoder);
	state.opusEncoder = null;
	state.captureTs = 0;
	state.warnedNoOpus = false;
	state.warnedChannels = false;
	state.cryptoKey = null;
	state.keyCheck = null;
	state.passphrase = '';
	state.channelKeyCheck = null;
	state.rekeyPending = false;
	state.warnedDecrypt = false;
	state.warnedEncryptedNoKey = false;
	if (state.micStream) {
		state.micStream.getTracks().forEach(t => t.stop());
		state.micStream = null;
	}
	if (state.audioContext) {
		state.audioContext.close();
		state.audioContext = null;
	}
	state.captureNode = null;
	state.ws = null;
	state.token = null;
	state.ownerId = null;
	enableTalkButton(false);
	const talkBtn = byId('talkBtn');
	talkBtn.textContent = 'Connect first';
	talkBtn.classList.remove('live');
	byId('connectBtn').hidden = false;
	byId('disconnectBtn').hidden = true;
	byId('renameBtn').hidden = true;
	byId('renameHint').hidden = true;
	byId('applyBtn').hidden = true;
	byId('resetBtn').hidden = true;
	byId('applyHint').hidden = true;
	byId('ownerSelect').hidden = true;
	byId('ownerLabel').hidden = true;
	byId('muteAllBtn').hidden = true;      // owner-only moderation control; renderMembers only re-shows it while connected
	byId('lockBtn').hidden = true;         // owner-only lock toggle; ditto
	byId('lockedBadge').hidden = true;     // clear the 🔒 indicator on disconnect
	byId('shareRekeyRow').hidden = true;   // owner-only rotation control; updateApplyControls only runs while connected
	byId('shareRekey').checked = true;     // back to the default-checked (auto-share) state for the next session
	setStatus(false, 'Disconnected');
	byId('transport').disabled = false;   // re-enable for the next connect (updateApplyControls only runs while connected)
	byId('passphrase').disabled = false;  // ditto — its locked state is set by updateApplyControls only while connected
	updateModeControl();                  // re-enables the mode selector (pre-connect branch)
	updateGlobalModeLocks();              // re-shows/hides + re-enables the channel, and shows/clears the passphrase, per the selected mode
}

function closeCodec(codec) {
	if (codec && codec.state !== 'closed') {
		try {
			codec.close();
		} catch (err) {
			// already closing
		}
	}
}

// --- wiring ---------------------------------------------------------------------------------------

window.addEventListener('DOMContentLoaded', () => {
	byId('connectBtn').addEventListener('click', connect);
	byId('disconnectBtn').addEventListener('click', disconnect);

	byId('renameBtn').addEventListener('click', rename);
	byId('applyBtn').addEventListener('click', applyOrSwitch);
	byId('resetBtn').addEventListener('click', resetApplyControls);
	// Owner-only "Mute all" toggle: mute every other member if any is still unmuted, else unmute everyone. Recompute
	// the intent at click time (not from the label) so it stays correct against the live roster. Applies immediately.
	byId('muteAllBtn').addEventListener('click', () => {
		const others = [...state.members.keys()].filter(id => id !== state.ownerId);
		const allMuted = others.length > 0 && others.every(id => state.mutedMembers.has(id));
		sendCtrl({type: 'muteAll', muted: !allMuted});
	});
	// Owner-only "Lock/Unlock channel" toggle: flip the current lock state (recomputed at click time). Immediate.
	byId('lockBtn').addEventListener('click', () => sendCtrl({type: 'setLocked', locked: !state.locked}));
	byId('ownerSelect').addEventListener('change', updateApplyControls);   // a pending transfer; applied via the button
	// Re-evaluate the adaptive Switch/Apply button as the form fields change (mode is handled in its own listener).
	byId('channel').addEventListener('input', updateApplyControls);
	byId('passphrase').addEventListener('input', updateApplyControls);
	byId('transport').addEventListener('change', updateApplyControls);
	// Enable Rename only once the Display name field differs from the current name.
	byId('display').addEventListener('input', updateRenameButton);

	// The Connect fields aren't wrapped in a <form>, so Enter in one does nothing by default. Treat Enter in
	// the Connect panel as "Connect" when a connect is possible (the Connect button is showing, i.e. disconnected);
	// once connected, Enter in the Display name field instead renames (the Connect button is then hidden).
	byId('setup').addEventListener('keydown', e => {
		if (e.key !== 'Enter' || e.isComposing) {
			return;
		}
		if (!byId('connectBtn').hidden) {
			e.preventDefault();
			connect();
		} else if (isOpen() && e.target.id === 'display' && !byId('renameBtn').disabled) {
			e.preventDefault();
			rename();
		}
	});

	// The Mode selector is a channel PROPERTY now: while connected, only the owner can change it (the selector
	// is disabled for others via updateModeControl), and the change is applied by the adaptive Apply button —
	// not live on select — so mode/passphrase/transport apply together in one click. Pre-connect it just picks
	// the initial mode for the next Connect.
	byId('mode').addEventListener('change', () => {
		updateGlobalModeLocks(); // reflect the global-mode channel lock immediately (pre- and post-connect)
		updateApplyControls();   // enable/relabel the Apply button to reflect the pending mode change
	});
	updateGlobalModeLocks(); // set the initial channel-input state to match the default mode

	// High fidelity toggles the mic DSP (echo cancellation / noise suppression / auto-gain) live: applied
	// to the running mic track immediately via applyConstraints, no reconnect. (The channel layout —
	// mono/stereo — is negotiated once at connect and is not changed by the live toggle.)
	byId('hifi').addEventListener('change', async () => {
		state.hifi = byId('hifi').checked;
		if (!state.micStream) {
			return; // pre-connect: just picks the initial setting for the next Connect
		}
		const dsp = !state.hifi;
		try {
			await Promise.all(state.micStream.getAudioTracks().map(t =>
				t.applyConstraints({echoCancellation: dsp, noiseSuppression: dsp, autoGainControl: dsp})));
			log('Mic processing ' + (dsp ? 'on (clean speech)' : 'off (hi-fi)'));
		} catch (err) {
			log('Could not change mic processing live (reconnect to apply): ' + err.message);
		}
	});

	const talk = byId('talkBtn');
	talk.addEventListener('mousedown', pressTalk);
	talk.addEventListener('mouseup', releaseTalk);
	talk.addEventListener('mouseleave', () => {
		if (state.mode !== 'FULL_DUPLEX') releaseTalk();
	});
	talk.addEventListener('touchstart', e => {
		e.preventDefault();
		pressTalk();
	}, {passive: false});
	talk.addEventListener('touchend', e => {
		e.preventDefault();
		releaseTalk();
	}, {passive: false});

	// Hold Space as a push-to-talk key.
	window.addEventListener('keydown', e => {
		if (e.code === 'Space' && !e.repeat && !talk.disabled && state.mode !== 'FULL_DUPLEX' && document.activeElement.tagName !== 'INPUT') {
			e.preventDefault();
			pressTalk();
		}
	});
	window.addEventListener('keyup', e => {
		if (e.code === 'Space' && !talk.disabled && state.mode !== 'FULL_DUPLEX' && document.activeElement.tagName !== 'INPUT') {
			e.preventDefault();
			releaseTalk();
		}
	});
});
