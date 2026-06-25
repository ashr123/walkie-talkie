# 📻 Walkie-Talkie Server

A real-time, push-to-talk voice server that passes **live audio streams** (not recordings) between
users. Written in **Java 25** (no `var`) on **Spring Boot 4.1.0**, with two interchangeable transports,
two reference clients, and three channel modes.

## What it does

| Choice           | Options                                                                                                                                              |
|------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Transport**    | **WebSocket relay** — the server forwards raw audio frames between members · **WebRTC** — the server relays signaling only, audio flows peer-to-peer |
| **Channel mode** | **Multi-channel PTT** (named rooms, half-duplex) · **Global PTT** (one shared room) · **Full-duplex** (everyone talks at once)                       |
| **Clients**      | A zero-install **browser** client · a **Java 25 desktop** client                                                                                     |
| **Encryption**   | Optional **end-to-end encryption** on the relay path (AES-256-GCM from a shared passphrase) · WebRTC media is already end-to-end (peer-to-peer)      |

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

The server listens on `http://localhost:8080`. Open it in a browser to use the web client.

#### Signing key (real / multi-instance deployment)

The bearer token is signed with **HMAC-SHA512**. With no key configured, the server generates a random key
at startup — fine for a single local instance, but tokens then won't survive a restart or validate across
instances (you'll see a `WARN` saying so). For a real or horizontally-scaled deployment, give **every
instance the same key** via `WALKIE_AUTH_SIGNING_KEY` (or the `walkie.auth-signing-key` property). Generate
a strong random key (≥ 64 bytes, to match the 512-bit MAC) and keep it out of source control:

```bash
# generate a key (store it in your secrets manager, not in the repo)
openssl rand -base64 64

# run with it (every instance uses the same value)
WALKIE_AUTH_SIGNING_KEY="$(openssl rand -base64 64)" java -jar walkie-server/build/libs/walkie-server-0.1.0.jar
```

### Browser client

1. Open <http://localhost:8080>.
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
  --server http://localhost:8080 --channel team1 --mode ptt --display Alice"
```

All flags are optional (run with `--args="--help"` for the full list):

| Flag                         | Default                 | Purpose                                                                                                                                                                                    |
|------------------------------|-------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--server <url>`             | `http://localhost:8080` | Base HTTP URL of the server.                                                                                                                                                               |
| `--channel <name>`           | `lobby`                 | Channel to join (ignored for global mode).                                                                                                                                                 |
| `--mode ptt\|global\|duplex` | `ptt`                   | Conversation mode **when creating** a channel; a later joiner adopts the channel's existing mode.                                                                                          |
| `--display <name>`           | `guest`                 | Name shown to others — **1–32 chars of `[A-Za-z0-9_.-]`, no spaces** (the server rejects anything else).                                                                                   |
| `--hifi`                     | off                     | Start in the Opus **music** profile (vs. voice); toggle live with `f` at the prompt.                                                                                                       |
| `--input <substr>`           | system default          | Capture from the input device whose name contains `<substr>`.                                                                                                                              |
| `--list-inputs`              | —                       | Print the available input devices and exit.                                                                                                                                                |
| `--key <passphrase>`         | `$WALKIE_KEY`           | **End-to-end encrypt** the audio (AES-256-GCM). Everyone in the channel — including browser peers — must use the same passphrase on the same channel/mode; a mismatch is rejected at join. |

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
  the server relays ciphertext opaquely and never holds the key. The key is derived with
  **PBKDF2-HMAC-SHA256** (600 000 iterations, salted per channel); the browser (WebCrypto) and Java client
  (`javax.crypto`) derive byte-identical keys, pinned by a cross-platform known-answer test. The same
  derivation also yields a **key-check value** the client sends at join, letting the server **reject a
  member whose passphrase doesn't match** the channel's (`passphrase_mismatch`) — without ever seeing the
  passphrase or the key. Browser E2EE needs a secure context (HTTPS or `localhost`). This is confidentiality
  between participants on top of transport security, not a replacement for serving over WSS/HTTPS.
- Stateless, CSRF-free token model; static client, health check and login are the only public routes.
- Input is validated (display-name + channel-name patterns, audio-frame size caps). The browser renders
  member names with `textContent` to avoid markup injection.
- **For production:** serve over WSS/HTTPS (TLS 1.2+), restrict `walkie.allowed-origins`, set
  `WALKIE_AUTH_SIGNING_KEY`, and swap the signed-token mint/verify for real IdP/JWT validation — the filter
  wiring stays the same.

## Java 25 features used

- **Virtual threads** for the servlet container and client I/O loops (`spring.threads.virtual.enabled`).
- **Scoped Values** (JEP 506, finalized) to carry the authenticated identity for the scope of a
  message and tag every log line emitted during it (mirrored into the SLF4J MDC) — see `RequestContext`.
- **Sealed interfaces + records + pattern-matching `switch`** for the protocol and dispatch.
- **Records as configuration properties**, text blocks, and `--release 25` throughout. No `var`.

Structured Concurrency (JEP 505) and Stable Values (JEP 502) are still *preview* in Java 25, so the
default build stays preview-free.

## Known constraints (by design)

- **WebRTC is browser-to-browser.** There is no mature pure-Java WebRTC stack, so the Java desktop
  client uses the relay transport. WebRTC group calls here use a peer-to-peer **mesh** (fine for small
  PTT groups); a large conference would need an SFU.
- **The relay decodes one Opus stream at a time.** Push-to-talk (the core mode) is ideal — a single
  talker holds the floor; switching speakers may briefly glitch the decoder as the stream changes. For
  *simultaneous* multi-talker audio, use the **WebRTC** transport, where each peer is its own
  independently-decoded Opus stream. The relay never mixes audio server-side.
- Channels and tokens are **in-memory** (single instance). Horizontal scaling would need a shared
  bus/registry.
- **End-to-end encryption uses one shared passphrase per channel** (a pre-shared key, no key exchange):
  no forward secrecy, no per-sender keys, and frame *metadata* (who is transmitting, when, and frame
  sizes) stays visible to the server. A channel is **uniformly** encrypted or plaintext: a joiner whose
  passphrase doesn't match the channel's is **rejected at join** (`passphrase_mismatch`) via a key-check
  value the server compares without ever learning the passphrase. It covers the **relay** transport;
  WebRTC media is already end-to-end via DTLS-SRTP.
