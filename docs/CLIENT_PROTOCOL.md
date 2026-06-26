# Walkie-Talkie Client Protocol

A from-scratch guide for implementing a walkie-talkie **client** that interoperates with `walkie-server`.
It covers authentication, the WebSocket transports, the JSON control protocol, the relay audio wire format
(including the multi-stream framing that makes **full-duplex** work), end-to-end encryption, and the
receiver-side decode/mix pipeline.

> **Audience:** anyone writing a new client (or maintaining the reference browser/Java clients). The two
> reference clients live in `walkie-server/src/main/resources/static/` (browser) and `walkie-client-java/`
> (desktop); cross-references below point at the authoritative code.
>
> **Status:** the multi-stream relay framing described here (`relayFraming = 1`) is rolling out server-first —
> the server enabler lands before the reference clients are updated to demux/mix. Legacy (`relayFraming = 0`)
> clients keep working throughout.

---

## 1. Overview & scope

The server is a **pure, opaque forwarder** on the relay path: it never decodes, decrypts, or mixes audio. It
fans each sender's frames out to the other channel members and, as the **only** payload-touching step,
prepends a 1-byte routing tag (a per-channel *stream index*) so receivers can tell senders apart.

That one byte is what makes **simultaneous multi-talker (full-duplex) audio work on the relay**. Opus decode
is per-stream **stateful**, so a single decoder fed two senders' interleaved packets garbles. The fix is
entirely client-side: **demultiplex by sender → one Opus decoder per sender → mix the decoded PCM locally.**
The server change is just the routing tag; all the intelligence is in the receiver.

A conformant full-duplex-capable client must:
1. authenticate and open the WebSocket (§2),
2. speak the JSON control protocol (§3) and advertise `relayFraming = 1` in its `Join` (§14),
3. parse the prefixed audio wire format (§5) and run the per-sender receiver pipeline (§8–§10),
4. optionally implement end-to-end encryption (§7), byte-compatibly with the reference clients.

WebRTC (§3a) is a separate transport for true peer-to-peer media and is independent of this relay change.

---

## 2. Transport & handshake

### Login

`POST /api/auth/login` — **no request body, no authentication required** (it is the only public application
endpoint besides static assets, `/actuator/health`, and `/error`). It returns:

```json
{ "token": "<opaque signed bearer token>" }
```

The token is a stateless, HMAC-SHA512-signed, short-lived (~60 s) credential; it is **opaque** to clients —
do not parse it, just echo it back. There is **no `/logout`**: a session ends when its WebSocket closes.

### WebSocket

Every other request — including the WS handshake — requires the token. Two transports:

| Endpoint     | Frames                         | Purpose                                            |
|--------------|--------------------------------|----------------------------------------------------|
| `/ws/audio`  | binary = audio, text = control | WebSocket relay (server forwards audio)            |
| `/ws/signal` | text only                      | WebRTC signaling relay (media flows peer-to-peer)  |

Pass the token **either** as `Authorization: Bearer <token>` **or** as a `?token=<url-encoded token>` query
parameter. Browsers cannot set headers on a WS handshake, so they (and the reference Java client) use the
query parameter:

```
wss://host/ws/audio?token=<URL-encoded token>
```

A missing/garbage/expired token is rejected at the handshake (HTTP 4xx, never the WebSocket upgrade). Use
`wss://` (TLS) in production; E2EE in the browser additionally requires a secure context (HTTPS or
`localhost`).

---

## 3. Control protocol (JSON text frames)

Control messages are a sealed `ClientMessage` / `ServerMessage` hierarchy, serialized as JSON with a `type`
discriminator (Jackson 3 `@JsonTypeInfo(use = NAME, property = "type")` + `@JsonTypeName`). The `type` field
selects the record; the remaining fields are its components.

### Channel modes (`ChannelMode`)

- `MULTI_CHANNEL_PTT` — named rooms, half-duplex push-to-talk.
- `GLOBAL_PTT` — one shared room; the channel name is **forced to `global`**.
- `FULL_DUPLEX` — everyone may transmit at once (no floor).

A channel's mode is fixed at creation and **adopted** by later joiners; only the **owner** (creator) may
change it, and ownership transfers to another member if the owner leaves.

### Client → server

| `type`         | Fields                                                              | Meaning                                         |
|----------------|---------------------------------------------------------------------|-------------------------------------------------|
| `join`         | `channel`, `mode`, `displayName`, `keyCheck`, **`relayFraming`**     | Join/create a channel (see §7 `keyCheck`, §14 `relayFraming`) |
| `leave`        | —                                                                   | Leave the current channel (keep the socket)     |
| `requestFloor` | —                                                                   | Ask for the talk floor (PTT modes)              |
| `releaseFloor` | —                                                                   | Release the floor                               |
| `changeMode`   | `mode`                                                              | Owner-only: change the channel mode             |
| `offer`        | `target`, `sdp`                                                     | WebRTC (see §3a)                                |
| `answer`       | `target`, `sdp`                                                     | WebRTC                                          |
| `ice`          | `target`, `candidate`, `sdpMid`, `sdpMLineIndex`                    | WebRTC                                          |

### Server → client

| `type`          | Fields                                                                  | Meaning                                           |
|-----------------|-------------------------------------------------------------------------|---------------------------------------------------|
| `joined`        | `selfId`, `channel`, `mode`, `ownerId`, `members[]`                      | Join ack + full snapshot (re-sync on every join)  |
| `memberJoined`  | `member` (`MemberInfo`)                                                  | A participant joined                              |
| `memberLeft`    | `memberId`                                                              | A participant left/disconnected                   |
| `floorGranted`  | —                                                                       | You hold the floor; you may transmit              |
| `floorDenied`   | `currentHolderId`                                                       | Floor request refused; names the current holder   |
| `floorTaken`    | `holderId`                                                              | Another member took the floor (also sent on join) |
| `floorIdle`     | —                                                                       | The floor is free                                 |
| `modeChanged`   | `mode`                                                                  | The channel mode changed; reset talk state        |
| `ownerChanged`  | `ownerId`                                                               | New owner (e.g. previous owner left)              |
| `signalOffer`   | `from`, `sdp`                                                           | WebRTC (see §3a)                                  |
| `signalAnswer`  | `from`, `sdp`                                                           | WebRTC                                            |
| `signalIce`     | `from`, `candidate`, `sdpMid`, `sdpMLineIndex`                          | WebRTC                                            |
| `error`         | `code`, `message`                                                       | A request failed (see §13 for codes)              |

`MemberInfo` = `{ id, displayName, streamId }` (see §4).

Typical flow: `login` → open `/ws/audio?token=…` → send `join` → receive `joined` (snapshot) → exchange
floor/audio → `leave`/close.

---

## 3a. WebRTC signaling (separate transport)

Not part of the relay full-duplex change, but required for a complete client. Connect to `/ws/signal` and
relay SDP/ICE; the **server rewrites `target` → `from` = the sender's session id** and forwards. Media flows
peer-to-peer (each peer is its own independently-decoded Opus stream — which is why WebRTC has no relay-style
single-decoder limit).

- Send `offer`/`answer`/`ice` with a `target` (a member id); the recipient receives
  `signalOffer`/`signalAnswer`/`signalIce` with `from` = your id.
- Reference client uses STUN `stun:stun.l.google.com:19302`, tunes Opus via SDP fmtp
  `maxaveragebitrate=64000;maxplaybackrate=48000;stereo=0;useinbandfec=1;usedtx=0`, and sets the sender
  `maxBitrate` to 64000.

---

## 4. Identity & stream indices

- **Identity is the per-connection `WebSocketSession` id** (`MemberInfo.id`, `Joined.selfId`). It is *not* a
  username; it keys membership, the floor, ownership, and audio routing. The `displayName` is a separate,
  validated label; clients append a short `#<id-prefix>` to disambiguate duplicate display names.
- **Stream index (`streamId`)**: the server assigns each member a compact **`uint8` per-channel index** at
  join and announces it in `MemberInfo.streamId`. Values `0..254`; **`255` (`0xFF`) is reserved** (future
  "extended id" escape) and is **never announced**. This index is the routing tag prefixed onto that member's
  relayed audio frames (§5).
- **Index reuse:** a freed index is **quarantined** (not immediately reused) to avoid colliding a recycled
  index with the leaver's still-in-flight audio. A receiver must still treat indices defensively (§9): bind
  lane identity to `(streamId + memberId)` and reset on reassignment.

---

## 5. Relay audio wire format (byte-exact)

### Direction matters

- **Inbound** (client → server): **unprefixed** — exactly the legacy frame. The server learns the sender from
  the connection, so a client never sends its own stream index.
- **Outbound** (server → client): **every** binary frame gains a **1-byte plaintext stream-index prefix**.

### Outbound layout

```
[ SID (1 byte) ][ body ... ]
```

`SID` = the sender's `uint8` stream index (`0x00..0xFE`). `body` is the original frame, unchanged:

```
Plaintext body:   [ SID ][ codec tag (1) ][ payload ... ]      tag: 0x01 = Opus, 0x02 = PCM
Encrypted body:   [ SID ][ 0xE2 ][ IV (12) ][ AES-256-GCM ciphertext+tag (≥16) ]
```

Worked examples (hex; `SID` values are real allocator indices, **not** ASCII):

```
00 01 <opus packet>          first sender (index 0), Opus, plaintext
00 02 <int16 LE samples>     first sender, PCM fallback, plaintext
01 01 <opus packet>          second sender (index 1), Opus, plaintext
00 E2 <12-byte IV> <ct+tag>  first sender, end-to-end encrypted
```

### Parsing rule (unambiguous)

1. **Length guard:** if `frame.length < 2`, **drop** the whole frame (it cannot carry `[SID][≥1 body byte]`).
2. **Demux:** `sid = frame[0] & 0xFF`; `body = frame[1..]`. The SID is always present and always plaintext.
3. **Disambiguate the body by `body[0]`** — the *same* first-byte the legacy format branches on:
   - `0xE2` → **encrypted**; hand `body` to the decryptor (§7) unchanged.
   - `0x01` / `0x02` → **plaintext**; parse `[tag][payload]`.
   This is unambiguous because the codec-tag set `{0x01, 0x02}` is disjoint from the scheme byte `0xE2`. A
   `SID` that happens to equal `0x01`/`0x02`/`0xE2` is harmless — it sits at `frame[0]` and is never read as a
   tag/scheme.
4. **Minimum sizes after stripping:** plaintext body ≥ 2 bytes (`[tag][≥1 payload]`); encrypted body ≥ 29
   bytes (`[0xE2][IV(12)][ct+tag(16)]`). Reject undersized bodies (a naive `frame[1..]` on a 1–2 byte frame
   otherwise yields an empty/short body that crashes or mis-parses).

> **Critical:** strip the SID **unconditionally and first**, *before* the "is E2EE on?" branch. Forgetting to
> strip on the no-encryption path feeds `[SID][tag][payload]` to the decoder, reading the SID as the codec
> tag → noise.

---

## 6. Codec details

- **Opus** (codec tag `0x01`): 48 kHz fullband, **20 ms** frames = **960 samples per channel**, in-band FEC,
  complexity 10. Channel count is carried *inside* the Opus stream (the TOC byte's stereo flag, mask `0x04`);
  a decoder emits its configured channel count, so a mono and a stereo client interoperate.
- **PCM fallback** (codec tag `0x02`): raw **mono S16LE @ 48 kHz** (used when a sender lacks WebCodecs Opus).
- The receiver **normalizes channel layout** (mono↔stereo) to its own output count before mixing (§8).

---

## 7. End-to-end encryption (optional, relay path)

When a shared passphrase is set, the sender encrypts the **whole** `[codec tag][payload]` plaintext and the
body becomes `[0xE2][IV(12)][AES-256-GCM ciphertext+tag]`. Must be **byte-identical** across clients:

- **Key derivation:** `PBKDF2-HMAC-SHA256(passphrase, salt, 600000)` → **384 bits**, where
  `salt = "walkie-talkie:e2ee:" + effectiveChannel` (`effectiveChannel = "global"` in `GLOBAL_PTT`, else the
  channel name). First **32 bytes** = AES-256 key; next **16 bytes** = **key-check value (KCV)**.
- **Per frame:** AES-256-GCM, **12-byte random IV**, 128-bit tag. The scheme byte `0xE2` is passed as GCM
  **additional authenticated data (AAD)** — and AAD is **only** `{0xE2}`.
- **Key-check:** send the hex KCV in `Join.keyCheck`. The server enforces a **uniform** channel (all members
  same passphrase or all plaintext) and rejects a mismatch with `error: passphrase_mismatch` — comparing the
  KCV without ever learning the passphrase.

**Known-answer vectors** (pin these in your tests; passphrase/channel per `FrameCryptoTest`):

```
AES key   : 2cd28ead697478bf2e0f7225a795406d055053873e56bd6a6bd8dcc30ec967ea
key-check : a305570d2140e4933493d84916508daa
ciphertext: 10ff6aa7a86d7962ccf168f8c801068e62d50ed4a3
```

> **Security note — the SID is NOT authenticated.** The stream-index prefix is plaintext, *outside* the
> encrypted envelope, and is **not** in the AAD. Payload confidentiality and integrity are fully preserved
> (the GCM tag still covers `{0xE2} ‖ ciphertext`), but a malicious/buggy **relay** can remap, duplicate, or
> flip the SID byte per frame without breaking GCM — degrading routing (spraying one talker across phantom
> lanes to defeat the active-speaker cap) or re-introducing decoder garble (collapsing two talkers onto one
> SID). This is strictly within the existing trust boundary (the relay is trusted for *availability*, not
> *confidentiality* — it can already drop/reorder/duplicate frames) and is no worse than that. It is called
> out here so client authors don't assume the SID is trustworthy. (The SID can't be authenticated without a
> new protocol step: encryption happens on the sender, which doesn't know its server-assigned index, and
> binding server state into the AAD would break the cross-platform KAT.)

---

## 8. Receiver pipeline

Per inbound binary frame:

1. **Length guard** — drop if `frame.length < 2`.
2. **Demux** — `sid = frame[0]`, `body = frame[1..]` (always, before any E2EE branch).
3. **Decrypt** (if E2EE on) — if `body[0] == 0xE2`, decrypt `body` (serialized **per SID**, see §9); if
   `0xE2` arrives with no key set, warn-once and drop; on decrypt failure, warn-once and drop.
4. **Route** — look up the per-sender **lane** for `sid` (create if absent, subject to the active-speaker
   cap; always a **fresh** lane on an unknown/un-announced SID — §9).
5. **Decode** — `tag = plain[0]`. `0x01` → feed `plain[1..]` to **that lane's** Opus decoder; `0x02` → mono
   S16LE → float. Reconfigure a lane's decoder if its stream's channel count changes (TOC `0x04`).
6. **Normalize** — convert the decoded PCM to the receiver's output channel count (mono→stereo duplicate,
   stereo→mono average) **before** mixing, so all lanes mix in one layout.
7. **Mix** — sum the lanes (§10).

One decoder per sender removes the **cross-sender** interleave garble. (Caveat to set expectations: a lane
recreated mid-turn — after age-out, or a new PTT turn — will briefly *warble* on its first Opus frames until
inter-frame state rebuilds. That is per-stream warm-up, not the cross-sender garble this design fixes.)

---

## 9. Decoder lifecycle & lanes

A receiver holds `Map<sid → Lane>`. A **Lane** owns: the Opus decoder, its channel count + decode timestamp,
a jitter buffer, the bound `memberId`, a `lastSeen` timestamp, and (browser) its mixing node / per-SID
decrypt chain.

- **Create** lazily on the first frame for a new SID, or eagerly when a `joined`/`memberJoined` carries the
  `streamId` (lets you pre-bind a display name). Cap-aware (§11).
- **Fresh lane on unknown SID** — audio travels on a separate, lossier path than control, so a frame on a
  recycled SID can arrive *before* the `memberJoined` announcing the reassignment. Always create a **fresh**
  lane (new decoder, empty buffer) for an unknown SID rather than reusing prior state.
- **Lane identity = `(sid + memberId)`** — when the roster binds a SID to a **different** `memberId` than the
  lane holds, **drop** that lane's buffered frames and **rebuild** it (fresh decoder) before accepting more.
- **Age-out** — close a lane idle longer than `SILENCE_TTL_MS` (§11).
- **Leave** — on `memberLeft`, resolve that member's SID from the roster and **close its lane immediately**.
- **Self-reconnect** — on a fresh `joined` (your own reconnect/re-sync), the server reassigns `selfId` and
  **every** `streamId`; **discard all lanes** and rebuild from the new `members[].streamId` set.
- **Decrypt ordering** — keep decryption serialized **per SID** (a per-SID promise chain in the browser; the
  Java client decrypts synchronously on the listener thread), so a slow decrypt for one sender can't reorder
  *that* sender's frames or head-of-line-block another.

---

## 10. Mixing

- **Browser:** do **not** sum manually — give each lane its **own** Web Audio node into `ctx.destination` and
  let the graph sum natively in float. Lane nodes are created lazily (after the context is running), so they
  must replicate the existing single node's construction invariants exactly — **`numberOfInputs: 0`** and
  **`outputChannelCount: [channels]`** — or you hit the documented zero-channel-output permanent-silence bug.
- **Java:** sum manually into **one** `SourceDataLine`. Use an `int[]` accumulator sized to the **max valid
  decoded length** across lanes this tick, sum each lane's valid prefix, then **clip** each sample to
  `[-32768, 32767]` and write little-endian.
- **Both paths hard-clip** at full scale (no limiter). Two loud talkers summing past full scale **clip-distort**
  (not crash) — apply per-stream gain if you want headroom.

---

## 11. Bounds & scaling

Three caps (treat as named, justified constants):

- **`MAX_ACTIVE_DECODERS` (~8)** — a large full-duplex channel relays every sender to every receiver, so a
  receiver can face up to N−1 concurrent decoders (O(N²) fan-out). Cap concurrent decoders per receiver; when
  exceeded, evict the **longest-silent** lane (recency-based — loudness is not computable for an un-decoded
  sender). **Behavior note:** beyond the cap, some senders are silently **inaudible** until a slot frees.
  This is a receiver-only policy; **no protocol change** (the server still fans out to all).
- **`SILENCE_TTL_MS` (~3–5 s)** — lane age-out (long enough to survive speech gaps + jitter, short enough to
  free decoders).
- **Per-lane jitter buffer (~50 frames ≈ 1 s)** — bound per lane so one bursty/buffered sender can't grow
  latency for others; **drop oldest** on overflow (audio is loss-tolerant). Keep the *target* depth small
  (depth = mouth-to-ear latency).

PTT never exceeds **one** active SID, so none of these caps engage there.

---

## 12. Backpressure & loss semantics

- **Audio is droppable end-to-end.** The server's per-recipient outbound queue is bounded (~5 s) and drops
  audio under pressure; receiver lanes drop oldest. A dropped audio frame is a momentary click.
- **Control is reliable.** The server drains control **ahead of** audio on a single per-recipient queue and
  **never** silently drops a control message; a client so far behind it can't even receive control is
  disconnected, and reconnects to re-sync via the `joined` snapshot.
- **Ordering** is guaranteed **per sender** (one server-side drainer per recipient + per-SID decrypt
  ordering). Cross-sender ordering is irrelevant — each sender has its own decoder.

---

## 13. Limits, validation & error codes

- **Display name:** `[A-Za-z0-9_.-]{1,32}` (no spaces). **Channel name:** `[A-Za-z0-9_-]{1,64}`.
- **Inbound audio frame:** ≤ `walkie.max-audio-frame-bytes` (default 8192) — enforced on the **un-prefixed**
  inbound frame, so the outbound +1 SID never trips it. **Text frame:** ≤ 65536 (default).
- **Error codes** (`error.code`):

| Code                  | Triggered by                                                          |
|-----------------------|-----------------------------------------------------------------------|
| `bad_message`         | Unparseable / unknown-type control frame                              |
| `invalid_channel`     | `join` with a channel name not matching the pattern                   |
| `invalid_display_name`| `join` with a display name not matching the pattern                   |
| `invalid_mode`        | `changeMode` to `GLOBAL_PTT` outside the `global` channel             |
| `not_in_channel`      | `requestFloor` / `releaseFloor` / `changeMode` / signal before `join` |
| `not_owner`           | `changeMode` by a non-owner                                           |
| `passphrase_mismatch` | `join` with a `keyCheck` differing from the channel's (E2EE §7)       |
| `unknown_target`      | WebRTC signal to an id not in the channel                             |

---

## 14. Versioning & compatibility

The SID prefix is a **breaking** relay-audio framing change (an un-upgraded client reading a prefixed frame —
or vice-versa — decodes **noise**, since the binary channel has no in-band version header). It is
**negotiated per connection on the control plane**:

- **`Join.relayFraming`** (`int`): a client's capability — `0` (or absent) = **legacy** un-prefixed framing;
  `1` = it can parse **SID-prefixed** framing (this document). Advertise `1` from a full-duplex-capable
  client.
- The server records each connection's advertised framing and **frames its outbound audio per recipient**: a
  `relayFraming = 1` recipient receives `[SID][body]`; a legacy recipient receives the un-prefixed `body`. So
  mixed-version clients coexist in one channel with no rejection — each simply gets the framing it can parse.
  (Senders are unaffected — inbound frames are never prefixed.)
- **`MemberInfo.streamId`** (`int`, `0..254`) carries each member's stream index; legacy clients ignore it.

**Rollout:** the server understands both framings, so clients upgrade independently and a legacy client keeps
working (it just can't demux simultaneous talkers). The E2EE known-answer vectors (§7) are **not** part of
this change — the encrypted body is byte-unchanged; only the relay framing is added, per recipient.

---

## 15. Conformance checklist & test vectors

A minimal full-duplex-capable client should pass:

- [ ] **Crypto KAT** — reproduce the §7 key / key-check / ciphertext vectors exactly.
- [ ] **Framing parse** — given `00 01 <opus>`, `00 E2 <iv><ct>`, and `01 01 <opus>`: demux the correct SID,
      route to the correct lane, and decode; given a 1-byte and a 2-byte frame: **drop** (no crash).
- [ ] **Per-sender decode** — two SIDs interleaved produce two clean, independently-decoded streams (no
      cross-sender garble).
- [ ] **Mix** — both streams are audible simultaneously; layout-normalized; sum clips (not crashes) past full
      scale.
- [ ] **Lane reset** — a SID rebinding to a new `memberId` rebuilds the lane (no stale-decoder garble); a
      fresh `joined` discards all lanes (self-reconnect).
- [ ] **Caps** — beyond `MAX_ACTIVE_DECODERS`, the longest-silent lane is evicted; per-lane jitter buffer
      drops oldest at its bound; lanes age out after `SILENCE_TTL_MS` and close immediately on `memberLeft`.
- [ ] **Negotiation** — advertise `relayFraming = 1` in `Join`; a legacy peer (no/0 framing) keeps working
      alongside (the server sends it un-prefixed audio).

> Sample frame hexdumps and the canonical KAT inputs live in `walkie-client-java`'s `FrameCryptoTest` and the
> reference clients (`app.js`, `AudioEngine.java`); use them as the authoritative reference implementation.
