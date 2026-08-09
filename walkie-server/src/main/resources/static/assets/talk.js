// The push-to-talk floor rules and the Talk control's single decision. Pulled out of app.js into a DOM-free
// module — a sibling of e2ee.js — so it can be unit-tested under Node's built-in test runner (`node --test`, no
// npm dependencies) from src/test/js/talk.test.js. Nothing here touches the DOM or any browser global: it maps a
// plain snapshot of client state to what the Talk button should SAY and DO, and app.js renders that.
//
// floorNarration is mirrored by the Java client's own floorNarration, kind for kind and key for key, with ONE
// deliberate exception: this one falls silent on `in-line` and `offered` while the browser's queue panel is
// showing them (see queueView), and the console client — which has no panel — keeps both. That is the only
// intended difference; anything else is drift.
//
// floorStateFor, floorActionFor and shouldAutoOpenMic are the SAME pure rules the Java client applies —
// WalkieClient.floorStateFor / floorActionFor / shouldAutoOpenMic, pinned by WalkieClientTest — and the FLOOR_*
// values are that client's FloorState enum names, so the two must stay in lock-step. They take the same positional
// arguments as their Java counterparts for that reason; talkDecision takes a snapshot object instead only because
// it reads a dozen fields. The hold-vs-tap axis in talkDecision is genuinely browser-only: the Java console
// client's `t` is a single keystroke with no press/release edge to distinguish.

export const FLOOR_LIVE = 'LIVE';       // we hold the floor and are transmitting
export const FLOOR_MY_TURN = 'MY_TURN'; // reserved for us: the floor is free and we are the queue head — claim it
export const FLOOR_IN_LINE = 'IN_LINE'; // waiting further back in the queue
export const FLOOR_IDLE = 'IDLE';       // none of the above: floor free, held by another, or reserved for another

/**
 * Derives our floor state from the authoritative snapshot (holderId + waiting) and our own id — the same rule the
 * Java client's floorStateFor applies, so both stay in lock-step. There is deliberately no "reserved" field: the
 * member offered a free floor is exactly waiting[0] (the server reserves the head the instant the floor frees).
 *
 * The `== null` on the holder is loose on purpose, so a FloorStatus that omits the field reads as a free floor
 * rather than as held by `undefined`; don't tighten it to `===`.
 */
export function floorStateFor(self, holderId, waiting) {
	return self === holderId
		? FLOOR_LIVE
		: holderId == null && waiting.length > 0 && waiting[0] === self
			? FLOOR_MY_TURN
			: waiting.includes(self)
				? FLOOR_IN_LINE
				: FLOOR_IDLE;
}

/**
 * The message the state-driven Talk control sends for a floor state — mirrors the Java client's floorActionFor:
 * LIVE → releaseFloor (stop), IN_LINE → releaseFloor (leave the queue), MY_TURN → requestFloor (claim), IDLE →
 * requestFloor (grab if free, else enqueue when the queue is on; the server ignores it when busy + queue off).
 */
export function floorActionFor(floorState) {
	return floorState === FLOOR_LIVE || floorState === FLOOR_IN_LINE
		? {type: 'releaseFloor'}
		: {type: 'requestFloor'};
}

/**
 * Whether nobody holds OR is reserved for the floor — the only IDLE sub-case that stays a plain hold-to-talk. A
 * busy floor instead becomes tap-to-raise-hand when the queue is on, or a disabled "held by X" when it's off.
 */
export function floorIsFree(holderId, waiting) {
	return !holderId && waiting.length === 0;
}

/**
 * Whether joining a channel — or switching it to full-duplex — should open the microphone by itself: full-duplex
 * only, and only when the user didn't ask to start muted and the owner hasn't muted us. Mirrors the Java client's
 * shouldAutoOpenMic argument for argument. Push-to-talk never auto-opens; there the mic follows the floor.
 *
 * The owner-mute term earns its place on two real paths: a member re-joining its CURRENT channel re-snapshots
 * itself as muted, and a switch to full-duplex must not open a muted member's mic. It is not the enforcement
 * boundary — the server drops a muted sender's frames regardless — but without it the client would report a live
 * mic while every frame was being discarded.
 */
export function shouldAutoOpenMic(mode, startMuted, selfMuted) {
	return mode === 'FULL_DUPLEX' && !startMuted && !selfMuted;
}

/**
 * Full-duplex voice-activity gate: true when a PCM buffer's RMS — after dividing by `scale` to normalise to
 * [-1, 1] — exceeds [#VAD_RMS_THRESHOLD]. `scale` is 32768 for captured Int16 and 1 for already-normalised
 * Float32 (a decoded relay lane, or an AnalyserNode's time-domain data).
 *
 * It lives here, beside the other talk rules, because it is what decides whether a member's row is highlighted as
 * "talking" in full-duplex — where every mic is open, so merely being unmuted means nothing. It has four callers
 * across both transports (the relay capture path, the relay decode lanes, and the WebRTC meter for self and for
 * each peer), and one rule with one threshold is the point: two transports that disagreed about what counts as
 * talking would highlight the same person differently depending on how their audio arrived.
 */
export function isVoiceActive(samples, scale) {
	let sum = 0;
	for (const item of samples) {
		sum += (item / scale) ** 2;
	}
	return samples.length > 0 && Math.sqrt(sum / samples.length) > VAD_RMS_THRESHOLD;
}

/**
 * The RMS a normalised buffer must exceed to count as speech. Rough on purpose — it separates "someone is
 * talking" from room tone and mic hiss, not speech from noise. Tune per mic/AGC if a quiet talker never lights up.
 */
export const VAD_RMS_THRESHOLD = 0.02;

/**
 * Whether this (transport, mode) pair needs a voice-activity METER to drive the roster highlight, rather than
 * getting it from somewhere else.
 *
 * Three drivers exist and each covers a different case: the relay decode lanes highlight a remote sender per
 * frame, the relay capture path highlights you, and `onFloorStatus` highlights the floor holder on WebRTC. The
 * combination they all miss is WebRTC + FULL_DUPLEX — there are no relay frames on either side to key off, and
 * full-duplex has no floor holder to name. Measured before the fix: a WebRTC full-duplex channel never lit a
 * single row, for anyone, while every other combination did.
 *
 * The `FULL_DUPLEX` term is load-bearing, not a tidy restriction. In a PTT mode on WebRTC the highlight is STICKY
 * — `onFloorStatus` sets it and leaves it until the floor moves — whereas a meter drives `markSpeaking`, which
 * arms a short silence timer. Running the meter in PTT would let the first pause longer than that timer clear the
 * holder's row, with nothing to re-light it until the floor next changed. So the meter must cover exactly the gap
 * and no more.
 */
export function needsVoiceMeter(transport, mode) {
	return transport === 'webrtc' && mode === 'FULL_DUPLEX';
}

/**
 * Whether the local microphone TRACK should be sending — the ONE answer both places that write
 * `MediaStreamTrack.enabled` must use. It is exactly "am I transmitting?", with no transport term and no mode
 * term: a track is a capture device, not a transport, so where its samples end up — the relay's capture worklet,
 * an `RTCRtpSender`, or both at once — cannot change whether the floor says the mic is open.
 *
 * Deleting the transport term is the whole point. `enableLocalTracks` used to write
 * `transport === 'webrtc' ? on : true`, reasoning that a relay client gates at the SEND site instead
 * (`onCapturedFrame` drops a frame while the floor is not held). That is true of the relay pipeline and false the
 * moment the SAME track is handed to a peer connection — which a relay client did whenever a WebRTC member
 * offered to it, because inbound offers were answered without consulting our own transport. Every disable site
 * was therefore an ENABLE site: one Talk press-and-release left the microphone streaming to that peer, outside
 * the server's floor and owner-mute enforcement and outside the passphrase E2EE, with both UIs showing a free
 * floor.
 *
 * Deleting the mode term matters too, and is a separate bug: `createPeer` used
 * `mode === 'FULL_DUPLEX' || transmitting`, which opened the mic on the first inbound offer regardless of
 * "Connect muted" or an owner mute. [#shouldAutoOpenMic] already weighs all three terms — `createPeer` simply
 * never asked it. Full-duplex still auto-opens, one step later and through that function, which is where the
 * decision belongs.
 *
 * `=== true` for [#grantOpensMic]'s reason: a value that is not a definite yes must not open a microphone.
 */
export function micTrackEnabled(transmitting) {
	return transmitting === true;
}

/**
 * Whether an arriving `FloorGranted` should OPEN THE MIC — the other half of the "should the mic open by itself?"
 * question above, for the push-to-talk path where the mic follows the floor.
 *
 * A grant is an ANSWER, and the answer can outlive the question. Tap Space and let go inside one round trip and the
 * order of events is: requestFloor out, key up (which sends releaseFloor), grant in. Opening the mic on that grant
 * transmits audio AFTER the user let go — until our own releaseFloor comes back as a snapshot and the release
 * reconciliation shuts it again. Invisible on localhost, where the grant beats the key-up; a real leak of speech the
 * user believes was never sent as soon as there is any latency. So the mic opens only while the control is still held.
 *
 * This cannot refuse a legitimate tap-to-claim, because there is no such thing: every floor state whose grant opens
 * the mic is a 'hold' state (LIVE / MY_TURN / IDLE with a free floor — see talkDecision), and the two 'tap' states
 * only ever toggle queue membership. Claiming a reserved turn is itself a hold.
 *
 * Full-duplex is false rather than "not applicable": the server sends no grant where there is no floor, but a grant
 * can still LAND in full-duplex — request the floor in a push-to-talk channel whose owner switches the mode while
 * that request is in flight, and `ModeChanged` overtakes it. There the mic belongs to the user's own toggle, so a
 * leftover grant must not open it (and could not say so honestly: the button reads "Mic OFF (click to talk)").
 *
 * `talkHeld === true` rather than truthiness, because a value that isn't a definite yes must fail CLOSED — this
 * decides whether a microphone starts transmitting.
 */
export function grantOpensMic(mode, talkHeld) {
	return mode !== 'FULL_DUPLEX' && talkHeld === true;
}

/**
 * What to tell someone whose grant arrived after they let go. It COACHES rather than explains: on a phone a tap is
 * routinely shorter than the round trip that fetches the floor, so this is the common case there rather than a rare
 * one, and "hold it longer" is the only thing the user can act on. The mechanics it used to describe — the mic never
 * opened, the floor went straight back — are documented on grantOpensMic and onFloorGranted, which is where someone
 * debugging will actually look. The button's own hint already covers the Space alternative, so this does not repeat it.
 */
export const TOO_QUICK_TO_TALK = 'Too quick — hold the button while you talk';

/**
 * Whether a HOLD is in progress right now: the control is a hold-gesture one and it is actually being held. Two
 * callers ask, both about an interruption that has to give the floor back even though no ordinary up-edge described it:
 *   - losing focus (another window taking it, the tab being hidden, a cancelled touch), where the keyup or mouseup is
 *     delivered to whatever took focus and never reaches this page at all;
 *   - a Space up-edge that arrives while focus has DRIFTED onto some other control since the down-edge (hold Space,
 *     press Tab, release) — spaceDrivesFloor rightly says Space is not ours any more, but the floor we took on the
 *     down-edge still has to come back, or the mic stays open with nothing held.
 *
 * Both terms carry weight:
 *   - `talkHeld` is what stops an ordinary window switch from sending a releaseFloor. Alt-tabbing with nothing held is
 *     the common case by far; the server would treat those as no-ops, but a control message per focus change is noise
 *     on a plane whose budget is spent on real floor traffic.
 *   - `mode === 'hold'` is what stops it toggling QUEUE MEMBERSHIP. The two 'tap' states release-to-leave and
 *     request-to-join a line, so treating an interruption as a tap would silently drop you out of the queue you are
 *     waiting in. Full-duplex is excluded for the same class of reason in the other direction: its mic is a user
 *     toggle, and switching windows mid-conference must not mute you.
 *
 * `talkHeld === true` matches grantOpensMic's strictness: an indefinite value is not a hold in progress. That cannot
 * strand a live mic, because in push-to-talk the mic only opens on a grant that grantOpensMic already required a
 * definite hold for.
 */
export function holdInProgress(mode, talkHeld) {
	return talkHeld === true && mode === 'hold';
}

/**
 * Whether the Space key should drive the talk floor, given the control's interaction mode and what has keyboard focus
 * — `focus` being `{tagName, isTalkButton}`, or null/undefined when nothing is focused.
 *
 * Space is not ours by right. It is the activation key of a focused `<button>` and the open key of a focused
 * `<select>`, and it pages a scrollable document. Claiming it globally (the old gate excluded only `INPUT`) meant a
 * keyboard user could not open the Channel mode dropdown at all: Space took the floor and `preventDefault()` swallowed
 * the dropdown, so the control simply looked broken. On a `<button>` the two actions raced instead, and which one won
 * depended on whether the keydown was prevented.
 *
 * So this is an ALLOW-list, not a deny-list: Space drives the floor only where no control owns it — nothing focused,
 * focus parked on the document (`BODY`/`HTML`, which is where it sits after a click on any non-focusable part of the
 * page), or focus on the Talk button itself. That last case keeps the common flow working: click Talk once with the
 * mouse and Space keeps talking afterwards, which is safe because that button carries no click handler — it drives
 * the floor from mousedown/mouseup, so a synthesized activation does nothing.
 *
 * An allow-list rather than enumerating INPUT/TEXTAREA/SELECT/BUTTON/`[contenteditable]` because the list is the part
 * that rots: a `<textarea>` or a rich-text field added later is respected here without anyone remembering to extend a
 * deny-list, and the failure direction if something unexpected has focus is "Space does what that element says",
 * which is the browser's own answer.
 */
export function spaceDrivesFloor(mode, focus) {
	if (mode !== 'hold' && mode !== 'tap') {
		return false;   // full-duplex toggles with a click, and a disabled control has no gesture to drive
	}
	// Truthiness, not `=== true` as on talkHeld: this field is built inline from `active === talkButton`, so it is a
	// boolean by construction — there is no loosely-typed source for the strictness to defend against.
	return focus == null || focus.tagName === 'BODY' || focus.tagName === 'HTML' || focus.isTalkButton;
}

/**
 * How many entries the visible queue list draws before collapsing the rest into a "+N more" tail.
 *
 * A cut is needed because the queue is UNBOUNDED server-side — `Channel.floorQueue` is a plain `LinkedHashSet`,
 * unlike the join-request list, which has `walkie.max-join-requests` — and a channel holds up to 255 members, so
 * an untruncated list could stand taller than the page it sits on. Eight makes the ordinary case (a handful of
 * raised hands) fully visible while never letting the list outgrow the roster beside it.
 *
 * Nothing actionable is lost at the cut: your OWN position is on the Talk button in every case ("In line #12 of
 * 30 — tap to leave"), which is the one number that still means something from deep in the line. That is also why
 * the truncation is a plain head-of-list slice rather than a window that follows you around — the surface that
 * answers "where am I" is the button, and this list answers "who else, and in what order".
 */
export const QUEUE_ROWS_SHOWN = 8;

/**
 * The floor queue as something to RENDER: both the ordered list panel and the per-member roster chip, derived
 * together so the two surfaces cannot disagree about who is in line or in what position.
 *
 * `entries` is the WHOLE queue and drives the roster chips — a chip belongs on a member's row whatever their
 * position, including past the visible cut. `visible` is the slice the list panel draws, with `hiddenCount` for
 * its tail. Same data, two surfaces, ONE derivation: the alternative is a list built from `waiting` and a chip
 * built from `waiting.indexOf(...)` at the call site, which is two copies of one rule. The mute badge alongside it
 * exists because that pattern was already regretted once — see `updateChannelSettings`, "the badge everyone sees
 * and the owner's tick alike, so the two cannot disagree".
 *
 * `shown` is the single gate, and it takes THREE terms rather than just the feature flag:
 *   - `floorQueueEnabled`, the owner's per-channel toggle, which can flip mid-session.
 *   - the mode: FULL_DUPLEX has no floor at all, so there is nothing to queue for — the same mode term
 *     `needsVoiceMeter` carries. The ownerless `global` room needs no case of its own, because its queue can never
 *     be enabled (its toggle answers `NOT_OWNER`), so the flag term already answers for it.
 *   - a non-empty queue, since an empty list is noise and the "✋ Queue on" badge already says the queue exists.
 *
 * Gating on the FLAG rather than on the queue's contents is what makes a disable clean. The server drains the
 * queue when the owner turns it off, and the `FloorQueueChanged` saying so arrives just before the emptied
 * snapshot — so reading the flag hides the panel a beat early instead of briefly showing a queue that is about to
 * vanish, which is the more honest of the two failures.
 *
 * `isOffered` marks the member whose claim window is ticking: exactly `waiting[0]` of a FREE floor, the rule
 * `ServerMessage.FloorStatus` documents and `floorStateFor` already applies for our own id. The `== null` on the
 * holder is loose for the same reason it is loose there — a snapshot that omits the field means a free floor, not
 * one held by `undefined`.
 */
export function queueView(view) {
	if (!view.floorQueueEnabled || view.mode === 'FULL_DUPLEX' || view.waiting.length === 0) {
		return {shown: false, size: 0, entries: [], visible: [], hiddenCount: 0};
	}
	const entries = view.waiting.map((memberId, index) => ({
		memberId,
		position: index + 1,
		isSelf: memberId === view.selfId,
		isOffered: view.holderId == null && index === 0,
	}));
	return {
		shown: true,
		size: entries.length,
		entries,
		visible: entries.slice(0, QUEUE_ROWS_SHOWN),
		hiddenCount: Math.max(0, entries.length - QUEUE_ROWS_SHOWN),
	};
}

/**
 * What a floor snapshot is worth SAYING, if anything — `null` when it should pass in silence.
 *
 * Returns `{kind, key}`: `kind` selects the wording (each client phrases it its own way), and `key` identifies the
 * SITUATION. A client logs only when the key differs from the one it last logged, which is what stops a snapshot
 * that repeats the status quo from narrating it again. That matters because FloorStatus is an authoritative
 * snapshot re-sent on many occasions — a member leaving, a mute change, a re-join — and not all of them move the
 * floor. The case that prompted this: toggling the raise-hand queue printed "Floor is free" into a floor that was
 * already free, once per toggle.
 *
 * LIVE and MY_TURN say nothing here on purpose: FloorGranted and FloorReserved are the imperative triggers that
 * announce those, and repeating them on every queue churn would talk over the alert.
 *
 * `in-line` and `offered` return the `silent` kind WHENEVER THE QUEUE PANEL IS ON SCREEN ([#queueView]'s `shown`),
 * which is why this needs the `mode` field. Both lines only repeat what the reader can already see in the panel, and
 * they repeat it on every churn: with five people in line, each arrival and departure moved everyone's number and
 * wrote a line per member, so the log filled with position updates and scrolled the things only the log can say
 * (a rotation, a rename, a mute) out of view. The gate is [#queueView]'s own, called here rather than passed in as
 * a flag, so "the panel is showing it" and "do not also say it" cannot come apart.
 *
 * `silent` is a THIRD answer, distinct from both a spoken kind and `null`, and the difference is load-bearing:
 * `null` means "forget what was last said" (the caller clears its key), whereas `silent` means "say nothing AND
 * leave the memory alone". Conflating them costs exactly what this suppression was meant to save: log "Talking: X",
 * raise your hand (suppressed), lower it again — with the key cleared, the unchanged "Talking: X" counts as new and
 * is printed again, so every raise/lower cycle reprints it even though the floor never moved.
 *
 * This is the ONE place the browser and the Java console client deliberately diverge (see the module header): the
 * console has no panel to look at, so there the narration IS the display and both kinds stay. Keep that asymmetry
 * in mind before "fixing" either side to match the other.
 *
 * `released` and `turnPassed` are transitions rather than states, and both are self-clearing (the client stops
 * transmitting / drops its awaiting-claim flag as it handles them), so they cannot repeat back-to-back — they are
 * keyed anyway so an intervening situation lets them be said again.
 */
/**
 * The answer for a snapshot the queue panel is already showing: say nothing, and leave the caller's "last thing I
 * said" memory untouched. Frozen and shared because it carries no per-snapshot data — a caller must branch on the
 * KIND, never compare identity.
 */
export const SILENT = Object.freeze({kind: 'silent'});

export function floorNarration(view) {
	const floorState = floorStateFor(view.selfId, view.holderId, view.waiting);
	if (floorState === FLOOR_LIVE || floorState === FLOOR_MY_TURN) {
		return null;
	}
	// Computed once and consulted by the two kinds the panel duplicates.
	const queueOnScreen = queueView(view).shown;
	if (floorState === FLOOR_IN_LINE) {
		if (queueOnScreen) {
			return SILENT;
		}
		const position = view.waiting.indexOf(view.selfId) + 1;
		return {kind: 'in-line', position, size: view.waiting.length, key: `in-line:${position}/${view.waiting.length}`};
	}
	if (view.awaitingClaim && view.floorQueueEnabled) {
		return {kind: 'turn-passed', key: 'turn-passed'};
	}
	if (view.released) {
		return {kind: 'released', key: 'released'};
	}
	if (view.holderId) {
		return {kind: 'talking', memberId: view.holderId, key: `talking:${view.holderId}`};
	}
	if (view.waiting.length > 0) {
		// Returning rather than falling through: the floor is RESERVED for the head, not free, so "Floor is free"
		// would be the one wrong thing to say here.
		return queueOnScreen ? SILENT : {kind: 'offered', memberId: view.waiting[0], key: `offered:${view.waiting[0]}`};
	}
	return {kind: 'free', key: 'free'};
}

/** A decision with no floor message behind it: the disabled states and the full-duplex mic toggle. */
function floorless(mode, label) {
	return {mode, label, myTurn: false, action: null};
}

/**
 * The gesture instruction for each interaction mode: what to DO with the control, where the label says what it
 * currently IS. It depends on the mode alone — every state sharing a mode is operated the same way — which is why
 * talkDecision attaches it in one place rather than repeating it per branch.
 *
 * A disabled control gets none. There is no gesture to describe, and its label already gives the reason ("Muted by
 * owner", "Floor held by X", "Waiting to be admitted…"); app.js hides the element for an empty hint so the space
 * collapses. This replaces a line of static prose that told EVERY state to hold the button — including the two tap
 * states, where holding does nothing and a tap joins or leaves the queue.
 */
const MODE_HINTS = {
	hold: 'Hold the button — or hold Space — while you talk.',
	tap: 'Tap to join or leave the line; holding does nothing here.',
	duplex: 'Full-duplex: your mic stays open. Click to mute yourself.',
	disabled: ''
};

/**
 * The Talk control's ONE decision for a snapshot of client state — what the button says, whether a gesture does
 * anything, and which floor message that gesture sends.
 *
 *   mode   — the interaction mode from the design's unified-control table:
 *              'duplex'   full-duplex mic on/off toggle (no floor);
 *              'disabled' no action at all: not connected, in no channel, owner-muted, or a busy floor with the
 *                         queue OFF;
 *              'hold'     press-and-hold drives the floor/mic (LIVE, MY_TURN, IDLE + free);
 *              'tap'      a click toggles queue membership; press/release do NOT drive the mic (IN_LINE, IDLE +
 *                         busy + queue on).
 *   label  — the button's text.
 *   hint   — the gesture instruction shown under it (see MODE_HINTS), empty where there is no gesture.
 *   myTurn — drives the pulsing "your turn" highlight.
 *   action — the control message a gesture sends (floorActionFor of our floor state), or null where there is no
 *            floor to act on. Note a hold's RELEASE is always releaseFloor, not this; see app.js releaseTalk.
 *
 * The button's `disabled` is exactly `mode === 'disabled'` — that equivalence holds in every branch below, so
 * app.js derives it instead of this returning it twice.
 *
 * Returning the label and the gesture together is the point: they used to be two independent trees (talkMode and
 * updateTalkButton), and the presentation one was missing the in-no-channel case entirely, so the button could be
 * enabled and inviting for a state in which every gesture was a no-op.
 *
 * `view` is a plain snapshot: {connected, channel, pendingChannel, selfId, muted, mode, transmitting, floorHolder,
 * floorWaiting, floorQueueEnabled, claimSecondsLeft, labelFor}. `muted` is "the owner has muted ME"; `labelFor` is
 * a member-id → display-name function, called only for the busy-floor label so the roster stays out of here;
 * `floorWaiting` is expected to be an array (app.js normalises the snapshot on arrival).
 */
export function talkDecision(view) {
	const decision = decideTalk(view);
	return {...decision, hint: MODE_HINTS[decision.mode]};
}

function decideTalk(view) {
	// Order matters, and it is the Java client's order (toggleTalk tests "no channel" before the owner-mute).
	// Nothing resets the channel MODE on a disconnect or a refused join, so a channel-less client can still be
	// holding 'FULL_DUPLEX': testing membership below that branch would hand it a working mic toggle for a channel
	// it is not in.
	if (!view.connected) {
		return floorless('disabled', 'Connect first');
	}
	if (view.channel === null) {
		// Connected but in NO channel — waiting to be admitted to a locked one, or declined. There is no floor to
		// act on, so every talk gesture is a no-op.
		//
		// This guard is load-bearing, not cosmetic: a DISABLED button still dispatches mouseleave (browsers suppress
		// activation events like click/mousedown on it, but not enter/leave), so without it merely moving the cursor
		// off the greyed-out control took the hold-release path and sent a releaseFloor, earning a NOT_IN_CHANNEL
		// from the server once per pass without the user pressing anything.
		//
		// A knocker still holding a channel (an in-place switch into a locked one) does NOT land here: the server
		// gives up your current channel only once a join succeeds, so it keeps a working control for the channel it
		// is still in. That is why membership outranks the pending knock.
		return floorless('disabled', view.pendingChannel === null ? 'Not in a channel' : 'Waiting to be admitted…');
	}
	if (view.muted) {
		// The server drops our audio and refuses us the floor regardless, but disabling here stops us talking into a
		// closed door and makes the reason plain.
		return floorless('disabled', 'Muted by owner');
	}
	if (view.mode === 'FULL_DUPLEX') {
		return floorless('duplex', view.transmitting ? 'Mic ON (click to mute)' : 'Mic OFF (click to talk)');
	}
	// Push-to-talk: both the label and the gesture come from the authoritative floor snapshot.
	const floorState = floorStateFor(view.selfId, view.floorHolder, view.floorWaiting);
	const action = floorActionFor(floorState);
	switch (floorState) {
		case FLOOR_LIVE:
			return {mode: 'hold', label: 'LIVE — release to stop', myTurn: false, action};
		case FLOOR_MY_TURN:
			// Reserved for us (hold to claim). The countdown is display-only — the server owns the real window — and is
			// absent in two REACHABLE states, so neither arm is dead code: the snapshot that makes us the head always
			// arrives BEFORE the FloorReserved carrying the window (a protocol guarantee, see ServerMessage.
			// FloorReserved), so this renders suffix-less for that one message; and it drops the suffix again once the
			// ticker reaches 0, until the authoritative snapshot flips us away.
			return {
				mode: 'hold',
				label: view.claimSecondsLeft > 0
					? `YOUR TURN — hold to talk · ${view.claimSecondsLeft}s`
					: 'YOUR TURN — hold to talk',
				myTurn: true,
				action
			};
		case FLOOR_IN_LINE:
			// Waiting in line (tap to leave). Position is 1-based; self is in the queue by definition of IN_LINE.
			return {
				mode: 'tap',
				label: `In line #${view.floorWaiting.indexOf(view.selfId) + 1} of ${view.floorWaiting.length} — tap to leave`,
				myTurn: false,
				action
			};
		default: // IDLE
			if (floorIsFree(view.floorHolder, view.floorWaiting)) {
				return {mode: 'hold', label: 'Hold to talk', myTurn: false, action};
			}
			// Floor busy: with the queue ON, offer to raise a hand (tap to join the line); with it OFF, disable the
			// button and name whoever holds it — or, when the floor is merely RESERVED, whoever it is offered to.
			return view.floorQueueEnabled
				? {mode: 'tap', label: 'Raise hand ✋', myTurn: false, action}
				: floorless('disabled', `Floor held by ${view.labelFor(view.floorHolder || view.floorWaiting[0])}`);
	}
}
