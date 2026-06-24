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

## Architecture

```
                     ┌──────────────────────── walkie-server (Spring Boot 4.1) ────────────────────────┐
  browser / java     │  /api/auth/login ── token ──► TokenAuthenticationFilter ──► Spring Security     │
  clients            │                                                                                 │
       │  WSS  ┌─────┤  /ws/audio  (BinaryWebSocket) ─► AudioRelayHandler  ─┐                          │
       ├───────┴─────┤  /ws/signal (TextWebSocket)   ─► SignalingHandler   ─┤                          │
       │             │                                                      ▼                          │
       │             │   ConnectionService ── ChannelRegistry ── Channel ── FloorControlService        │
       │             │   (pattern-matching dispatch, audio fan-out, PTT floor arbitration)             │
       └─────────────┴─────────────────────────────────────────────────────────────────────────────────┘

  Control plane: JSON text frames  (sealed ClientMessage / ServerMessage records, Jackson 3 polymorphic)
  Media plane (relay): 48 kHz, 20 ms frames (mono, or stereo from the Java client) — Opus via WebCodecs (FEC) or PCM fallback,
                       each frame prefixed with a 1-byte codec tag; fanned out server-side (codec-agnostic)
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

### Browser client

1. Open <http://localhost:8080>.
2. Pick a transport, channel mode, and channel (optionally tick **High fidelity** to disable the mic
   noise-suppression/echo-cancellation DSP); click **Connect** and allow microphone access.
3. **Push-to-talk** modes: hold the big button (or hold **Space**). **Full-duplex**: click to toggle your mic.

Open the page in two tabs (or two machines) to talk between them.

### Java desktop client

```bash
JAVA_OPTS= ./gradlew :walkie-client-java:run --args="\
  --server http://localhost:8080 --user alice --channel team1 --mode ptt --display Alice"
```

`--mode` accepts `ptt` (multi-channel PTT, default), `global` (single global PTT), or `duplex`
(full-duplex); add the `--hifi` flag for the Opus music profile. At the prompt: `t` to talk/stop, `q` to
quit, `h` for help. The Java client encodes Opus (48 kHz, FEC via Concentus — stereo when the audio
device supports it, otherwise mono) and interoperates with
relay-mode browser clients.

## Security

- **Token auth on every endpoint.** `POST /api/auth/login` mints a random, ephemeral bearer token
  (no secret is hard-coded or persisted). A `TokenAuthenticationFilter` validates the token (header or
  `?token=` for browser WebSocket handshakes) and the Spring Security chain enforces it on `/ws/**`.
- Stateless, CSRF-free token model; static client, health check and login are the only public routes.
- Input is validated (usernames, channel-name pattern, audio-frame size caps). The browser renders
  member names with `textContent` to avoid markup injection.
- **For production:** serve over WSS/HTTPS (TLS 1.2+), restrict `walkie.allowed-origins`, and replace
  the in-memory `AuthService` with real IdP/JWT validation — the filter wiring stays the same.

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
