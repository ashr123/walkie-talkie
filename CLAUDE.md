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
JAVA_OPTS= ./gradlew :walkie-server:bootRun       # HTTPS on https://localhost:8443 by default (auto self-signed cert); walkie.tls.enabled=false -> http://localhost:8080
java -jar walkie-server/build/libs/walkie-server-0.1.0.jar   # or run the built boot jar

# Java desktop client (relay transport). --mode: ptt|global|duplex ; --hifi flag for the music profile; --help for all options
JAVA_OPTS= ./gradlew :walkie-client-java:run --args="--server https://localhost:8443 --display alice --channel team1 --mode ptt"

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
to another member if the owner leaves. Floor state is a monitor-guarded `volatile String` holder on `Channel`
(every mutation runs under the channel monitor; the hot-path reads `holdsFloor`/`floorHolder` are lock-free
volatile reads, re-validated under the monitor before audio fan-out); full-duplex bypasses it. **Floor anti-hogging** (PTT modes, in `ConnectionService`): a holder gone silent past
`walkie.floor-idle-release-seconds` (default 5) is preempted when another member requests the floor (idle
auto-release — `Channel.preemptFloorIfIdle`, relay holders only, keyed off frame *timing* not content), and any
holder is force-released after `walkie.floor-max-hold-seconds` (default 300) of continuous holding (max-hold —
a scheduled sweep `releaseExpiredFloors` via `Channel.releaseIfExpired`, plus an immediate check in `onAudio` on
a relay holder's next frame). Max-hold is a pure time cap and bounds **any** holder incl. WebRTC; idle
auto-release is relay-only. Both `0`-disable; on a server-initiated release the (ex-)holder is told
(`FloorTaken`/`FloorIdle`) so its client stops transmitting. Floor timing uses a `java.time.Clock` + `Instant`
(injectable for tests). **The `global` channel is special and server-managed:** it is reachable *only*
via `GLOBAL_PTT` (a `MULTI_CHANNEL_PTT`/`FULL_DUPLEX` join naming `global` is rejected with
`reserved_channel`); it is **always unencrypted** (a `GLOBAL_PTT` join carrying a `keyCheck` is rejected
with `encryption_not_allowed`, so anyone can join without knowing a passphrase); and it is created with a
sentinel owner (`ConnectionService.GLOBAL_CHANNEL_OWNER = "server"`, never a session id) so **no
participant owns it** — its mode can't be changed (`not_owner`) and ownership never transfers. It is
dropped when empty and recreated server-owned + unencrypted on the next join; clients render its owner as
"server-managed".

**In-place channel switch & rename.** A client changes channel/mode/passphrase **without a new socket**:
re-sending `Join` on the live connection is handled as "leave the old channel, join the new one" on the same
`WebSocketSession`, so the **session id (identity) and the audio loops survive** — only per-channel state
(roster, floor, stream indices, E2EE key) turns over. `handleJoin` validates the target **before** leaving: a
duplicate `Join` for the *current* channel short-circuits to an idempotent re-snapshot (no membership churn),
and a bad target (`invalid_channel` / `invalid_display_name` / `reserved_channel` / `encryption_not_allowed`)
is refused **without** dropping you — the **only** failure that can still drop a switcher is
`passphrase_mismatch`, detectable solely inside the atomic `joinOrCreate` (which necessarily runs after the
leave). Transport can't switch in place (different endpoint + audio pipeline), so both clients reconnect for
it. **Rename** is a separate `Rename` → `setDisplayName` + broadcast `MemberRenamed` (no membership/floor
churn). Re-key safety: the Java client holds the key in a `volatile FrameCrypto` reassigned on the console
thread and read **once** into a local by the capture/receive threads (no TOCTOU NPE across a swap); the browser
awaits `deriveJoinKey` before re-sending `Join`.

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
Java client interoperate. The **server never inspects the payload** — it relays frames opaquely and only
enforces `walkie.max-audio-frame-bytes`, a per-sender frame-rate cap (`walkie.max-audio-frames-per-second`,
dropped before fan-out — `AudioRateLimiter`), and the PTT-floor / membership gate. E2EE makes the *payload*
opaque to an honest-but-curious relay; it does **not** constrain the relay as **router** — a malicious relay can
still drop, reorder, duplicate or misroute frames, and the per-sender stream index it stamps is plaintext. The browser
encodes Opus via WebCodecs (mono; PCM
fallback where WebCodecs is absent); the Java client uses Concentus (stereo when the device supports
it, else mono). **Relay multi-stream framing (full-duplex).** Opus decode is per-stream stateful, so to
carry simultaneous talkers the server fans each frame out prefixed with the sender's per-channel **1-byte
stream index** (`[sid][body]`); a receiver demultiplexes by index, decodes each sender with its **own**
decoder, and mixes locally (browser: one `AudioDecoder` + Web Audio node per sender into `ctx.destination`;
Java: one Concentus decoder per sender summed into the speaker line). The prefix is **unconditional**: the
server prefixes **every** relayed frame and all clients demux it — there is no legacy un-prefixed path. The
server still **never inspects the body** (the index sits outside any E2EE envelope). Byte-exact framing, versioning and
the receiver pipeline live in
`docs/CLIENT_PROTOCOL.md`. WebRTC remains an alternative full-duplex transport (each peer an
independently-decoded stream) and tunes Opus via SDP munging + sender `maxBitrate`.

**Relay end-to-end encryption (optional).** When a shared passphrase is set (browser passphrase field,
or `--key` / `WALKIE_KEY` on the Java client), the sender encrypts the *whole* `[codec tag][payload]`
plaintext and the wire frame becomes `[scheme 0xE2][IV(12)][AES-256-GCM ciphertext+tag(16)]`. A single
`PBKDF2-HMAC-SHA512(passphrase, "walkie-talkie:e2ee:"+effectiveChannel, 600 000)` run derives **384 bits**:
the first 256 are the AES key, the next 128 are a **key-check value** the client sends in its `Join`. PBKDF2's
first output block is length-independent, so the AES key is byte-identical to a 256-bit derivation — the
known-answer test still holds. The key is derived identically by both clients; the server never sees it and
relays opaquely (the +29-byte envelope stays under `max-audio-frame-bytes`). `FrameCrypto` (Java) and the
`deriveKey`/`encryptFrame`/`decryptFrame` trio (`app.js`) **must stay byte-identical**; `FrameCryptoTest`
pins cross-platform known-answer vectors (key *and* key-check, generated by Node's WebCrypto) so they can't
drift. **Mismatch enforcement:** the server records the channel creator's key-check and rejects a joiner
whose key-check differs (`passphrase_mismatch`, in `ChannelRegistry.joinOrCreate`) — so a channel is
*uniformly* encrypted or plaintext, enforced without the server learning the passphrase (the key-check is
brute-force-equivalent to the ciphertext it already relays). **Owner rotation:** the channel owner can
change/clear that key-check live (`ChangePassphrase` → broadcast `PassphraseChanged`,
`ConnectionService.handleChangePassphrase`; `null` key-check = make it plaintext, with full
rotate/enable/disable flexibility). The write goes through `ChannelRegistry.changePassphrase`'s
`channels.computeIfPresent(name, …)` span, so it shares the **bin lock** that `joinOrCreate`'s `channels.compute`
validates a key-check under — a rotation is therefore atomic w.r.t. every concurrent join (which sees the old
value and is then told, or sees the new value) and w.r.t. a concurrent ownership transfer (`leave` also runs
under that lock). The broadcast then runs **under the channel monitor (`synchronized(channel)`) reading the
channel's LIVE `keyCheck`** — over the mutated instance the registry returns, not a fresh `find()` (mirroring
`handleLeave`'s same-object discipline so a drop-and-recreate can't misroute it). Reading the live value under
the monitor (rather than fanning out the request's captured key-check lock-free) makes two rotations that
straddle an ownership change CONVERGE — a delayed broadcast carries the current key-check, so no member is left
gating against a stale one; `Channel.keyCheck` is `volatile` for that lock-free hot-path gate read with a
monitor-guarded broadcast write. The audio relay path is unchanged (opaque forwarding), so the cross-key
transition just drops a few GCM-failing frames. **Key distribution:** the owner may **auto-distribute** the new
passphrase by sending `wrappedKey` = the new passphrase encrypted under the *old* channel key (base64 AES-GCM
blob, same crypto as a frame — `FrameCrypto.wrap`/`unwrap`, `wrapPassphrase`/`unwrapPassphrase`); the server
relays it opaquely (never sees the passphrase) and any member still holding the old key unwraps it, verifies the
result against the announced key-check, and adopts **automatically** (browser `onPassphraseChanged`; Java
`handlePassphraseChanged`). The owner opts out (`wrappedKey: null` — browser "share with members" checkbox off,
Java `p!`) for a **revocation-style** rotation that forces out-of-band re-entry; an *enable* (plaintext→encrypted)
has no old key to wrap under, so it is always manual. **Rotation is a transition, not revocation** — the new key
is wrapped under the old, so it is only as secret as the old; to truly exclude a member, move to a fresh channel.
A member that can't match the announced key-check is **muted**: both clients gate the transmit path on "the
key-check of the key I hold equals the channel's announced one" (`frameDisposition` / `outboundFrame`), so a
not-yet-rekeyed member **never emits plaintext** (the *enable* case, no old key) **and a stale-key straggler**
(an un-adopted rotation) emits no undecodable audio either — both stay silent until they adopt the new key. Global stays unencrypted — its sentinel owner makes any rotation there `not_owner`. **Ownership
transfer:** the owner can hand ownership to a named current member (`TransferOwnership` → broadcast
`OwnerChanged`, `handleTransferOwnership` via `ChannelRegistry.transferOwnership`), validated and written inside
the same `computeIfPresent` bin lock so it can't race `leave`'s auto-election or target a leaving member; the
browser exposes it as an owner dropdown, the Java client as `o <#id-prefix>`. The leading scheme byte (kept out of the
codec-tag set `{1,2}`) still lets a receiver tell an encrypted frame from a plaintext peer and drop it
cleanly; it is **also passed as AES-GCM additional authenticated data (AAD)** (`Cipher.updateAAD` / WebCrypto
`additionalData`), so the scheme byte is bound into the tag and a tampered/forged scheme byte fails decryption.
(The server's stream-index prefix sits **outside** this GCM frame, so it is not authenticated; and because the
channel key is shared, GCM integrity proves a frame came from *a* key-holder, not *which* member.)
WebCrypto needs a secure context, so browser E2EE requires HTTPS or `localhost` (not
`http://<LAN-IP>`). The relay path only; WebRTC media is already end-to-end (DTLS-SRTP, peer-to-peer). Async
WebCrypto on the browser is serialized through `txChain`/`rxChain` so it can't reorder the stateful Opus stream.

**Security / identity.** Stateless, **store-free** token auth. `POST /api/auth/login` takes **no input**
and mints a self-contained **HMAC-SHA512-signed** token (`AuthService`, key from `walkie.auth-signing-key`
/ env `WALKIE_AUTH_SIGNING_KEY`, random per-process fallback for dev). `TokenAuthenticationFilter` reads
`Authorization: Bearer` or a `?token=` query param (browsers can't set headers on a WS handshake) and
**verifies the signature + expiry cryptographically — no lookup**; on success it sets a constant principal
(`"ws-client"`). There is **no `/logout`**: the token is short-lived and self-expiring, so ending a session
is just closing the WebSocket. `SecurityConfig` permits static assets, `/error`, health, and login, and
authenticates everything else including `/ws/**` (keeping `/error` permitted makes validation failures
surface as 400, not 403). **A participant's identity is the per-connection, server-assigned `WebSocketSession.getId()`
** —
it keys channel membership, the floor, ownership and routing, and it's what `Joined.selfId`/`MemberInfo.id`
carry; clients can't choose or spoof it. (That authority holds only under the trusted-server model: a frame's
sender is read from the server-stamped, plaintext stream index, which no cryptography binds — so attribution is
as trustworthy as the relay.) The **display name** is the only human label: the client sends it in `Join`, the server
validates it
against `[A-Za-z0-9_.-]{1,32}` (else `invalid_display_name`); clients append a short `#<id-prefix>` when two
members share one. The token's short TTL is the only bound on replay — the random nonce only makes each token
unique/unguessable,
it is **not** tracked, so a captured token is freely replayable to open new sockets within its ~60 s lifetime (no
revocation list — the accepted trade-off of going store-free). Serve over WSS and keep `walkie.allowed-origins`
tight in production: it **defaults to `*`** (wide open), and since CSRF is disabled the WS origin check is the
relied-upon anti-CSWSH control, so it MUST be overridden.
**Transport TLS is ON by default** (`TlsConfiguration`, a `WebServerFactoryCustomizer` gated by
`walkie.tls.enabled`, default true): the server serves HTTPS/WSS on 8443, auto-generating a self-signed
localhost cert into `~/.walkie-talkie/` when no `WALKIE_TLS_KEYSTORE` is supplied (via the JDK's `keytool`
with a fixed arg list — no user input). The Java client auto-trusts that dev cert on localhost (reading the
exported `dev-cert.pem`) or a `--tls-truststore`, with verification **never** disabled (`TlsTrust`). Set
`walkie.tls.enabled=false` to serve plain HTTP — the integration tests do this (`src/test/resources/
application.properties`), and it's the mode for a TLS-terminating reverse proxy (see `deploy/`). WSS encrypts
*everything* on the wire — control **and** the binary audio frames — whereas the optional passphrase E2EE is
application-layer and covers only the audio *payload* (control is never passphrase-encrypted, since the
server must read and act on it).

**Concurrency.** Virtual threads are enabled (`spring.threads.virtual.enabled`). Each
`WebSocketClientSession` owns an **asynchronous outbound mailbox** drained by exactly one dedicated virtual
thread: `send`/`sendAudio` encode on the caller thread, then hand the frame off without blocking — so a slow
recipient backs up only its own queue (never the fan-out caller `Channel.forEachOther` or other recipients),
and the single consumer keeps each recipient's frames in submission order (required by the stateful Opus
decode). Audio and control are split: audio is bounded and **dropped** on overflow (lossy, real-time), while
control (floor/mode/owner/membership) is delivered reliably and drained ahead of audio — a client too far
behind even for control is disconnected to force a clean reconnect/re-sync. The wrapping
`ConcurrentWebSocketSessionDecorator` is kept only as the socket-layer backstop (its send-time / buffer
limit aborts a wedged in-flight write). In the Java client the Opus encoder/decoder are confined to the
capture/playback threads respectively.

## Testing notes

Server tests mix unit (`FloorControlServiceTest`, `ChannelRegistryTest` via the `FakeClientSession`
helper) and integration (`WebSocketRelayIntegrationTest`, which boots on a random port and drives real
`StandardWebSocketClient` connections). In Spring Boot 4, `TestRestTemplate` is not at its old package
— the integration test uses the JDK `HttpClient` for the login call, and `@LocalServerPort` comes from
`org.springframework.boot.test.web.server`.

**Browser client tests** live in `walkie-server/src/test/js/` (outside `static/`, so they're not served) and
run on **Node's built-in runner** (`node --test`), no npm deps. The browser's E2EE + the outbound transmit-gate
decision are isolated in `static/assets/e2ee.js` (DOM-free, imported by `app.js`) so they're testable under
Node — which exposes the same Web Crypto API. `e2ee.test.js` pins the SAME known-answer vectors as Java's
`FrameCryptoTest` (keeping the two clients byte-identical) plus the `frameDisposition` no-plaintext gate. The
`:walkie-server:jsTest` Gradle task (an `Exec` guarded by an `onlyIf` Node-on-PATH check, hooked into `check`)
runs them as part of `build`. `walkie-server/package.json` (`"type":"module"`, no deps) only tells Node these
`.js` files are ES modules; it isn't served and Gradle ignores it. The Java client mirror is
`WalkieClient.outboundFrame` (pinned by `WalkieClientTest`).
