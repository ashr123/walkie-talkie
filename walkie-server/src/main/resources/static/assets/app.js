// Browser walkie-talkie client. Supports both transports the server exposes:
//   - relay : audio streams over a binary WebSocket and the server fans it out.
//   - webrtc: the server relays SDP/ICE only; audio flows peer-to-peer (mesh).
// ...and all three channel modes (multi-channel PTT, global PTT, full-duplex).
//
// Audio quality: 48 kHz fullband. On the relay path we encode Opus via WebCodecs (with in-band FEC)
// when available, falling back to raw 48 kHz PCM otherwise. Each relay frame is prefixed with a
// 1-byte codec tag so receivers can decode whatever the sender used. The WebRTC path uses the
// browser's native Opus, tuned up via SDP + sender bitrate.

// Tiny alias for the regular DOM accessor — NOT jQuery (there is no jQuery in this project).
const byId = (id) => document.getElementById(id);
const STUN = {iceServers: [{urls: 'stun:stun.l.google.com:19302'}]};

const SAMPLE_RATE = 48000;
const FRAME_US = 20000;          // 20 ms frame, in microseconds
const MONO_BITRATE = 64000;      // fullband voice, high quality
const STEREO_BITRATE = 128000;   // stereo needs ~2x for equivalent quality
const CODEC_OPUS = 1;
const CODEC_PCM = 2;
const E2EE_SCHEME = 0xe2;        // wire marker for an encrypted frame: [scheme][IV(12)][ciphertext+tag]; kept outside the codec-tag set {1,2} so a plaintext receiver drops it cleanly
const E2EE_AAD = Uint8Array.of(E2EE_SCHEME);   // the scheme byte, authenticated (GCM additionalData) but not encrypted, so the envelope is covered by the tag
const OPUS_SUPPORTED = (typeof AudioEncoder !== 'undefined' && typeof AudioDecoder !== 'undefined');
const DISPLAY_NAME = /^[A-Za-z0-9_.-]{1,32}$/;   // must match the server's display-name validation
const SERVER_OWNER = 'server';   // ownerId the server stamps on the server-managed "global" room (matches ConnectionService.GLOBAL_CHANNEL_OWNER); no participant owns it
const MAX_ACTIVE_DECODERS = 8;   // cap on per-sender decoders we mix at once (O(N^2) fan-out guard); evict longest-silent
const SILENCE_TTL_MS = 4000;     // close a per-sender lane after this much silence (survives speech gaps + jitter)

const state = {
    token: null,
    ws: null,
    transport: 'relay',
    mode: 'MULTI_CHANNEL_PTT',
    hifi: false,
    selfId: null,
    ownerId: null,
    audioContext: null,
    captureNode: null,
    micStream: null,
    channels: 1,            // negotiated in setupAudio: 2 if the mic provides stereo, else 1
    transmitting: false,
    connecting: false,      // true while a connect() flow is in flight — guards against double-clicking Connect
    opusEncoder: null,
    captureTs: 0,
    warnedNoOpus: false,
    warnedChannels: false,
    cryptoKey: null,        // AES-256-GCM key for relay E2EE, or null when no passphrase
    keyCheck: null,         // key-check value sent in the join so the server can reject a mismatched passphrase
    txChain: null,          // serializes async frame encryption (send side) so it can't reorder our Opus stream
    warnedDecrypt: false,
    warnedEncryptedNoKey: false,  // warn once if encrypted frames arrive while no passphrase is set
    peers: new Map(),       // remoteId -> RTCPeerConnection (WebRTC)
    members: new Map(),     // id -> displayName
    // Relay full-duplex: one decode/playback "lane" per sender, keyed by the server-assigned stream index,
    // mixed natively by ctx.destination. The maps relate stream indices to member ids for lifecycle/binding.
    lanes: new Map(),        // stream id (uint8) -> {node, decoder, decoderChannels, decodeTs, rxChain, memberId, lastSeen}
    streamOf: new Map(),     // member id -> stream id
    memberOfStream: new Map(), // stream id -> member id
    laneSweep: null,         // interval id for the silent-lane age-out sweep
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

async function connect() {
    if (state.connecting || isOpen()) {
        return;   // a connect flow is already in progress (or we're connected) — ignore extra clicks
    }
    state.transport = byId('transport').value;
    state.mode = byId('mode').value;
    state.hifi = byId('hifi').checked;
    const display = byId('display').value.trim();
    const channel = byId('channel').value.trim() || 'lobby';
    const passphrase = byId('passphrase').value;   // read once; used only on the relay path (E2EE)

    if (!DISPLAY_NAME.test(display)) {
        log('Display name must be 1-32 chars of letters, digits, _ . or - (no spaces).');
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
    byId('connectBtn').disabled = true;
    try {
        // Login takes no input: it just mints a signed, short-lived token. Identity in a channel is the
        // server-assigned session id; the display name is sent with the join below.
        const res = await fetch('/api/auth/login', {method: 'POST'});
        if (!res.ok) {
            log('Login failed: HTTP ' + res.status);
            return;
        }
        const auth = await res.json();
        state.token = auth.token;
        log('Authenticated as ' + display);

        await setupAudio();
        log(state.transport === 'relay'
            ? `Relay codec: ${OPUS_SUPPORTED ? 'Opus 48 kHz + FEC' : 'PCM 48 kHz (no WebCodecs)'}`
            : 'WebRTC: Opus 48 kHz (tuned)');

        // End-to-end encryption applies to the relay path only (WebRTC media is already peer-to-peer).
        const derived = (state.transport === 'relay' && passphrase)
            ? await deriveKey(passphrase, state.mode === 'GLOBAL_PTT' ? 'global' : channel)
            : null;
        state.cryptoKey = derived ? derived.key : null;
        state.keyCheck = derived ? derived.keyCheck : null;
        state.txChain = Promise.resolve();
        state.warnedDecrypt = false;
        if (state.transport === 'relay') {
            log(state.cryptoKey ? 'End-to-end encryption: ON (AES-256-GCM)' : 'End-to-end encryption: off');
        }

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
            byId('connectBtn').disabled = true;
            byId('disconnectBtn').disabled = false;
        };
        ws.onmessage = onWsMessage;
        ws.onclose = (ev) => {
            log('WebSocket closed (' + ev.code + ')');
            cleanup();
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

function sendCtrl(obj) {
    if (isOpen()) {
        state.ws.send(JSON.stringify(obj));
    }
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
        case 'floorGranted':
            log('Floor granted — you are live');
            beginTransmit();
            break;
        case 'floorDenied':
            log('Floor busy (held by ' + msg.currentHolderId + ')');
            break;
        case 'floorTaken':
            log('Talking: ' + (state.members.get(msg.holderId) || msg.holderId));
            break;
        case 'floorIdle':
            log('Floor is free');
            break;
        case 'modeChanged':
            onModeChanged(msg.mode);
            break;
        case 'ownerChanged':
            onOwnerChanged(msg.ownerId);
            break;
        case 'signalOffer':
            onOffer(msg.from, msg.sdp).catch((err) => log('Offer error: ' + err.message));
            break;
        case 'signalAnswer':
            onAnswer(msg.from, msg.sdp).catch((err) => log('Answer error: ' + err.message));
            break;
        case 'signalIce':
            onIce(msg.from, msg.candidate, msg.sdpMid, msg.sdpMLineIndex).catch((err) => log('ICE error: ' + err.message));
            break;
        case 'error':
            log('Server error [' + msg.code + ']: ' + msg.message);
            if (msg.code === 'passphrase_mismatch') {
                log('Disconnecting — this channel needs a different passphrase.');
                disconnect();
            }
            break;
        default:
            log('Unknown message: ' + msg.type);
    }
}

function onJoined(msg) {
    state.selfId = msg.selfId;
    state.ownerId = msg.ownerId;
    state.mode = msg.mode;
    // A fresh snapshot (including our own reconnect) reassigns every stream index, so drop all decode lanes
    // and rebuild the roster + stream-index maps from scratch.
    closeAllLanes();
    state.memberOfStream.clear();
    state.streamOf.clear();
    state.members.clear();
    msg.members.forEach(addMember);
    renderMembers();
    log(`Joined "${msg.channel}" (${msg.mode}) with ${msg.members.length} member(s)`);
    log(state.ownerId === SERVER_OWNER
        ? 'Server-managed global room — everyone can talk (push-to-talk), no owner, no encryption.'
        : state.selfId === state.ownerId
            ? 'You own this channel — use the Mode selector to change it for everyone.'
            : 'Owner: ' + (state.members.get(state.ownerId) || state.ownerId));

    if (state.transport === 'webrtc') {
        msg.members
            .filter((m) => m.id !== state.selfId)
            .forEach((m) => offerTo(m.id).catch((err) => log('Offer error: ' + err.message)));
    }

    if (state.mode === 'FULL_DUPLEX') {
        beginTransmit(); // full-duplex: mic is live as soon as you join
    } else {
        state.transmitting = false;
        enableLocalTracks(false);
    }
    enableTalkButton(true);
    updateTalkButton();
    updateModeControl();
    updateGlobalModeLocks();
}

function onModeChanged(mode) {
    state.mode = mode;
    state.transmitting = false;
    enableLocalTracks(false);
    if (mode === 'FULL_DUPLEX') {
        beginTransmit(); // full-duplex: the mic goes live immediately
    }
    updateTalkButton();
    updateModeControl();
    updateGlobalModeLocks();
    log('Mode changed to ' + mode);
}

function onOwnerChanged(ownerId) {
    state.ownerId = ownerId;
    updateModeControl();
    log(state.selfId === ownerId
        ? 'You are now the channel owner — you can change the mode.'
        : 'Channel owner is now ' + (state.members.get(ownerId) || ownerId));
}

// Reflects the live mode in the selector and lets only the owner change it while connected; when
// disconnected the selector is just the initial-mode chooser for the next Connect.
function updateModeControl() {
    const select = byId('mode');
    if (isOpen()) {
        select.value = state.mode;
        select.disabled = state.selfId !== state.ownerId;
    } else {
        select.disabled = false;
    }
}

// Locks the channel + passphrase inputs in GLOBAL_PTT mode. That mode joins the server-managed "global"
// room, which forces the channel name to "global" and forbids end-to-end encryption (the server rejects an
// encrypted global join), so both fields are misleading if editable. Each field's typed value is stashed and
// restored when switching back. Driven by the live mode when connected, else the selector.
function updateGlobalModeLocks() {
    const global = (isOpen() ? state.mode : byId('mode').value) === 'GLOBAL_PTT';
    lockInGlobalMode(byId('channel'), byId('channelHint'), 'global', global);
    lockInGlobalMode(byId('passphrase'), byId('passphraseHint'), '', global);
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
    state.transmitting = true;
    enableLocalTracks(true);
    updateTalkButton();
}

function endTransmit() {
    state.transmitting = false;
    enableLocalTracks(false);
    updateTalkButton();
}

function enableLocalTracks(on) {
    // WebRTC: gate the outgoing track. Relay: gating happens where frames are sent.
    if (state.micStream) {
        state.micStream.getAudioTracks().forEach((t) => {
            t.enabled = state.transport === 'webrtc' ? on : true;
        });
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
    state.channels = (OPUS_SUPPORTED && micSettings.channelCount === 2) ? 2 : 1;
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
        capture.port.onmessage = (e) => onCapturedFrame(e.data);
        state.captureNode = capture;
    }
}

function setupRelayCodec() {
    if (!OPUS_SUPPORTED) {
        return; // PCM fallback path; no codec objects needed
    }
    state.opusEncoder = new AudioEncoder({
        output: (chunk) => sendEncoded(chunk),
        error: (e) => log('Opus encoder error: ' + e.message),
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
    if (state.opusEncoder) {
        const int16 = new Int16Array(pcmBuffer);
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
    const out = new Uint8Array(payloadBytes.length + 1);
    out[0] = tag;
    out.set(payloadBytes, 1);
    if (!state.cryptoKey) {
        state.ws.send(out.buffer);
        return;
    }
    // Serialize encryption so async WebCrypto can't reorder the stateful Opus stream.
    state.txChain = state.txChain
        .then(() => encryptFrame(out))
        .then((enc) => {
            if (isOpen()) {
                state.ws.send(enc.buffer);
            }
        })
        .catch((err) => log('Encrypt error: ' + err.message));
}

// --- end-to-end encryption (relay path) -----------------------------------------------------------

// Derive the per-channel material from the shared passphrase. Must match the Java client and FrameCryptoTest
// exactly: PBKDF2-HMAC-SHA512, 600000 iterations, salt "walkie-talkie:e2ee:" + channel. A single 384-bit
// derivation gives the AES-256-GCM key (first 32 bytes) plus a 16-byte key-check value (next 16) sent in the
// join so the server can reject a mismatched passphrase. PBKDF2's first block is length-independent, so the
// AES key is identical to a 256-bit derivation — the known-answer test still holds. Returns {key, keyCheck}.
async function deriveKey(passphrase, effectiveChannel) {
    const enc = new TextEncoder();
    const base = await crypto.subtle.importKey('raw', enc.encode(passphrase), 'PBKDF2', false, ['deriveBits']);
    const bits = new Uint8Array(await crypto.subtle.deriveBits(
        {
            name: 'PBKDF2',
            salt: enc.encode('walkie-talkie:e2ee:' + effectiveChannel),
            iterations: 600000,
            hash: 'SHA-512'
        },
        base, 384));
    const key = await crypto.subtle.importKey('raw', bits.slice(0, 32), 'AES-GCM', false, ['encrypt', 'decrypt']);
    const keyCheck = [...bits.slice(32, 48)].map((b) => Number(b).toString(16).padStart(2, '0')).join('');
    return {key, keyCheck};
}

// Wraps a plaintext frame as scheme(1) ‖ IV(12) ‖ ciphertext+tag(16). The scheme byte lets a receiver
// distinguish an encrypted frame from a plaintext peer's [codec tag][payload] (which starts with 1 or 2).
async function encryptFrame(plaintext) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await crypto.subtle.encrypt({
        name: 'AES-GCM',
        iv,
        tagLength: 128,
        additionalData: E2EE_AAD
    }, state.cryptoKey, plaintext));
    const out = new Uint8Array(1 + iv.length + ct.length);
    out[0] = E2EE_SCHEME;
    out.set(iv, 1);
    out.set(ct, 1 + iv.length);
    return out;
}

// Recovers the plaintext frame from scheme ‖ IV ‖ ciphertext+tag; rejects on a missing scheme byte (a
// plaintext peer in an encrypted channel) or a bad tag (tampered / wrong passphrase) — never decoding ciphertext as audio.
function decryptFrame(frame) {
    if (frame.length < 29 || frame[0] !== E2EE_SCHEME) {  // 1 scheme + 12 IV + 16 tag minimum
        return Promise.reject(new Error('not an end-to-end-encrypted frame'));
    }
    return crypto.subtle.decrypt({
        name: 'AES-GCM',
        iv: frame.subarray(1, 13),
        tagLength: 128,
        additionalData: E2EE_AAD
    }, state.cryptoKey, frame.subarray(13));
}

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
    if (!state.cryptoKey) {
        processFrame(lane, body);
        return;
    }
    // Decryption is serialized PER sender (one stateful decoder per lane), so a slow decrypt for one sender
    // can't reorder that sender's frames or head-of-line-block another.
    lane.rxChain = lane.rxChain
        .then(() => decryptFrame(body))
        .then((plaintext) => processFrame(lane, new Uint8Array(plaintext)))
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
            const streamChannels = (payload[0] & 0x04) ? 2 : 1;
            if (lane.decoderChannels !== streamChannels) {
                lane.decoder.configure({codec: 'opus', sampleRate: SAMPLE_RATE, numberOfChannels: streamChannels});
                lane.decoderChannels = streamChannels;
                lane.decodeTs = 0;
            }
            const chunk = new EncodedAudioChunk({type: 'key', timestamp: lane.decodeTs, duration: FRAME_US, data: payload});
            lane.decodeTs += FRAME_US;
            lane.decoder.decode(chunk);
        } else if (!state.warnedNoOpus) {
            state.warnedNoOpus = true;
            log('Received Opus audio but this browser cannot decode it (no WebCodecs).');
        }
    } else if (tag === CODEC_PCM) {
        // Raw Int16 LE, mono (the PCM fallback is always mono), starting at body offset 1.
        const count = (body.length - 1) >> 1;
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
    const lane = {node, decoder: null, decoderChannels: null, decodeTs: 0, rxChain: Promise.resolve(), memberId, lastSeen: performance.now()};
    if (OPUS_SUPPORTED) {
        lane.decoder = new AudioDecoder({
            output: (audioData) => playbackDecoded(lane, audioData),
            error: (e) => log('Opus decoder error: ' + e.message),
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
    state.micStream.getAudioTracks().forEach((track) => {
        track.enabled = state.mode === 'FULL_DUPLEX' || state.transmitting;
        pc.addTrack(track, state.micStream);
    });
    pc.onicecandidate = (e) => {
        if (e.candidate) {
            sendCtrl({
                type: 'ice', target: remoteId,
                candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex,
            });
        }
    };
    pc.ontrack = (e) => attachRemoteAudio(remoteId, e.streams[0]);
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
        .filter((sender) => sender.track && sender.track.kind === 'audio')
        .forEach(async (sender) => {
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
    el.play().catch((e) => log('Browser blocked audio autoplay (click the page to enable): ' + e.message));
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

function addMember(member) {
    state.members.set(member.id, member.displayName);
    if (member.streamId !== undefined && member.streamId !== null) {
        state.streamOf.set(member.id, member.streamId);
        state.memberOfStream.set(member.streamId, member.id);
    }
    renderMembers();
}

function removeMember(id) {
    state.members.delete(id);
    const sid = state.streamOf.get(id);
    if (sid !== undefined) {
        closeLane(sid);   // free the departed member's decoder immediately
        state.memberOfStream.delete(sid);
        state.streamOf.delete(id);
    }
    renderMembers();
}

function renderMembers() {
    const ul = byId('members');
    ul.replaceChildren();
    // When two members share a display name, disambiguate each with a short session-id prefix (the real
    // identity); the full id is shown on hover.
    const counts = new Map();
    state.members.forEach((name) => counts.set(name, (counts.get(name) || 0) + 1));
    state.members.forEach((name, id) => {
        const duplicated = counts.get(name) > 1;
        const label = duplicated ? `${name} (#${id.slice(0, 8)})` : name;
        const li = document.createElement('li');
        if (duplicated) {
            li.title = id;
        }
        if (id === state.selfId) {
            const span = document.createElement('span');
            span.className = 'self';
            // textContent (not innerHTML) so a crafted display name can't inject markup.
            span.textContent = label + ' (you)';
            li.appendChild(span);
        } else {
            li.textContent = label;
        }
        ul.appendChild(li);
    });
}

function enableTalkButton(enabled) {
    byId('talkBtn').disabled = !enabled;
}

function updateTalkButton() {
    const btn = byId('talkBtn');
    btn.classList.toggle('live', state.transmitting);
    if (state.mode === 'FULL_DUPLEX') {
        btn.textContent = state.transmitting ? 'Mic ON (click to mute)' : 'Mic OFF (click to talk)';
    } else {
        btn.textContent = state.transmitting ? 'LIVE — release to stop' : 'Hold to talk';
    }
}

function cleanup() {
    state.peers.forEach((_, id) => closePeer(id));
    state.peers.clear();
    if (state.laneSweep) {
        clearInterval(state.laneSweep);
        state.laneSweep = null;
    }
    closeAllLanes();
    state.memberOfStream.clear();
    state.streamOf.clear();
    state.members.clear();
    renderMembers();
    state.transmitting = false;
    state.connecting = false;
    closeCodec(state.opusEncoder);
    state.opusEncoder = null;
    state.captureTs = 0;
    state.warnedNoOpus = false;
    state.warnedChannels = false;
    state.cryptoKey = null;
    state.keyCheck = null;
    state.warnedDecrypt = false;
    state.warnedEncryptedNoKey = false;
    if (state.micStream) {
        state.micStream.getTracks().forEach((t) => t.stop());
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
    byId('connectBtn').disabled = false;
    byId('disconnectBtn').disabled = true;
    setStatus(false, 'Disconnected');
    updateModeControl();
    updateGlobalModeLocks();
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

    // While connected, only the owner can change the mode (the selector is disabled for others); this
    // sends ChangeMode and the server's ModeChanged broadcast updates everyone. Pre-connect it just
    // picks the initial mode for the next Connect.
    const modeSelect = byId('mode');
    modeSelect.addEventListener('change', () => {
        updateGlobalModeLocks(); // reflect the global-mode channel lock immediately (pre- and post-connect)
        if (!isOpen()) {
            return; // pre-connect: just choosing the initial mode for the next Connect
        }
        if (state.selfId === state.ownerId) {
            sendCtrl({type: 'changeMode', mode: modeSelect.value});
        } else {
            modeSelect.value = state.mode; // non-owner can't change it live — snap back
            updateGlobalModeLocks();        // re-sync the lock to the snapped-back mode
        }
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
            await Promise.all(state.micStream.getAudioTracks().map((t) =>
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
    talk.addEventListener('touchstart', (e) => {
        e.preventDefault();
        pressTalk();
    }, {passive: false});
    talk.addEventListener('touchend', (e) => {
        e.preventDefault();
        releaseTalk();
    }, {passive: false});

    // Hold Space as a push-to-talk key.
    window.addEventListener('keydown', (e) => {
        if (e.code === 'Space' && !e.repeat && !talk.disabled && state.mode !== 'FULL_DUPLEX' && document.activeElement.tagName !== 'INPUT') {
            e.preventDefault();
            pressTalk();
        }
    });
    window.addEventListener('keyup', (e) => {
        if (e.code === 'Space' && !talk.disabled && state.mode !== 'FULL_DUPLEX' && document.activeElement.tagName !== 'INPUT') {
            e.preventDefault();
            releaseTalk();
        }
    });
});
