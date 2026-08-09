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
JAVA_OPTS= ./gradlew :walkie-server:bootRun -Paot=false   # reflective (non-AOT) startup — for debugging, and the mode for a DevTools auto-restart dev loop (spring-boot-devtools is dev-only, excluded from the jar; static/ edits LiveReload the browser regardless of AOT, but a code recompile — IDE build or `gradle -t compileJava` — only auto-restarts under reflective startup)
java -jar walkie-server/build/libs/walkie-server-0.1.0.jar   # the built boot jar — ALWAYS AOT (bundled spring.properties); add --walkie.tls.enabled=false for HTTP

# Java desktop client (relay transport). --mode: ptt|global|duplex ; --hifi flag for the music profile; --help for all options
WALKIE_KEY=hunter2 JAVA_OPTS= ./gradlew :walkie-client-java:run --args="--server https://localhost:8443 --display alice --channel team1 --mode ptt"   # --display/--channel/--key are required (--key reads WALKIE_KEY)

# Tests
JAVA_OPTS= ./gradlew :walkie-server:test                                            # one module
JAVA_OPTS= ./gradlew :walkie-server:test --tests '*ChannelTest'                     # one class
JAVA_OPTS= ./gradlew :walkie-server:test --tests '*ChannelTest.fullDuplexAlwaysGrantsTheFloorAndTracksNoHolder'  # one method
```

## Build layout

Three Gradle modules (Kotlin DSL build scripts — Gradle has no Java DSL, so `settings.gradle.kts` and the
per-module `build.gradle.kts` must stay Kotlin). Shared build logic, however, is **plain Java**: it lives in
`buildSrc/src/main/java/JavaConventionsPlugin.java`, a binary `Plugin<Project>` published under the id
`walkietalkie.java-conventions` by the `gradlePlugin` block in `buildSrc/build.gradle.kts` (sets `--release 25`,
`-parameters`, JUnit Platform, jacoco). Each module applies it via
`plugins { id("walkietalkie.java-conventions") }`. Do **not** reintroduce
`subprojects {}`/`allprojects {}`/`apply(plugin=...)` — that's the legacy pattern this replaced — and do not turn
it back into a precompiled `*.gradle.kts` script plugin: that is what used to drag the Kotlin compiler into
`buildSrc`, and with it a JVM-target pin (Kotlin DSL could only target JVM 25 on this JDK 26 host while Gradle's
Java task defaulted to 26, tripping the inconsistent-target warning).

- `walkie-shared` — wire protocol only, zero Spring deps.
- `walkie-server` — Spring Boot 4.1 (Spring Framework 7, Jackson 3, Jakarta EE 11); serves the browser
  client from `src/main/resources/static/`.
- `walkie-client-java` — console client (`javax.sound.sampled` + JDK WebSocket + Concentus Opus).

`check` (so `build`) also runs **`checkJavadocReferences`** — a `JavadocReferenceCheck` task
(`buildSrc/src/main/java/`, next to the conventions plugin; `java-gradle-plugin` brings the `java` plugin both
compile with, and `buildSrc/build.gradle.kts` pins that one compiler to Java 25). Both classes are in the **default
package** on purpose: a class in a named package cannot import one from the default package, so giving the plugin a
package would cut it off from this task type. It
fails on a `///` Javadoc reference naming a type or member that doesn't exist (`[SomeType]`, `[Type#member]`,
`[#member]`). The compiler treats those links as plain text, so a rename — or documenting something before writing
it — otherwise leaves a silently broken reference. Each module scans its own sources while the type index spans the
whole build (references cross modules routinely). It is deliberately conservative: Markdown links, bracketed prose
(`[tag][payload]`), `java.lang`, imported and fully-qualified third-party types are all skipped, as is a bare
simple name in a file with a wildcard import — but NOT an `Outer.Nested` whose outer half is one of ours, since a
wildcard import can't explain that away. Report: `<module>/build/reports/javadoc-references.txt`.

**A channel is SINGLE-transport, and the channel — not the socket — is where that lives.** `Transport`
(`shared/protocol/Transport.java`) rides on the wire now, and `Channel.transport` is the one authority: every
member of a channel is on the plane the channel names, `ServerMessage.Joined` reports it, and a joiner ADOPTS it
exactly as it adopts the mode. Mixing the two planes in one channel was silently broken rather than flexible: the
relay fan-out skipped a signaling member and a signaling sender's frames were dropped on arrival, so a mixed
channel was a full roster with working floor control and NO audio in either direction — indistinguishable from
working. Worse, a WebRTC member would offer to a relay member, whose browser answered and attached its microphone
to a peer connection outside every server gate (see `micTrackEnabled` below).

`ClientSession.transport()` survives only as the endpoint you DIALLED — an opening bid. It seeds a channel this
session creates (as does `ClientMessage.Join.transport`, which the owner's selector fills in and which beats the
dialled endpoint when both are present) and is otherwise not the answer to anything. Read the channel. The
per-session mirror this replaced had to be kept in step with the channel's copy, and could not be: a session
switching channels is a member of its old and its new channel at once (the departure happens only after the join
succeeds, so no refusal can strand a switcher), the two sit under different `ConcurrentHashMap` bin locks, and so
the old channel's move could land on a session the new one had just claimed — leaving that member permanently on
the wrong plane with nothing to correct it.

There is consequently no `TRANSPORT_MISMATCH` and no refusal: a joiner cannot disagree with a channel it gets no
vote on. The owner moves the whole channel with `changeTransport`, under the same bin lock as every join, and
every member gets a `transportChanged` and rebuilds its LOCAL audio pipeline in place. **Nobody reconnects**, and
that is the point — a reconnect mints a new session id, and ownership, the floor and the roster position are all
keyed on it, so an owner literally could not move their own channel without ceasing to own it. The two endpoints
were always able to support this: `/ws/audio` is `/ws/signal` plus a binary handler, identical control protocol.
The Java console client is the one client that cannot follow a move to WebRTC — it is relay-only, since there is
no mature pure-Java WebRTC stack — so it says so and leaves the channel cleanly rather than sitting in one whose
audio it can neither send nor receive.

**The floor queue is a SECTION of the roster, not a second list beside it.** The Members panel holds two `<ul>`s —
`#floorQueue` (whoever is in line, in queue order) and `#members` (everyone else, by name) — and a member has exactly
ONE row, which MOVES between them. Nobody is listed twice, and the two lists together can never be longer than the
roster alone, which is why nothing truncates.

It started as a panel plus a `✋N` chip on the roster row, i.e. the same person drawn twice. Hiding queued members
from the roster would have been the obvious fix and is the wrong one: a row is not a name, it carries the crown, the
mute badge, and the **owner's Mute/Unmute button**. Hiding it takes away the owner's only control over exactly the
people about to be handed the floor — and muting is meaningful there, since `detachFromFloorIfMuted` DEQUEUES a muted
member. Moving the row keeps all of it. (Muted-while-queued is therefore not a state to render; the server does not
allow it.)

`renderMembers` BUILDS the rows, `renderQueue` PLACES them. Placement — which section, in what order — is decided in
exactly one function, so no caller can update the roster and leave the queue describing the state before it. That is
not hypothetical: an earlier arrangement had two independent draw calls, and `renameMember` called only one, so the
queue kept a member's old display name while the row beside it showed the new one.

`renderQueue` MOVES rows (`appendChild` relocates an element rather than recreating it) and never rebuilds them.
`renderMembers` replaces every `<li>`, and focus falling back to `<body>` is exactly what `spaceDrivesFloor` reads as
"Space drives the floor" — so rebuilding on every floor transition meant one queued member's tap could turn a
bystander's next Space press into an open microphone. Hands go up and down constantly, making this the busiest roster
update there is; the speaking highlight took the same decision for the same reason. Both lists carry `.member-list`
so a row cannot restyle itself on the way across, and the position numbers come from a **CSS counter over document
order** — so nothing in JS numbers anything, and a row that leaves the section stops being numbered by the same
token. `queueView` deliberately exposes no position field: order is the position, and a second expression of it could
disagree with the counter.

`shown` takes THREE terms, not just the feature flag: the owner's `floorQueueEnabled`, the mode (FULL_DUPLEX has no
floor, the same term `needsVoiceMeter` carries), and a non-empty queue. `global` needs no case of its own — its queue
can never be enabled, so the flag answers for it. Gating on the FLAG rather than the contents is what makes a disable
clean: the server drains the queue and the `FloorQueueChanged` arrives just BEFORE the emptied snapshot, so reading
the flag collapses the section a beat early instead of briefly drawing a queue about to vanish.

The empty-roster note is a real `<li>` rather than the `:empty::before` rule it used to be: `#members` is
legitimately empty whenever EVERY member is queued, and a rule keyed on that announced "Not in a channel yet." to a
channel full of raised hands.

Because the section shows them, `floorNarration` returns its `SILENT` kind for `in-line` and `offered` whenever it is
up — otherwise five people in line meant every arrival and departure renumbered everyone AND wrote a line per member,
scrolling the things only the log can say (a rotation, a rename, a mute) out of view. It calls `queueView` for that
verdict rather than taking a flag, so "the section is showing it" and "do not also say it" cannot come apart.

`SILENT` is a THIRD answer, and the difference from `null` is load-bearing: `null` means "say nothing and FORGET what
was last said" (the caller clears its suppression key), `SILENT` means "say nothing and LEAVE the memory alone".
Collapsing them costs precisely what the suppression was for — log "Talking: X", raise a hand (suppressed), lower it,
and the unchanged "Talking: X" counts as new and prints again, so every raise/lower cycle reprints it. Both halves
are pinned by mutation tests, along with the case a reviewer caught in the TEST rather than the source: a fixture
meant to prove "everything else is still narrated" left `waiting` empty, so the section was never actually up and a
blanket silence would have passed it.

**This is the one place the two clients deliberately differ.** The Java console client's `floorNarration` mirrors the
browser's kind-for-kind and key-for-key everywhere else, but it has no section to read, so there those two lines ARE
the queue display and both stay. Both sides say so in a comment; anything else that differs is drift.

`check` also runs **`checkBrowserModuleDocs`** (`BrowserModuleDocCheck`, same directory), which keeps the
browser-module list further down this file honest: every DOM-free module must be named here and must have a
`<name>.test.js`, and the count word introducing that list must match how many there are. Five commits in one
sitting added `mic-errors.js`, `names.js` and four rules to `talk.js` without touching that list — each change was
self-contained and correct, and the documentation sat one directory up, so nothing pointed at the drift. The module
set is not a list this task maintains: it is whatever `app.js` imports from `./`, so a new module is covered the
moment it is wired up and `audio-worklet.js` (loaded by URL, never imported) stays correctly out of scope. Report:
`<module>/build/reports/browser-modules.txt`.

Four more `check` tasks apply that same idea to README.md and docs/CLIENT_PROTOCOL.md — derive the truth from
source, fail when the prose has fallen behind. Each is registered on the module that OWNS the source (so an
empty source set is a hard "the file moved" failure rather than a silent pass, and the failure lands on whoever
is editing the code):

| Task | Module | Source of truth | Bar |
|------|--------|-----------------|-----|
| `checkProtocolMessageDocs` | walkie-shared | `@JsonTypeName` + record components of `ClientMessage`/`ServerMessage` | a §3 table row keyed by the wire name, listing every field |
| `checkErrorCodeDocs` | walkie-shared | `ErrorCode` constants | a §13 table row (bidirectional) |
| `checkConfigurationKeyDocs` | walkie-server | `WalkieProperties` record components | `walkie.the-key` written as code anywhere in README |
| `checkClientOptionDocs` | walkie-client-java | picocli `@Option` names | a row in README's options table (bidirectional) |

Three design points worth not re-deriving. **Where the token has to appear decides whether the check is worth
anything.** "Mentioned somewhere in the file" is nearly worthless for a prose-heavy document: measured, deleting
BOTH §3 message tables still leaves 39 of 40 wire names matching a word-boundary search, because the later
sections cross-reference almost all of them. So the bar is a table row wherever a table IS the reference, and
otherwise an inline code span — and code spans are found by splitting a line on backticks (odd indices are code)
rather than by regex, because ``` `[^`]*TOKEN[^`]*` ``` also matches the prose BETWEEN two code spans.
**Field names are checked inside their own row**, never document-wide: `channel`, `member`, `mode`, `code` and
`from` are ordinary English that a 66 KB document satisfies by accident. **Defaults are not checked at all** —
`8 * 1024` against "8 KiB" and `Duration.ofMinutes(5)` against `5m` are both correct, so a value check would cry
wolf and still miss real drift.

Two anti-rot guards, because a pattern that stops matching stops checking and reports success:
`DocumentedTokensCheck` takes a `sentinel` (one real token, near the END of the set — patterns break by
truncating) and fails loudly if the extraction stops finding it; `ProtocolMessageDocCheck` fails when the
number of parsed record headers differs from the number of `@JsonTypeName` annotations. `ErrorCode`'s
`@JsonEnumDefaultValue` constant is exempted *by the annotation*, not by name — it is the forward-compatibility
fallback, never something the server sends. Reports: `<module>/build/reports/{protocol-messages,error-codes,
configuration-keys,client-options}.txt`.

Project rule: **no `var` keyword** anywhere. A linter may reformat saved files (tabs, `///` Javadoc,
`_` for unused catch/pattern vars) — match the existing style rather than fighting it.

## Architecture (the parts that span files)

**Two transports, one core.** Both are authenticated WebSocket endpoints whose handlers
(`AudioRelayHandler` for `/ws/audio`, `SignalingHandler` for `/ws/signal`) are thin subclasses of
`BaseWalkieHandler`. All real logic lives in the transport-agnostic `ConnectionService`, which never
touches a `WebSocketSession` (so it's unit-testable with a fake `ClientSession`). It owns membership
(`ChannelRegistry` → `Channel`), push-to-talk floor arbitration (its own floor handlers plus the
monitor-guarded floor state on `Channel` — there is **no** separate floor-control service), audio
fan-out, and WebRTC signaling relay. To understand message handling, read `ConnectionService.dispatch`
— a **pattern-matching `switch` over the sealed `ClientMessage`**, so adding a message type forces
every site to handle it.

**Channel modes** (`ChannelMode`): `MULTI_CHANNEL_PTT`, `GLOBAL_PTT` (channel name forced to `global`),
`FULL_DUPLEX` (no floor). A channel's mode is set at creation and **adopted** by later joiners; only
the **owner** (creator) may change it (`ChangeMode` → broadcast `ModeChanged`), and ownership transfers
to another member if the owner leaves. Floor state is a monitor-guarded `volatile String` holder on `Channel`
(every mutation runs under the channel monitor; the hot-path reads `holdsFloor`/`floorHolder` are lock-free
volatile reads, re-validated under the monitor before audio fan-out); full-duplex bypasses it. The floor is
conveyed to clients by ONE authoritative snapshot `FloorStatus(holderId, waiting)` — broadcast on **every** floor
change and sent to-one right after `Joined` — plus two to-one imperative triggers, `FloorGranted` ("go live") and
`FloorReserved(claimSeconds)` ("your turn — claim it"; queue only). `FloorTaken`/`FloorIdle`/`FloorDenied` are
**retired** (all subsumed by `FloorStatus`). **Trigger ordering is part of the contract, and differs per trigger:**
`FloorReserved` is sent **after** the `FloorStatus` that makes you `waiting[0]` of a free floor, because
reservedness is *derived* from that snapshot (`ConnectionService.reserveFloorHead` reserves, the snapshot fans out,
then `notifyReserved` triggers — one `reserveAndBroadcast` helper for the plain broadcasts, spelled out at the
leave/mute sites whose snapshot rides a batched fan-out). `FloorGranted` is deliberately sent **before** its
snapshot, because it is what opens the mic — snapshot-first there would render "LIVE" over a closed mic. Pinned by
`ConnectionServiceTest`'s two index-based ordering tests plus `FloorLifecycleIntegrationTest`. **Floor anti-hogging** (PTT modes, in `ConnectionService`): a holder gone silent past
`walkie.floor-idle-release` (default 5) is preempted when another member requests the floor (idle
auto-release — `Channel.preemptFloorIfIdle`, relay holders only, keyed off frame *timing* not content), and any
holder is force-released after `walkie.floor-max-hold` (default 300) of continuous holding (max-hold —
a scheduled sweep `releaseExpiredFloors` via `Channel.releaseIfExpired`, plus an immediate check in `onAudio` on
a relay holder's next frame). Max-hold is a pure time cap and bounds **any** holder incl. WebRTC; idle
auto-release is relay-only. Both `0`-disable; on a server-initiated release the ex-holder learns it is no longer
the holder from the re-broadcast `FloorStatus` and stops transmitting. Floor timing uses a `java.time.Clock` +
`Instant` (injectable for tests), and the three timers are bound as **`Duration`s** (`walkie.floor-idle-release: 5s`,
`floor-max-hold: 5m`, `floor-reservation: 10s`) rather than bare second counts, so the unit lives in the configuration
value and `ConnectionService` needs no conversion. `@DurationUnit(ChronoUnit.SECONDS)` on each record component is what
makes a UNIT-LESS value (a command-line `--walkie.floor-max-hold=300`, an env var, an operator's older properties file)
mean seconds — without it Spring's binder reads a bare number as MILLISECONDS, which was measured turning a bare `5` into a
ping every 5 ms. Absent binds as `null` for a Duration (a `long` arrived as 0, indistinguishable from a deliberate
"off"), so absent/negative fall back to the default while `0s` is honoured as disabled. **The `global` channel is special and server-managed:** it is reachable *only*
via `GLOBAL_PTT` (a `MULTI_CHANNEL_PTT`/`FULL_DUPLEX` join naming `global` is rejected with
`RESERVED_CHANNEL`); it is **always unencrypted** (a `GLOBAL_PTT` join carrying a `keyCheck` is rejected
with `ENCRYPTION_NOT_ALLOWED`, so anyone can join without knowing a passphrase); and it is created with a
sentinel owner (`ConnectionService.GLOBAL_CHANNEL_OWNER = "server"`, never a session id) so **no
participant owns it** — its mode can't be changed (`NOT_OWNER`) and ownership never transfers. It is
dropped when empty and recreated server-owned + unencrypted on the next join; clients render its owner as
"server-managed".

**Push-to-talk floor queue ("raise hand"), owner-toggleable per channel, default OFF.** The owner turns it on/off
(`SetFloorQueue` → broadcast `FloorQueueChanged`; the state rides in `Joined.floorQueueEnabled`, and a new channel
adopts `walkie.floor-queue-default` — off == the pre-queue behaviour, where a busy floor is simply not granted).
The toggle carries a `FloorStatus` **only when it moved the floor** — `Channel.setFloorQueueEnabled` returns whether
it dropped waiters or ended a claim window, and the broadcast is guarded on that. It used to send one
unconditionally, which is not a floor change at all: enabling decides who MAY wait, and both clients dutifully
narrated the unchanged snapshot as "Floor is free" once per toggle.
With the queue on, `RequestFloor` against a busy floor **enqueues** the member (FIFO, `Channel.enqueueFloor`)
instead of refusing it; when the floor frees it is **RESERVED** to the head for `walkie.floor-reservation`
(default 10) — **grant-to-claim, never a hot mic**: the head must claim (a `RequestFloor` → `FloorGranted`) within
that window or it is dropped and the floor passes to the next in line. The reserved member is **derived**, not
stored — it is exactly the head of `waiting` whenever `holderId` is null, because the server reserves the head the
instant the floor frees. `RequestFloor`/`ReleaseFloor` are interpreted by the sender's own floor state (grab /
claim / enqueue vs. stop / leave-queue / decline), so the queue needs no new client command. A muted member is
dropped from the queue; the ownerless `global` room is always off; full-duplex has no floor and no queue. The 1 s
`releaseExpiredFloors` sweep now runs three per-channel steps under the monitor — max-hold, idle-release (queue
advance, relay-only), then reservation-expiry — each handing the freed floor to the queue head (`reserveHead`,
re-broadcasting `FloorStatus`, and only THEN a to-one `FloorReserved` to that head — see the ordering rule above).

**Owner-enforced mute.** The owner can mute members (`MuteMember` for one, `MuteAll` for everyone but the owner
→ broadcast `MuteStatus`, `ConnectionService.handleMuteMember` / `handleMuteAll`); the muted set is per-`Channel`
state (`Channel.mutedMembers`, a `ConcurrentHashMap.newKeySet()`) surfaced in `MemberInfo.muted`.
**`MuteStatus(muted)` is ONE authoritative snapshot of every muted id**, not a per-member event — the same doctrine
as `FloorStatus`, and the retirement of a per-member `MemberMuted` mirrors that of `FloorTaken`/`FloorIdle`/
`FloorDenied`. It is what makes a channel-wide mute O(N) frames instead of O(N²): the old shape handed each of N
members one message per changed member, and a 255-member channel (the stream-index cap) meant 254 frames per click
against a 1024-deep control queue whose overflow *disconnects* the client — so an owner could spend the 200 msg/s
inbound budget on toggling and drop the room. `Channel.mutedMembers()` is the snapshot accessor and
`ConnectionService.muteStatusOf` the builder; it is an unordered `Set` on the wire (unlike `FloorStatus.waiting`,
whose order IS its meaning) so the server never imposes an order — arranging ids for DISPLAY is the client's job,
and both clients sort by display name to match their rosters. The initial value still rides in `MemberInfo.muted` on `Joined`
(as `locked`/`floorQueueEnabled` do), so `MuteStatus` is only sent for a CHANGE and the join path keeps seeding
synchronously — which is what keeps the full-duplex mic auto-open decision, made inside each client's `Joined`
handler, race-free. Clients derive mute STATE from the snapshot and TRANSITIONS by DIFFING it (as `FloorStatus` is
diffed), summarising a bulk change rather than printing a line per member. Enforcement is
**server-side and does not trust the client**: `onAudio` drops a muted sender's frame (`Channel.isMuted`, a
lock-free volatile-set read on the hot path, alongside the `holdsFloor` gate), and `handleRequestFloor` refuses a
muted member the floor so it can't seize-and-hold it (blocking PTT) even though its audio would be dropped. Muting
a member takes it off the floor entirely (`releaseFloor` if it was the live holder, `dequeueFloor` if it was
waiting/reserved) and re-broadcasts `FloorStatus` — offering the freed/advanced floor to the queue head — so a
talking-then-muted member stops. **Enforcement is relay-only** — WebRTC media is peer-to-peer (DTLS-SRTP), so a WebRTC talker's mute is
best-effort at its own client (it still sees itself in `MuteStatus` and stops), matching the E2EE relay-only boundary.
Only the owner may mute (`NOT_OWNER` otherwise); the owner can't mute itself and an unknown/left id is
`UNKNOWN_TARGET`; the ownerless `global` room can't be muted (`NOT_OWNER` via its sentinel owner).
**`MuteAll` is a one-shot over the members PRESENT**, so the standing rule is a separate owner flag:
`SetMuteNewMembers` → broadcast `MuteNewMembersChanged`, riding in `Joined.muteNewMembers` like `locked` /
`floorQueueEnabled`, backed by `Channel.muteNewMembers` (volatile) + `mutesNewMembers()`/`setMuteNewMembers`. It is
applied in **`Channel.add`** — the one atomic publication point of a member, alongside the stream index — which is
what lets the joiner learn of its own mute from `memberInfos()` in its OWN `Joined` roster (the bit each client's
full-duplex mic auto-open reads inside its `Joined` handler) and the others from `MemberJoined(muted=true)`, with
**no** `MuteStatus` for a join: that message is for CHANGES and would name an id nobody has been introduced to yet.
Only on the FRESH `computeIfAbsent` path (an idempotent re-`Join` — the browser's Apply flow — must not re-apply it
over a deliberate unmute) and **never the owner** (an owner can't unmute itself, so that would strand the channel's
only moderator; `unmuteOwner` covers a later promotion). Arming it changes NOBODY already present — deliberately,
so it can't cut off whoever is mid-sentence — and it starts OFF per channel with no `walkie.*` default, since a
property-seeded ON would mute each channel's own creator. Web: an owner-only **Channel settings** block of three
checkboxes (locked / raise-hand queue / mute on entry) plus a one-shot **Mute everyone now** button, with the three
states shown to EVERYONE as badges beside the Members heading; Java: `entry on|off` and a 🔇 marker in `w`. Concurrency
mirrors the floor discipline: the mute flip + floor release + `MuteStatus` broadcast run under
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
lock also never removes existing members. A locked channel **parks** a newcomer for the owner's approval rather than refusing it (see
"Owner-approved join requests" below); `CHANNEL_LOCKED` is returned only when parking is disabled
(`walkie.max-join-requests: 0`). Either way a switch INTO a locked channel is handled **without** dropping you
(see the in-place switch note below). The `ChannelLocked` broadcast
runs under the channel monitor reading the live `isLocked()` (convergence, like the passphrase/owner
broadcasts). The lock persists across a departure-triggered ownership change (a new owner inherits it and can
unlock); the sentinel-owned `global` room can't be locked (`NOT_OWNER`). Web: an owner-only Lock/Unlock toggle
in the Members header + a 🔒 badge shown to everyone; Java: `lock`/`unlock` commands + a 🔒 marker in `w` and
the join line.

**Owner-approved join requests ("requests to join").** A LOCKED channel doesn't turn newcomers away — it **parks**
them on a per-channel waiting list for the owner to admit or deny (`ConnectionService.handleResolveJoinRequest` /
`handleResolveAllJoinRequests`, `ClientMessage.ResolveJoinRequest` / `ResolveAllJoinRequests` /
`WithdrawJoinRequest`). There is deliberately **no second toggle**: the lock IS the switch, and
`walkie.max-join-requests` (default 16) bounds the list — set it to `0` and a locked channel refuses outright with
`CHANNEL_LOCKED`, the pre-feature behaviour and the "closed, don't even ask" setting. The list is
`Channel.joinRequests`, a monitor-guarded FIFO `SequencedMap` keyed by session id, seeded from
`Channel.Defaults(floorQueueEnabled, maxJoinRequests)` at creation like the floor-queue default. **Invariant: a
request exists only while the channel is locked** — knocks happen only in the locked branch and unlocking drains
the list in the same bin-locked step. Nothing is time-driven, so the 1 Hz floor sweep is not involved.
**A parked newcomer is NOT a member**: no stream index, no roster entry, no broadcasts, `channelName` untouched —
so every existing gate (`onAudio`, `requireChannel`) already refuses it with no new enforcement.
**Admission is grant-then-claim**, because the server *cannot* add the newcomer itself: a waiting session may be a
member of another channel, and leaving it would mean calling a `ChannelRegistry` mutate from inside another's
`compute` remapping, which `ConcurrentHashMap` forbids. So `joinOrCreate`'s locked branch has three ways through —
spend a one-shot grant (`Channel.consumeGrant`, which bypasses the LOCK only; capacity and the key-check still
apply), else validate the key-check and `knock`, else refuse. The grant is the **security boundary**: it is what
stops a parked newcomer admitting itself by simply re-sending `Join`. `JoinOutcome` therefore gains `Pending`, and
`ClientSession.pendingChannel` (single-valued: one outstanding request per session) is what lets `onClose` scrub a
waiting entry in O(1) — teardown reconciles by `channelName`, which a knocker doesn't have.
The owner sees `ServerMessage.JoinRequests`, an authoritative to-one snapshot re-sent on every change (the
`FloorStatus` doctrine); a granted-but-unclaimed entry stays listed so it can be revoked, and a newly elected owner
inherits the list. `JoinApproved` is one trigger for three causes the client need not distinguish (admitted,
unlocked, or the channel was dropped when its last member left — in which case the re-`Join` **recreates** it and
that newcomer owns it). `ChannelRegistry.leave` returns a sealed `LeaveOutcome`
(`Removed`/`OwnerElected`/`ChannelDropped`/`NotFound`) precisely so the dropped-channel case can't be overlooked.
Web: a **Requests to join (N)** block above the roster with per-row Admit/Deny + Admit all/Deny all, and a waiting
banner with Cancel; Java: `requests`, `admit <#id|all>`, `deny <#id|all>`, `cancel`, and a waiting count in `w`.
See docs/CLIENT_PROTOCOL.md §3f.

**In-place channel switch & rename.** A client changes channel/mode/passphrase **without a new socket**:
re-sending `Join` on the live connection is handled as "join the new channel, then leave the old one" on the same
`WebSocketSession`, so the **session id (identity) and the audio loops survive** — only per-channel state
(roster, floor, stream indices, E2EE key) turns over. **A switch is all-or-nothing: NO failure drops a switcher.**
`handleJoin` validates what it can up front (a duplicate `Join` for the *current* channel short-circuits to an
idempotent re-snapshot with no membership churn; `INVALID_CHANNEL` / `INVALID_DISPLAY_NAME` / `RESERVED_CHANNEL`
/ `ENCRYPTION_NOT_ALLOWED` / `CHANNEL_ROUTING_MISMATCH` are refused cheaply), and the verdicts that are knowable
only inside the atomic `joinOrCreate` — `PASSPHRASE_MISMATCH`, `CHANNEL_FULL`, `CHANNEL_LOCKED`,
`TOO_MANY_JOIN_REQUESTS`, and being parked for owner approval — are handled by departing the old channel
**only after** the join succeeds (`departChannel`, split out of `handleLeave` so it removes membership without
clearing the session's current-channel pointer). The display name `Join` carries is rolled back on a failed
switch too (`undoRename`), so a refused switcher can't be left in its old channel under a name that channel was
never told about. (Earlier versions left first, so a wrong passphrase dropped the switcher from both channels.) Transport can't switch in place (different endpoint + audio pipeline), so both clients reconnect for
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

**Relay end-to-end encryption (MANDATORY outside `global`).** Every channel except the server-managed `global`
room is end-to-end encrypted: a join carrying no key-check is refused with `PASSPHRASE_REQUIRED`
(`ConnectionService.handleJoin`), and `global` is the exact inverse — it refuses one with
`ENCRYPTION_NOT_ALLOWED`. Both clients derive on BOTH transports; on WebRTC the key never encrypts a frame
(DTLS-SRTP already does) but its key-check is the membership credential the server matches, which is also what
lets a relay member and a WebRTC member share a channel at all. With a shared passphrase (browser passphrase field,
or `--key` / `WALKIE_KEY` on the Java client), the sender encrypts the *whole* `[codec tag][payload]`
plaintext and the wire frame becomes `[scheme 0xE2][IV(12)][AES-256-GCM ciphertext+tag(16)]`. A single
`PBKDF2-HMAC-SHA512(passphrase, "walkie-talkie:e2ee:"+effectiveChannel, 600 000)` run derives **384 bits**:
the first 256 are the AES key, the next 128 are a **key-check value** the client sends in its `Join`. PBKDF2's
first output block is length-independent, so the AES key is byte-identical to a 256-bit derivation — the
known-answer test still holds. The key is derived identically by both clients; the server never sees it and
relays opaquely (the +29-byte envelope stays under `max-audio-frame-bytes`). `FrameCrypto` (Java) and the
`deriveKey`/`encryptFrame`/`decryptFrame` trio (`static/assets/e2ee.js`) **must stay byte-identical**; `FrameCryptoTest`
pins cross-platform known-answer vectors (key *and* key-check, generated by Node's WebCrypto) so they can't
drift. **Mismatch enforcement:** the server records the channel creator's key-check and rejects a joiner
whose key-check differs (`PASSPHRASE_MISMATCH`, in `ChannelRegistry.joinOrCreate`) — so a channel is
*uniformly* encrypted, enforced without the server learning the passphrase (the key-check is
brute-force-equivalent to the ciphertext it already relays). Note the two refusals are distinct and both matter:
`PASSPHRASE_MISMATCH` means you disagree with a channel's key, `PASSPHRASE_REQUIRED` that you brought none —
and the second is what used to quietly create a plaintext channel instead. **Owner rotation:** the owner can
rotate that key-check live but never clear it (`ChangePassphrase` → broadcast `PassphraseChanged`,
`ConnectionService.handleChangePassphrase`; a `null` key-check is refused by
`ChannelRegistry.changePassphrase`'s `RekeyResult.EncryptionRequired`, inside the same bin lock as the write, so
the channel is never even momentarily plaintext). Both clients also refuse to SEND a clearing rotation, and —
more importantly — refuse to OBEY one: `rekeyAction` maps a null announced key-check to `keep`, not to dropping
the key. That branch used to drop it, which is now a downgrade rather than a feature.

The deeper fix is in the transmit gate itself. `frameDisposition(held, announced, plaintextAllowed)` and
`WalkieClient.outboundFrame(frame, key, announced, plaintextAllowed)` now decide "may I send plaintext?" from
arguments the CALLER owns — you are in `global` **and** you never derived a key — instead of inferring it from
`announced == null`, which is server-supplied. Both terms are load-bearing and each is pinned by its own test: the
first because one forged `passphraseChanged {keyCheck: null}` used to turn a whole encrypted channel into
cleartext transmitters, the second because the MODE also arrives in the `Joined` snapshot, so a server lying about
it would otherwise reach the same place. Holding a key is the one fact a client owns outright, and it wins.
Corollary now enforced in both clients: no audio is sent while channel-less, since that state clears the recorded
key-check and neither send path was gated on more than "the socket is open". The write goes through `ChannelRegistry.changePassphrase`'s
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
Java `p!`) for a **revocation-style** rotation that forces out-of-band re-entry. There is always an old key to
wrap under, since every rotation is encrypted→encrypted (a channel is created encrypted and cannot be cleared);
only the FIRST passphrase, chosen when the channel is created, travels out-of-band. **Rotation is a transition, not revocation** — the new key
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
against `[\p{L}\p{M}\p{N} _.-]{1,32}` on its CANONICAL form — NFC-normalised then stripped, `ConnectionService.canonicalDisplayName`
(else `INVALID_DISPLAY_NAME`). That is letters, combining marks and digits from ANY script (Hebrew, Han, accented
Latin — `\p{M}` because niqqud and Arabic diacritics ARE marks) plus a plain space, `_`, `.` and `-`, 1-32 CODE
POINTS. Everything invisible is refused: the other separators (`\p{Zs}`: NBSP, ideographic space) and every
format/control character (`\p{C}`: ZWSP, soft hyphen, the bidi overrides, C0/C1). NOT as an impersonation defence —
both clients always print the id beside a name — but because a control character can split a log record in two
(names reach the log via the MDC) and a bidi override reorders the text AROUND it. Spaces INSIDE a name are kept as
typed (nothing collapses runs); the roster renders with `white-space: pre-wrap` so the browser shows them, matching
the Java client's terminal roster. Stripping precedes the pattern deliberately: a name of nothing but spaces would
otherwise satisfy `{1,32}`. **Channel names deliberately keep the ASCII rule** `[A-Za-z0-9_-]{1,64}` — the channel
name is the E2EE salt, derived CLIENT-side before the join is sent, so the server cannot canonicalise it
unilaterally without both clients agreeing byte-for-byte; it is also a `ChannelRegistry` map key, the `?channel=`
affinity routing key an external ingress hashes, and the `c <channel> [mode] [key]` console grammar splits on
whitespace. The rule lives in three mirrored copies — server, `static/assets/names.js`, `WalkieClient` — pinned by
the same vectors in `names.test.js` and `ConnectionServiceTest`, as the E2EE vectors are.
Clients append a short `#<id-prefix>` when two
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
*everything* on the wire — control **and** the binary audio frames — whereas the passphrase E2EE is
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
reconnect/re-sync. **WebSocket keepalive (idle connections).** The drainer's park doubles as the idleness detector: it waits for work
with a timeout of `walkie.keepalive-ping-interval` (a bindable `Duration` — `30s`, `PT30S` or a bare `30` via `@DurationUnit`; default 30 s, `0s` disables, absent binds as null and defaults), and a timeout — nothing to send for a
whole interval — is exactly when it emits a WebSocket **Ping** instead (`WebSocketClientSession.awaitWork`). No
scheduler, no session registry, and busy sessions are never pinged, because a queued frame satisfies the park first.
This exists because an idle WebSocket is what middleboxes reap and this server is legitimately silent for minutes
(`FloorStatus` and friends broadcast only on a CHANGE; a listening member sends nothing): an idle socket through a
Cloudflare tunnel was measured closing at 125 s with a bare TCP FIN and no Close frame (Cloudflare documents ~100 s,
Enterprise-only to change), and nginx's default `proxy_read_timeout` is 60 s — so the `deploy/` reverse-proxy story
would drop a quiet channel too. A Ping and not an application message on purpose: it needs no `ClientMessage` type,
no dispatch case and no client code, because browsers and the JDK's `java.net.http.WebSocket` both answer with a Pong
automatically (the JDK guarantees it — "the WebSocket implementation will automatically send a reciprocal pong").
Dead-peer detection is NOT built on it: nothing tracks whether the Pong came back.

**A channel fan-out reaches only members whose CURRENT channel is that channel.** An in-place switch adds the
session to its target and departs the old channel afterwards (so a refusal can't drop it from both), holding no lock
on the old one across the gap — so a concurrent change there could otherwise fan out to a session that has already
moved. `MessageBroadcaster.deliverIfStillIn` compares `channel.name()` against `ClientSession.channelName()`, which
the join hook sets to the target before announcing anything. It is filtered server-side because no channel-scoped
message carries a channel name, and a stray one does NOT heal: most are change events with no periodic re-sync (a
stray `MemberLeft` drops a real member from a roster, a stray `ModeChanged` flips a client's mode; only
`FloorStatus` self-corrects). Control plane only — the audio fan-out is untouched, a stray frame being self-healing
noise. **All outbound control goes through `MessageBroadcaster`** (which owns the `MessageCodec`):
`toOne` for a single recipient (a `Joined` snapshot, a floor grant, an error), `toAll`/`toOthers` for a channel
fan-out. It serializes each message **once** and hands the same JSON to every recipient's mailbox via
`ClientSession.sendEncoded`, so a fan-out to N members costs one encode, not N — and it keeps `ConnectionService`
transport-agnostic: it passes the broadcaster a typed `ServerMessage` and never touches the wire format, and the
session holds no codec (a dumb `sendEncoded`/`sendAudio` sink). The wrapping
`ConcurrentWebSocketSessionDecorator` is kept only as the socket-layer backstop (its send-time / buffer limit
aborts a wedged in-flight write). In the Java client the Opus encoder/decoder are confined to the capture/playback
threads respectively. **Floor + queue concurrency:** every floor mutation and the `FloorStatus` / `FloorGranted` /
`FloorReserved` broadcast it triggers run under the per-channel monitor (`synchronized(channel)`), and the queue
holds a reservation invariant — `floorReservedAt != EPOCH` **iff** a claim window is running: `reserveHead` is
idempotent (never re-stamps a running window), `expiredReservationHead` never expires an unstamped head, and
removing the reserved head (claim / decline / leave / mute) resets the clock. That is what lets the 1 s
`releaseExpiredFloors` sweep and the leave/mute paths call `reserveHead` unconditionally on any floor-freeing
event without racing a concurrent grant.

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
`CHANNEL_ROUTING_MISMATCH`**: `WalkieClient.switchTo` advances the connect target (`connectTarget`, a single
`ConnectTarget(channel, mode)` record so the pair can't tear across the console/reconnect threads; distinct from
the server-confirmed `currentChannel`/`currentMode`) and applies the target's key optimistically;
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

Server tests mix unit (`ConnectionServiceTest`, `ChannelTest`, `ChannelRegistryTest` via the `FakeClientSession`
helper) and integration (`WebSocketRelayIntegrationTest`, which boots on a random port and drives real
`StandardWebSocketClient` connections). In Spring Boot 4, `TestRestTemplate` is not at its old package
— the integration test uses the JDK `HttpClient` for the login call, and `@LocalServerPort` comes from
`org.springframework.boot.test.web.server`.

**Browser client tests** live in `walkie-server/src/test/js/` (outside `static/`, so they're not served) and
run on **Node's built-in runner** (`node --test`), no npm deps. `app.js` itself is NOT importable under Node (a
top-level `window.addEventListener` throws), so anything worth testing is pulled out into a **DOM-free sibling
module** that `app.js` imports — the pattern to follow for any new pure browser rule. Adding one to `app.js`'s
imports without adding a bullet below (or a test) fails `check` — see `checkBrowserModuleDocs` above. There are
six:
- `static/assets/e2ee.js` — E2EE + the outbound transmit-gate decision, testable because Node exposes the same
  Web Crypto API. `e2ee.test.js` pins the SAME known-answer vectors as Java's `FrameCryptoTest` (keeping the two
  clients byte-identical) plus the `frameDisposition` no-plaintext gate. Java mirror: `WalkieClient.outboundFrame`.
- `static/assets/channel-flags.js` — the channel's standing owner flags (`locked` / `floorQueueEnabled` /
  `muteNewMembers`) as ONE table each, plus `flagDisplay(flag, view)`. `app.js` BUILDS both the everyone-visible
  badge row and the owner's checkboxes from it, so a fourth flag is a table entry rather than markup in two places
  plus three render sites. (Layout note, since it has already been got wrong once: the Members/Log row must NOT use
  `flex-wrap` — wrapping makes each line's cross size content-driven, so the Log panel sizes to its own text and
  `#log`'s `overflow-y: auto` never engages, growing the page instead of scrolling. Let the panels shrink
  (`min-width: 0`) and stack via the media queries, which keep the height bounded.) `flagDisplay` is a pure function of the snapshot the SERVER wrote — the click handler only
  sends `flag.command(...)` — which is what makes a refused toggle snap back instead of leaving the UI claiming
  something untrue; `channel-flags.test.js` states that property directly, and pins the per-message wire field
  (`setLocked` carries `locked`, the others `enabled`) and that `field` matches the `Joined` component it is filled
  from.
- `static/assets/mic-errors.js` — what to tell the user when `getUserMedia` fails: advice per `DOMException.name`
  (plus the legacy Chrome/WebKit aliases, which survive longest in the embedded WebViews most likely to refuse
  capture) and the missing-`mediaDevices` message. Phrased as what to DO, because the raw text people actually hit —
  "The request is not allowed by the user agent or the platform in the current context" — is accurate and useless.
  Anything unrecognised keeps the raw message so no detail is lost.
- `static/assets/names.js` — BOTH name rules, each with its canonicaliser: `DISPLAY_NAME` +
  `canonicalDisplayName` and `CHANNEL_NAME` + `canonicalChannelName` (each NFC, then trim). All four MUST match
  the server's copies in `ConnectionService` and the Java client's in `WalkieClient` — the server is the authority,
  and the Rename button compares the typed value against the name the server confirmed.
  A channel name is the stricter rule: the same allow-list minus `.`, with a plain space allowed. Being an
  allow-list keeps every invisible character out, which matters more here than for a display name since a channel
  name is a rendezvous key with no `#id` printed beside it.
  Two things about whitespace are load-bearing. **Only U+0020 is in the pattern**, and `canonicalChannelName`
  COLLAPSES runs of `[\p{Zs}\t\n\r\v\f]` to one plain space before trimming — so NBSP, the ideographic space
  and a double space all converge on the same room instead of minting invisible duplicates of it. That is a
  deliberate deviation from `canonicalDisplayName`, which leaves `Roy  Ash` as typed: a display name is a label
  beside an id, a channel name IS the rendezvous. **And the set is written out rather than using `\s`**, because
  JavaScript's `\s` matches NBSP while Java's does not — using the shorthand would have the browser accept a name
  the server rejected. `\p{Zs}` has identical membership in both. Parity is pinned by the same vector list in
  `names.test.js` and `WalkieClientTest`. The console client's `c <channel> [mode] [key]` now takes a quoted name
  (`c "my room" ptt secret`) via `splitChannelArgs`, which quotes only the CHANNEL — the trailing passphrase stays
  a remainder, because it may contain spaces too.
  **Canonicalising a channel name is not cosmetic the way it is for a display name: it is the PBKDF2 salt.** Two
  members whose channel names differ by one byte derive DIFFERENT KEYS and sit in one room hearing nothing, each
  told `PASSPHRASE_MISMATCH` for a passphrase that is provably identical — unfalsifiable from the UI. Measured:
  Hebrew `שׁלום` written with the precomposed presentation form U+FB2A and as U+05E9 U+05C1 renders identically
  and derives a different key before NFC, the same key after (SHIN WITH SHIN DOT is a composition EXCLUSION, so
  NFC decomposes it). Both `e2ee.test.js` and `FrameCryptoTest` pin that convergence and a Hebrew-salt
  known-answer vector; `ConnectionServiceTest` pins that the two spellings land in ONE channel. Canonicalise once
  per entry point and use that string for all three of its jobs — the salt, the `?channel=` routing key and the
  `Join` — which is what `connect()`/`applyOrSwitch()` and `WalkieClient`'s constructor/`c` command now do. The
  handshake interceptor normalises the query param too, or the affinity comparison rejects a name that looks
  identical.
- `static/assets/connect-form.js` — whether the Connect form is ready to send: `connectProblems` returns
  `{field, kind, message}` per unsatisfied field (`kind` is `absent` or `invalid`, which is what lets the summary
  say "Missing: …" for an empty box and "Check: …" for a mistyped one), with `canConnect` and `readinessSummary`
  derived from it. The name rules themselves live in `names.js`; this module composes them. It exists because the
  SAME verdict drives
  two surfaces — the button's `disabled` state, recomputed on every keystroke, and the guard inside
  `connect()`/`applyOrSwitch()` when they act — and those were previously separate code, which is how a rule
  tightened in one place and not the other starts either blocking a legal form or letting an illegal one through.
  `secureContext` is an ARGUMENT, not something the module reads: that keeps it DOM-free and testable under Node,
  and is honest, since whether a key can be derived at all is an input to whether the form can be submitted. Note
  the fields carry no HTML `required`/`pattern` attributes on purpose — that would be a second, slightly different
  copy of the rules owned by the browser. `connect-form.test.js` pins the absent-vs-illegal distinction by MESSAGE
  (a mutation run showed field-only assertions let a dropped rule survive, since `''` fails the format pattern too)
  and asserts at most one problem per field, which is the invariant `readinessSummary` relies on.
- `static/assets/talk.js` — the floor rules (`floorStateFor`/`floorActionFor`/`floorIsFree`), `queueView` (the
  floor queue as something to DRAW — see the queue-display note below), the full-duplex
  mic auto-open policy (`shouldAutoOpenMic`, whose three terms are mode / "Connect muted" / owner-mute),
  `grantOpensMic` (a grant that outlived its hold must NOT open the mic — see the hold-vs-tap note below),
  `micTrackEnabled` (whether the local `MediaStreamTrack.enabled` should be on — exactly "am I transmitting?",
  with NO transport term and NO mode term, and the single rule for both writers: `enableLocalTracks` and
  `createPeer`. It replaced `transport === 'webrtc' ? on : true`, which forced a relay client's track ENABLED at
  every disable site. That was safe only while a relay client could never hold a peer connection — but inbound
  WebRTC offers were answered without consulting our own transport, so a relay member handed an offer attached
  this microphone to a peer, and one Talk press-and-release then left it streaming outside the server's floor and
  owner-mute enforcement AND outside the passphrase E2EE, with both UIs showing a free floor. `createPeer` also
  carried its own `mode === 'FULL_DUPLEX' ||` disjunct, which ignored "Connect muted" and owner-mute; full-duplex
  still auto-opens, via `shouldAutoOpenMic`, which weighs all three terms. Both clients now discard signaling on
  the relay transport and `ClientSession#supportsSignaling` is the server-side belt — the audio path was gated in
  both directions and signaling in neither. Residual, stated honestly: the RULE is pinned in `talk.test.js`, but
  the two `app.js` call sites are not, because `app.js` is not importable under Node),
  `isVoiceActive` + `VAD_RMS_THRESHOLD` (the full-duplex "actually talking" gate, moved here from app.js when it
  went from two call sites to four — one rule and one threshold, or the two transports would disagree about what
  counts as talking for the same person),
  `needsVoiceMeter` (which (transport, mode) pair needs a voice-activity METER to drive the roster highlight.
  Exactly one does: WebRTC + FULL_DUPLEX. The other three drivers are the relay decode lanes, the relay capture
  path and `onFloorStatus`'s floor holder — and that combination has none of them, so measured with two real
  browser clients it never lit a single row, for anyone, on either side. The `FULL_DUPLEX` term is load-bearing,
  not tidiness: on WebRTC in a PTT mode the highlight is STICKY until the floor moves, whereas a meter drives
  `markSpeaking`'s silence timer, so metering there would let the first pause longer than that timer clear the
  holder's row with nothing to re-light it. Two implementation notes worth not rediscovering — the analyser window
  must be a real fraction of the poll interval, since a 5 ms look every 100 ms caught a talker only
  intermittently; and the sweep RECONCILES its meters every tick rather than being set up once at join, because a
  peer's stream arrives on `ontrack` a full round trip later and at a different moment for the offering and
  answering sides, which showed up as one client highlighting and the other never doing so),
  `holdInProgress` (whether an interruption — lost focus, a hidden tab, a cancelled touch, or a Space up-edge after
  focus drifted — has a hold to end), `spaceDrivesFloor` (an ALLOW-list: Space drives the floor only when nothing
  owns it — nothing focused, the document body, or the Talk button — so a focused `<select>` keeps the key that
  opens it), `floorNarration` (what a `FloorStatus` is worth SAYING: `null`, or a `{kind, key}` whose key identifies
  the SITUATION so an unchanged one stays silent — the snapshot is re-sent on plenty of occasions that do not move
  the floor), the `TOO_QUICK_TO_TALK` coaching line, and
  `talkDecision`,
  the Talk control's ONE decision: state in, `{mode, label, myTurn, action}` out, with `btn.disabled` derived as
  `mode === 'disabled'`. `app.js` holds only the projection of state into it (`talkNow`), a four-write renderer
  (`updateTalkButton`) and the gesture handlers. `talk.test.js` pins every reachable button state, incl. that a
  channel-less client is `'disabled'` — a **disabled button still dispatches `mouseleave`**, so a `'hold'` there
  made a cursor crossing the control release a floor it never held. Java mirrors: `WalkieClient.floorStateFor` /
  `floorActionFor` / `shouldAutoOpenMic` / `floorNarration` (pinned by `WalkieClientTest`), which the FLOOR_* names,
  positional signatures, cases and — for the narration — the KEYS deliberately match, so the two clients fall silent
  on exactly the same snapshots; the hold-vs-tap axis and the Space-ownership rule are browser-only.

The `:walkie-server:jsTest` Gradle task (an `Exec` guarded by an `onlyIf` Node-on-PATH check, hooked into `check`)
runs them as part of `build`, and picks up a new `*.test.js` with no build change. `walkie-server/package.json`
(`"type":"module"`, no deps) only tells Node these `.js` files are ES modules; it isn't served and Gradle ignores
it.
