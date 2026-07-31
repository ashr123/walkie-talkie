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

| `type`                   | Fields                                           | Meaning                                                                                                                                                                                                                                                      |
|--------------------------|--------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `join`                   | `channel`, `mode`, `displayName`, `keyCheck`     | Join/create — or **switch** channel in place when re-sent on a live socket (§3c); `keyCheck` per §7                                                                                                                                                          |
| `leave`                  | —                                                | Leave the current channel (keep the socket)                                                                                                                                                                                                                  |
| `requestFloor`           | —                                                | Ask for the talk floor (PTT modes). **State-interpreted** by your floor state: grab a free floor, claim your reserved turn, or — when the queue is on and the floor is busy — join the FIFO queue (§3b)                                                      |
| `releaseFloor`           | —                                                | Give up the floor. **State-interpreted**: stop talking if you hold it, or leave the queue / decline your turn if you are waiting or reserved (§3b)                                                                                                           |
| `changeMode`             | `mode`                                           | Owner-only: change the channel mode                                                                                                                                                                                                                          |
| `rename`                 | `displayName`                                    | Change your own display name in place (→ `memberRenamed`, §3c)                                                                                                                                                                                               |
| `changePassphrase`       | `keyCheck`, `wrappedKey`                         | Owner-only: rotate/clear the channel passphrase; `keyCheck` = the new one's KCV, or `null` to make it plaintext. Optional `wrappedKey` = the new passphrase encrypted under the OLD key so members auto-adopt; `null` opts out (§3c) (→ `passphraseChanged`) |
| `transferOwnership`      | `newOwnerId`                                     | Owner-only: hand ownership to another current member (→ `ownerChanged`, §3c)                                                                                                                                                                                 |
| `muteMember`             | `memberId`, `muted`                              | Owner-only: mute/unmute one member's relay audio; server-enforced (→ `muteStatus`, §3d)                                                                                                                                                                     |
| `muteAll`                | `muted`                                          | Owner-only: mute/unmute every member but the owner at once (→ ONE `muteStatus`, §3d)                                                                                                                                                     |
| `setLocked`              | `locked`                                         | Owner-only: lock/unlock the channel to NEW members (→ `channelLocked`, §3e); existing members unaffected                                                                                                                                                     |
| `setFloorQueue`          | `enabled`                                        | Owner-only: turn this channel's push-to-talk floor queue on/off (→ `floorQueueChanged` + a fresh `floorStatus`, §3b); disabling clears any waiting queue. Full-duplex → `INVALID_MODE`; non-owner / `global` → `NOT_OWNER`                                   |
| `setMuteNewMembers`      | `enabled`                                        | Owner-only: mute every member that JOINS from now on (→ `muteNewMembersChanged`, §3d). A standing rule, the complement of `muteAll`'s one-shot; changes nobody already present. Non-owner / `global` → `NOT_OWNER`                                            |
| `resolveJoinRequest`     | `sessionId`, `admit`                             | Owner-only: admit or deny ONE newcomer waiting at this locked channel (§3f). Admitting records a one-shot approval and sends that newcomer `joinApproved` — it is its own re-sent `join` that completes the join                                             |
| `resolveAllJoinRequests` | `admit`                                          | Owner-only: admit or deny EVERY waiting newcomer, in arrival order (§3f). A no-op when nobody is waiting                                                                                                                                                     |
| `withdrawJoinRequest`    | —                                                | Stop waiting to be admitted (§3f). Sent by the waiting client itself, so it takes no arguments — a session waits at only one door. A no-op when not waiting                                                                                                  |
| `offer`                  | `target`, `sdp`                                  | WebRTC (see §3a)                                                                                                                                                                                                                                             |
| `answer`                 | `target`, `sdp`                                  | WebRTC                                                                                                                                                                                                                                                       |
| `ice`                    | `target`, `candidate`, `sdpMid`, `sdpMLineIndex` | WebRTC                                                                                                                                                                                                                                                       |

### Server → client

| `type`              | Fields                                                                             | Meaning                                                                                                                                                                                                                                  |
|---------------------|------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `joined`            | `selfId`, `channel`, `mode`, `ownerId`, `locked`, `floorQueueEnabled`, `muteNewMembers`, `members[]` | Join ack + full snapshot (re-sync on every join); `locked` = channel locked to new members (§3e); `floorQueueEnabled` = the PTT floor queue is on (§3b); `muteNewMembers` = arrivals are muted (§3d). YOUR own mute is in your `members[]` entry. A `floorStatus` follows immediately |
| `memberJoined`      | `member` (`MemberInfo`)                                                            | A participant joined                                                                                                                                                                                                                     |
| `memberLeft`        | `memberId`                                                                         | A participant left/disconnected                                                                                                                                                                                                          |
| `floorGranted`      | —                                                                                  | You hold the floor; you may transmit. Imperative "go live" trigger to the new holder only — the broadcast `floorStatus` renders who holds it (§3b)                                                                                       |
| `floorStatus`       | `holderId`, `waiting`                                                              | Authoritative PTT floor snapshot: `holderId` = live holder or `null`; `waiting` = FIFO queue. Broadcast on every floor change + after join; clients derive ALL floor UI from it (§3b)                                                    |
| `floorReserved`     | `claimSeconds`                                                                     | It's your turn: the floor is reserved for you for `claimSeconds`. Claim with `requestFloor` before the window lapses or it passes to the next in line. To-one trigger to the reserved head (§3b)                                         |
| `floorQueueChanged` | `enabled`                                                                          | The owner turned the floor queue on/off (broadcast; also in `joined.floorQueueEnabled`). When off the queue is cleared — a following `floorStatus` reflects it (§3b)                                                                     |
| `muteNewMembersChanged` | `enabled`                                                                      | The owner armed/disarmed "mute new members on entry" (broadcast; also in `joined.muteNewMembers`). Changes nobody's mute, so NO `muteStatus` accompanies it (§3d)                                                                 |
| `modeChanged`       | `mode`                                                                             | The channel mode changed; reset talk state                                                                                                                                                                                               |
| `ownerChanged`      | `ownerId`                                                                          | New owner (e.g. previous owner left)                                                                                                                                                                                                     |
| `memberRenamed`     | `memberId`, `displayName`                                                          | A member changed its display name (incl. you — §3c)                                                                                                                                                                                      |
| `muteStatus`        | `muted` (ids)                                                                      | Authoritative owner-mute snapshot: every muted id, on every change (broadcast to all, incl. the muted members — §3d)                                                                                                                                                        |
| `channelLocked`     | `locked`                                                                           | The owner locked/unlocked the channel to new members (broadcast to all — §3e)                                                                                                                                                            |
| `joinPending`       | `channel`                                                                          | Your `join` reached a locked channel that PARKS newcomers, so you are on its waiting list and its owner decides (§3f). You are not a member of it; if this was a switch you are still in the channel you were already in. Stay connected |
| `joinApproved`      | `channel`                                                                          | You are cleared to join — **re-send `join`** to complete it (§3f). One trigger for three causes it deliberately does not distinguish: the owner admitted you, the owner unlocked, or the channel was dropped (your `join` recreates it)  |
| `joinRequests`      | `requests[]` (`JoinRequestInfo`)                                                   | The channel's waiting list in arrival order — authoritative snapshot, re-sent on every change. Sent **to the owner only** (§3f); entries already approved but not yet claimed stay listed, so an approval can be revoked                 |
| `passphraseChanged` | `keyCheck`, `wrappedKey`                                                           | The owner changed/cleared the channel passphrase (`null` = now unencrypted). If `wrappedKey` is present, decrypt it with your old key to auto-adopt; else re-derive from the out-of-band passphrase and verify against `keyCheck` (§3c)  |
| `signalOffer`       | `from`, `sdp`                                                                      | WebRTC (see §3a)                                                                                                                                                                                                                         |
| `signalAnswer`      | `from`, `sdp`                                                                      | WebRTC                                                                                                                                                                                                                                   |
| `signalIce`         | `from`, `candidate`, `sdpMid`, `sdpMLineIndex`                                     | WebRTC                                                                                                                                                                                                                                   |
| `error`             | `code`, `message`                                                                  | A request failed (see §13 for codes)                                                                                                                                                                                                     |

`MemberInfo` = `{ id, displayName, streamId, muted }` (see §4; `muted` = whether the owner has muted this
member — §3d).

`JoinRequestInfo` = `{ id, displayName }` — a newcomer waiting to be admitted (§3f). Deliberately **not** a
`MemberInfo`: a waiting session is not a member and has no stream index, and inventing one would alias a real
member's audio lane (§4).

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

All floor UI derives from ONE authoritative snapshot, `floorStatus { holderId, waiting }`, which the server
broadcasts to the channel on **every** floor change and sends to you once right after `joined`. `holderId` is the
live holder (or `null`); `waiting` is the FIFO queue in order. A client renders its own state purely from the
snapshot plus its own id:

- `holderId == me` → **you are live** (you may transmit).
- `holderId == null && waiting[0] == me` → **it's your turn** (you are the reserved head).
- `me ∈ waiting` (not the head) → **in line** at position `waiting.indexOf(me)`.
- otherwise → the floor is busy (someone else holds/is offered it) or free.

There is deliberately **no `reserved` field**: the member being offered a free floor is exactly `waiting[0]`
whenever `holderId == null`, because the server reserves the head the instant the floor frees (there is never a
"free, queue non-empty, nobody reserved" state). So `reserved = (holderId == null && waiting.length > 0) ?
waiting[0] : null` — derived identically by every client.

**Two to-one imperative triggers** accompany the snapshot for the moments you must *act*. Neither carries floor
state you cannot already derive, so **treat them as prompts, never as a source of truth** — render from the
snapshot:

- **`floorGranted`** — you just acquired the floor: open your mic and transmit. (The broadcast `floorStatus`
  renders who holds it for everyone else.) This one is sent **before** its `floorStatus`, because it is what
  actually opens your mic: were the snapshot first you would briefly show yourself live over a closed mic.
- **`floorReserved { claimSeconds }`** — sent to the reserved head: it's your turn. Alert the user and start a
  `claimSeconds` countdown. **Guaranteed to arrive AFTER** the `floorStatus` that shows you as `waiting[0]` of a
  free floor, so by the time it lands your own derivation above already says *it's your turn*, and this message
  adds only the alert and the window length. You therefore never need to buffer or latch it — and a client that
  renders from the snapshot alone is never briefly told the opposite (before this guarantee, both reference
  clients spent one message showing "in line — tap to leave" for a turn that was already theirs, and acting on
  that would have *declined* it). **Grant-to-claim, never a hot mic:** you must take the normal talk action
  (`requestFloor`) within the window to go live. Miss it and the server drops you from the queue and offers the
  floor to the next in line (you will see a `floorStatus` in which you are no longer the head).

`requestFloor` / `releaseFloor` are **interpreted by your current floor state** — there are no separate queue
commands: `requestFloor` grabs a free floor, claims your reserved turn, or (when the queue is on and the floor is
busy) joins the FIFO queue; `releaseFloor` stops talking if you hold the floor, or leaves the queue / declines
your turn if you are waiting or reserved.

**The queue is owner-toggleable per channel** (`setFloorQueue` → broadcast `floorQueueChanged`; the current
state also rides in `joined.floorQueueEnabled`), **default off**. With it off there is no line — a request for a
busy floor simply is not granted and the snapshot keeps showing it busy (the pre-queue behaviour). The ownerless
`global` room is always off; full-duplex has no floor or queue.

The old imperative triggers `floorTaken` / `floorIdle` / `floorDenied` are **retired** (all subsumed by
`floorStatus`), so a client learns it **lost the floor** purely from a `floorStatus` in which it is no longer
`holderId` — treat that transition as "you lost the floor": stop transmitting and reset the talk control. The
server revokes the floor by **idle auto-release** (a relay holder silent for `walkie.floor-idle-release`,
default 5, when another member wants the floor — relay-only, measured from frame *timing* so it works on
encrypted channels) and **max-hold** (any holder past `walkie.floor-max-hold`, default 300 — a pure time
cap that also bounds a WebRTC peer, §3a). Both `0`-disable. When the queue is on, a freed floor is offered to the
queue head (a `floorStatus` to all, then a fresh `floorReserved` to that head — in that order, see above) instead
of going idle. A normal active relay
talker is **never** idle-released: it sends a frame every 20 ms (even through speech pauses), refreshing the
activity mark, so idle auto-release only catches a holder that genuinely went silent on the wire without
releasing.

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
- **Validation happens before the leave**, so a bad target — `INVALID_CHANNEL`, `INVALID_DISPLAY_NAME`,
  `RESERVED_CHANNEL`, `ENCRYPTION_NOT_ALLOWED` — is refused and you **stay** in your current channel. The one
  exception is **`PASSPHRASE_MISMATCH`**: it is only detectable while joining the target (after the leave), so
  a wrong passphrase for the target channel no longer drops you from the old one: a switch is **all-or-nothing**
  (the server gives up your current channel only once the join has actually succeeded), so a refused switch
  leaves your membership, floor and roster entry untouched — supply the correct passphrase and try again.
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
  request (`NOT_OWNER`, e.g. ownership was just lost) leaves the old key in place.

Notes: only the owner may rotate (`NOT_OWNER` otherwise; `NOT_IN_CHANNEL` before joining). The server-managed
`global` room is owned by a sentinel, so a rotation there is refused — it stays unencrypted. Broadcasting the
key-check (and the wrapped blob) leaks nothing new to the server: both are brute-force-equivalent to the
ciphertext the relay already carries (§7), and the audio relay is opaque, so a brief window where members hold
different keys just drops a few GCM-failing frames — there is no atomic cross-client key swap, and no forward
secrecy. **Rotation is a transition, not revocation:** auto-distribution wraps the new key under the *old* one,
so the new passphrase is only as secret as the old — anyone who held the old key (or captured the wrapped blob)
can recover it. Withholding `wrappedKey` forces out-of-band re-entry but still can't claw the old key back from
someone who already had it; to genuinely exclude a member, move to a fresh channel.

**Transfer ownership (owner)** — the owner hands ownership to another **current member** with
`transferOwnership { newOwnerId }` (a session id). The server validates that you own the channel (`NOT_OWNER`
otherwise) and that the target is a member (`UNKNOWN_TARGET` otherwise), reassigns the owner, and broadcasts
`ownerChanged { ownerId }` to the whole channel — the very same message a departure-triggered auto-election
sends, so clients need no new handling; the new owner simply gains the owner-only abilities (mode/passphrase
changes, further transfers). The global room's sentinel owner makes a transfer there `NOT_OWNER`. The browser
exposes this as a **Channel owner** dropdown; the Java client as `o <#id-prefix>` (the prefix shown next to
each member).

---

## 3d. Owner-enforced mute

The channel owner can silence members. `muteMember { memberId, muted }` mutes (or unmutes) one member;
`muteAll { muted }` mutes/unmutes **every member but the owner** at once. On each state change the server
broadcasts `muteStatus { muted }` — the **authoritative snapshot of every currently-muted id** — to the whole
channel, **including the muted members themselves**, so each learns to disable its own talk control. It is ONE
message however many members flipped, so `muteAll` costs each recipient a single frame rather than one per member.
A member's mute state also rides in `MemberInfo.muted` in every `joined` snapshot and `memberJoined`, so a late
joiner renders who's muted, and `muteStatus` is only ever sent for a CHANGE — the same shape as `locked` and
`floorQueueEnabled` (§3e, §3b), which ride in `joined` and then have their own change broadcasts.

The per-member `memberMuted { memberId, muted }` event is **retired**, subsumed by `muteStatus` — the same move
that retired `floorTaken` / `floorIdle` / `floorDenied` in favour of `floorStatus` (§3b).

**Muting arrivals.** `muteAll` is a one-shot over the members present when it is sent, so a newcomer arriving
afterwards is **not** muted. The standing rule is a separate owner flag, `setMuteNewMembers { enabled }` →
broadcast `muteNewMembersChanged` (state also in `joined.muteNewMembers`): while it is on, every member is muted
**as it is added**. The two compose — an owner quieting a room and keeping it quiet sends both — and neither
implies the other, so arming the rule never cuts off whoever is mid-sentence. A member muted this way learns of it
from `muted` in its OWN `joined` roster, and the others from the `memberJoined` that introduces it; there is **no**
`muteStatus` for a join, since nothing changed for anyone already there. The channel **owner** is never muted by
the rule (an owner cannot unmute itself), and an entry-muted member that later inherits the channel is unmuted
with it.

Because it carries STATE, derive mute state directly (`me ∈ muted` → you are muted) and derive TRANSITIONS
("you were muted", "X was unmuted") by **diffing** it against the set you held — exactly as `floorStatus` is
diffed for "you lost the floor". Treat `muted` as a **set**: unlike `floorStatus.waiting`, whose order IS its
meaning, the order here is unspecified and you must not depend on it — if you display several ids, impose your own
order (both reference clients sort by display name, matching their rosters). A `muteAll` therefore yields one diff
with many ids in it, which a client is free to summarise rather than reporting member by member.

- **Server-enforced, client not trusted.** While a member is muted the server **drops its relayed audio**
  (the `onAudio` fan-out gate, alongside the floor check) and **refuses it the talk floor** (`requestFloor` is
  silently denied), so a tampered client can neither be heard nor seize-and-hold the floor to block a PTT
  channel. A muted member's transmit path is stopped best-effort at the client too (mic off, talk control
  disabled with a "muted" label), but that is courtesy — the guarantee is the server drop.
- **Relay path only.** WebRTC media is peer-to-peer (DTLS-SRTP), so the server cannot drop it; a WebRTC talker
  still sees itself in `muteStatus` and stops as a courtesy, but the hard guarantee holds only on the relay
  transport — the same boundary as the E2EE payload encryption (§7).
- **Muting takes the member off the floor.** A muted member is released if it was the live holder and dequeued
  if it was waiting/reserved; the server then re-broadcasts `floorStatus` (§3b) — offering the freed floor to the
  queue head if the queue is on — so the ex-holder's client stops transmitting and the floor reopens.
- **Scope & lifetime.** Mute is per-channel state and is cleared when the member leaves (a re-used id does not
  inherit it). It is **not** related to the E2EE "muted straggler" of §3c/§7 (a member whose key doesn't match),
  which is a client-side transmit gate, not an owner action.
- **Authorization.** Only the owner may mute (`NOT_OWNER` otherwise); the owner can't mute itself and an
  unknown/left target is `UNKNOWN_TARGET`. The server-managed `global` room has a sentinel owner, so muting
  there is `NOT_OWNER`.

The browser exposes per-member **Mute**/**Unmute** buttons and a **Mute all** toggle in the Members list (owner
only, applied immediately); the Java client uses `mute <#id|all>` / `unmute <#id|all>`.

---

## 3e. Owner-locked channel

The owner locks/unlocks the channel to NEW members with `setLocked { locked }`; the server broadcasts
`channelLocked { locked }` to the whole channel and carries the current state in `Joined.locked` (so a
re-snapshot renders it). Locking blocks only **new joins** — existing members are unaffected.

- **Server-enforced in the atomic join.** While locked, `join` (or an in-place switch, §3c) from a member not
  already in the channel is refused with `CHANNEL_LOCKED` — checked **before** the key-check, so it applies
  even with the correct passphrase. The check runs inside the same `ConcurrentHashMap` bin lock as the
  key-check validation, so a `setLocked` toggle is atomic with respect to every concurrent join.
- **Only newcomers.** An existing member re-joining its **current** channel (the idempotent re-snapshot, §3c)
  is never blocked. But a member who **leaves** a locked channel can't rejoin until it's unlocked (it's a
  newcomer again).
- **A locked channel PARKS newcomers by default** (§3f): instead of `CHANNEL_LOCKED`, a newcomer receives
  `joinPending` and its owner decides. `CHANNEL_LOCKED` is then only returned when the server is configured NOT
  to park them (`walkie.max-join-requests: 0`), which is the "closed, don't even ask" setting.
- **No failure drops a switcher.** A switch is all-or-nothing (§3c): being refused — or parked — leaves you in
  the channel you were already in, with your floor and roster entry intact.
- **Authorization & lifetime.** Only the owner may lock (`NOT_OWNER` otherwise; `NOT_IN_CHANNEL` before
  joining). The lock persists across a departure-triggered ownership change — the new owner inherits it and can
  unlock. The server-managed `global` room has a sentinel owner, so locking there is `NOT_OWNER`.

The browser exposes an owner-only **Lock/Unlock channel** toggle in the Members header and a **🔒 Locked** badge
shown to everyone; the Java client uses `lock` / `unlock` and shows a 🔒 marker in `w` and the join line.

---

## 3f. Owner-approved join requests ("requests to join")

A locked channel does not turn newcomers away — it **parks** them for its owner to admit or deny. Bounded by
`walkie.max-join-requests` (default 16); set it to `0` and a locked channel refuses outright with
`CHANNEL_LOCKED` instead, which is the "closed, don't even ask" setting. There is no separate toggle: the lock
*is* the switch.

**The knocker's side.**

1. Send `join` as normal. If the target is locked and parks newcomers, you get `joinPending { channel }` instead
   of `joined` or an error. You are **not** a member of it — no roster, no floor state, no audio — and if this
   `join` was a switch you are **still in the channel you were already in** (§3c).
2. Show a waiting state and **stay connected**. Exactly one of these follows: `joinApproved`, an `error` with
   `JOIN_REQUEST_DENIED`, or nothing at all if you withdraw or disconnect.
3. On `joinApproved { channel }`, **re-send `join`** to complete it. This last step is yours by design: the
   server cannot add you itself, because a waiting newcomer may still be a member of another channel and
   leaving that one from inside the atomic join is not permitted. It also means *your* client, not the server,
   chooses the moment your audio context switches channels.
4. `withdrawJoinRequest` gives up. A session waits at only one door, so knocking elsewhere withdraws the first
   request automatically, and disconnecting scrubs it.

Re-sending `join` while waiting is harmless and idempotent — you stay in place and the owner is not re-notified,
so a client that retries cannot flood them. But re-sending it is **not** a way in: only an approval the owner
actually granted admits you.

**The owner's side.**

- `joinRequests { requests[] }` is the authoritative waiting list, in arrival order, re-sent on **every** change
  (the same doctrine as `floorStatus`: one snapshot, no incremental add/remove to drift). It is sent **to the
  owner only** — nobody else can act on it, and broadcasting it would tell every member who is knocking.
- `resolveJoinRequest { sessionId, admit }` decides one; `resolveAllJoinRequests { admit }` decides all.
  Non-owner → `NOT_OWNER`; an id that isn't waiting → `UNKNOWN_TARGET`.
- An approval is a **one-shot grant**, not an addition. It bypasses the lock only — capacity and the key-check
  still apply — and it is consumed by the newcomer's own re-sent `join`. An approval whose client never comes
  back therefore **stays on the list**, which is deliberate: it is exactly the entry an owner may want to revoke
  (`admit: false` works on it).
- A newly elected owner is handed the current list; the outgoing one is not expected to keep it.

**Lifecycle.** A request lives until the owner decides, the owner unlocks, the newcomer withdraws or
disconnects, or the channel is dropped. Nothing about it is time-driven — there is no expiry:

| Event                                | What waiting newcomers get                                                                                                                                     |
|--------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Owner admits                         | `joinApproved` (then their own `join` completes it)                                                                                                            |
| Owner denies                         | `error` `JOIN_REQUEST_DENIED`                                                                                                                                  |
| Owner **unlocks**                    | `joinApproved` — an unlocked channel admits anyone, so leaving them parked would be incoherent                                                                 |
| Last member leaves (channel dropped) | `joinApproved` — the lock died with the channel, and whoever re-sends `join` first **recreates it and owns it**                                                |
| Waiting list already full            | `error` `TOO_MANY_JOIN_REQUESTS` (transient)                                                                                                                   |
| Wrong passphrase                     | `error` `PASSPHRASE_MISMATCH` — the key-check is validated **before** parking, so an owner is never asked to approve somebody who could not have got in anyway |
| Waiting newcomer renames             | nothing to the newcomer, but the owner is re-sent `joinRequests` — the list renders that name and its membership did not change, so nothing else would refresh it. Both routes count: `rename`, or re-sending `join` with a new `displayName`. A re-`join` with the SAME name is not a change and is deliberately not re-notified, so a retry loop cannot flood the owner. A parked **switcher**'s rename is rolled back with the rest of its refused switch, so the list keeps the name its own channel knows it by |

The `global` room has a sentinel owner and so can never be locked, which means it never has a waiting list.

The browser shows the owner a **Requests to join (N)** block above the roster with per-row Admit/Deny (plus
Admit all / Deny all), and a waiting newcomer a banner with **Cancel**. The Java client uses `requests` to list,
`admit <#id|all>` / `deny <#id|all>` to decide, and `cancel` to stop waiting; `w` carries the waiting count.

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
  `GLOBAL_PTT` join that carries a `keyCheck` with `ENCRYPTION_NOT_ALLOWED`, so E2EE never actually runs there.)
- **Per frame:** AES-256-GCM, **12-byte random IV**, 128-bit tag. The scheme byte `0xE2` is passed as GCM
  **additional authenticated data (AAD)** — and AAD is **only** `{0xE2}`.
- **Key-check:** send the hex KCV in `Join.keyCheck`. The server enforces a **uniform** channel (all members
  same passphrase or all plaintext) and rejects a mismatch with `error: PASSPHRASE_MISMATCH` — comparing the
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
- **PTT floor timers:** max-hold force-release of **any** holder after `walkie.floor-max-hold`
  (default 300; a periodic sweep, plus a relay holder's next frame) and idle auto-release of a silent **relay**
  holder after `walkie.floor-idle-release` (default 5; on contention); each `0`-disables (§3b). When the
  floor queue is on, the reserved head has `walkie.floor-reservation` (default 10) to claim its turn
  before it is dropped and the floor passes to the next in line — a positive claim window, so `0`/blank falls
  back to the default, it is not "disabled" (§3b).
- **Channel size:** ≤ **255 members** (one per stream index, 0..254). A join that would overflow is refused with
  `CHANNEL_FULL` rather than assigning a colliding index.
- **Error codes** (`error.code`): the shared `ErrorCode` enum, serialized **as its constant name** (like
  `ChannelMode`). The code is the machine-readable contract; the accompanying `message` is display-only (the
  same code can carry different texts). **Tolerate unknown codes** — a newer server may mint codes your client
  doesn't know: log them and carry on (the reference Java client deserializes them to the `UNKNOWN` fallback via
  Jackson's read-unknown-enum-values-as-default + `@JsonEnumDefaultValue`; the browser's string matches simply
  fall through).

| Code                     | Triggered by                                                                                                                                                                                      |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BAD_MESSAGE`            | Unparseable / unknown-type control frame                                                                                                                                                          |
| `INVALID_CHANNEL`        | `join` with a channel name not matching the pattern                                                                                                                                               |
| `INVALID_DISPLAY_NAME`   | `join` or `rename` with a display name not matching the pattern                                                                                                                                   |
| `INVALID_MODE`           | `changeMode` to `GLOBAL_PTT` outside the `global` channel, or `setFloorQueue` in a full-duplex channel                                                                                            |
| `RESERVED_CHANNEL`       | `join` (or in-place switch) naming the channel `global` with a non-`GLOBAL_PTT` mode                                                                                                              |
| `ENCRYPTION_NOT_ALLOWED` | a `GLOBAL_PTT` `join` carrying a non-null `keyCheck` (the global room is always plaintext)                                                                                                        |
| `NOT_IN_CHANNEL`         | `requestFloor` / `releaseFloor` / `changeMode` / `changePassphrase` / `transferOwnership` / `muteMember` / `muteAll` / `setLocked` / `setFloorQueue` / signal before `join`                       |
| `NOT_OWNER`              | `changeMode`, `changePassphrase`, `transferOwnership`, `muteMember`, `muteAll`, `setLocked` or `setFloorQueue` by a non-owner                                                                     |
| `PASSPHRASE_MISMATCH`    | `join` with a `keyCheck` differing from the channel's (E2EE §7). On an in-place switch (§3c) you KEEP your current channel — a switch is all-or-nothing                                           |
| `CHANNEL_LOCKED`         | `join` (or in-place switch) to a locked channel on a server configured NOT to park newcomers (`walkie.max-join-requests: 0`, §3e). Otherwise a locked channel replies `joinPending` instead (§3f) |
| `CHANNEL_FULL`           | `join` (or in-place switch) to a channel already at its member cap (one stream index per member, 0..254 → 255 members). You keep your current channel                                             |
| `TOO_MANY_JOIN_REQUESTS` | `join` at a locked channel whose waiting list is already at `walkie.max-join-requests` (§3f). Transient — the list drains as the owner decides, so retrying later may work                        |
| `JOIN_REQUEST_DENIED`    | The owner declined your request to join (§3f). Not a malformed request: the answer was simply no, so stop waiting and stay connected                                                              |
| `UNKNOWN_TARGET`         | WebRTC signal, `transferOwnership`, or `muteMember` (unknown/left id, or the owner itself) — a target not mutable in the channel                                                                  |

---

## 14. Wire format notes

The outbound relay framing is **fixed**, not negotiated: the server prefixes the 1-byte stream index on
**every** relayed binary frame, and every client demuxes it (§5). There is no capability flag and no
un-prefixed mode — a client that doesn't strip the prefix will decode **noise**.

- **`MemberInfo.streamId`** (`int`, `0..254`) carries each member's stream index, announced in `joined` /
  `memberJoined` so a client can pre-bind a lane (and its display name) before the first frame arrives.
- **`MemberInfo.muted`** (`boolean`) carries the owner-mute state (§3d) in every `joined` / `memberJoined`, so a
  late joiner renders who's muted without waiting for a `muteStatus`.
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
> reference clients (the browser's `assets/e2ee.js` — with `assets/app.js` for the surrounding pipeline — and
> `AudioEngine.java`); use them as the authoritative reference implementation.
