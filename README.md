# 📻 Walkie-Talkie Server

A real-time, push-to-talk voice server that passes **live audio streams** (not recordings) between
users. Written in **Java 25** (no `var`) on **Spring Boot 4.1.0**, with two interchangeable transports,
two reference clients, and three channel modes.

## What it does

| Choice           | Options                                                                                                                                                              |
|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Transport**    | **WebSocket relay** — the server forwards raw audio frames between members · **WebRTC** — the server relays signaling only, audio flows peer-to-peer                 |
| **Channel mode** | **Multi-channel PTT** (named rooms, half-duplex) · **Global PTT** (one shared, server-managed, always-unencrypted room) · **Full-duplex** (everyone talks at once)   |
| **Clients**      | A zero-install **browser** client · a **Java 25 desktop** client                                                                                                     |
| **Encryption**   | Optional **end-to-end encryption** on the relay path (AES-256-GCM from a shared passphrase) · WebRTC media is already end-to-end encrypted (DTLS-SRTP, peer-to-peer) |

## Architecture

```
  browser / Java clients
        │
        │  POST /api/auth/login   → mint a signed bearer token (no input)
        │  WSS  (token via Authorization header, or ?token= on the WebSocket handshake)
        ▼
  ┌─ walkie-server (Spring Boot 4.1) ───────────────────────────────────────────────────┐
  │                                                                                     │
  │  TokenAuthenticationFilter ─► Spring Security  (token = Authentication cred.)       │
  │                                                                                     │
  │  /ws/audio  (binary) ─► AudioRelayHandler ┐                                         │
  │  /ws/signal (text)   ─► SignalingHandler  ┴─► ConnectionService                     │
  │                                               ├─ ChannelRegistry ── Channel         │
  │                                               ├─ FloorControl (push-to-talk floor)  │
  │                                               └─ audio fan-out · WebRTC signaling   │
  │                                                                                     │
  │  stateless signed token, verified at the handshake (no store); WS close ends it     │
  └─────────────────────────────────────────────────────────────────────────────────────┘

  Control plane: JSON text frames — sealed ClientMessage / ServerMessage records
    (Jackson 3 polymorphic).
  Media plane (relay): 48 kHz, 20 ms frames (mono, or stereo from the Java client),
    Opus via WebCodecs (FEC) or PCM fallback, each frame prefixed with a 1-byte codec
    tag. The server fans frames out opaquely (codec-agnostic, never inspecting the
    payload); clients may additionally end-to-end encrypt each frame (AES-256-GCM
    from a shared passphrase) before sending — the server relays ciphertext blindly.
  Media plane (webrtc): Opus 48 kHz fullband, tuned (high bitrate + FEC), peer-to-peer.
```

### Relay data flow

This traces one captured frame from a sender to every other member on the **WebSocket relay** path — the
virtual-thread model, the per-recipient control/audio mailbox, and the abuse/backpressure hardening. (WebRTC
is peer-to-peer: the server relays only signaling, so media never flows through here.)

The inbound frame is handled on the *sender's* virtual thread, which runs the gates and then *hands it
off* (a non-blocking `offer()`) into each recipient's mailbox; each recipient session owns two queues —
**control** (reliable, drained first) and **audio** (bounded, lossy) — drained by its **own** dedicated
virtual thread, so a slow recipient never stalls the sender or the others. (The recipient stage is shown
once, for Bob.)

```
  Alice  (sender client)
    │   capture 20 ms  →  Opus encode  →  (optional) AES-256-GCM encrypt
    │   binary  [tag][payload]   (or  [0xE2][IV][ciphertext])
    ▼   /ws/audio   —  handled on Alice's inbound VIRTUAL THREAD
  ┌─────────────────────────────────────────────────────────────────────────────────────┐
  │ ConnectionService.onAudio        (still on Alice's inbound virtual thread)          │
  │                                                                                     │
  │ Drop the frame — no fan-out — if ANY gate fails:                                    │
  │    1.  size ≤ walkie.max-audio-frame-bytes                                          │
  │    2.  holdsFloor(Alice)?           ─  push-to-talk floor                           │
  │    3.  AudioRateLimiter.tryAcquire? ─  per-sender token bucket  (flood guard)       │
  │    4.  within max-hold?             ─  else free the floor + broadcast FloorStatus  │
  │                                                                                     │
  │ Then  prefix 1-byte stream id  →  [sid][body] ,  markFloorActivity                  │
  │ Then  Channel.forEachOther(Alice)  →  offer() one frame into EACH recipient mailbox │
  └─────────────────────────────────────────────────────────────────────────────────────┘
       │
       │   HAND-OFF ONLY: offer() returns at once, so Alice's vthread never blocks.
       │   Fan-out = one independent lane per recipient (a slow Bob never delays Carol).
       ▼   The recipient stage below is shown once, for Bob.
  ┌─────────────────────────────────────────────────────────────────────────────────────┐
  │ Bob's session  (WebSocketClientSession)   —  TWO queues, ONE drainer vthread        │
  │                                                                                     │
  │ controlOut  (cap 1024,  RELIABLE)    ─  floor / mode / owner / membership           │
  │    overflow → close the socket (POLICY_VIOLATION);                                  │
  │               the client reconnects and re-syncs via the Joined snapshot            │
  │                                                                                     │
  │ audioOut    (cap 256 ≈ 5 s,  LOSSY)  ─  the fanned-out [sid][body] frames land here │
  │    overflow → drop THIS frame   (a momentary click; the next frame heals)           │
  │                                                                                     │
  │      │   drainLoop — ONE virtual thread:                                            │
  │      ▼   drain controlOut FIRST, then audioOut — parked on a permit, no polling     │
  │ ConcurrentWebSocketSessionDecorator    —  socket-layer backstop                     │
  │      │   bounds a wedged in-flight write; write errors are swallowed,               │
  │      ▼   so one bad recipient never affects the others                              │
  └─────────────────────────────────────────────────────────────────────────────────────┘
       ▼
  Bob  (recipient client)
     demux stream id  →  (optional) decrypt  →  per-sender Opus decode  →  mix  →  play
```

### Modules

| Module               | Purpose                                                                                                                           |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `walkie-shared`      | Wire protocol: `ChannelMode`, sealed `ClientMessage` / `ServerMessage` records (Jackson-3 polymorphic). Zero Spring dependencies. |
| `walkie-server`      | Spring Boot app: both transports, channel/floor logic, token security, and the browser client under `resources/static`.           |
| `walkie-client-java` | Console desktop client: `javax.sound.sampled` capture/playback over the relay transport, on virtual threads.                      |

## Build & run

### Prerequisites

- A JDK capable of `--release 25` (JDK 25 or newer). The project compiles to the **Java 25** language
  and class-file level via `options.release = 25`, so it builds on a newer host JDK without needing a
  separate JDK 25 install. To pin a strict JDK 25 toolchain instead, see the note in
  [`build.gradle.kts`](build.gradle.kts).
- The bundled Gradle wrapper (Gradle 9.6.1) — no system Gradle required.

> **Environment note (this machine):** the shell exports a `JAVA_OPTS` containing the long-removed
> `-XX:PermSize` PermGen flags, and Gradle's launcher inherits `JAVA_OPTS`. Prefix Gradle commands with
> `JAVA_OPTS=` to neutralize it: `JAVA_OPTS= ./gradlew build`.

### Build and test

```bash
JAVA_OPTS= ./gradlew build      # compiles all modules and runs the test suite
```

The suite includes the **browser client's** tests: plain ES-module tests under
[`walkie-server/src/test/js/`](walkie-server/src/test/js) that run on **Node's built-in test runner** (no npm
dependencies). They pin the relay end-to-end-encryption to the same known-answer vectors as the Java
`FrameCryptoTest`, so both clients stay byte-for-byte interoperable, and assert the no-plaintext transmit gate.
`./gradlew build` runs them via the `:walkie-server:jsTest` task when `node` is on `PATH` (it logs a skip if
not). To run them directly: `node --test walkie-server/src/test/js/e2ee.test.js`.

### Run the server

```bash
JAVA_OPTS= ./gradlew :walkie-server:bootRun
# or
java -jar walkie-server/build/libs/walkie-server-0.1.0.jar
```

By default the server runs over **HTTPS on `https://localhost:8443`** with an auto-generated self-signed
certificate (also valid for `https://[::1]:8443`; your browser shows a one-time warning) — see *Transport
encryption (TLS / WSS)* below. To run plain HTTP on `http://localhost:8080` instead, set
`walkie.tls.enabled=false`. Open the URL in a browser to use the web client.

#### Signing key (real / multi-instance deployment)

The bearer token is signed with **HMAC-SHA512**. With no key configured, the server generates a random key
at startup — fine for a single local instance, but tokens then won't survive a restart or validate across
instances (you'll see a `WARN` saying so). For a real or horizontally-scaled deployment, give **every
instance the same key** via `WALKIE_AUTH_SIGNING_KEY` (or the `walkie.auth-signing-key` property). Generate
a strong random key (≥ 64 bytes, to match the 512-bit MAC) and keep it out of source control:

```bash
# generate a key (store it in your secrets manager, not in the repo)
# the base64 text itself becomes the key (~88 chars / well over 64 bytes of UTF-8, matching the 512-bit MAC)
openssl rand -base64 64

# run with it (every instance uses the same value)
WALKIE_AUTH_SIGNING_KEY="$(openssl rand -base64 64)" java -jar walkie-server/build/libs/walkie-server-0.1.0.jar
```

### Transport encryption (TLS / WSS)

**TLS is on by default**, so the whole connection is encrypted in transit — control messages, the binary
audio frames, the HTTPS login, and the `?token=` on the WebSocket handshake. (The optional audio passphrase
is a separate, end-to-end layer that protects only the audio *payload* between participants; it is **not** a
substitute for transport TLS, and control messages are never passphrase-encrypted because the server has to
read and act on them.)

**Local dev (zero-config).** With no keystore configured, the server **auto-generates a self-signed localhost
certificate on first use** (into `~/.walkie-talkie/`) and **reuses it across restarts** (regenerating only if it
is missing, expired, the wrong key size, or from the older IPv4-only SAN set). It is valid for
`localhost`, `127.0.0.1`, and `::1`, so both `https://localhost:8443` and `https://[::1]:8443` work.
The browser shows a one-time "accept the certificate" warning. The **Java client auto-trusts** this dev cert
on localhost (it reads the exported `~/.walkie-talkie/dev-cert.pem`), so it just works — and TLS verification
is never disabled.

**Your own / a stronger cert.** Point the server at a keystore via the environment (path + password are never
hardcoded or committed):

```bash
export WALKIE_TLS_KEYSTORE_PASSWORD=…              # your keystore password
scripts/gen-dev-cert.sh                            # optional: writes ./dev-keystore.p12 (EC P-384) + ./dev-cert.pem
WALKIE_TLS_KEYSTORE="file:$PWD/dev-keystore.p12" JAVA_OPTS= ./gradlew :walkie-server:bootRun
# Java client against a custom cert:  --tls-truststore ./dev-cert.pem
```

**Turn TLS off** with `walkie.tls.enabled=false` — the server then serves plain `http://localhost:8080`. Use
this for the production model below (a TLS-terminating reverse proxy on a trusted loopback).

**Production — terminate TLS at a reverse proxy.** Run the app with `walkie.tls.enabled=false` (plain HTTP on
loopback) behind a proxy. Ready-to-edit configs are in [`deploy/`](deploy): a [`Caddyfile`](deploy/Caddyfile)
(automatic HTTPS via Let's Encrypt) and an [`nginx.conf.example`](deploy/nginx.conf.example) (mind the
`Upgrade`/`Connection` headers the WebSocket endpoints require). Tighten `walkie.allowed-origins` to your
HTTPS origin so the handshake's origin check (anti-CSWSH) accepts only your real site. TLS 1.3 / 1.2 only
throughout.

### Browser client

1. Open <https://localhost:8443> (or <https://[::1]:8443>) and accept the one-time self-signed-certificate
   warning (or <http://localhost:8080> if you started the server with `walkie.tls.enabled=false`).
2. Pick a transport, channel mode, and channel (optionally tick **High fidelity** to disable the mic
   noise-suppression/echo-cancellation DSP — this can be toggled **live** while connected and applies
   immediately). To encrypt audio **end-to-end**, set the same **Encryption
   passphrase** as everyone else in the channel (relay transport only; needs HTTPS or `localhost`).
   Click **Connect** and allow microphone access.
3. **Push-to-talk** modes: hold the big button (or hold **Space**). If the owner has enabled the channel's
   **floor queue** (a "raise hand" line — owner-only **Enable queue**/**Disable queue** control) and the floor
   is busy, the button flips to **tap to raise your hand**: you join the line, see your place, and tap again to
   leave. When your turn comes the button pulses **YOUR TURN — hold to talk** with a countdown (grant-to-claim);
   hold it in time to go live or the turn passes to the next person. **Full-duplex**: click to toggle your
   mic — or tick **Connect muted** before connecting to join with the mic off (it stays muted until you
   click Talk). That checkbox shows only in full-duplex mode and locks once connected.
4. **Disconnect** (or closing the tab) closes the WebSocket, which ends your session — the signed token is
   stateless and self-expiring, so there's nothing to revoke.

A channel's mode belongs to whoever **created** it: a later joiner adopts the existing mode (the server
sends it), and only the owner can change it (for non-owners the **Mode** selector is disabled). If the owner
leaves, ownership passes to another member — and the owner can also **hand ownership over** deliberately (see
below).

**Channel properties, applied in one click.** Think of **Channel**, **Transport**, **Mode** and **Encryption
passphrase** as the channel's properties (the form lists the **Channel** first, because it gates the rest while
connected). Change any of them and click the single adaptive button:

- It reads **Switch channel** when you've changed the channel name — it moves you to that room *without
  dropping the session* (the same WebSocket, session id, microphone and audio context are reused; the server
  leaves your old channel and joins the new one, carrying the chosen mode/passphrase), and the fresh roster
  snapshot re-syncs everything.
- It reads **Apply changes** when the channel name is unchanged — it applies your **transport**, **mode** and
  **passphrase** changes to the *current* channel in one click. While connected, those three are editable **only
  by the channel owner** on the current channel; for a non-owner the Transport and Mode selectors are disabled
  and the Encryption passphrase field is read-only. They all re-enable the moment you change the **channel name**
  (you're switching to a different room, so you pick its properties like a fresh connect), and the passphrase
  field also re-enables to adopt an owner's announced re-key. Changing the **transport** can't be done in place
  (a different endpoint + audio pipeline), so it reconnects transparently as a new session.
- It's **disabled** when nothing has changed.

The owner rotating the **passphrase** (or clearing it to turn encryption off) never reaches the server as a
secret — the client only sends the new *key-check*. By default the owner also **auto-shares** the new
passphrase: it's wrapped (encrypted) under the channel's *old* key and relayed, so connected members decrypt it
with the key they already hold and **adopt it automatically** — the server still never sees the passphrase. The
**Share new passphrase with current members** checkbox (shown to the owner during an encrypted→encrypted
rotation) turns that off for a **revocation-style** rotation, where members must re-enter the new secret
**out-of-band**; the very first time you enable encryption is always out-of-band too (there's no old key to wrap
under). Every change is announced (no silent downgrade). A member that doesn't yet hold a key matching the new
key-check stays in the channel but is **muted** — it can't be heard and can't decode others, and **never falls
back to sending audio in the clear** (nor stale-key audio the re-keyed channel can't decode) — until it adopts
the new key (automatically, or by typing the new passphrase and clicking **Apply changes**). Note this means
**rotation is a transition, not revocation**: the new key is only as secret as the old one it's wrapped under —
to genuinely lock someone out, move to a fresh channel.

**Hand over ownership.** The **Channel owner** dropdown is shown **only to the current owner** (everyone else
already sees who owns the channel from the 👑 crown in the members list); the owner picks another member to make
them the owner, and everyone's owner-only controls update. The connected-only **Rename** button changes just
your display name in place (everyone's roster updates); it leaves the channel untouched.

**Mute participants.** The channel owner can silence a member: each member row in the **Members** list gets a
**Mute**/**Unmute** button (shown only to the owner), and a **Mute all** toggle in the panel header mutes (or
un-mutes) everyone else at once. These apply **immediately** — there's no Apply step. A muted member is shown
dimmed with a 🔇 marker for everyone. Enforcement is on the **server**, which does not trust the clients: it
**drops a muted member's relayed audio** and refuses it the talk floor, so a member can't talk around a mute by
tampering with its client. If the owner mutes someone who is *currently* talking, their floor is freed at once
and their client stops transmitting and shows **Muted by owner** on a disabled talk button (re-enabled on
unmute). The mute is per-channel — it's cleared when the member leaves — and the ownerless `global` room can't be
muted. (Caveat: on the **WebRTC** transport, media is peer-to-peer, so the server can't enforce the mute on the
audio itself; the muted client still stops sending as a courtesy, but the guarantee holds only on the relay
transport.)

**Lock the channel.** The owner can freeze the room to newcomers: a **Lock channel** / **Unlock channel** toggle
in the **Members** panel header (owner-only, applied immediately) stops anyone else joining — even with the right
passphrase. Everyone sees a **🔒 Locked** badge while it's on. Enforcement is on the **server** (the join is
refused with `CHANNEL_LOCKED`), so it doesn't rely on the clients. Existing members are unaffected; a member who
*leaves* a locked channel, though, can't come back until it's unlocked. (The ownerless `global` room can't be
locked.)

Open the page in two tabs (or two machines) to talk between them.

### Java desktop client

A console client over the WebSocket-relay transport. Run it with the Gradle `run` task, passing CLI flags
through `--args`:

```bash
JAVA_OPTS= ./gradlew :walkie-client-java:run --args="\
  --server https://localhost:8443 --channel team1 --mode ptt --display Alice"
```

Or build a single runnable fat jar and launch it directly:

```bash
JAVA_OPTS= ./gradlew :walkie-client-java:fatJar
java -jar walkie-client-java/build/libs/walkie-client-java-0.1.0-all.jar \
  --server https://localhost:8443 --channel team1 --mode ptt --display Alice
```

All flags are optional (run with `--args="--help"` for the full list):

| Flag                         | Default                  | Purpose                                                                                                                                                                                                                                                                      |
|------------------------------|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--server <url>`             | `https://localhost:8443` | Base URL of the server (on localhost the dev cert is auto-trusted; use `http://…` for a server run with `walkie.tls.enabled=false`).                                                                                                                                         |
| `--channel <name>`           | `lobby`                  | Channel to join (ignored for global mode; the name `global` is reserved for global mode).                                                                                                                                                                                    |
| `--mode ptt\|global\|duplex` | `ptt`                    | Conversation mode **when creating** a channel; a later joiner adopts the channel's existing mode.                                                                                                                                                                            |
| `--display <name>`           | `guest`                  | Name shown to others — **1–32 chars of `[A-Za-z0-9_.-]`, no spaces** (the server rejects anything else).                                                                                                                                                                     |
| `--hifi`                     | off                      | Start in the Opus **music** profile (vs. voice); toggle live with `f` at the prompt.                                                                                                                                                                                         |
| `--input <substr>`           | system default           | Capture from the input device whose name contains `<substr>`. Run `--help` to see the detected device names.                                                                                                                                                                 |
| `--key <passphrase>`         | `$WALKIE_KEY`            | **End-to-end encrypt** the audio (AES-256-GCM). Everyone in the channel — including browser peers — must use the same passphrase on the same channel/mode; a mismatch is rejected at join. Ignored in global mode — that room is the server's unencrypted broadcast channel. |
| `--tls-truststore <pem>`     | —                        | Extra PEM certificate to trust for TLS, besides the system CAs and (on localhost) the server's auto-generated dev cert. Verification stays on.                                                                                                                               |
| `--muted`                    | off                      | Full-duplex only: join with the mic muted (type `t` to unmute). Ignored in push-to-talk modes.                                                                                                                                                                               |

> Tip: run `--help` to see the detected capture-device names, then pass a distinctive substring to
> `--input` (e.g. `--input "USB"`).

**Interactive commands** (type at the prompt): `t` talk/stop — in push-to-talk it is **state-driven**: it grabs
a free floor, claims your turn when the floor is reserved for you, or (when the owner has enabled the floor queue
and the floor is busy) joins/leaves the "raise hand" line, showing your place and alerting you when it's your
turn · `queue on` / `queue off` toggle this channel's push-to-talk floor queue (owner only) · `w` list who's in the channel · `m
<ptt|global|duplex>` change the mode — `ptt`/`duplex` change the current channel's mode (owner only); `m global` **switches you** to the server-managed global room (like the browser's mode selector, this is a room switch, not a mode change) · `c <channel> [mode] [key]` switch channel without dropping
the session (mode/key default to the current ones) · `p [passphrase]` change the passphrase (owner; blank turns
encryption off; auto-shares the new passphrase so members adopt it automatically — a member uses `p` to apply
the owner's new passphrase) · `p! [passphrase]` rotate **without** auto-sharing (revocation-style; members must
re-enter it) · `o <#id>` hand ownership to another member (owner; `<#id>` is the prefix shown next to a member)
· `mute <#id|all>` / `unmute <#id|all>` mute or unmute a member — or everyone but yourself — as the owner (the
server enforces it: a muted member's audio is dropped and it's shown `[muted]` in `w`; being muted stops your
mic and refuses `t` until you're unmuted) · `lock` / `unlock` lock or unlock the channel to new members as the
owner (server-enforced; a blocked newcomer is refused with `CHANNEL_LOCKED`; existing members are unaffected) ·
`n <name>` rename · `f` toggle hi-fi
(music/voice) live · `q` quit (closes the socket, ending the session) · `h` help.

The client encodes Opus at 48 kHz with in-band FEC (Concentus) — stereo when the audio device supports it,
otherwise mono — and interoperates with relay-mode browser clients.

## Security

- **Stateless signed-token auth.** `POST /api/auth/login` takes **no input** and mints a self-contained,
  **HMAC-SHA512-signed** bearer token; `TokenAuthenticationFilter` verifies its signature and expiry
  cryptographically — **no server-side store** — and the Spring Security chain enforces it on `/ws/**`. The
  signing key comes from `walkie.auth-signing-key` / `WALKIE_AUTH_SIGNING_KEY` (random per-process fallback
  for dev; never hard-coded — see [Run the server](#run-the-server) for generating one).
- **Session lifecycle = the WebSocket.** There is no `/logout` and no token store to evict: the token is
  short-lived and self-expiring, so a session ends simply when its socket closes. That short TTL is the only
  bound on replay of a leaked token — the accepted trade-off of being store-free.
- **Identity is the per-connection session id**, not a username: it keys channel membership, the floor,
  ownership and routing. The display name is a separate, validated label (`[A-Za-z0-9_.-]{1,32}`), and
  duplicate display names are disambiguated for the user with a short `#id` prefix.
- **Optional end-to-end encryption (relay path).** With a shared passphrase, audio frames are encrypted
  with **AES-256-GCM** before they leave the client and decrypted only by other holders of the passphrase —
  the server relays ciphertext opaquely and never holds the key. Because the key is **shared per channel**, this
  proves a frame came from *some* passphrase-holder (confidentiality and integrity against the relay and
  non-members) — **not which member sent it**: there is no per-sender authentication. The key is derived with
  **PBKDF2-HMAC-SHA512** (600 000 iterations, salted per channel); the browser (WebCrypto) and Java client
  (`javax.crypto`) derive byte-identical keys, pinned by a cross-platform known-answer test. The same
  derivation also yields a **key-check value** the client sends at join, letting the server **reject a
  member whose passphrase doesn't match** the channel's (`PASSPHRASE_MISMATCH`) — without ever seeing the
  passphrase or the key. Browser E2EE needs a secure context (HTTPS or `localhost`). This is confidentiality
  between participants on top of transport security, not a replacement for serving over WSS/HTTPS. The channel
  **owner** can rotate that passphrase — or toggle encryption on/off — live, via a `ChangePassphrase` →
  `PassphraseChanged` flow that carries only the new key-check (never the passphrase). By default the owner
  **auto-distributes** the new passphrase: it wraps it (encrypts it) under the channel's *old* key and the
  server relays that blob opaquely, so any member still holding the old key unwraps it and adopts the new
  passphrase **automatically** — convenient key distribution that still keeps the server blind. The owner can
  opt out (browser checkbox / `p!` on the Java client) for a **revocation-style** rotation where members must
  re-enter the new secret out-of-band; the first *enable* of encryption is always out-of-band (no old key to
  wrap under). Every change is announced (no silent downgrade). A member that can't match the new key-check is
  **muted** — the client suppresses transmission rather than ever sending plaintext (the enable case) or
  stale-key audio (a not-yet-rekeyed straggler) into the channel — until it adopts the new key. Because the
  wrapped key is encrypted under the old one, **rotation is a transition, not revocation** (the new key is only
  as secret as the old); there is no forward secrecy. Ownership can likewise be handed to another member
  (`TransferOwnership` → `OwnerChanged`); the server validates the requester owns the channel and the target is
  a current member.
- Stateless, CSRF-free token model; static client, health check and login are the only public routes.
- Input is validated (display-name + channel-name patterns, audio-frame size caps). The browser renders
  member names with `textContent` to avoid markup injection.
- **Per-sender flood guard.** Each sender's relayed audio is rate-limited with a token bucket
  (`walkie.max-audio-frames-per-second`, default 100 ≈ 2× the ~50 fps nominal); frames over the rate are
  dropped **before** fan-out, so one client can't amplify load across the channel (N recipients) or force
  excess decode work. It counts frames without inspecting them, so it works on encrypted channels too — the
  per-frame **size** cap is `walkie.max-audio-frame-bytes` (default 8 KiB).
- **Push-to-talk floor anti-hogging.** A half-duplex channel's talk floor can't be held forever: any holder is
  force-released after `walkie.floor-max-hold-seconds` (default 300) of continuous holding — a periodic
  background sweep enforces this hard cap (a relay holder also hits it immediately on its next frame). On top of
  that, **idle auto-release** hands the floor to a waiting requester once the current holder has sent no audio
  for `walkie.floor-idle-release-seconds` (default 5). Set either to `0` to disable. On a server-initiated
  release the (ex-)holder is told so its client stops transmitting. Idle auto-release applies to **relay
  holders only** — it needs a per-frame activity signal, which peer-to-peer WebRTC media doesn't give the
  server; max-hold is a pure time cap and bounds every holder, including WebRTC.
- **Push-to-talk floor queue ("raise hand").** A channel owner can turn on a per-channel floor queue (default
  **off** — a busy floor is simply not granted, as before). With it on, asking for a busy floor puts you **in
  line** (FIFO) and everyone sees their place; when the floor frees it passes to the head of the line. It is
  **grant-to-claim, never a hot mic**: when your turn arrives the floor is *reserved* for you for
  `walkie.floor-reservation-seconds` (default 10) and you must take the normal talk action to go live — miss the
  window and you're dropped and the floor moves to the next in line. The owner toggles it with `SetFloorQueue`;
  the on/off default a new channel adopts is `walkie.floor-queue-default` (default `false`). The reservation
  window is a positive claim window, so `0`/blank means "use the default", not "disabled" (unlike the two timers
  above). The ownerless `global` room is always off, and full-duplex has no floor or queue.
- **For production:** serve over WSS/HTTPS (TLS 1.2+) — see _Transport encryption (TLS / WSS)_ above and the
  `deploy/` proxy configs — and **override `walkie.allowed-origins`**: it **defaults to `*`** (wide open),
  and because CSRF is disabled this WebSocket origin check is the sole anti-CSWSH (cross-site WebSocket
  hijacking) control, so it MUST be set to your real HTTPS origin(s). Also set `WALKIE_AUTH_SIGNING_KEY`, and
  swap the signed-token mint/verify for real IdP/JWT validation — the filter wiring stays the same.

## Java 25 features used

- **Virtual threads** for the servlet container and client I/O loops (`spring.threads.virtual.enabled`).
- **Scoped Values** (JEP 506, finalized) to carry the authenticated identity for the scope of a control
  message and tag the log lines emitted during it (mirrored into the SLF4J MDC); the per-frame audio relay
  path is intentionally left unscoped — see `RequestContext`.
- **Sealed interfaces + records + pattern-matching `switch`** for the protocol and dispatch.
- **Records as configuration properties**, text blocks, and `--release 25` throughout. No `var`.

Structured Concurrency (JEP 505) and Stable Values (JEP 502) are still *preview* in Java 25, so the
default build stays preview-free.

## Native image / AOT readiness

The server is **GraalVM-native-ready**. The `org.graalvm.buildtools.native` plugin is applied (version
aligned with Spring Boot's `native-build-tools-plugin.version`), so Spring Boot's AOT tasks are wired in:

```bash
JAVA_OPTS= ./gradlew :walkie-server:processAot     # run the AOT engine; emits generated context + reachability-metadata.json
JAVA_OPTS= ./gradlew :walkie-server:nativeCompile  # build the native executable      (needs a GraalVM JDK on PATH)
JAVA_OPTS= ./gradlew :walkie-server:nativeTest     # run the test suite as a native image (needs a GraalVM JDK)
```

Only the `native*` tasks need a GraalVM JDK installed; a normal `JAVA_OPTS= ./gradlew build` on the stock
JVM works unchanged. Note that `build`/`test` now also **generate and compile** the AOT sources (via
`processAot`/`processTestAot`) as a dependency, so a context that can't be AOT-processed fails the ordinary
build. The **test suite still executes reflectively** (it only runs in AOT mode under `nativeTest`).

**Normal runs use the AOT-generated context on the JVM.** `bootRun` and the runnable boot jar are wired to
run with `spring.aot.enabled=true` and the generated classes on the classpath, so they load the pre-computed
`ApplicationContextInitializer` instead of reflective startup (the log says `Starting AOT-processed …`). The
jar bundles the flag as `BOOT-INF/classes/spring.properties`, so `java -jar` is **always** AOT (that file wins
over any `-Dspring.aot.enabled`); `bootRun` sets it as a system property you can override with **`-Paot=false`**.

The `walkie.tls.enabled` toggle keeps working under AOT: `TlsConfiguration` reads it **at runtime** inside
`customize()` rather than via a build-time `@ConditionalOnProperty`, so one AOT build serves either mode from
the same generated context — `bootRun` / `java -jar` default to **HTTPS:8443**, and `--walkie.tls.enabled=false`
brings up **HTTP:8080** even in AOT mode (verified: the log shows `Starting AOT-processed …` followed by either
`TLS enabled on port 8443` or `TLS disabled … serving plain HTTP`). `-Paot=false` remains only as a general
reflective-startup escape hatch (e.g. for debugging), not something you need for the HTTP dev mode.

**Why the code is already clean.** There is **no application-level reflection**, all beans use **constructor
injection**, and there are **no runtime proxies** (`@Transactional`/`@Async`/`@Aspect`), no SpEL/`@Value`,
and no programmatic bean registration — the patterns that fight AOT. Spring Boot's AOT engine handles the
framework reflection on its own.

**The one thing AOT can't see, registered explicitly.** The WebSocket protocol (`ClientMessage`/`ServerMessage`
and the types they carry) is (de)serialized inside `MessageCodec`, a plain `@Component`, so Spring's AOT
engine — which only auto-derives Jackson binding hints from `@Controller` request/response types — never
discovers it. `ProtocolRuntimeHints` (a `RuntimeHintsRegistrar` wired via `@ImportRuntimeHints` on
`MessageCodec`) registers reflection/binding hints for the whole protocol, derived from each sealed root's
`getPermittedSubclasses()` so the hint set can't drift as message types are added. `ProtocolRuntimeHintsTest`
asserts the coverage against a `RuntimeHints` instance, so a missing hint fails an ordinary JVM test run
rather than only surfacing at native runtime. (Confirmed present in the generated `reachability-metadata.json`.)

**On `spring.aot.enabled` — it is deliberately *not* in `application.yml`.** `AotDetector.useGeneratedArtifacts()`
reads it via `SpringProperties`, which consults a classpath `spring.properties` and JVM system properties
*before* the `Environment` (and `application.yml`) is loaded — so a value in `application.yml` is never read.
It also returns `true` automatically inside a native image (no flag needed there). We enable it for JVM runs
the two places it is actually read: `bootRun` sets it as a **system property** (override with `-Paot=false`),
and the boot jar bundles it as **`BOOT-INF/classes/spring.properties`** (which takes precedence over any `-D`,
so the jar is always AOT). It is kept off `src/main/resources` on purpose, so it never reaches the **test**
classpath — tests have no generated context there and must stay reflective.

**Native-image-only deployment facts:**

- **The self-signed dev-cert path won't work in native.** When TLS is on and no `WALKIE_TLS_KEYSTORE` is set,
  `TlsConfiguration` shells out to the JDK's `keytool` to mint a dev cert — and `keytool` isn't present in a
  native image (there is no bundled JDK), so that path fails at startup. A native deployment must supply a real
  keystore via `WALKIE_TLS_KEYSTORE`, or run with `walkie.tls.enabled=false` **behind a TLS-terminating reverse
  proxy** — never in the clear (see `deploy/`). TLS itself stays 1.2+/1.3. (The TLS on/off choice is a runtime
  read, so a native image honours `walkie.tls.enabled` the same as the JVM — it is only the dev-cert
  *auto-generation* that native can't do.)
- The JCA algorithms used (`PKCS12` keystore, `HmacSHA512`, EC/X.509 parsing, `SecureRandom`) need GraalVM's
  security services enabled in the native build args; the community reachability-metadata repository (enabled
  in `build.gradle.kts`) covers the embedded Tomcat/Jackson/Spring internals.

## Known constraints (by design)

- **WebRTC is browser-to-browser.** There is no mature pure-Java WebRTC stack, so the Java desktop
  client uses the relay transport. WebRTC group calls here use a peer-to-peer **mesh** (fine for small
  PTT groups); a large conference would need an SFU.
- **The relay mixes on the client, not the server.** Simultaneous talkers (full-duplex) work on the relay
  path: the server prefixes each fanned-out frame with the sender's 1-byte stream index, and the receiver
  decodes every sender with its own Opus decoder and mixes locally — the server never mixes or even inspects
  audio. The cost is per-receiver, not server-side: a receiver runs one decoder per active talker, capped
  (longest-silent eviction) so a crowded channel can't fan out unboundedly. WebRTC remains an alternative —
  each peer is its own independently-decoded stream, mixed by the browser.
- **Backpressure is handled per recipient.** Each connection has its own outbound queue drained by a
  dedicated virtual thread, so one slow or backpressured client never blocks delivery to the others. A slow
  recipient's **audio** is dropped frame-by-frame (real-time, lossy); its **control** messages
  (floor/mode/owner/membership) are never dropped — a client so far behind it can't even receive those is
  disconnected and re-syncs via the `Joined` snapshot on reconnect.
- Channels are **in-memory**; tokens are **stateless** (share one `WALKIE_AUTH_SIGNING_KEY` across instances).
  To run **multiple instances**, set `walkie.channel-affinity=true` and front them with an ingress that
  consistent-hashes the handshake `?channel=` query param, so all members of a channel land on the one instance
  that owns it (each instance owns a disjoint set of channels — no shared media bus). The server pins each socket
  to a channel and refuses a switch to a channel owned by another instance with `CHANNEL_ROUTING_MISMATCH`; **both
  clients then reconnect automatically** — they open a fresh socket carrying `?channel=<target>` so the ingress
  re-pins them to the owning instance, transparently completing the switch. Caveats: the single `global` room hashes to one
  instance, so it doesn't scale horizontally; and a single channel that must exceed one instance's capacity would
  need a shared backplane (not implemented). Off by default = single instance, everything served locally.
- **End-to-end encryption uses one shared passphrase per channel** (a pre-shared key, no key exchange):
  no forward secrecy, no per-sender keys, and frame *metadata* (who is transmitting, when, and frame
  sizes) stays visible to the server. A channel is **uniformly** encrypted or plaintext: a joiner whose
  passphrase doesn't match the channel's is **rejected at join** (`PASSPHRASE_MISMATCH`) via a key-check
  value the server compares without ever learning the passphrase. The **owner** can rotate that passphrase
  (or toggle encryption on/off) live and, by default, **auto-distribute** it by wrapping the new key under
  the old one so members adopt it automatically (the server stays blind) — but because the new key is wrapped
  under the old, **rotation is a transition, not revocation**: it is only as secret as the old key, so to
  exclude a member you move to a fresh channel. The owner can opt out of auto-distribution for a
  revocation-style rotation (members then re-enter the new secret out-of-band), and the first time encryption
  is enabled is always out-of-band (no old key to wrap under). Members who don't yet hold a matching key can't
  hear or be heard until they adopt it. It covers the **relay** transport; WebRTC media is already end-to-end
  via DTLS-SRTP.
