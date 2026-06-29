# Walkie-Talkie Client Protocol

A from-scratch guide for implementing a walkie-talkie **client** that interoperates with `walkie-server`.
It covers authentication, the WebSocket transports, the JSON control protocol, the relay audio wire format
(including the multi-stream framing that makes **full-duplex** work), end-to-end encryption, and the
receiver-side decode/mix pipeline.

> **Audience:** anyone writing a new client (or maintaining the reference browser/Java clients). The two
> reference clients live in `walkie-server/src/main/resources/static/` (browser) and `walkie-client-java/`
> (desktop); cross-references below point at the authoritative code.

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
2. speak the JSON control protocol (§3),
3. parse the prefixed audio wire format (§5) and run the per-sender receiver pipeline (§8–§10),
4. optionally implement end-to-end encryption (§7), byte-compatibly with the reference clients.

WebRTC (§3a) is a separate transport for true peer-to-peer media and is independent of this relay change.

---

## 2. Transport & handshake

### Login

`POST /api/auth/login` — **no request body, no authentication required** (it is the only public application
endpoint besides static assets, `/actuator/health`, `/actuator/info`, and `/error`). It returns:

```json
{ "token": "<opaque signed bearer token>" }
```

The token is a stateless, HMAC-SHA512-signed, short-lived (~60 s) credential; it is **opaque** to clients —
do not parse it, just echo it back. There is **no `/logout`** and no revocation list: the token authorizes only
the handshake and is never re-validated on the live socket, so a session ends when its WebSocket closes — and a
leaked token can open new sockets until it expires.

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
change it, and ownership transfers to another member if the owner leaves. (Exception: the server-managed
`global` channel has a sentinel owner — no participant can change its mode or become its owner.)

### Client → server

| `type`              | Fields                                           | Meaning                                                                                                                                                                                                                                                      |
|---------------------|--------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `join`              | `channel`, `mode`, `displayName`, `keyCheck`     | Join/create — or **switch** channel in place when re-sent on a live socket (§3c); `keyCheck` per §7                                                                                                                                                          |
| `leave`             | —                                                | Leave the current channel (keep the socket)                                                                                                                                                                                                                  |
| `requestFloor`      | —                                                | Ask for the talk floor (PTT modes)                                                                                                                                                                                                                           |
| `releaseFloor`      | —                                                | Release the floor                                                                                                                                                                                                                                            |
| `changeMode`        | `mode`                                           | Owner-only: change the channel mode                                                                                                                                                                                                                          |
| `rename`            | `displayName`                                    | Change your own display name in place (→ `memberRenamed`, §3c)                                                                                                                                                                                               |
| `changePassphrase`  | `keyCheck`, `wrappedKey`                         | Owner-only: rotate/clear the channel passphrase; `keyCheck` = the new one's KCV, or `null` to make it plaintext. Optional `wrappedKey` = the new passphrase encrypted under the OLD key so members auto-adopt; `null` opts out (§3c) (→ `passphraseChanged`) |
| `transferOwnership` | `newOwnerId`                                     | Owner-only: hand ownership to another current member (→ `ownerChanged`, §3c)                                                                                                                                                                                 |
| `offer`             | `target`, `sdp`                                  | WebRTC (see §3a)                                                                                                                                                                                                                                             |
| `answer`            | `target`, `sdp`                                  | WebRTC                                                                                                                                                                                                                                                       |
| `ice`               | `target`, `candidate`, `sdpMid`, `sdpMLineIndex` | WebRTC                                                                                                                                                                                                                                                       |

### Server → client

| `type`              | Fields                                              | Meaning                                                                                                                                                                                                                                 |
|---------------------|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `joined`            | `selfId`, `channel`, `mode`, `ownerId`, `members[]` | Join ack + full snapshot (re-sync on every join)                                                                                                                                                                                        |
| `memberJoined`      | `member` (`MemberInfo`)                             | A participant joined                                                                                                                                                                                                                    |
| `memberLeft`        | `memberId`                                          | A participant left/disconnected                                                                                                                                                                                                         |
| `floorGranted`      | —                                                   | You hold the floor; you may transmit                                                                                                                                                                                                    |
| `floorDenied`       | `currentHolderId`                                   | Floor request refused; names the current holder                                                                                                                                                                                         |
| `floorTaken`        | `holderId`                                          | Another member holds the floor (sent on join; also to a holder preempted by idle auto-release — §3b)                                                                                                                                    |
| `floorIdle`         | —                                                   | The floor is free (also sent to a holder force-released by max-hold — §3b)                                                                                                                                                              |
| `modeChanged`       | `mode`                                              | The channel mode changed; reset talk state                                                                                                                                                                                              |
| `ownerChanged`      | `ownerId`                                           | New owner (e.g. previous owner left)                                                                                                                                                                                                    |
| `memberRenamed`     | `memberId`, `displayName`                           | A member changed its display name (incl. you — §3c)                                                                                                                                                                                     |
| `passphraseChanged` | `keyCheck`, `wrappedKey`                            | The owner changed/cleared the channel passphrase (`null` = now unencrypted). If `wrappedKey` is present, decrypt it with your old key to auto-adopt; else re-derive from the out-of-band passphrase and verify against `keyCheck` (§3c) |
| `signalOffer`       | `from`, `sdp`                                       | WebRTC (see §3a)                                                                                                                                                                                                                        |
| `signalAnswer`      | `from`, `sdp`                                       | WebRTC                                                                                                                                                                                                                                  |
| `signalIce`         | `from`, `candidate`, `sdpMid`, `sdpMLineIndex`      | WebRTC                                                                                                                                                                                                                                  |
| `error`             | `code`, `message`                                   | A request failed (see §13 for codes)                                                                                                                                                                                                    |

`MemberInfo` = `{ id, displayName, streamId }` (see §4).

Typical flow: `login` → open `/ws/audio?token=…` → send `join` → receive `joined` (snapshot) → exchange
floor/audio → `leave`/close. (Re-send `join` any time to **switch** channels without reconnecting — §3c.)

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

## 3b. Push-to-talk floor lifecycle

A holder normally keeps the floor until it sends `releaseFloor` (or disconnects). The server may also
**revoke** it, so a client MUST treat the following as "you lost the floor" — stop transmitting and reset its
talk control:

- **`floorTaken` whose `holderId` is not your own, while you believed you held the floor** — you were
  preempted by **idle auto-release**: you sent no audio for `walkie.floor-idle-release-seconds` (default 5)
  *and* another member requested the floor. Idle is measured from frame *timing*, never content, so it works
  on encrypted channels; it applies to **relay holders only** (the server has no activity signal for WebRTC
  media, §3a).
- **`floorIdle` while you held the floor** — you were force-released by the **max-hold** cap
  (`walkie.floor-max-hold-seconds`, default 300) after holding the floor that long. Re-`requestFloor` to keep
  talking.

Both timers `0`-disable. Max-hold is a pure time cap (a periodic server sweep enforces it) and bounds **any**
holder, including a WebRTC peer (§3a); idle auto-release applies to **relay holders only**, since it needs the
per-frame activity signal that peer-to-peer WebRTC media doesn't give the server. A normal active relay talker
is **never** idle-released: it transmits continuously while holding (the mic sends a frame every 20 ms, even
through speech pauses), which refreshes the activity mark on every frame — so idle auto-release only catches a
holder that genuinely went silent on the wire without releasing.

---

## 3c. Live channel changes (switch, rename, re-key, ownership)

None of these needs a new socket — they all reuse the live connection (and its session id).

**Switch channel** — re-send `join` with a different `channel` / `mode` / `keyCheck` (and the right
`displayName`). The server handles it as *leave the old channel, then join the new one* on the **same
`WebSocketSession`**, so:

- `selfId` is **unchanged** — it *is* the session id and the socket is the same, unlike a reconnect (which
  gets a new one).
- The new `joined` is a full snapshot of the **new** channel: a new roster, mode, owner, and a **fresh
  `streamId` for every member** (stream indices are per-channel). Treat it exactly like the self-reconnect
  case (§9): **discard every decoder lane and all per-channel state, then rebuild** from `members[].streamId`.
- On the relay path, **re-derive the E2EE key for the new channel before sending `join`** — the key salts on
  the channel name (§7), so the key changes with the channel. Re-sending `join` for the channel you are
  **already** in is idempotent (the server just re-sends the snapshot); do **not** re-key in that case — to
  change the passphrase of the channel you're in, the owner uses `changePassphrase` (below), not a `join`.
- **Validation happens before the leave**, so a bad target — `invalid_channel`, `invalid_display_name`,
  `reserved_channel`, `encryption_not_allowed` — is refused and you **stay** in your current channel. The one
  exception is **`passphrase_mismatch`**: it is only detectable while joining the target (after the leave), so
  a wrong passphrase for the target channel **does** drop you from the old one — supply the correct passphrase.
- **Transport** (relay ↔ WebRTC) **cannot** switch in place — it is a different endpoint and audio pipeline; to
  change it a client must reconnect (a new socket, hence a new `selfId`). The reference browser client does
  this transparently on a transport change.

**Rename** — send `rename` with a new `displayName` (same `[A-Za-z0-9_.-]{1,32}` rule as `join`,
§13). The server updates your label and broadcasts `memberRenamed { memberId, displayName }` to the channel
**including you** — that echo, not local optimism, is the authority for your own roster label. Rename never
touches channel membership, the floor, or stream indices.

**Change the passphrase (owner)** — the channel **owner** rotates the E2EE key for everyone with
`changePassphrase`, whose `keyCheck` is the KCV (§7) of the **new** passphrase, or `null` to make the channel
plaintext. The server records the new key-check and broadcasts `passphraseChanged { keyCheck, wrappedKey }` to
**all** members (including the owner). The passphrase itself is **never** sent to the server in clear. On
`passphraseChanged`:

- If `keyCheck` is `null`, the channel is now plaintext: drop your key and send/receive in the clear.
- Otherwise adopt the new passphrase — two ways:
  - **Auto-adopt (when `wrappedKey` is present).** `wrappedKey` is the new passphrase encrypted under the
    channel's **OLD** key (base64 of an AES-256-GCM blob, the same wire crypto as a frame; §7). A member that
    still holds the old key decrypts it, re-derives the AES key from the recovered passphrase, verifies that
    against `keyCheck`, and swaps — **no out-of-band step**. The server relays the blob opaquely and never learns
    the passphrase. A blob you can't decrypt (you hold a different/older key, it was tampered, or a newer rotation
    superseded it) simply falls through to the manual path.
  - **Manual (when `wrappedKey` is absent or undecryptable).** Re-derive your AES key from the new passphrase
    obtained out-of-band and check it against `keyCheck`; swap on a match. The owner withholds `wrappedKey` for a
    **revocation-style** rotation (see the caveat below); the very first *enable* (plaintext → encrypted) has no
    old key to wrap under, so it is always manual.
- **Until you hold a key whose KCV equals the announced `keyCheck`, you are muted:** suppress transmission (send
  neither plaintext nor stale-key ciphertext) and you can't decode others. This covers BOTH the *enable*
  transition (you have **no** old key) AND a **stale-key straggler** after a rotation you haven't adopted (your
  old key no longer matches). A conformant client MUST gate its send path on "the KCV of the key I hold equals
  the channel's announced `keyCheck`" — not merely "I hold *some* key" — so a straggler can't emit audio the
  re-keyed channel can't decode and an enable can't leak plaintext. The reference clients implement this as a
  pure decision (`frameDisposition` / `outboundFrame`).
- The owner applies the new key on the **echoed** `passphraseChanged`, not optimistically — so a rejected
  request (`not_owner`, e.g. ownership was just lost) leaves the old key in place.

Notes: only the owner may rotate (`not_owner` otherwise; `not_in_channel` before joining). The server-managed
`global` room is owned by a sentinel, so a rotation there is refused — it stays unencrypted. Broadcasting the
key-check (and the wrapped blob) leaks nothing new to the server: both are brute-force-equivalent to the
ciphertext the relay already carries (§7), and the audio relay is opaque, so a brief window where members hold
different keys just drops a few GCM-failing frames — there is no atomic cross-client key swap, and no forward
secrecy. **Rotation is a transition, not revocation:** auto-distribution wraps the new key under the *old* one,
so the new passphrase is only as secret as the old — anyone who held the old key (or captured the wrapped blob)
can recover it. Withholding `wrappedKey` forces out-of-band re-entry but still can't claw the old key back from
someone who already had it; to genuinely exclude a member, move to a fresh channel.

**Transfer ownership (owner)** — the owner hands ownership to another **current member** with
`transferOwnership { newOwnerId }` (a session id). The server validates that you own the channel (`not_owner`
otherwise) and that the target is a member (`unknown_target` otherwise), reassigns the owner, and broadcasts
`ownerChanged { ownerId }` to the whole channel — the very same message a departure-triggered auto-election
sends, so clients need no new handling; the new owner simply gains the owner-only abilities (mode/passphrase
changes, further transfers). The global room's sentinel owner makes a transfer there `not_owner`. The browser
exposes this as a **Channel owner** dropdown; the Java client as `o <#id-prefix>` (the prefix shown next to
each member).

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

- **Inbound** (client → server): **no prefix** — a client never sends its own stream index; the server learns
  the sender from the connection.
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
3. **Disambiguate the body by `body[0]`** — the *same* first byte that tells a plaintext body from an encrypted one:
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

- **Key derivation:** `PBKDF2-HMAC-SHA512(passphrase, salt, 600000)` → **384 bits**, where
  `salt = "walkie-talkie:e2ee:" + effectiveChannel` (`effectiveChannel = "global"` in `GLOBAL_PTT`, else the
  channel name). First **32 bytes** = AES-256 key; next **16 bytes** = **key-check value (KCV)**. (The `global`
  branch is for byte-compatibility only — the server forces the `global` room to be unencrypted, rejecting a
  `GLOBAL_PTT` join that carries a `keyCheck` with `encryption_not_allowed`, so E2EE never actually runs there.)
- **Per frame:** AES-256-GCM, **12-byte random IV**, 128-bit tag. The scheme byte `0xE2` is passed as GCM
  **additional authenticated data (AAD)** — and AAD is **only** `{0xE2}`.
- **Key-check:** send the hex KCV in `Join.keyCheck`. The server enforces a **uniform** channel (all members
  same passphrase or all plaintext) and rejects a mismatch with `error: passphrase_mismatch` — comparing the
  KCV without ever learning the passphrase.
- **Rotation:** the channel **owner** may change the passphrase mid-session with `changePassphrase` (§3c),
  whose `keyCheck` is the KCV of the **new** passphrase (or `null` to make the channel plaintext). The server
  swaps the recorded KCV and broadcasts `passphraseChanged`; it still never sees the passphrase. Members adopt
  the new key one of two ways: **auto** — the owner may include `wrappedKey`, the new passphrase encrypted under
  the OLD key (same frame crypto), which any old-key holder decrypts and adopts with no out-of-band step (the
  server relays it opaquely); or **manual** — re-derive from the new passphrase obtained out-of-band. Either way
  the result is verified against the announced KCV, and **until a member holds a key whose KCV matches it that
  member is muted** — sending neither plaintext (the *enable* case, no old key) nor stale-key ciphertext (a
  straggler whose old key no longer matches). Auto-distribution is **not** revocation: the new key is wrapped
  under the old, so it is only as secret as the old key — the owner opts out (`wrappedKey: null`) for a
  revocation-style rotation, but truly excluding a member means moving to a fresh channel. No forward secrecy.

**Known-answer vectors** (pin these in your tests; passphrase/channel per `FrameCryptoTest`):

```
AES key   : 43321a28736472e94ff819ef9364476d5324b8fa550115409047f7da41fcbc06
key-check : c9ea045aeadb2254fff7fa0efeb4d18a
ciphertext: 64d66fb60c1fe48c515bb15362b5bcd63cca8d0a48
```

> **Security note — the SID is NOT authenticated, by design.** It is plaintext, *outside* the encrypted
> envelope, and not in the AAD. Relay E2EE's threat model is an **honest-but-curious relay**: payload
> **confidentiality** (the relay can't hear the audio) and **integrity against any party without the channel
> key** (it can't forge audio that decrypts — the GCM tag covers `{0xE2} ‖ ciphertext`) are preserved. The
> channel key is *shared*, so GCM proves a frame came from *some* passphrase-holder — **not which member**;
> there is **no per-sender authentication**. And it does **not** defend **routing authenticity or availability
> against a *malicious* relay**, which can't be done cheaply: the relay *is* the router, so it can already
> drop, reorder, duplicate, or misroute frames. A hostile relay remapping/flipping the SID (collapsing two
> talkers onto one lane → decoder garble, or spraying one talker across phantom lanes) is therefore no worse
> than its existing powers — and the phantom-lane case is already bounded by the active-speaker cap (§11). So
> **treat the SID as an untrusted routing *hint*, never an authenticated sender identity.** Authenticating the
> sender against a hostile relay is feasible but a deliberate non-goal here — and note the *real* obstacle:
> under the shared channel key, merely carrying the sender id *inside* the encrypted body proves only that *a*
> passphrase-holder wrote that id, not *which* member sent it (any key-holder can forge it). Genuine per-sender
> authenticity needs **asymmetric per-member signing keys** (out of scope here) — not binding the SID into the
> AAD (which would only *detect* relay tampering on encrypted channels while still losing the frame).

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
- **Self-reconnect or channel switch** — on **any** fresh `joined` (a reconnect/re-sync, or an in-place
  channel switch — §3c), **every** `streamId` changes; **discard all lanes** and rebuild from the new
  `members[].streamId` set. (On a reconnect the server also reassigns `selfId`; on an in-place switch the
  socket — and thus `selfId` — is unchanged.)
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
- **Inbound audio frame rate:** ≤ `walkie.max-audio-frames-per-second` per sender (default 100; ~50 fps is
  nominal). Excess frames are dropped **before** fan-out — a flood guard that counts frames without inspecting
  them, so it works on encrypted channels. Always on (0/blank → default, never disabled).
- **PTT floor timers:** max-hold force-release of **any** holder after `walkie.floor-max-hold-seconds`
  (default 300; a periodic sweep, plus a relay holder's next frame) and idle auto-release of a silent **relay**
  holder after `walkie.floor-idle-release-seconds` (default 5; on contention); each `0`-disables (§3b).
- **Error codes** (`error.code`):

| Code                     | Triggered by                                                                                                                        |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `bad_message`            | Unparseable / unknown-type control frame                                                                                            |
| `invalid_channel`        | `join` with a channel name not matching the pattern                                                                                 |
| `invalid_display_name`   | `join` or `rename` with a display name not matching the pattern                                                                     |
| `invalid_mode`           | `changeMode` to `GLOBAL_PTT` outside the `global` channel                                                                           |
| `reserved_channel`       | `join` (or in-place switch) naming the channel `global` with a non-`GLOBAL_PTT` mode                                                |
| `encryption_not_allowed` | a `GLOBAL_PTT` `join` carrying a non-null `keyCheck` (the global room is always plaintext)                                          |
| `not_in_channel`         | `requestFloor` / `releaseFloor` / `changeMode` / `changePassphrase` / `transferOwnership` / signal before `join`                    |
| `not_owner`              | `changeMode`, `changePassphrase` or `transferOwnership` by a non-owner                                                              |
| `passphrase_mismatch`    | `join` with a `keyCheck` differing from the channel's (E2EE §7); on an in-place switch (§3c) it also drops you from the old channel |
| `unknown_target`         | WebRTC signal — or `transferOwnership` — to an id not in the channel                                                                |

---

## 14. Wire format notes

The outbound relay framing is **fixed**, not negotiated: the server prefixes the 1-byte stream index on
**every** relayed binary frame, and every client demuxes it (§5). There is no capability flag and no
un-prefixed mode — a client that doesn't strip the prefix will decode **noise**.

- **`MemberInfo.streamId`** (`int`, `0..254`) carries each member's stream index, announced in `joined` /
  `memberJoined` so a client can pre-bind a lane (and its display name) before the first frame arrives.
- The E2EE known-answer vectors (§7) are independent of the framing — the encrypted **body** is byte-unchanged;
  the stream-index prefix sits outside it (and outside the GCM envelope, §7).

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

> Sample frame hexdumps and the canonical KAT inputs live in `walkie-client-java`'s `FrameCryptoTest` and the
> reference clients (`app.js`, `AudioEngine.java`); use them as the authoritative reference implementation.
