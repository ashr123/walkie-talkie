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
  ┌─ walkie-server (Spring Boot 4.1) ────────────────────────────────────────────────────────────────┐
  │                                                                                                  │
  │  TokenAuthenticationFilter ─► Spring Security      (the token is the Authentication credential)  │
  │                                                                                                  │
  │  /ws/audio   (binary) ─► AudioRelayHandler ┐                                                     │
  │  /ws/signal  (text)   ─► SignalingHandler  ┴─► ConnectionService                                 │
  │                                                 ├─ ChannelRegistry ── Channel                    │
  │                                                 ├─ FloorControlService (push-to-talk floor)      │
  │                                                 └─ audio fan-out · WebRTC signaling relay        │
  │                                                                                                  │
  │  stateless signed token, verified at the handshake (no store); a session ends when its WS closes │
  └──────────────────────────────────────────────────────────────────────────────────────────────────┘ 

  Control plane: JSON text frames (sealed ClientMessage / ServerMessage records, Jackson 3 polymorphic)
  Media plane (relay):  48 kHz, 20 ms frames (mono, or stereo from the Java client) — Opus via WebCodecs
                        (FEC) or PCM fallback, each frame prefixed with a 1-byte codec tag. The server
                        fans frames out opaquely (codec-agnostic, never inspecting the payload); clients
                        may additionally end-to-end encrypt each frame (AES-256-GCM from a shared
                        passphrase) before sending — the server relays the ciphertext blindly, no key
  Media plane (webrtc): Opus 48 kHz fullband, tuned (high bitrate + FEC), peer-to-peer
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
- The bundled Gradle wrapper (Gradle 9.6) — no system Gradle required.

> **Environment note (this machine):** the shell exports a `JAVA_OPTS` containing the long-removed
> `-XX:PermSize` PermGen flags, and Gradle's launcher inherits `JAVA_OPTS`. Prefix Gradle commands with
> `JAVA_OPTS=` to neutralize it: `JAVA_OPTS= ./gradlew build`.

### Build and test

```bash
JAVA_OPTS= ./gradlew build      # compiles all modules and runs the test suite
```

### Run the server

```bash
JAVA_OPTS= ./gradlew :walkie-server:bootRun
# or
java -jar walkie-server/build/libs/walkie-server-0.1.0.jar
```

By default the server runs over **HTTPS on `https://localhost:8443`** with an auto-generated self-signed
certificate (your browser shows a one-time warning) — see *Transport encryption (TLS / WSS)* below. To run
plain HTTP on `http://localhost:8080` instead, set `walkie.tls.enabled=false`. Open the URL in a browser to
use the web client.

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
is missing, expired, or the wrong key size), serving `https://localhost:8443`.
The browser shows a one-time "accept the certificate" warning. The **Java client auto-trusts** this dev cert
on localhost (it reads the exported `~/.walkie-talkie/dev-cert.pem`), so it just works — and TLS verification
is never disabled.

**Your own / a stronger cert.** Point the server at a keystore via the environment (path + password are never
hardcoded or committed):

```bash
export WALKIE_TLS_KEYSTORE_PASSWORD=…              # your keystore password
scripts/gen-dev-cert.sh                            # optional: writes ./dev-keystore.p12 (RSA-16384) + ./dev-cert.pem
WALKIE_TLS_KEYSTORE="file:$PWD/dev-keystore.p12" JAVA_OPTS= ./gradlew :walkie-server:bootRun
# Java client against a custom cert:  --tls-truststore ./dev-cert.pem
```

**Turn TLS off** with `walkie.tls.enabled=false` — the server then serves plain `http://localhost:8080`. Use
this for the production model below (a TLS-terminating reverse proxy on a trusted loopback).

**Production — terminate TLS at a reverse proxy.** Run the app with `walkie.tls.enabled=false` (plain HTTP on
loopback) behind a proxy. Ready-to-edit configs are in [`deploy/`](deploy/): a [`Caddyfile`](deploy/Caddyfile)
(automatic HTTPS via Let's Encrypt) and an [`nginx.conf.example`](deploy/nginx.conf.example) (mind the
`Upgrade`/`Connection` headers the WebSocket endpoints require). Tighten `walkie.allowed-origins` to your
HTTPS origin so the handshake's origin check (anti-CSWSH) accepts only your real site. TLS 1.3 / 1.2 only
throughout.

### Browser client

1. Open <https://localhost:8443> and accept the one-time self-signed-certificate warning (or
   <http://localhost:8080> if you started the server with `walkie.tls.enabled=false`).
2. Pick a transport, channel mode, and channel (optionally tick **High fidelity** to disable the mic
   noise-suppression/echo-cancellation DSP — this can be toggled **live** while connected and applies
   immediately). To encrypt audio **end-to-end**, set the same **Encryption
   passphrase** as everyone else in the channel (relay transport only; needs HTTPS or `localhost`).
   Click **Connect** and allow microphone access.
3. **Push-to-talk** modes: hold the big button (or hold **Space**). **Full-duplex**: click to toggle your mic.
4. **Disconnect** (or closing the tab) closes the WebSocket, which ends your session — the signed token is
   stateless and self-expiring, so there's nothing to revoke.

A channel's mode belongs to whoever **created** it: a later joiner adopts the existing mode (the server
sends it), and only the creator can change it. When you own the channel the **Mode** selector stays
enabled while connected — changing it switches the mode live for everyone (everyone's controls update);
for non-owners it's disabled. If the owner leaves, ownership passes to another member.

Open the page in two tabs (or two machines) to talk between them.

### Java desktop client

A console client over the WebSocket-relay transport. Run it with the Gradle `run` task, passing CLI flags
through `--args`:

```bash
JAVA_OPTS= ./gradlew :walkie-client-java:run --args="\
  --server https://localhost:8443 --channel team1 --mode ptt --display Alice"
```

All flags are optional (run with `--args="--help"` for the full list):

| Flag                         | Default                 | Purpose                                                                                                                                                                                    |
|------------------------------|-------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--server <url>`             | `https://localhost:8443`| Base URL of the server (on localhost the dev cert is auto-trusted; use `http://…` for a server run with `walkie.tls.enabled=false`).                                                                                                                                                               |
| `--channel <name>`           | `lobby`                 | Channel to join (ignored for global mode; the name `global` is reserved for global mode).                                                                                                                                                 |
| `--mode ptt\|global\|duplex` | `ptt`                   | Conversation mode **when creating** a channel; a later joiner adopts the channel's existing mode.                                                                                          |
| `--display <name>`           | `guest`                 | Name shown to others — **1–32 chars of `[A-Za-z0-9_.-]`, no spaces** (the server rejects anything else).                                                                                   |
| `--hifi`                     | off                     | Start in the Opus **music** profile (vs. voice); toggle live with `f` at the prompt.                                                                                                       |
| `--input <substr>`           | system default          | Capture from the input device whose name contains `<substr>`.                                                                                                                              |
| `--list-inputs`              | —                       | Print the available input devices and exit.                                                                                                                                                |
| `--key <passphrase>`         | `$WALKIE_KEY`           | **End-to-end encrypt** the audio (AES-256-GCM). Everyone in the channel — including browser peers — must use the same passphrase on the same channel/mode; a mismatch is rejected at join. Ignored in global mode — that room is the server's unencrypted broadcast channel. |
| `--tls-truststore <pem>`     | —                       | Extra PEM certificate to trust for TLS, besides the system CAs and (on localhost) the server's auto-generated dev cert. Verification stays on. |

> Tip: run once with `--list-inputs` to see capture-device names, then pass a distinctive substring to
> `--input` (e.g. `--input "USB"`).

**Interactive commands** (type at the prompt): `t` talk/stop · `m <ptt|global|duplex>` change the mode
(owner only) · `f` toggle hi-fi (music/voice) live · `q` quit (closes the socket, ending the session) · `h`
help.

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
  member whose passphrase doesn't match** the channel's (`passphrase_mismatch`) — without ever seeing the
  passphrase or the key. Browser E2EE needs a secure context (HTTPS or `localhost`). This is confidentiality
  between participants on top of transport security, not a replacement for serving over WSS/HTTPS.
- Stateless, CSRF-free token model; static client, health check and login are the only public routes.
- Input is validated (display-name + channel-name patterns, audio-frame size caps). The browser renders
  member names with `textContent` to avoid markup injection.
- **For production:** serve over WSS/HTTPS (TLS 1.2+) — see _Transport encryption (TLS / WSS)_ above and the `deploy/` proxy configs — restrict `walkie.allowed-origins`, set
  `WALKIE_AUTH_SIGNING_KEY`, and swap the signed-token mint/verify for real IdP/JWT validation — the filter
  wiring stays the same.

## Java 25 features used

- **Virtual threads** for the servlet container and client I/O loops (`spring.threads.virtual.enabled`).
- **Scoped Values** (JEP 506, finalized) to carry the authenticated identity for the scope of a control
  message and tag the log lines emitted during it (mirrored into the SLF4J MDC); the per-frame audio relay
  path is intentionally left unscoped — see `RequestContext`.
- **Sealed interfaces + records + pattern-matching `switch`** for the protocol and dispatch.
- **Records as configuration properties**, text blocks, and `--release 25` throughout. No `var`.

Structured Concurrency (JEP 505) and Stable Values (JEP 502) are still *preview* in Java 25, so the
default build stays preview-free.

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
- Channels and tokens are **in-memory** (single instance). Horizontal scaling would need a shared
  bus/registry.
- **End-to-end encryption uses one shared passphrase per channel** (a pre-shared key, no key exchange):
  no forward secrecy, no per-sender keys, and frame *metadata* (who is transmitting, when, and frame
  sizes) stays visible to the server. A channel is **uniformly** encrypted or plaintext: a joiner whose
  passphrase doesn't match the channel's is **rejected at join** (`passphrase_mismatch`) via a key-check
  value the server compares without ever learning the passphrase. It covers the **relay** transport;
  WebRTC media is already end-to-end via DTLS-SRTP.
