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
JAVA_OPTS= ./gradlew :walkie-server:bootRun       # runs AOT-processed (spring.aot.enabled), HTTPS on https://localhost:8443 (auto self-signed cert)
JAVA_OPTS= ./gradlew :walkie-server:bootRun --args='--walkie.tls.enabled=false'   # still AOT-processed, plain HTTP on http://localhost:8080 (TLS toggle is a runtime read, works under AOT)
JAVA_OPTS= ./gradlew :walkie-server:bootRun -Paot=false   # general escape hatch: reflective (non-AOT) startup, for debugging
java -jar walkie-server/build/libs/walkie-server-0.1.0.jar   # the built boot jar — ALWAYS AOT (bundled spring.properties); add --walkie.tls.enabled=false for HTTP

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
`RESERVED_CHANNEL`); it is **always unencrypted** (a `GLOBAL_PTT` join carrying a `keyCheck` is rejected
with `ENCRYPTION_NOT_ALLOWED`, so anyone can join without knowing a passphrase); and it is created with a
sentinel owner (`ConnectionService.GLOBAL_CHANNEL_OWNER = "server"`, never a session id) so **no
participant owns it** — its mode can't be changed (`NOT_OWNER`) and ownership never transfers. It is
dropped when empty and recreated server-owned + unencrypted on the next join; clients render its owner as
"server-managed".

**Owner-enforced mute.** The owner can mute members (`MuteMember` for one, `MuteAll` for everyone but the owner
→ broadcast `MemberMuted`, `ConnectionService.handleMuteMember` / `handleMuteAll`); the muted set is per-`Channel`
state (`Channel.mutedMembers`, a `ConcurrentHashMap.newKeySet()`) surfaced in `MemberInfo.muted`. Enforcement is
**server-side and does not trust the client**: `onAudio` drops a muted sender's frame (`Channel.isMuted`, a
lock-free volatile-set read on the hot path, alongside the `holdsFloor` gate), and `handleRequestFloor` refuses a
muted member the floor so it can't seize-and-hold it (blocking PTT) even though its audio would be dropped. Muting
the current floor holder frees the floor (`releaseFloor` + broadcast `FloorIdle`) so a talking-then-muted member
stops. **Enforcement is relay-only** — WebRTC media is peer-to-peer (DTLS-SRTP), so a WebRTC talker's mute is
best-effort at its own client (it still gets `MemberMuted` and stops), matching the E2EE relay-only boundary.
Only the owner may mute (`NOT_OWNER` otherwise); the owner can't mute itself and an unknown/left id is
`UNKNOWN_TARGET`; the ownerless `global` room can't be muted (`NOT_OWNER` via its sentinel owner). Concurrency
mirrors the floor discipline: the mute flip + floor release + `MemberMuted` broadcast run under
`synchronized(channel)`, and `Channel.remove` scrubs `mutedMembers` **under that same monitor** (with a
membership re-check in the handler) so a leave can't race a mute into a ghost entry that outlives the member. Both
clients reflect it: a muted member is shown 🔇/`[muted]`, and being muted disables the talk control ("Muted by
owner" / refuses `t`) and stops the mic. Web mute buttons live in the Members column and apply immediately (not
via the Apply/Reset flow); the Java client exposes `mute`/`unmute <#id|all>`.

**Owner-enforced channel lock.** The owner can lock a channel to NEW members (`SetLocked` → broadcast
`ChannelLocked`, `ConnectionService.handleSetLocked` via `ChannelRegistry.setLocked`); the flag is
`Channel.locked` (`volatile`) and rides in `Joined.locked`. Enforcement is in the **atomic join**: a locked
`Channel` makes `ChannelRegistry.joinOrCreate` refuse to add a member (returns `null`, before the key-check),
so a newcomer is rejected with `CHANNEL_LOCKED` even with the right passphrase. The `setLocked` write and the
join's lock read share the **same bin lock** as the key-check (both under `channels.compute*`), so a toggle is
atomic w.r.t. every concurrent join — a joiner sees consistently the locked or the unlocked state. Only
NEWCOMERS are blocked: an existing member's in-place re-join to its **current** channel short-circuits in
`handleJoin` before `joinOrCreate` (idempotent re-snapshot, carrying `locked`), so it's never locked out; the
lock also never removes existing members. `CHANNEL_LOCKED` behaves like `PASSPHRASE_MISMATCH` (both are
detectable only inside the atomic join): an initial connect fails cleanly, and a switch INTO a locked channel
drops you — the clients handle it the same way (browser disconnects; Java exits). The `ChannelLocked` broadcast
runs under the channel monitor reading the live `isLocked()` (convergence, like the passphrase/owner
broadcasts). The lock persists across a departure-triggered ownership change (a new owner inherits it and can
unlock); the sentinel-owned `global` room can't be locked (`NOT_OWNER`). Web: an owner-only Lock/Unlock toggle
in the Members header + a 🔒 badge shown to everyone; Java: `lock`/`unlock` commands + a 🔒 marker in `w` and
the join line.

**In-place channel switch & rename.** A client changes channel/mode/passphrase **without a new socket**:
re-sending `Join` on the live connection is handled as "leave the old channel, join the new one" on the same
`WebSocketSession`, so the **session id (identity) and the audio loops survive** — only per-channel state
(roster, floor, stream indices, E2EE key) turns over. `handleJoin` validates the target **before** leaving: a
duplicate `Join` for the *current* channel short-circuits to an idempotent re-snapshot (no membership churn),
and a bad target (`INVALID_CHANNEL` / `INVALID_DISPLAY_NAME` / `RESERVED_CHANNEL` / `ENCRYPTION_NOT_ALLOWED`)
is refused **without** dropping you — the **only** failure that can still drop a switcher is
`PASSPHRASE_MISMATCH`, detectable solely inside the atomic `joinOrCreate` (which necessarily runs after the
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
annotations stay under `com.fasterxml.jackson.core`). Jackson 3 exceptions are unchecked. **AOT/native
readiness:** because that (de)serialization happens in a `@Component` and not a controller signature,
Spring's AOT engine can't auto-discover the protocol types, so `ProtocolRuntimeHints`
(a `RuntimeHintsRegistrar` wired via `@ImportRuntimeHints` on `MessageCodec`) registers their reflection
hints — derived from each sealed root's `getPermittedSubclasses()` so a newly added message type is covered
automatically (the carried `MemberInfo`/`ChannelMode`/`ErrorCode` are reached transitively, verified by
`ProtocolRuntimeHintsTest`). The `org.graalvm.buildtools.native` plugin **is** applied, so `processAot` /
`processTestAot` / `nativeCompile` / `nativeTest` exist; `build`/`test` generate+compile the AOT sources as a
dependency but the **test suite stays reflective**. **`bootRun` and the boot jar run AOT-processed by default**
(`spring.aot.enabled=true` — bootRun via a system property overridable with `-Paot=false`; the jar via a
bundled `BOOT-INF/classes/spring.properties` that wins over any `-D`, so the jar is always AOT). Never put
`spring.aot.enabled` in `application.yml` — `AotDetector`/`SpringProperties` read it before the YAML loads.
`TlsConfiguration` reads `walkie.tls.enabled` **at runtime** (not `@ConditionalOnProperty`), so the TLS/HTTP
toggle keeps working under AOT — one AOT build serves HTTPS:8443 (default) or HTTP:8080
(`--walkie.tls.enabled=false`). Do **not** reintroduce a build-time `@Conditional` on that bean; AOT would
freeze it. See README "Native image / AOT readiness" for the native caveat (`keytool` is absent in a native
image, so the dev-cert auto-gen path can't run there → supply `WALKIE_TLS_KEYSTORE` or terminate TLS at a proxy).

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
whose key-check differs (`PASSPHRASE_MISMATCH`, in `ChannelRegistry.joinOrCreate`) — so a channel is
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
(an un-adopted rotation) emits no undecodable audio either — both stay silent until they adopt the new key. Global stays unencrypted — its sentinel owner makes any rotation there `NOT_OWNER`. **Ownership
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
against `[A-Za-z0-9_.-]{1,32}` (else `INVALID_DISPLAY_NAME`); clients append a short `#<id-prefix>` when two
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
thread: the caller hands off each outbound frame without blocking — a control frame already encoded by
`MessageBroadcaster`, or a raw audio `byte[]` — so a slow recipient backs up only its own queue (never the
fan-out caller `Channel.forEachOther` or other recipients), and the single consumer keeps each recipient's frames
in submission order (required by the stateful Opus decode). Audio and control are split: audio is bounded and
**dropped** on overflow (lossy, real-time), while control (floor/mode/owner/membership) is delivered reliably and
drained ahead of audio — a client too far behind even for control is disconnected to force a clean
reconnect/re-sync. **All outbound control goes through `MessageBroadcaster`** (which owns the `MessageCodec`):
`toOne` for a single recipient (a `Joined` snapshot, a floor grant, an error), `toAll`/`toOthers` for a channel
fan-out. It serializes each message **once** and hands the same JSON to every recipient's mailbox via
`ClientSession.sendEncoded`, so a fan-out to N members costs one encode, not N — and it keeps `ConnectionService`
transport-agnostic: it passes the broadcaster a typed `ServerMessage` and never touches the wire format, and the
session holds no codec (a dumb `sendEncoded`/`sendAudio` sink). The wrapping
`ConcurrentWebSocketSessionDecorator` is kept only as the socket-layer backstop (its send-time / buffer limit
aborts a wedged in-flight write). In the Java client the Opus encoder/decoder are confined to the capture/playback
threads respectively.

**Multi-instance (channel affinity), off by default.** The `Channel` (membership, floor, owner/mode/lock,
keyCheck, mute, audio fan-out, stream-index pool) is entirely in-process, so horizontal scaling uses
**channel affinity**: an external ingress consistent-hashes the handshake `?channel=` query param so every
member of a channel lands on the one instance that owns it (each instance owns a disjoint set of channels — no
shared media bus). Both clients send that param; `ChannelHandshakeInterceptor` captures it into the session
(`ClientSession.handshakeChannel()`). When `walkie.channel-affinity=true`, `ConnectionService.handleJoin`
enforces the invariant **a socket may only serve a channel this instance owns** — the handshake channel, or one
it already hosts (`channelRegistry.find` present ⇒ that channel routes here by the affinity invariant) — and
refuses a switch to a channel owned elsewhere with `CHANNEL_ROUTING_MISMATCH` (client reconnects, ingress
re-pins). The flag defaults **false** (single instance): the check is skipped and in-place switches work as
before. Tokens are already stateless (share `WALKIE_AUTH_SIGNING_KEY`). **The Java client auto-reconnects on
`CHANNEL_ROUTING_MISMATCH`**: `WalkieClient.switchTo` advances the connect target (`connectChannel`/`connectMode`,
distinct from the server-confirmed `currentChannel`/`currentMode`) and applies the target's key optimistically;
on the mismatch, `reconnect()` (its own virtual thread — not the listener callback) closes the socket and opens a
fresh one carrying `?channel=<target>`, whose `onOpen` re-joins the target. A `reconnecting` flag makes the old
socket's `onClose` a no-op (not a lost connection → no process exit) and collapses a burst of mismatches into one
reconnect; the sender loop now survives a single failed send (socket closing/swapped) instead of exiting.
The **browser** client auto-reconnects too, reusing its transport-change path: on the mismatch it sets
`state.pendingReconnect` and disconnects, and `ws.onclose` → `connect()` re-reads the form (which still holds the
target channel/mode/passphrase), so the fresh socket carries `?channel=<target>` and the ingress re-pins it. Not
yet done: the `global` room hashes to one instance (doesn't scale); a single oversized channel would need a
shared backplane.
See README "Known constraints".

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
