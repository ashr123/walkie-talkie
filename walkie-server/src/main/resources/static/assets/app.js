// Browser walkie-talkie client. Supports both transports the server exposes:
//   - relay : audio streams over a binary WebSocket and the server fans it out.
//   - webrtc: the server relays SDP/ICE only; audio flows peer-to-peer (mesh).
// ...and all three channel modes (multi-channel PTT, global PTT, full-duplex).
//
// Audio quality: 48 kHz fullband. On the relay path we encode Opus via WebCodecs (with in-band FEC)
// when available, falling back to raw 48 kHz PCM otherwise. Each relay frame is prefixed with a
// 1-byte codec tag so receivers can decode whatever the sender used. The WebRTC path uses the
// browser's native Opus, tuned up via SDP + sender bitrate.

const $ = (id) => document.getElementById(id);
const STUN = {iceServers: [{urls: 'stun:stun.l.google.com:19302'}]};

const SAMPLE_RATE = 48000;
const FRAME_US = 20000;          // 20 ms frame, in microseconds
const MONO_BITRATE = 64000;      // fullband voice, high quality
const STEREO_BITRATE = 128000;   // stereo needs ~2x for equivalent quality
const CODEC_OPUS = 1;
const CODEC_PCM = 2;
const OPUS_SUPPORTED = (typeof AudioEncoder !== 'undefined' && typeof AudioDecoder !== 'undefined');

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
    playbackNode: null,
    micStream: null,
    channels: 1,            // negotiated in setupAudio: 2 if the mic provides stereo, else 1
    transmitting: false,
    opusEncoder: null,
    opusDecoder: null,
    decoderChannels: null,  // channel count the decoder is configured for, matched to the sender's stream
    captureTs: 0,
    decodeTs: 0,
    warnedNoOpus: false,
    warnedChannels: false,
    peers: new Map(),     // remoteId -> RTCPeerConnection
    members: new Map(),   // id -> displayName
};

function log(message) {
    const el = $('log');
    const time = new Date().toLocaleTimeString();
    el.textContent += `[${time}] ${message}\n`;
    el.scrollTop = el.scrollHeight;
}

function setStatus(connected, text) {
    $('statusDot').classList.toggle('on', connected);
    $('statusText').textContent = text;
}

function isOpen() {
    return state.ws && state.ws.readyState === WebSocket.OPEN;
}

// --- connection -----------------------------------------------------------------------------------

async function connect() {
    state.transport = $('transport').value;
    state.mode = $('mode').value;
    state.hifi = $('hifi').checked;
    const username = $('username').value.trim() || 'guest';
    const display = $('display').value.trim() || username;
    const channel = $('channel').value.trim() || 'lobby';

    try {
        const res = await fetch('/api/auth/login', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({username}),
        });
        if (!res.ok) {
            log('Login failed: HTTP ' + res.status);
            return;
        }
        const auth = await res.json();
        state.token = auth.token;
        log('Logged in as ' + auth.userId);

        await setupAudio();
        log(state.transport === 'relay'
            ? `Relay codec: ${OPUS_SUPPORTED ? 'Opus 48 kHz + FEC' : 'PCM 48 kHz (no WebCodecs)'}`
            : 'WebRTC: Opus 48 kHz (tuned)');

        const path = state.transport === 'webrtc' ? '/ws/signal' : '/ws/audio';
        const proto = location.protocol === 'https:' ? 'wss' : 'ws';
        const url = `${proto}://${location.host}${path}?token=${encodeURIComponent(state.token)}`;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';
        state.ws = ws;

        ws.onopen = () => {
            log('WebSocket open (' + state.transport + ')');
            sendCtrl({type: 'join', channel, mode: state.mode, displayName: display});
            setStatus(true, 'Connected — ' + state.transport);
            $('connectBtn').disabled = true;
            $('disconnectBtn').disabled = false;
        };
        ws.onmessage = onWsMessage;
        ws.onclose = (ev) => {
            log('WebSocket closed (' + ev.code + ')');
            cleanup();
        };
        ws.onerror = () => log('WebSocket error');
    } catch (err) {
        log('Connect error: ' + err.message);
    }
}

function disconnect() {
    logout();
    if (state.ws) {
        sendCtrl({type: 'leave'});
        state.ws.close();
    }
}

// Graceful logout: revoke the token server-side. (An unexpected disconnect is still covered by the
// server evicting the token when the WebSocket closes.) `keepalive` lets it finish during page unload.
function logout() {
    if (state.token) {
        fetch('/api/auth/logout', {
            method: 'POST',
            headers: {Authorization: 'Bearer ' + state.token},
            keepalive: true,
        }).catch(() => { /* best-effort */ });
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
            break;
        default:
            log('Unknown message: ' + msg.type);
    }
}

function onJoined(msg) {
    state.selfId = msg.selfId;
    state.ownerId = msg.ownerId;
    state.mode = msg.mode;
    state.members.clear();
    msg.members.forEach(addMember);
    renderMembers();
    log(`Joined "${msg.channel}" (${msg.mode}) with ${msg.members.length} member(s)`);
    log(state.selfId === state.ownerId
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
    const select = $('mode');
    if (isOpen()) {
        select.value = state.mode;
        select.disabled = state.selfId !== state.ownerId;
    } else {
        select.disabled = false;
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

    // A pure source: no inputs, one output of the negotiated channel count. Without outputChannelCount
    // the (default) unconnected input can make Chrome hand process() a zero-channel output — outputs[0][0]
    // is then undefined, process() throws, and Chrome stops calling it (permanent silence).
    const playback = new AudioWorkletNode(ctx, 'playback-processor', {
        numberOfInputs: 0,
        numberOfOutputs: 1,
        outputChannelCount: [state.channels],
        processorOptions: {channels: state.channels},
    });
    playback.connect(ctx.destination);
    state.playbackNode = playback;

    if (state.transport === 'relay') {
        setupRelayCodec();
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
    state.opusDecoder = new AudioDecoder({
        output: (audioData) => playbackDecoded(audioData),
        error: (e) => log('Opus decoder error: ' + e.message),
    });
    // The decoder is configured lazily in handleAudioFrame to match the SENDER's channel count (read from
    // the Opus TOC byte), not ours: WebCodecs' Opus decoder yields silence if its configured channel count
    // differs from the stream's, so a stereo sender must be decoded as stereo even on a mono listener.
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
    state.ws.send(out.buffer);
}

function handleAudioFrame(arrayBuffer) {
    if (arrayBuffer.byteLength < 2) {
        return;
    }
    const tag = new Uint8Array(arrayBuffer, 0, 1)[0];
    if (tag === CODEC_OPUS) {
        if (state.opusDecoder) {
            const payload = new Uint8Array(arrayBuffer, 1);
            // The Opus TOC byte's stereo flag (0x04) gives the stream's channel count, which may differ
            // from ours (e.g. a stereo Concentus/Java sender into a mono browser). Configure the decoder
            // to match the stream — otherwise WebCodecs decodes to silence — and reconfigure if it changes
            // (one talker at a time, so this is rare). playbackDecoded adapts the result to our output.
            const streamChannels = (payload[0] & 0x04) ? 2 : 1;
            if (state.decoderChannels !== streamChannels) {
                state.opusDecoder.configure({codec: 'opus', sampleRate: SAMPLE_RATE, numberOfChannels: streamChannels});
                state.decoderChannels = streamChannels;
                state.decodeTs = 0;
            }
            const chunk = new EncodedAudioChunk({type: 'key', timestamp: state.decodeTs, duration: FRAME_US, data: payload});
            state.decodeTs += FRAME_US;
            state.opusDecoder.decode(chunk);
        } else if (!state.warnedNoOpus) {
            state.warnedNoOpus = true;
            log('Received Opus audio but this browser cannot decode it (no WebCodecs).');
        }
    } else if (tag === CODEC_PCM) {
        // Raw Int16 LE, mono (the PCM fallback is always mono), starting at byte offset 1.
        const view = new DataView(arrayBuffer, 1);
        const count = (arrayBuffer.byteLength - 1) >> 1;
        const f32 = new Float32Array(count);
        for (let i = 0; i < count; i++) {
            f32[i] = view.getInt16(i * 2, true) / 0x8000;
        }
        enqueuePlayback(f32, count, 1);
    }
}

function playbackDecoded(audioData) {
    const frames = audioData.numberOfFrames;
    // The decoder is configured to match the stream (see handleAudioFrame), so numberOfChannels is the
    // stream's count. Copy each channel plane as f32-planar and interleave manually — the reliably
    // supported WebCodecs path; interleaved-'f32' copyTo can mis-extract a multi-channel AudioData.
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
    enqueuePlayback(interleaved, frames, srcChannels);
}

// Adapt an interleaved Float32 buffer (srcChannels) to the device's output channel count and queue it
// for the playback worklet — so a mono sender plays on a stereo listener (and vice versa) regardless of
// how the Opus decoder reports channels. Mirrors the Java client's "decode to my own channel count".
// Takes ownership of `src`: the underlying buffer is transferred (detached) once queued.
function enqueuePlayback(src, frames, srcChannels) {
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
    state.playbackNode.port.postMessage(interleaved.buffer, [interleaved.buffer]);
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
    renderMembers();
}

function removeMember(id) {
    state.members.delete(id);
    renderMembers();
}

function renderMembers() {
    const ul = $('members');
    ul.replaceChildren();
    state.members.forEach((name, id) => {
        const li = document.createElement('li');
        if (id === state.selfId) {
            const span = document.createElement('span');
            span.className = 'self';
            // textContent (not innerHTML) so a crafted display name can't inject markup.
            span.textContent = name + ' (you)';
            li.appendChild(span);
        } else {
            li.textContent = name;
        }
        ul.appendChild(li);
    });
}

function enableTalkButton(enabled) {
    $('talkBtn').disabled = !enabled;
}

function updateTalkButton() {
    const btn = $('talkBtn');
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
    state.members.clear();
    renderMembers();
    state.transmitting = false;
    closeCodec(state.opusEncoder);
    closeCodec(state.opusDecoder);
    state.opusEncoder = null;
    state.opusDecoder = null;
    state.captureTs = 0;
    state.decodeTs = 0;
    state.decoderChannels = null;
    state.warnedNoOpus = false;
    state.warnedChannels = false;
    if (state.micStream) {
        state.micStream.getTracks().forEach((t) => t.stop());
        state.micStream = null;
    }
    if (state.audioContext) {
        state.audioContext.close();
        state.audioContext = null;
    }
    state.captureNode = null;
    state.playbackNode = null;
    state.ws = null;
    state.token = null;
    state.ownerId = null;
    enableTalkButton(false);
    const talkBtn = $('talkBtn');
    talkBtn.textContent = 'Connect first';
    talkBtn.classList.remove('live');
    $('connectBtn').disabled = false;
    $('disconnectBtn').disabled = true;
    setStatus(false, 'Disconnected');
    updateModeControl();
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
    $('connectBtn').addEventListener('click', connect);
    $('disconnectBtn').addEventListener('click', disconnect);

    // While connected, only the owner can change the mode (the selector is disabled for others); this
    // sends ChangeMode and the server's ModeChanged broadcast updates everyone. Pre-connect it just
    // picks the initial mode for the next Connect.
    const modeSelect = $('mode');
    modeSelect.addEventListener('change', () => {
        if (!isOpen()) {
            return; // pre-connect: just choosing the initial mode for the next Connect
        }
        if (state.selfId === state.ownerId) {
            sendCtrl({type: 'changeMode', mode: modeSelect.value});
        } else {
            modeSelect.value = state.mode; // non-owner can't change it live — snap back
        }
    });

    const talk = $('talkBtn');
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
