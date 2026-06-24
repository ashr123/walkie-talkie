# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Critical environment gotcha

This machine's shell exports a `JAVA_OPTS` containing long-removed PermGen flags (`-XX:PermSize`),
and Gradle's launcher inherits `JAVA_OPTS`. **Every Gradle command must be prefixed with an empty
`JAVA_OPTS=`** or it crashes with `Unrecognized VM option 'PermSize=512m'`:

```bash
JAVA_OPTS= ./gradlew build
```

Do not edit the user's shell profile to work around this. `java -jar ...` does not read `JAVA_OPTS`,
so only Gradle needs the prefix. Use the bundled wrapper (`./gradlew`, Gradle 9.6) — the system
`gradle` is the broken one. Only JDK 26 is installed; the build targets Java 25 via `--release 25`
(see below), so no JDK 25 install is required.

## Commands

```bash
JAVA_OPTS= ./gradlew build                       # compile all modules + run all tests
JAVA_OPTS= ./gradlew :walkie-server:bootRun       # run the server on http://localhost:8080
java -jar walkie-server/build/libs/walkie-server-0.1.0.jar   # or run the built boot jar

# Java desktop client (relay transport). --mode: ptt|global|duplex ; --hifi flag for the music profile; --help for all options
JAVA_OPTS= ./gradlew :walkie-client-java:run --args="--server http://localhost:8080 --user alice --channel team1 --mode ptt"

# Tests
JAVA_OPTS= ./gradlew :walkie-server:test                                            # one module
JAVA_OPTS= ./gradlew :walkie-server:test --tests '*FloorControlServiceTest'         # one class
JAVA_OPTS= ./gradlew :walkie-server:test --tests '*FloorControlServiceTest.fullDuplexGrantsEveryoneAndTracksNoHolder'  # one method
```

## Build layout

Three Gradle modules (Kotlin DSL); shared build logic lives in a **`buildSrc` precompiled script
plugin** `walkietalkie.java-conventions` (sets `--release 25`, `-parameters`, JUnit Platform). Each
module applies it via `plugins { id("walkietalkie.java-conventions") }`. Do **not** reintroduce
`subprojects {}`/`allprojects {}`/`apply(plugin=...)` — that's the legacy pattern this replaced.

- `walkie-shared` — wire protocol only, zero Spring deps.
- `walkie-server` — Spring Boot 4.1 (Spring Framework 7, Jackson 3, Jakarta EE 11); serves the browser
  client from `src/main/resources/static/`.
- `walkie-client-java` — console client (`javax.sound.sampled` + JDK WebSocket + Concentus Opus).

Project rule: **no `var` keyword** anywhere. A linter may reformat saved files (tabs, `///` Javadoc,
`_` for unused catch/pattern vars) — match the existing style rather than fighting it.

## Architecture (the parts that span files)

**Two transports, one core.** Both are authenticated WebSocket endpoints whose handlers
(`AudioRelayHandler` for `/ws/audio`, `SignalingHandler` for `/ws/signal`) are thin subclasses of
`BaseWalkieHandler`. All real logic lives in the transport-agnostic `ConnectionService`, which never
touches a `WebSocketSession` (so it's unit-testable with a fake `ClientSession`). It owns membership
(`ChannelRegistry` → `Channel`), push-to-talk floor arbitration (`FloorControlService`), audio
fan-out, and WebRTC signaling relay. To understand message handling, read `ConnectionService.dispatch`
— a **pattern-matching `switch` over the sealed `ClientMessage`**, so adding a message type forces
every site to handle it.

**Channel modes** (`ChannelMode`): `MULTI_CHANNEL_PTT`, `GLOBAL_PTT` (channel name forced to `global`),
`FULL_DUPLEX` (no floor). A channel's mode is set at creation and **adopted** by later joiners; only
the **owner** (creator) may change it (`ChangeMode` → broadcast `ModeChanged`), and ownership transfers
to another member if the owner leaves. Floor state is an `AtomicReference<String>` on `Channel`;
full-duplex bypasses it.

**Protocol.** `ClientMessage`/`ServerMessage` are sealed interfaces with nested records in
`walkie-shared`, made polymorphic for Jackson 3 with `@JsonTypeInfo(use=NAME, property="type")` +
`@JsonTypeName` (Jackson 3 needs only `@JsonTypeInfo` on sealed types). The server (de)serializes via
`MessageCodec` using the auto-configured Jackson 3 bean — note the type is
`tools.jackson.databind.json.JsonMapper` (Jackson 3 moved databind to the `tools.jackson` group;
annotations stay under `com.fasterxml.jackson.core`). Jackson 3 exceptions are unchecked.

**Audio wire contract (cross-cutting — read before touching audio).** On the relay transport each
binary frame is `[1-byte codec tag][payload]`: tag `1` = Opus (48 kHz, 20 ms / 960-samples-per-channel
frames), tag `2` = raw PCM S16LE 48 kHz. Channel count is carried inside the Opus stream, and decoders
emit their own configured channel count (down/upmixing as needed) — so the mono browser and a stereo
Java client interoperate. The **server never inspects the payload** — it relays frames opaquely and
only enforces `walkie.max-audio-frame-bytes`. The browser encodes Opus via WebCodecs (mono; PCM
fallback where WebCodecs is absent); the Java client uses Concentus (stereo when the device supports
it, else mono). Opus decode is per-stream
stateful, so the relay path is effectively **one-talker-at-a-time** (ideal for PTT); true simultaneous
multi-talker is the WebRTC transport's job (each peer is an independently-decoded stream). The WebRTC
path tunes Opus via SDP munging + sender `maxBitrate`.

**Security.** Stateless token auth. `POST /api/auth/login` mints an ephemeral in-memory token
(`AuthService`). `TokenAuthenticationFilter` reads `Authorization: Bearer` or a `?token=` query param
(browsers can't set headers on a WS handshake, hence the query param) and populates the
`SecurityContext`; handlers then read the principal from `session.getPrincipal()`. `SecurityConfig`
permits static assets, `/error`, health, and login, and authenticates everything else including
`/ws/**`. Keeping `/error` permitted is required — otherwise validation failures dispatch to `/error`
and surface as 403 instead of 400.

**Concurrency.** Virtual threads are enabled (`spring.threads.virtual.enabled`). Outbound WS sends are
wrapped in `ConcurrentWebSocketSessionDecorator` so fan-out from multiple threads is safe. In the Java
client the Opus encoder/decoder are confined to the capture/playback threads respectively.

## Testing notes

Server tests mix unit (`FloorControlServiceTest`, `ChannelRegistryTest` via the `FakeClientSession`
helper) and integration (`WebSocketRelayIntegrationTest`, which boots on a random port and drives real
`StandardWebSocketClient` connections). In Spring Boot 4, `TestRestTemplate` is not at its old package
— the integration test uses the JDK `HttpClient` for the login call, and `@LocalServerPort` comes from
`org.springframework.boot.test.web.server`.
