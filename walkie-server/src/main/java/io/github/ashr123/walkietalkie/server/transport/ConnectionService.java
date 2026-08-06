package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;
import io.github.ashr123.option.SomeInt;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.ratelimit.SessionRateLimiter;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.support.RequestContext;
import io.github.ashr123.walkietalkie.shared.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// Transport-agnostic heart of the server. Both WebSocket handlers feed decoded control messages and
/// raw audio frames here; this class owns membership, push-to-talk floor arbitration, audio fan-out
/// and WebRTC signaling relay. It never touches a `WebSocketSession` directly, which keeps it
/// unit-testable with fake [ClientSession] instances.
@SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
@Service
public class ConnectionService {

	private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
	/// Letters, combining marks and digits from ANY script, plus `_` and `-`, 1-64 code points. Mirrored in the
	/// browser's `static/assets/names.js` and the Java client's `WalkieClient`, and pinned against the same vectors.
	///
	/// Stricter than [#DISPLAY_NAME] on purpose: no whitespace (the Java console client's `c <channel> [mode] [key]`
	/// command splits on it) and no `.`. Being an ALLOW-list, it also excludes every separator and every
	/// format/control character for free — which matters more here than for a display name, because a channel name
	/// is a rendezvous key with no `#id` printed beside it, so a name carrying an invisible character is a room
	/// nobody else can retype.
	private static final Pattern CHANNEL_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N}_-]{1,64}");
	/// Display names hold letters, combining marks and digits from ANY script — Hebrew, Han, accented Latin — plus a
	/// plain space, `_`, `.` and `-`. Combining marks are not optional: Hebrew niqqud and Arabic diacritics are marks,
	/// so omitting `\p{M}` would silently reject vocalised text.
	///
	/// What is excluded is everything that cannot be SEEN. Every other separator (`\p{Zs}` — NBSP, ideographic space,
	/// the thin spaces) and every format or control character (`\p{C}` — ZWSP, ZWNJ, soft hyphen, the bidi overrides,
	/// C0/C1) is refused. Not because they let one member impersonate another — both clients always print the session
	/// id beside a name, so two look-alike names are still told apart — but because a control character can split a
	/// log record in two (names reach the log through the MDC) and a bidi override such as U+202E reorders the text
	/// AROUND it, so a roster row or log line could be made to read differently than it is.
	///
	/// Length is 1-32 CODE POINTS rather than UTF-16 units: Java matches a supplementary letter as one unit inside a
	/// `\p{...}` class, so 32 astral letters pass and 33 do not. Verified rather than assumed.
	private static final Pattern DISPLAY_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N} _.-]{1,32}");

	/// Human wording for the rule above: the pattern itself is unreadable to anyone who has to act on the error.
	private static final String DISPLAY_NAME_RULE =
			"Display name must be 1-32 letters, digits or spaces (any language), '_', '.' or '-' — no invisible characters";
	private static final String GLOBAL_CHANNEL = "global";
	/// Owner id stamped on the server-managed "global" channel. It is deliberately NOT a session id — session
	/// ids are Spring-generated UUID strings, so "server" can never collide — which means no real participant
	/// is ever the owner: the global room's mode can't be changed or claimed by a user ([#handleChangeMode]'s
	/// owner check always fails for it) and ownership never transfers when a member leaves. Clients recognize
	/// this constant to label the room "server-managed".
	private static final String GLOBAL_CHANNEL_OWNER = "server";

	private final ChannelRegistry channelRegistry;
	private final WalkieProperties properties;
	private final MessageBroadcaster broadcaster;
	private final SessionRateLimiter audioRateLimiter;
	private final SessionRateLimiter controlRateLimiter;
	private final Clock clock;
	private final Duration floorIdleRelease;
	private final Duration floorMaxHold;
	/// The push-to-talk floor-queue claim window: how long the reserved head is given to CLAIM its turn before the
	/// reservation-expiry sweep drops it and offers the floor to the next in line. Carried to the reserved head in
	/// [ServerMessage.FloorReserved] so its client can run the countdown (see docs/CLIENT_PROTOCOL.md §3b).
	private final Duration floorReservation;
	/// The server-wide settings a newly created channel adopts — the floor-queue default and the join-request cap.
	/// Built once here (rather than per join) because they are fixed for the process; the sentinel-owned `global`
	/// room deliberately bypasses them and uses [Channel.Defaults#NONE].
	private final Channel.Defaults channelDefaults;

	@Autowired
	public ConnectionService(ChannelRegistry channelRegistry,
	                         WalkieProperties properties,
	                         MessageBroadcaster broadcaster) {
		this(channelRegistry, properties, broadcaster, Clock.systemUTC());
	}

	/// Package-private seam: lets tests drive the push-to-talk floor timers and both rate limiters with a
	/// controllable clock instead of wall time. The audio and control flood guards are owned here (one
	/// [SessionRateLimiter] each, from the configured per-second ceilings) rather than injected, so they share
	/// this clock.
	ConnectionService(ChannelRegistry channelRegistry,
	                  WalkieProperties properties,
	                  MessageBroadcaster broadcaster,
	                  Clock clock) {
		this.channelRegistry = channelRegistry;
		this.properties = properties;
		this.broadcaster = broadcaster;
		this.audioRateLimiter = new SessionRateLimiter(properties.maxAudioFramesPerSecond(), clock);
		this.controlRateLimiter = new SessionRateLimiter(properties.maxControlMessagesPerSecond(), clock);
		this.clock = clock;
		// No conversion: the properties are Durations, so the unit lives in the configuration value.
		this.floorIdleRelease = properties.floorIdleRelease();
		this.floorMaxHold = properties.floorMaxHold();
		this.floorReservation = properties.floorReservation();
		this.channelDefaults = new Channel.Defaults(properties.floorQueueDefault(), properties.maxJoinRequests());
	}

	public static void onConnect(ClientSession session) {
		// Scope the lifecycle line so it carries the session id (and the name, once known) via the MDC, like the
		// per-message lines. At connect the client hasn't joined yet, so the name is still blank.
		try (RequestContext.Scope _ = RequestContext.scope(session)) {
			log.info("connected (transport={})", session.transport());
		}
	}

	/// The canonical form of a display name — what the server stores, broadcasts and compares: NFC-composed, then
	/// stripped of leading and trailing whitespace. Null in, null out, so the callers' own null check still reads.
	///
	/// NFC because one name can arrive as two different byte sequences that render identically (`é` as a single code
	/// point or as `e` plus a combining acute; Hebrew with niqqud likewise) — without it, a rename to a
	/// visually-identical name is an invisible change, and two members can hold names nobody can tell apart for a
	/// reason no client can explain. Strip because leading and trailing spaces carry no information and the browser
	/// does not even render them (its roster collapses whitespace runs and drops the edges — measured, all four
	/// variants came out the same pixel width).
	///
	/// The ORDER matters and is the reason this is not a one-liner at each call site: a name of nothing but spaces
	/// satisfies the pattern's `{1,32}` on its own, so stripping has to happen first and leave an empty string for
	/// the pattern to reject. Multiple spaces INSIDE a name are deliberately left alone.
	private static String canonicalDisplayName(String requested) {
		return requested == null ? null : Normalizer.normalize(requested, Normalizer.Form.NFC).strip();
	}

	/// The canonical form of a channel name — NFC, then stripped — applied to every join before the name is
	/// validated, used as the registry key, or echoed in a `Joined` snapshot.
	///
	/// Canonicalising SERVER-side is not redundant with the clients doing it. The channel name is each client's
	/// PBKDF2 salt, so two members who spell the same visible name differently derive different keys; if the server
	/// also kept them as different registry keys they would at least be in different rooms and merely puzzled,
	/// whereas keying them together and letting the key-checks disagree produces a `PASSPHRASE_MISMATCH` for a
	/// passphrase that is, as far as either user can see, identical. Normalising here means the server's idea of
	/// "same channel" matches the clients' idea of "same salt": either both agree, or the mismatch is a genuinely
	/// different passphrase. (Measured: Hebrew `שׁלום` written with the precomposed presentation form U+FB2A and as
	/// U+05E9 U+05C1 render identically and derive different keys before NFC, the same key after.)
	private static String canonicalChannelName(String requested) {
		return requested == null ? null : Normalizer.normalize(requested, Normalizer.Form.NFC).strip();
	}

	/// Handles one decoded control message. The caller's identity is bound for the dynamic scope of the call and
	/// surfaced on the log lines emitted while handling it (via the MDC) — see [RequestContext#scope]. The audio
	/// relay path ([#onAudio]) is deliberately not scoped, to avoid per-frame MDC churn.
	public void onMessage(ClientSession session, ClientMessage message) {
		// Drop a late frame from an already-closed session before touching the rate limiter: tryAcquire's
		// computeIfAbsent would otherwise re-create a bucket for a session onClose already forgot, leaking one map
		// entry that is never forgotten again (the audio path guards the same resurrection at its own tryAcquire,
		// keyed off channelName — which can't be used here, since Join arrives before this session has a channel).
		if (session.isClosed()) {
			return;
		}
		// Per-session control-plane flood guard: drop messages from a sender over its rate ceiling BEFORE doing
		// any work (dispatch, broadcasts, the MDC scope), so a control flood — e.g. a rename storm fanning out to
		// the whole channel — can't amplify cost. Dropped silently, like the audio guard (replying would itself
		// amplify); an honest client (sparse control + ICE bursts) stays well under the limit.
		if (!controlRateLimiter.tryAcquire(session.id())) {
			return;
		}
		try (RequestContext.Scope _ = RequestContext.scope(session)) {
			dispatch(session, message);
		}
	}

	private void dispatch(ClientSession session, ClientMessage message) {
		switch (message) {
			case ClientMessage.Join join -> handleJoin(session, join);
			case ClientMessage.Leave _ -> handleLeave(session);
			case ClientMessage.RequestFloor _ -> handleRequestFloor(session);
			case ClientMessage.ReleaseFloor _ -> handleReleaseFloor(session);
			case ClientMessage.ChangeMode(ChannelMode mode) -> handleChangeMode(session, mode);
			case ClientMessage.ChangePassphrase(String keyCheck, String wrappedKey) ->
					handleChangePassphrase(session, keyCheck, wrappedKey);
			case ClientMessage.TransferOwnership(String newOwnerId) -> handleTransferOwnership(session, newOwnerId);
			case ClientMessage.Rename(String displayName) -> handleRename(session, displayName);
			case ClientMessage.MuteMember(String memberId, boolean muted) -> handleMuteMember(session, memberId, muted);
			case ClientMessage.MuteAll(boolean muted) -> handleMuteAll(session, muted);
			case ClientMessage.SetLocked(boolean locked) -> handleSetLocked(session, locked);
			case ClientMessage.ResolveJoinRequest(String sessionId, boolean admit) ->
					handleResolveJoinRequest(session, sessionId, admit);
			case ClientMessage.ResolveAllJoinRequests(boolean admit) -> handleResolveAllJoinRequests(session, admit);
			case ClientMessage.WithdrawJoinRequest _ -> handleWithdrawJoinRequest(session);
			case ClientMessage.SetFloorQueue(boolean enabled) -> handleSetFloorQueue(session, enabled);
			case ClientMessage.SetMuteNewMembers(boolean enabled) -> handleSetMuteNewMembers(session, enabled);
			case ClientMessage.Offer(String target, String sdp) ->
					relaySignal(session, target, new ServerMessage.SignalOffer(session.id(), sdp));
			case ClientMessage.Answer(String target, String sdp) ->
					relaySignal(session, target, new ServerMessage.SignalAnswer(session.id(), sdp));
			case ClientMessage.IceCandidate(String target, String candidate, String sdpMid, Integer sdpMLineIndex) ->
					relaySignal(session, target, new ServerMessage.SignalIce(session.id(), candidate, sdpMid, sdpMLineIndex));
		}
	}

	/// Sends a control-plane error to the requester AND logs why the request was refused — so an operator can
	/// see the reason, and when a client then disconnects (e.g. it closes after a passphrase mismatch) the
	/// preceding line explains why. Runs in the requester's message scope, so the log carries its id + name.
	private void sendError(ClientSession session, ErrorCode code, String message) {
		log.info("request refused: {} — {}", code, message);
		broadcaster.toOne(session, new ServerMessage.ErrorMessage(code, message));
	}

	private void handleJoin(ClientSession session, ClientMessage.Join join) {
		String requested = join.mode() == ChannelMode.GLOBAL_PTT ? GLOBAL_CHANNEL : canonicalChannelName(join.channel());

		// Connect guard: a duplicate Join for the channel this session is already in is idempotent — re-send
		// the current snapshot so the client re-syncs, but do NOT churn membership (no leave/rejoin, no
		// MemberLeft + MemberJoined flicker for the other members). A Join for a different channel still switches.
		if (requested != null
				&& requested.equals(session.channelName())
				&& channelRegistry.find(requested) instanceof Some(Channel current)) {
			// Under the monitor because the roster carries each member's MUTE bit, and every mute flip runs under this
			// same monitor: read lock-free, this re-snapshot could hand back a mute state a concurrent MuteStatus
			// broadcast has already superseded, and nothing would correct it until the next mute change (a client
			// re-seeds its whole mute set from this roster).
			synchronized (current) {
				broadcaster.toOne(
						session,
						new ServerMessage.Joined(
								session.id(),
								current.name(),
								current.mode(),
								current.ownerId(),
								current.isLocked(),
								current.isFloorQueueEnabled(),
								current.mutesNewMembers(),
								current.memberInfos()
						)
				);
			}
			return;
		}

		// Validate the switch TARGET first, so a bad request (typo'd channel name, invalid display name, or
		// reserved/encryption misuse) is refused cheaply. These are the checks that CAN be made up front; the rest
		// (passphrase, capacity, lock) are only knowable inside the atomic join — which is why the departure from
		// the current channel now happens AFTER it succeeds, leaving no failure that can drop a switcher.
		if (requested == null || !CHANNEL_NAME.matcher(requested).matches()) {
			sendError(session, ErrorCode.INVALID_CHANNEL,
					"Channel name must match " + CHANNEL_NAME.pattern());
			return;
		}
		// Canonicalise BEFORE validating, and carry the canonical form forward — it is what gets stored, broadcast and
		// compared from here on, so every client sees the same bytes for the same name.
		String displayName = canonicalDisplayName(join.displayName());
		if (displayName == null || !DISPLAY_NAME.matcher(displayName).matches()) {
			sendError(session, ErrorCode.INVALID_DISPLAY_NAME, DISPLAY_NAME_RULE);
			return;
		}
		// The "global" channel is the server-managed broadcast room: reachable ONLY via global push-to-talk,
		// and never end-to-end encrypted — so anyone can join it (there is no shared passphrase to know).
		if (GLOBAL_CHANNEL.equals(requested) && join.mode() != ChannelMode.GLOBAL_PTT) {
			sendError(session, ErrorCode.RESERVED_CHANNEL,
					"'" + GLOBAL_CHANNEL + "' is reserved — use Single global push-to-talk to join it.");
			return;
		}
		if (join.mode() == ChannelMode.GLOBAL_PTT && join.keyCheck() != null) {
			sendError(session, ErrorCode.ENCRYPTION_NOT_ALLOWED,
					"The global channel can't be end-to-end encrypted — clear the passphrase to join it.");
			return;
		}
		// ...and the exact inverse: every OTHER channel is end-to-end encrypted, so a join must bring a key-check.
		// There are no plaintext channels any more; `global` above is the single exception.
		//
		// Discriminated on the MODE, not the channel name, for the same reason as the check above: `requested` was
		// rewritten to GLOBAL_CHANNEL for GLOBAL_PTT at the top of this method, so a name test would exempt any
		// channel a client happened to call "global".
		//
		// Placed here, after the display-name and channel-name checks, so those still report their own specific
		// reason for a join that is malformed in more than one way — a client that sent a bad name AND no
		// passphrase is better told about the name it can see than about a key it never derived. Placed BEFORE the
		// atomic join for the reason the block below documents: nothing has been given up yet, so a refusal costs
		// the session neither its channel nor its floor.
		//
		// Transport-independent by design. A WebRTC session's media never reaches the server and is already
		// encrypted (DTLS-SRTP), so its key-check buys no media confidentiality — but requiring it keeps ONE
		// invariant ("every non-global channel has a passphrase"), makes the passphrase a membership credential on
		// that path, and is what lets a relay member and a WebRTC member finally share a channel: their
		// key-checks now agree instead of one being null and failing PASSPHRASE_MISMATCH.
		if (join.mode() != ChannelMode.GLOBAL_PTT && join.keyCheck() == null) {
			sendError(session, ErrorCode.PASSPHRASE_REQUIRED,
					"'" + requested + "' needs an end-to-end-encryption passphrase — every channel but '"
							+ GLOBAL_CHANNEL + "' is encrypted.");
			return;
		}
		// Channel-affinity (multi-instance): this socket may only serve a channel THIS instance owns — the channel
		// it was routed to at the handshake, or one it already hosts (a live local Channel proves that channel
		// routes here, by the affinity invariant). A switch to a channel owned by another instance is refused so
		// the client reconnects and the router re-pins it. Off (single instance): every channel is served here, so
		// this is skipped and switches stay in place. (The idempotent re-Join of the current channel returned above,
		// so it never reaches here.)
		if (properties.channelAffinity()
				&& !requested.equals(session.handshakeChannel())
				&& !(channelRegistry.find(requested) instanceof Some<Channel>)) {
			sendError(session, ErrorCode.CHANNEL_ROUTING_MISMATCH,
					"'" + requested + "' is served by another instance — reconnect to switch to it.");
			return;
		}

		// A SWITCH does not give up its current channel until the target has actually taken it. Every way a join can
		// fail — a wrong passphrase, a full channel, a locked one, a full waiting list, or being parked for approval
		// — is only knowable INSIDE the atomic join below, so leaving first (as this used to) meant any of them left
		// the client in no channel at all. Departing afterwards instead makes the whole `Join` all-or-nothing: on
		// failure the session keeps its channel, its floor and its roster entry, exactly as if it had never asked.
		String previousChannel = session.channelName();

		// The display name rides on Join too, and it must be in place BEFORE the join: the roster snapshot the
		// registry captures under its lock reads it. So apply it now and undo it if the join doesn't happen — else a
		// refused switcher would sit in its old channel under a name that channel was never told about.
		String previousDisplayName = session.displayName();
		session.setDisplayName(displayName);
		// onMessage snapshotted the MDC name at scope entry, when it was still blank — advance it so this handler's
		// lines carry name=... instead of name=-. The scope's restore-on-exit still cleans it up.
		RequestContext.updateDisplayName(displayName);

		// Emit the joiner's initial state — its Joined snapshot then an authoritative FloorStatus snapshot — from
		// INSIDE the registry's add monitor span (see joinOrCreate's onJoinUnderLock). Sending it there, atomically
		// with the joiner becoming broadcast-eligible, serializes it with floor transitions: a concurrent
		// release/grant/reserve can't land a floor broadcast that races this hint (leaving the joiner seeing a stale
		// holder or an out-of-date queue) — any subsequent change reaches the now-eligible joiner via the normal
		// broadcast and it converges on the truth. Unconditional: FloorStatus renders the whole floor UI (holder +
		// queue), so it seeds the joiner even when the floor is free (holderId == null, empty queue).
		Consumer<ChannelRegistry.JoinOutcome.Admitted> emitInitialState = joined -> {
			Channel joinedChannel = joined.channel();
			session.joinedChannel(joinedChannel.name());
			broadcaster.toOne(
					session,
					new ServerMessage.Joined(
							session.id(),
							joinedChannel.name(),
							joinedChannel.mode(),
							joinedChannel.ownerId(),
							joinedChannel.isLocked(),
							joinedChannel.isFloorQueueEnabled(),
							joinedChannel.mutesNewMembers(),
							joined.roster()
					)
			);
			broadcaster.toOne(session, floorStatusOf(joinedChannel));
		};

		// Global is server-owned (sentinel owner) and forced unencrypted (null key-check); every other channel
		// is owned by its creator and adopts the joiner's key-check. Only a NON-global newly created channel adopts
		// the server-wide floor-queue default — the sentinel-owned global room is created with the queue OFF (false)
		// and can never be toggled on (its floor-queue toggle is NOT_OWNER), since it is unbounded and a large queue
		// would mean heavy position-broadcast churn.
		ChannelRegistry.JoinOutcome outcome = join.mode() == ChannelMode.GLOBAL_PTT
				? channelRegistry.joinOrCreate(requested, join.mode(), null, session, GLOBAL_CHANNEL_OWNER, Channel.Defaults.NONE, emitInitialState)
				: channelRegistry.joinOrCreate(requested, join.mode(), join.keyCheck(), session, channelDefaults, emitInitialState);
		// The atomic join carries its own verdict, decided under the registry bin lock, so a refusal names its EXACT
		// reason — no re-reading the channel afterwards to guess which of the three rules rejected us (a re-read
		// could report a reason that only became true in the instant after the failed join).
		switch (outcome) {
			case ChannelRegistry.JoinOutcome.Refused(ChannelRegistry.JoinOutcome.Reason reason) -> {
				undoRename(session, previousDisplayName);
				refuseJoin(session, reason);
			}
			case ChannelRegistry.JoinOutcome.Pending(Channel channel, boolean alreadyWaiting) -> {
				undoRename(session, previousDisplayName);
				// Compare AFTER undoRename: for a switcher it puts the name back, so the name did not really change
				// and the owner needs no refresh. A re-knock that DID rename must refresh, since the waiting list
				// renders that name and its membership is otherwise unchanged.
				parkJoinRequest(session, channel, alreadyWaiting, !previousDisplayName.equals(session.displayName()));
			}
			case ChannelRegistry.JoinOutcome.Admitted admitted -> {
				// We are a member somewhere now, so we are not waiting at any door — but HOW we stop waiting differs:
				String wasWaitingFor = session.pendingChannel();
				if (wasWaitingFor != null && wasWaitingFor.equals(admitted.channel().name())) {
					// This join spent a grant, which removed the request INSIDE the atomic join. Nothing is left to
					// withdraw, so the owner's view of the list has to be refreshed explicitly.
					session.pendingCleared();
					notifyOwnerOfJoinRequests(admitted.channel());
				} else {
					// It gave up on one door by successfully joining a DIFFERENT channel: withdraw the abandoned
					// request, which refreshes THAT channel's owner.
					withdrawPendingElsewhere(session, null);
				}
				// The target has us; only now let go of the channel we came from. Departing BEFORE announcing keeps
				// the observable order a switch has always had (the old channel's MemberLeft, then the new channel's
				// MemberJoined) and keeps the "left" line tagged with the channel actually left, since announceJoin
				// is what advances the logging context to the new one.
				if (previousChannel != null && !previousChannel.equals(admitted.channel().name())) {
					departChannel(session, previousChannel);
				}
				announceJoin(session, admitted);
			}
		}
	}

	/// Puts the display name back after a join that did not happen. `Join` carries the name alongside the channel, so
	/// without this a refused or parked SWITCHER would keep the new name while the channel it is still in — and which
	/// received no `MemberRenamed` — goes on showing the old one.
	///
	/// Only a session still in a channel is rolled back. For one that is in none, there is no roster to contradict and
	/// the name is the only label it has: rolling it back would blank a fresh connection's name (its previous one is
	/// ""), leaving the owner of a locked channel looking at an anonymous entry in its waiting list.
	private void undoRename(ClientSession session, String previousDisplayName) {
		if (session.channelName() != null && !previousDisplayName.equals(session.displayName())) {
			session.setDisplayName(previousDisplayName);
			RequestContext.updateDisplayName(previousDisplayName);
		}
	}

	/// The target is locked and parks newcomers, so this session is now on its waiting list rather than in it.
	///
	/// Waiting costs the session nothing: `handleJoin` departs the old channel only once the target has actually taken
	/// it, so a switcher that ends up parked keeps its current channel, floor and roster entry while it waits — and a
	/// fresh connection simply stays channel-less.
	///
	/// The waiting marker is set HERE rather than inside the registry because it is session state, not channel state —
	/// and it is what lets the disconnect path scrub the request in O(1).
	private void parkJoinRequest(ClientSession session, Channel channel, boolean alreadyWaiting, boolean renamed) {
		// At most one outstanding request per session: knocking at a second door drops the first, so a session can
		// never be waiting in two places (which the single-valued marker could not represent, and the disconnect
		// scrub could not clean up).
		withdrawPendingElsewhere(session, channel.name());
		session.pendingIn(channel.name());
		broadcaster.toOne(session, new ServerMessage.JoinPending(channel.name()));
		// Refresh the owner unless this changed nothing they can see. An idempotent re-knock carrying the SAME name
		// is pure churn, and re-sending the snapshot for it would let a client looping on Join flood the owner's
		// mailbox — but a re-knock that renamed did change what their list renders.
		if (!alreadyWaiting || renamed) {
			notifyOwnerOfJoinRequests(channel);
		}
		if (!alreadyWaiting) {
			log.info("waiting for approval to join {} (locked)", channel.name());
		}
	}

	/// Drops any request this session left waiting at a DIFFERENT channel, notifying that channel's owner so their
	/// list stays truthful. A no-op when the session is not waiting anywhere, or is already waiting at `keepChannel`.
	private void withdrawPendingElsewhere(ClientSession session, String keepChannel) {
		String pending = session.pendingChannel();
		if (pending == null || pending.equals(keepChannel)) {
			return;
		}
		session.pendingCleared();
		if (channelRegistry.find(pending) instanceof Some(Channel previous)) {
			synchronized (previous) {
				if (previous.withdraw(session.id()) instanceof Some<ClientSession>) {
					notifyOwnerOfJoinRequests(previous);
				}
			}
		}
	}

	/// Sends the channel's owner the authoritative waiting-list snapshot. Owner-only: nobody else can act on it, and
	/// broadcasting it would tell every member who is knocking. Read and sent under the channel monitor so two
	/// concurrent changes converge on the channel's LIVE list rather than each fanning out its own captured view —
	/// the same discipline as the owner/passphrase/lock broadcasts. The sentinel-owned `global` room can't be locked,
	/// so it never has requests and never reaches the send.
	private void notifyOwnerOfJoinRequests(Channel channel) {
		synchronized (channel) {
			if (channel.member(channel.ownerId()) instanceof Some(ClientSession owner)) {
				broadcaster.toOne(owner, new ServerMessage.JoinRequests(channel.joinRequestInfos()));
			}
		}
	}

	/// Owner-only: admit or deny ONE waiting newcomer.
	///
	/// Admitting does NOT add the member here. The server cannot move a session into the channel itself — a newcomer
	/// may still be in another channel, and leaving that one would mean calling a registry mutate from inside
	/// another's remapping, which `ConcurrentHashMap` forbids — so it records a one-shot approval and tells the
	/// newcomer to complete the join with its own `Join`. That also means the newcomer's client, not the server,
	/// decides the moment its audio context switches channels.
	private void handleResolveJoinRequest(ClientSession session, String sessionId, boolean admit) {
		if (!(requireOwnedChannel(session) instanceof Some(Channel channel))) {
			return;
		}
		// The decision + its notification run under the channel monitor, so two concurrent decisions can't both
		// resolve the same request, and the owner's refreshed snapshot always reflects the live list.
		synchronized (channel) {
			Option<ClientSession> resolved = admit ? channel.grant(sessionId) : channel.withdraw(sessionId);
			if (!(resolved instanceof Some(ClientSession newcomer))) {
				sendError(session, ErrorCode.UNKNOWN_TARGET, "Nobody with id '" + sessionId + "' is waiting to join.");
				return;
			}
			if (admit) {
				broadcaster.toOne(newcomer, new ServerMessage.JoinApproved(channel.name()));
			} else {
				newcomer.pendingCleared();
				sendError(newcomer, ErrorCode.JOIN_REQUEST_DENIED, "The channel owner declined your request to join.");
			}
			broadcaster.toOne(session, new ServerMessage.JoinRequests(channel.joinRequestInfos()));
			log.info("{} {} ({})", admit ? "admitted" : "denied", sessionId, newcomer.displayName());
		}
	}

	/// Owner-only: admit or deny EVERY waiting newcomer, in arrival order — the same per-newcomer effects as
	/// [#handleResolveJoinRequest], in one step.
	private void handleResolveAllJoinRequests(ClientSession session, boolean admit) {
		if (!(requireOwnedChannel(session) instanceof Some(Channel channel))) {
			return;
		}
		synchronized (channel) {
			List<ClientSession> resolved = admit ? channel.grantAll() : channel.drainJoinRequests();
			for (ClientSession newcomer : resolved) {
				if (admit) {
					broadcaster.toOne(newcomer, new ServerMessage.JoinApproved(channel.name()));
				} else {
					newcomer.pendingCleared();
					sendError(newcomer, ErrorCode.JOIN_REQUEST_DENIED, "The channel owner declined your request to join.");
				}
			}
			broadcaster.toOne(session, new ServerMessage.JoinRequests(channel.joinRequestInfos()));
			if (!resolved.isEmpty()) {
				log.info("{} all {} waiting newcomer(s)", admit ? "admitted" : "denied", resolved.size());
			}
		}
	}

	/// The waiting client itself gives up. Reuses the same scrub as a disconnect, so the owner's list is refreshed
	/// by exactly one code path; a no-op when the sender is not waiting anywhere.
	private void handleWithdrawJoinRequest(ClientSession session) {
		withdrawPendingElsewhere(session, null);
	}

	/// The channel this session owns, or [io.github.ashr123.option.None] after replying with the reason it isn't
	/// eligible — `NOT_IN_CHANNEL` before joining, `NOT_OWNER` otherwise. The sentinel-owned `global` room can never
	/// be locked, so it never has waiting newcomers; its owner check refuses there anyway.
	private Option<Channel> requireOwnedChannel(ClientSession session) {
		if (!(requireChannel(session) instanceof Some(Channel channel))) {
			return Option.of((Channel) null);
		}
		if (!session.id().equals(channel.ownerId())) {
			sendError(session, ErrorCode.NOT_OWNER, "Only the channel owner can resolve join requests");
			return Option.of((Channel) null);
		}
		return Option.of(channel);
	}

	/// Reports a refused join to the would-be joiner. The reason is the one the atomic join itself decided, so each
	/// arm states a fact rather than a guess. The client is left connected and simply not in a channel.
	private void refuseJoin(ClientSession session, ChannelRegistry.JoinOutcome.Reason reason) {
		switch (reason) {
			case LOCKED -> sendError(
					session,
					ErrorCode.CHANNEL_LOCKED,
					"This channel is locked by its owner — you can't join it right now."
			);
			case FULL -> sendError(
					session,
					ErrorCode.CHANNEL_FULL,
					"This channel is full — it has reached its member limit."
			);
			case PASSPHRASE_MISMATCH -> sendError(
					session,
					ErrorCode.PASSPHRASE_MISMATCH,
					"This channel is using a different encryption passphrase (or none) — you can't join it."
			);
			// There are exactly two transports, so the joiner's OWN transport names the other by elimination — this
			// refusal exists precisely because they differ. The switch is exhaustive on purpose: adding a third
			// transport stops this compiling, which is the moment the required one must be carried ON the refusal
			// rather than derived here.
			case TRANSPORT_MISMATCH -> sendError(
					session,
					ErrorCode.TRANSPORT_MISMATCH,
					"Every member of this channel is on the " + switch (session.transport()) {
						case AUDIO_RELAY -> "WebRTC";
						case SIGNALING -> "WebSocket relay";
					} + " transport — switch to it to join them."
			);
			case WAITING_LIST_FULL -> sendError(
					session,
					ErrorCode.TOO_MANY_JOIN_REQUESTS,
					"Too many people are already waiting to join this channel — try again shortly."
			);
		}
	}

	/// Completes a successful join: the joiner's own initial state was already emitted inside the registry's monitor
	/// span (see `emitInitialState`), so what remains is making it visible to everyone else and logging it.
	private void announceJoin(ClientSession session, ChannelRegistry.JoinOutcome.Admitted joined) {
		// Advance the MDC channel so the "joined" line below (and anything after it in this scope) is tagged
		// with the channel just joined, instead of repeating channel=… in the body.
		RequestContext.updateChannel(joined.channel().name());

		// Tell the OTHER members about the joiner. This is intentionally OUTSIDE the registry lock: it concerns
		// the joiner's visibility to others, not the joiner's own floor view, so it needs no floor serialization.
		broadcaster.toOthers(
				joined.channel(),
				session.id(),
				new ServerMessage.MemberJoined(new MemberInfo(
						session.id(),
						session.displayName(),
						joined.channel().requireStreamIndex(session.id()),
						// The joiner IS muted from the outset when the owner has armed "mute new members"
						// ([Channel#add] applies it), and this is the only message that tells the others — the rule
						// deliberately emits no [ServerMessage.MuteStatus], which is for CHANGES and would name an id
						// they have not been introduced to yet. A lock-free read is sound here even though the add ran
						// under the monitor: that add happened earlier on THIS thread, so its write to the concurrent
						// mute set is already visible to us.
						joined.channel().isMuted(session.id())
				))
		);

		// Identity (session + name) and the channel are carried by the MDC prefix now (the name advanced in
		// handleJoin, the channel just updated). "created" when this join brought the channel into being.
		log.info("{} mode={}", joined.created() ? "created" : "joined", joined.channel().mode());
	}

	/// Leaves the channel this session is currently in, if any, and clears its current-channel pointer.
	private void handleLeave(ClientSession session) {
		// Snapshot channelName ONCE: it feeds the null-guard and the departure below, and a concurrent onClose/switch
		// nulling it in between would turn find(null)/leave(null) into a ConcurrentHashMap null-key NPE (the same
		// hazard fixed in onAudio / handleTransferOwnership's local-first form).
		String channelName = session.channelName();
		if (channelName == null) {
			return;
		}
		departChannel(session, channelName);
		session.leftChannel();
	}

	/// Removes `session` from the NAMED channel and announces the departure, WITHOUT touching the session's
	/// current-channel pointer. That separation is what lets a switch join its target first and only then let go of
	/// the channel it came from: by that point the pointer already names the NEW channel, so this must not clear it.
	/// [#handleLeave] is the ordinary entry point, which departs the current channel and then clears the pointer.
	private void departChannel(ClientSession session, String channelName) {
		Option<Channel> channelBeforeLeave = channelRegistry.find(channelName);
		// Remove the member + re-elect an owner atomically in the registry, THEN announce — broadcasting MemberLeft
		// only AFTER the removal closes the ghost-member window: a member joining between an earlier broadcast and
		// the removal could otherwise snapshot a roster still containing the leaver yet never receive its MemberLeft.
		// The registry mutate (via Channel.remove) also tears the leaver off the floor: it clears the holder, scrubs
		// it from the queue, and — if it was the reserved head — ends that reservation (resets the clock to EPOCH so
		// the next head gets a fresh window). We must NOT hold the channel monitor across channelRegistry.leave (the
		// registry takes its bin lock then this monitor, so the reverse order deadlocks — see the lock-order note on
		// Channel), so the floor teardown runs AFTER the removal, on LIVE state.
		ChannelRegistry.LeaveOutcome departure = channelRegistry.leave(channelName, session.id());
		// The channel emptied and was dropped, so anyone still waiting at its door can never be admitted by an owner
		// that no longer exists. Release them instead: the lock died with the channel, so they are cleared to join,
		// and whichever of them re-sends Join first recreates it and owns it. Done BEFORE the survivor fan-out below
		// because there are no survivors on this path (the channel is gone).
		if (departure instanceof ChannelRegistry.LeaveOutcome.ChannelDropped(List<ClientSession> cleared)) {
			for (ClientSession waiting : cleared) {
				waiting.pendingCleared();
				broadcaster.toOne(waiting, new ServerMessage.JoinApproved(channelName));
			}
			if (!cleared.isEmpty()) {
				log.info("channel dropped; released {} waiting newcomer(s) to rejoin", cleared.size());
			}
		}
		boolean ownerChanged = departure instanceof ChannelRegistry.LeaveOutcome.OwnerElected;
		if (channelBeforeLeave instanceof Some(Channel channel)) {
			// Announce to the survivors of the SAME channel object the leave acted on — NOT a fresh find()-by-name,
			// which could resolve a dropped-and-recreated same-named channel and notify its members instead.
			synchronized (channel) {
				// Announce everything this leave changed to the survivors in ONE fan-out — MemberLeft, an optional
				// OwnerChanged (+ owner-unmute), and the fresh floor snapshot — rather than a separate member-iteration
				// for each. toOthers excludes the leaver, which was already removed above, so this is the survivor set.
				List<ServerMessage> events = new ArrayList<>();
				events.add(new ServerMessage.MemberLeft(session.id()));
				if (ownerChanged) {
					// Read the CURRENT owner (not the value leave returned) under the monitor: when two owners
					// leave back-to-back, the monitor orders the OwnerChanged broadcasts and each carries the
					// latest elected owner, so a survivor converges on the real owner rather than ending up
					// believing it is a member who has already left.
					String newOwnerId = channel.ownerId();   // the live elected owner (see the note above), read once under the monitor
					events.add(new ServerMessage.OwnerChanged(newOwnerId));
					// Auto-election can promote a muted member (it picks any remaining member): the new owner is
					// never muted, so unmute it if needed — else it would be a muted owner nobody can unmute.
					if (unmuteOwner(channel)) {
						events.add(muteStatusOf(channel));
					}
					// The waiting list is owner-only knowledge, and ownership just moved: hand the new owner the
					// current list (to-one, so it doesn't ride the survivor fan-out) or it would inherit newcomers it
					// cannot see. The outgoing owner is gone, so nothing needs clearing there.
					if (channel.member(newOwnerId) instanceof Some(ClientSession newOwner)) {
						broadcaster.toOne(newOwner, new ServerMessage.JoinRequests(channel.joinRequestInfos()));
					}
					log.info(
							"ownership transferred to {} ({})",
							newOwnerId,
							channel.member(newOwnerId) instanceof Some(ClientSession newOwner)
									? newOwner.displayName()
									: "?"
					);
				}
				// Floor teardown on LIVE state, run UNCONDITIONALLY. This is safe despite the monitor gap around
				// channelRegistry.leave precisely because reserveHead is IDEMPOTENT: it (re-)reserves the head only
				// when the floor is free with NO running window, so it never re-stamps (never moves backward) a
				// reservation a concurrent sweep may already have started for the current head, and the sweep's EPOCH
				// guard stops it dropping a head this leave is about to reserve. The snapshot rides the same fan-out
				// and always re-syncs the survivors (an unchanged floor just re-sends it — harmless).
				//
				// Spelled out rather than folded into reserveAndBroadcast because the snapshot RIDES that fan-out: the
				// FloorReserved trigger has to wait for `toOthers` below, since the reserved head is one of the others
				// and must see itself as the head of a free floor BEFORE being told it is its turn (notifyReserved).
				// Reordering these two lines alone would change nothing on the wire — floorStatusOf only builds the
				// message; the send is `toOthers`.
				ClientSession reserved = reserveFloorHead(channel, clock.instant());
				events.add(floorStatusOf(channel));
				broadcaster.toOthers(channel, session.id(), events.toArray(ServerMessage[]::new));
				notifyReserved(reserved);
			}
		}
		log.info("left");   // the channel left is in the MDC prefix; clear it for any later line in this scope
		RequestContext.updateChannel(null);
	}

	private void handleRequestFloor(ClientSession session) {
		if (!(requireChannel(session) instanceof Some(Channel channel))) {
			return;
		}
		if (channel.isMuted(session.id())) {
			// The owner muted this member: refuse the floor outright so a muted member can't seize and HOLD it
			// (which would block everyone else in a PTT channel even though onAudio drops the muted member's frames).
			// A conforming client never asks — its talk control is disabled by the mute snapshot — so this is the server
			// enforcement boundary against a client that ignores its mute. Silent, like the onAudio frame drop.
			// (Fast-path/full-duplex gate; the PTT acquire below re-checks under the monitor to close the race.)
			log.debug("refused the floor to a muted member");
			return;
		}
		if (channel.mode() == ChannelMode.FULL_DUPLEX) {
			broadcaster.toOne(session, new ServerMessage.FloorGranted());
			return;
		}
		Instant now = clock.instant();
		// Acquire/enqueue/preempt AND the resulting broadcast happen under the floor monitor so the
		// FloorGranted/FloorStatus can't interleave with a concurrent release/reserve reaching the new holder.
		synchronized (channel) {
			// Re-check the mute UNDER the monitor: the entry-gate isMuted read above is lock-free and can be stale
			// (a concurrent mute can have landed since). setMuted runs under this same monitor, so this read is
			// authoritative — without it a member muted between the gate and here would still acquire (or preempt
			// into) the single PTT floor and hold it, blocking the channel until the max-hold sweep (and, for a
			// WebRTC holder, idle auto-release can never reclaim it). Mirrors the holdsFloor re-check in onAudio.
			if (channel.isMuted(session.id())) {
				log.debug("refused the floor to a member muted concurrently with its request");
				return;
			}
			// tryAcquireFloor grants only if the floor is free AND (the queue is empty OR this caller is the reserved
			// head claiming its turn) — so a plain grab and a reserved-head claim are the same path.
			if (channel.tryAcquireFloor(session.id(), now)) {
				grantFloor(channel, session);
				log.debug("acquired the floor");
				return;
			}
			// The floor is busy — held, or reserved by/offered to another member.
			if (channel.isFloorQueueEnabled()) {
				// Raise-hand: join the FIFO queue and re-broadcast positions. An enqueue never creates a reservation
				// (the floor is still held/reserved by someone else); the head is offered the floor only when the
				// floor next frees — the release/leave paths and the idle-release sweep do that via reserveFloorHead.
				channel.enqueueFloor(session.id());
				broadcastFloorStatus(channel);
				log.debug("queued for the floor");
				return;
			}
			// Queue off: the pre-queue behaviour. Try to reclaim an idle holder; else the request is refused and
			// NOTHING is sent — the client already shows "busy" from the last FloorStatus (FloorDenied is retired).
			String currentHolderId = channel.floorHolder() instanceof Some(String holder) ? holder : null;
			if (preemptIfIdle(channel, session, currentHolderId, now)) {
				log.info(
						"preempted idle floor holder {} ({})",
						currentHolderId,
						currentHolderId != null && channel.member(currentHolderId) instanceof Some(ClientSession held) ? held.displayName() : "?");
				grantFloor(channel, session);
			} else {
				log.debug(
						"denied the floor (held by {} ({}))",
						currentHolderId,
						currentHolderId != null && channel.member(currentHolderId) instanceof Some(ClientSession held) ? held.displayName() : "?");
			}
		}
	}

	/// Idle auto-release: reclaim the floor for `requester` if the current holder has gone silent past the
	/// configured window. "Silent" = no relayed frame for that long, which the server reads from frame timing
	/// without decoding audio — so it works on encrypted channels too. A holder who keeps talking is never idle
	/// and is instead bounded by the max-hold cap in onAudio. Restricted to a relay holder: a WebRTC holder's
	/// media flows peer-to-peer and never reaches onAudio, so the server has no activity signal for it and must
	/// not preempt an active WebRTC speaker as "idle". The swap and re-stamp are atomic in [Channel].
	private boolean preemptIfIdle(Channel channel, ClientSession requester, String currentHolderId, Instant now) {
		return !floorIdleRelease.isZero()
				&& currentHolderId != null
				&& !currentHolderId.equals(requester.id())
				&& channel.member(currentHolderId) instanceof Some(ClientSession holder)
				&& holder.supportsAudioRelay()
				&& channel.preemptFloorIfIdle(currentHolderId, requester.id(), now, now.minus(floorIdleRelease));
	}

	/// Confirms the (already-acquired) floor to `session` with the imperative [ServerMessage.FloorGranted] "go live"
	/// trigger, then broadcasts the authoritative [ServerMessage.FloorStatus] to the whole channel (the
	/// acquire/activity marks were stamped atomically with the swap in [Channel]). The snapshot doubles as the
	/// notice to a just-preempted ex-holder that the floor is no longer theirs (its id is no longer the holder).
	///
	/// Trigger BEFORE snapshot here, which is the opposite of the FREE -> RESERVED transition ([#notifyReserved]) —
	/// deliberately, and not an inconsistency to tidy up. FloorGranted is what actually opens the microphone, so a
	/// snapshot arriving first would name us the holder while the mic is still closed: one message of "LIVE — release
	/// to stop" over a dead mic. FloorReserved carries no such state (only the countdown length), so there the
	/// snapshot must lead or the client contradicts the alert.
	private void grantFloor(Channel channel, ClientSession session) {
		broadcaster.toOne(session, new ServerMessage.FloorGranted());
		broadcastFloorStatus(channel);
	}

	/// The authoritative push-to-talk floor snapshot for `channel`: the live holder id (or `null` when the floor is
	/// free) plus the FIFO waiting queue. Clients derive ALL floor UI from it (holder, your-turn, in-line position,
	/// busy, free — see [ServerMessage.FloorStatus]). MUST be read under `synchronized(channel)` so the holder and
	/// the queue are a single consistent snapshot.
	private static ServerMessage.FloorStatus floorStatusOf(Channel channel) {
		return new ServerMessage.FloorStatus(
				channel.floorHolder() instanceof Some(String holder) ? holder : null,
				channel.floorQueue());
	}

	/// The authoritative owner-mute snapshot for `channel`: every currently-muted member id. MUST be read under
	/// `synchronized(channel)` so it can't tear against a concurrent mute flip or a leaver's scrub.
	///
	/// There is deliberately no `broadcastMuteStatus` mirror of [#broadcastFloorStatus]: every emitter either batches
	/// this into an existing fan-out (the mute paths, and the auto-unmute of a newly elected or newly appointed
	/// owner) or sends it to-one after a [ServerMessage.Joined], so a standalone-broadcast helper would have no
	/// caller.
	private static ServerMessage.MuteStatus muteStatusOf(Channel channel) {
		return new ServerMessage.MuteStatus(channel.mutedMembers());
	}

	/// Broadcasts the current [#floorStatusOf] snapshot to the whole channel. Call under `synchronized(channel)` so
	/// the snapshot is consistent and its fan-out is ordered with the floor transition that triggered it. For a
	/// FREE -> RESERVED transition this MUST go out BEFORE the to-one [ServerMessage.FloorReserved] — see
	/// [#notifyReserved].
	private void broadcastFloorStatus(Channel channel) {
		broadcaster.toAll(channel, floorStatusOf(channel));
	}

	/// The FREE -> RESERVED transition, step 1 of 2: offers the freed floor to the queue head and resolves that head
	/// to its session. Returns `null` when nothing was reserved — the floor is held, the queue is empty, a
	/// reservation is already running, or the head has since left. Callers invoke it EXACTLY ONCE per floor-free
	/// transition (release/decline/leave/idle/max-hold/expiry) so it never re-stamps a running reservation — see
	/// [Channel#reserveHead]. MUST be called under `synchronized(channel)`.
	///
	/// The session is resolved HERE rather than in [#notifyReserved] so the recipient is captured atomically with the
	/// reservation that named it, not after the snapshot fan-out that follows.
	private static ClientSession reserveFloorHead(Channel channel, Instant now) {
		String head = channel.reserveHead(now);
		return head != null && channel.member(head) instanceof Some(ClientSession reserved) ? reserved : null;
	}

	/// Step 2 of 2: sends the reserved head the imperative [ServerMessage.FloorReserved] "your turn — start the claim
	/// countdown" trigger. A no-op when nothing was reserved.
	///
	/// MUST be called AFTER the [ServerMessage.FloorStatus] that shows this member as `waiting[0]` of a FREE floor has
	/// been fanned out, because clients DERIVE reservedness from that snapshot — there is deliberately no `reserved`
	/// field on the wire (docs/CLIENT_PROTOCOL.md §3b). A trigger that overtakes its snapshot lands while the member
	/// still looks merely queued behind the ex-holder, and both clients then contradict the alert they just raised: the
	/// browser rendered "In line #1 of N — tap to leave" for that message, and the Java client's `t` resolved to
	/// IN_LINE and sent [ClientMessage.ReleaseFloor], DECLINING the turn the terminal bell had just announced.
	/// Contrast [#grantFloor], which is deliberately the other way round.
	private void notifyReserved(ClientSession reserved) {
		if (reserved != null) {
			broadcaster.toOne(reserved, new ServerMessage.FloorReserved(floorReservation.toSeconds()));
		}
	}

	/// The whole FREE -> RESERVED transition for the callers whose snapshot is a broadcast of its own: reserve the
	/// head, fan out [#broadcastFloorStatus], then trigger that head. Holding the three together is what keeps the
	/// order from drifting back; the callers whose snapshot instead RIDES a larger fan-out (leave, mute) have to spell
	/// the same sequence out, and must not collapse it back into this. Under `synchronized(channel)`.
	private void reserveAndBroadcast(Channel channel, Instant now) {
		ClientSession reserved = reserveFloorHead(channel, now);
		broadcastFloorStatus(channel);
		notifyReserved(reserved);
	}

	private void handleReleaseFloor(ClientSession session) {
		if (!(requireChannel(session) instanceof Some(Channel channel)) || channel.mode() == ChannelMode.FULL_DUPLEX) {
			return;
		}
		Instant now = clock.instant();
		synchronized (channel) {
			if (channel.releaseFloor(session.id())) {
				// The live holder gave up the floor: re-broadcast, then offer it to the queue head (if any).
				reserveAndBroadcast(channel, now);
				log.debug("released the floor");
				return;
			}
			// Not the holder — a waiter leaving the line, or the reserved head declining its turn. dequeueFloor
			// resets the reservation clock IFF this caller was the reserved head (so the next head gets a fresh
			// window; a mid-queue leave keeps the running head's window). The reserve is then unconditional but
			// IDEMPOTENT: it re-reserves + triggers only when the floor is free with an unstamped head — i.e. only
			// when the head genuinely changed — so a mid-queue leave is a reserve no-op that never re-stamps.
			if (channel.dequeueFloor(session.id())) {
				reserveAndBroadcast(channel, now);
				log.debug("left the floor queue");
			}
		}
	}

	/// Scheduled safety net for the push-to-talk floor timers. Three per-channel steps run under the channel
	/// monitor (so none can race a concurrent grant, and each transition is atomic with its broadcast):
	///
	/// 1. **Max-hold** — force-release any holder that has held past the cap. onAudio enforces this lazily on the
	///    holder's next frame; the sweep also reclaims a holder that STOPPED sending frames without releasing (the
	///    case onAudio can't see). Keys off hold time only (never audio content), so it bounds **any** holder,
	///    including a WebRTC member whose media never reaches the server.
	/// 2. **Idle-release (queue advance)** — when the queue is on and non-empty, free a relay holder gone silent
	///    past the idle window so the floor can be offered to the queue head. Relay-only (WebRTC has no activity
	///    signal). Skipped if max-hold already freed the floor this pass.
	/// 3. **Reservation-expiry** — drop the reserved head that did not claim within the window and offer the floor
	///    to the next in line.
	///
	/// Each step is individually guarded so the sweep still does useful work when max-hold is disabled (0) —
	/// reservation-expiry always applies while the queue is on. Steps 1–2 run first on purpose: a fresh reservation
	/// they create is stamped at `now`, which step 3 then correctly skips (its window has not elapsed).
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
	void releaseExpiredFloors() {
		Instant now = clock.instant();
		for (Channel channel : channelRegistry.channels()) {
			// Cheap lock-free skip: a provably-idle channel (no holder, no running reservation) has zero sweep work,
			// so avoid opening a logging scope and taking its monitor for it. Every step below still re-checks under
			// the monitor, so a channel that turns active right after this read is simply handled on the next tick.
			if (channel.hasNoSweepWork()) {
				continue;
			}
			// Tag every line this per-channel pass logs with channel=… in the MDC, the way the per-message handler
			// lines are (the channel is logging context, not identity — see RequestContext). The sweep runs on a
			// scheduler thread with no acting client, so identity stays "system"; the AFFECTED member is named as
			// the subject in each message.
			try (RequestContext.Scope _ = RequestContext.channelScope(channel)) {
				synchronized (channel) {
					boolean freed = false;
					// 1. Max-hold cap (bounds any holder, incl. WebRTC).
					if (!floorMaxHold.isZero()) {
						String holder = channel.releaseIfExpired(now.minus(floorMaxHold));
						if (holder != null) {
							freed = true;
							log.info("{} ({}) reached the max floor-hold time; floor released by sweep",
									holder,
									channel.member(holder) instanceof Some(ClientSession held) ? held.displayName() : "?");
						}
					}
					// 2. Idle-release to advance the queue: only when nothing was freed above, the queue is on and
					// non-empty, and the current holder is a relay member (WebRTC gives the server no activity signal,
					// so an active WebRTC speaker must not be reclaimed as "idle").
					if (!freed
							&& !floorIdleRelease.isZero()
							&& channel.isFloorQueueEnabled()
							&& !channel.isFloorQueueEmpty()
							&& channel.floorHolder() instanceof Some(String holderId)
							&& channel.member(holderId) instanceof Some(ClientSession holder)
							&& holder.supportsAudioRelay()) {
						String released = channel.releaseIfIdle(now.minus(floorIdleRelease));
						if (released != null) {
							freed = true;
							log.info("{} ({}) idle past the release window; floor offered to the queue",
									released, holder.displayName());
						}
					}
					// 3. Whatever freed the floor above, re-broadcast the snapshot and offer the floor to the queue head.
					if (freed) {
						reserveAndBroadcast(channel, now);
					}
					// 4. Reservation-expiry: a reserved head that missed its claim window is dropped, and the floor is
					// offered to the next in line — if anyone was behind it. Runs last so a reservation freshly
					// stamped at `now` by steps 1–3 is not itself treated as expired. dequeueFloor comes FIRST because
					// it is the one call here that changes the snapshot (it drops the lapsed head), so the broadcast
					// inside reserveAndBroadcast must follow it.
					String missed = channel.expiredReservationHead(now.minus(floorReservation));
					if (missed != null) {
                        channel.dequeueFloor(missed);
						reserveAndBroadcast(channel, now);
						// The reserve stamps a fresh reservation on the next head IFF one was behind the dropped
						// member: a present reservedHolder() means the floor was handed on; an absent one means the
						// queue emptied and the floor is simply free. The {} tail reflects which.
						log.info(
								"{} ({}) missed its floor reservation; {}",
								missed,
								channel.member(missed) instanceof Some(ClientSession m) ? m.displayName() : "?",
								channel.reservedHolder() instanceof Some<String>
										? "the floor was offered to the next in line"
										: "no one else was waiting, so the floor is now free"
						);
					}
				}
			}
		}
	}

	/// Relays a raw audio frame to the other relay-capable members of the sender's channel. The frame
	/// is dropped when the sender is not currently authorized to talk (push-to-talk floor not held), when
	/// the owner has muted the sender, or when it violates the configured size bounds.
	///
	/// Takes the frame as a [ByteBuffer] rather than a `byte[]` so the single copy of it happens HERE, in
	/// [#prefixedFrame], after every gate — a frame dropped as oversized, muted, floorless or rate-limited is never
	/// copied at all, and the array that is finally built is the one shared with every recipient, so there is exactly
	/// one copy per relayed frame rather than one in the transport plus one here. The buffer is consumed within this
	/// call and never retained — deliberately, though not because it is doomed: Tomcat hands the handler a PRIVATE
	/// per-message buffer (`WsFrameBase.processDataBinary` allocates one, copies into it, and clears its own reusable
	/// buffer before dispatch), so keeping it would in fact work there. What is missing is a promise —
	/// `MessageHandler.Whole#onMessage` says nothing about the argument's lifetime — so retaining it would rest on a
	/// container detail. It is moot regardless: the stream index has to be contiguous with the body in ONE payload
	/// (`sendMessage` takes a single `BinaryMessage`; neither Spring nor the Jakarta API offers a gather write), so a
	/// fresh buffer and a copy are required whatever the carrier type is.
	public void onAudio(ClientSession session, ByteBuffer audio) {
		// Read channelName ONCE into a local: the null-check and the registry lookup below both need it, and a
		// concurrent onClose/leave (leftChannel() → null) landing between two separate reads would turn the second
		// into find(null) → ConcurrentHashMap.get(null) → NPE, thrown on the per-frame audio hot path with no
		// try/catch above it (handleBinaryMessage doesn't catch). One read also keeps the whole gate consistent.
		String channelName = session.channelName();
		if (!session.supportsAudioRelay()
				|| !audio.hasRemaining()
				|| audio.remaining() > properties.maxAudioFrameBytes()
				|| channelName == null
				|| !(channelRegistry.find(channelName) instanceof Some(Channel channel))
				|| !channel.holdsFloor(session.id())
				// Owner-enforced mute: drop the frame server-side so a muted member (PTT holder or any
				// full-duplex talker) can't route audio around a client that ignores its own mute. This is a
				// lock-free volatile-set read on the hot path, mirroring the holdsFloor gate above.
				|| channel.isMuted(session.id())) {
			return;
		}
		// Push-to-talk hold limits (no-op in full-duplex, which has no single holder). The sender is the floor
		// holder here (the gate above checked holdsFloor). Done BEFORE the rate-limit gate so a holder's activity
		// is recorded from every frame it SENDS — even ones the flood guard later drops — and the max-hold cap
		// is evaluated on each received frame. Both key off frame timing, never audio content, so they hold on
		// encrypted channels too.
		if (channel.mode() != ChannelMode.FULL_DUPLEX) {
			Instant now = clock.instant();
			// Re-check the holder + expiry AND release-and-notify under the floor monitor, so the whole
			// transition is atomic with its broadcast (no concurrent grant can slip in and get a stray FloorIdle)
			// and the expiry is read against a holder that can't change under us. markFloorActivity / the acquire
			// / the other release paths all take this same monitor, so transitions are totally ordered per channel.
			synchronized (channel) {
				// Re-validate the floor UNDER the monitor: the entry gate's holdsFloor read (above) is lock-free and
				// may be stale (a concurrent leave/preempt can have revoked the floor since). If it's no longer ours,
				// drop the frame so a revoked holder's audio is never fanned out (PTT single-talker invariant).
				if (!channel.holdsFloor(session.id())) {
					return;
				}
				if (!floorMaxHold.isZero()
						&& Duration.between(channel.floorAcquiredAt(), now).compareTo(floorMaxHold) >= 0
						&& channel.releaseFloor(session.id())) {
					// Talk-time limit reached: free the floor, re-broadcast the snapshot to the whole channel (incl. the
					// (ex-)speaker so its client stops transmitting and resets), and offer the floor to the queue head
					// (if any); the speaker must re-request to continue.
					reserveAndBroadcast(channel, now);
					// onAudio is intentionally not session-scoped (no per-frame MDC churn), so the holder's id + name
					// are logged inline; the channel is mirrored into the MDC (channelScope) so this line carries
					// channel=… like the handler lines rather than repeating it in the message.
					try (RequestContext.Scope _ = RequestContext.channelScope(channel)) {
						log.info("{} ({}) reached the max floor-hold time; floor released",
								session.id(), session.displayName());
					}
					return;
				}
				channel.markFloorActivity(now);
			}
		}
		// A late frame from a session that left/closed since the entry gate must not resurrect a rate-limiter
		// bucket after onClose's forget(); re-check liveness before computeIfAbsent. (For PTT the holdsFloor
		// re-check above already covers it; this also covers full-duplex.)
		if (session.channelName() == null
				// Per-sender flood guard: drop frames from a sender exceeding the configured rate BEFORE fan-out, so a
				// flooder can't amplify cost across the channel (N recipients) or force excess decode work. This counts
				// frames without inspecting them, so it works on end-to-end-encrypted channels too (see SessionRateLimiter).
				|| !audioRateLimiter.tryAcquire(session.id())) {
			return;
		}

		// Tag the fan-out with the sender's per-channel stream index so receivers can demultiplex talkers; every
		// client decodes per sender and mixes locally (see docs/CLIENT_PROTOCOL.md §5). If the sender has no index
		// — its slot was just freed by a concurrent leave racing this in-flight frame — drop the frame rather than
		// stamp a bogus index and misroute it into another member's decode lane.
		if (channel.streamIndexOf(session.id()) instanceof SomeInt(int index)) {
			byte[] prefixed = prefixedFrame(index, audio);
			channel.forEachOther(session.id(), other -> {
				if (other.supportsAudioRelay()) {
					try {
						other.sendAudio(prefixed);
					} catch (RuntimeException e) {
						log.debug("Audio relay to {} ({}) failed: {}", other.id(), other.displayName(), e.getMessage());
					}
				}
			});
		}
		// The sender's stream index was freed (it is leaving) — drop this straggler frame.
	}

	/// Materialises a relayed audio frame as `[sid][body]`: the 1-byte stream index followed by the body verbatim —
	/// the server never inspects the body (plaintext `[tag][payload]` or the E2EE `[scheme][IV][ct]` envelope), and
	/// the index sits outside any encryption.
	///
	/// This is the ONE copy an inbound frame costs. The destination is allocated a byte longer than the body and the
	/// bulk `get` writes straight past the reserved slot, so there is no second array and no `System.arraycopy` —
	/// the transport used to materialise an exactly-sized array which this then re-copied. Doing it here rather than
	/// in the handler is also what makes a DROPPED frame free: this runs only once every gate has passed.
	private static byte[] prefixedFrame(int streamIndex, ByteBuffer body) {
		byte[] out = new byte[body.remaining() + 1];
		out[0] = (byte) streamIndex;
		body.get(out, 1, out.length - 1);
		return out;
	}

	/// `closeReason` is a short human description of why the socket closed (from the WebSocket close code +
	/// reason — e.g. "normal close", "abnormal close — no close frame …", "policy violation — send backlog"),
	/// supplied by the transport handler so the disconnect line explains the cause.
	public void onClose(ClientSession session, String closeReason) {
		// Scope the whole teardown so the leave + disconnect lines carry the session id AND the display name via
		// the MDC (this is why a disconnect previously logged no name — onClose wasn't bound to the identity).
		try (RequestContext.Scope _ = RequestContext.scope(session)) {
			handleLeave(session);
			// A session waiting to be admitted somewhere is NOT a member there, so handleLeave (which reconciles by
			// channelName) cannot see it — scrub it explicitly, or its entry would outlive the socket and hold a slot
			// on the owner's list forever. Passing null as the channel to keep means "keep none".
			withdrawPendingElsewhere(session, null);
			audioRateLimiter.forget(session.id());
			controlRateLimiter.forget(session.id());
			log.info("disconnected ({})", closeReason);
		}
	}

	private void relaySignal(ClientSession session, String targetId, ServerMessage message) {
		if (!(requireChannel(session) instanceof Some(Channel channel))) {
			return;
		}
		if (channel.member(targetId) instanceof Some(ClientSession target)) {
			// Both ends must be on the signaling transport. Dropped SILENTLY, mirroring how a signaling session's
			// audio frames are dropped on arrival rather than answered with an error — a client that sends these on
			// the wrong transport is confused, not owed a reply, and a per-ICE-candidate error would be a flood.
			//
			// Why it matters on the RECEIVING side: handed an offer, a relay client would attach its microphone to a
			// peer connection, and peer-to-peer media takes neither the floor/owner-mute enforcement below nor the
			// passphrase E2EE — both of which only exist on the relay frame path. See ClientSession#supportsSignaling.
			if (!session.supportsSignaling() || !target.supportsSignaling()) {
				log.debug("Dropped {} between transports ({} -> {})",
						message.getClass().getSimpleName(), session.transport(), target.transport());
				return;
			}
			broadcaster.toOne(target, message);
		} else {
			sendError(session, ErrorCode.UNKNOWN_TARGET,
					"No member '" + targetId + "' in this channel");
		}
	}

	private Option<Channel> requireChannel(ClientSession session) {
		String name = session.channelName();
		if (name == null) {
			sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			return None.instance();
		}
		return channelRegistry.find(name);   // already an Option<Channel> — no ternary, no null
	}

	/// The caller's channel IF the caller currently owns it, else `null` after sending the right error: the shared
	/// owner-gate for the inline owner-only handlers (mode change, mute) — routed here rather than through the
	/// registry because they act on channel state under the channel monitor, not under the bin lock. Sends
	/// `NOT_IN_CHANNEL` (via [#requireChannel]) when not in a channel and `NOT_OWNER` when in one but not its owner;
	/// `action` completes the "Only the channel owner can …" message. The owner check reads the live `ownerId`, so a
	/// concurrent ownership transfer just means an already-authorized action may still land — benign and reversible,
	/// not a privilege leak. The sentinel-owned `global` room is never equal to a session id, so it fails here.
	private Option<Channel> requireOwnedChannel(ClientSession session, String action) {
		if (!(requireChannel(session) instanceof Some(Channel channel))) {
			return None.instance();   // requireChannel already sent NOT_IN_CHANNEL
		}
		if (!session.id().equals(channel.ownerId())) {
			sendError(session, ErrorCode.NOT_OWNER, "Only the channel owner can " + action);
			return None.instance();
		}
		return Option.of(channel);
	}

	/// Changes the current channel's mode, but only for its owner. Clears the floor and broadcasts the
	/// new mode to every member so their controls update; a non-owner gets a `NOT_OWNER` error.
	private void handleChangeMode(ClientSession session, ChannelMode mode) {
		if (!(requireOwnedChannel(session, "change the mode") instanceof Some(Channel channel))) {
			return;
		}
		if (mode == ChannelMode.GLOBAL_PTT && !channel.name().equals(GLOBAL_CHANNEL)) {
			sendError(session, ErrorCode.INVALID_MODE, "Global PTT applies only to the 'global' channel");
			return;
		}
		if (channel.mode() == mode) {
			return;
		}
		// Mode switch + floor reset + the broadcast happen under the floor monitor, so the FloorStatus can't race a
		// concurrent grant and the mode/floor everyone sees is consistent.
		synchronized (channel) {
			channel.setMode(mode);
			channel.clearFloor();   // resets ALL floor state: holder, waiting queue AND any running reservation
			// Broadcast to everyone (incl. the owner): the new mode and a fresh (empty) floor snapshot, so any
			// 'talking'/queued indicator is superseded and a fresh push-to-talk floor is available.
			broadcaster.toAll(channel, new ServerMessage.ModeChanged(mode), floorStatusOf(channel));
		}
		log.info("changed mode to {}", mode);
	}

	/// Rotates the current channel's end-to-end-encryption passphrase, but only for its owner. The server never
	/// learns the passphrase — the request carries only the key-check derived from the new one — so all it does is
	/// record the new key-check and broadcast a `PassphraseChanged` to every member (including the owner) so each
	/// client re-derives its key from the new passphrase, obtained out-of-band exactly as the original was.
	///
	/// A rotation can only ever replace one key with another. Clearing it (`keyCheck: null`) used to turn the
	/// channel plaintext and is now refused with `PASSPHRASE_REQUIRED`, since there are no plaintext channels.
	/// A non-owner gets `NOT_OWNER`; a request before joining gets `NOT_IN_CHANNEL`. The server-managed `global`
	/// room has the sentinel owner, so a rotation there is refused as `NOT_OWNER` — it stays the unencrypted
	/// broadcast room, and never reaches the clearing rule at all.
	///
	/// Concurrency: the key-check write happens inside the registry's channel-name `computeIfPresent` span (see
	/// [ChannelRegistry#changePassphrase]), serializing it with every join's key-check validation. The broadcast
	/// then runs **under the channel monitor reading the channel's LIVE key-check** — mirroring [#handleLeave]'s
	/// `OwnerChanged` discipline — over the mutated channel the registry returns (not a fresh `find()`). Reading
	/// the live value under the monitor (rather than fanning out the request's captured key-check lock-free) makes
	/// two rotations that straddle an ownership change CONVERGE: a broadcast delayed past a later rotation carries
	/// the channel's current key-check, so no member is left comparing its no-plaintext gate against a stale
	/// key-check the channel no longer uses. The audio relay path needs no change — it forwards frames opaquely,
	/// so a brief transition where some members hold the new key and others the old just drops a few GCM-failing
	/// frames, exactly as a channel switch does.
	private void handleChangePassphrase(ClientSession session, String keyCheck, String wrappedKey) {
		String channelName = session.channelName();   // snapshot once: a concurrent leave nulling it would make changePassphrase(null,…) NPE
		if (channelName == null) {
			sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			return;
		}
		switch (channelRegistry.changePassphrase(channelName, session.id(), keyCheck)) {
			case ChannelRegistry.RekeyResult.NotFound _ -> sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			case ChannelRegistry.RekeyResult.NotOwner _ ->
					sendError(session, ErrorCode.NOT_OWNER, "Only the channel owner can change the passphrase");
			case ChannelRegistry.RekeyResult.EncryptionRequired _ -> sendError(session, ErrorCode.PASSPHRASE_REQUIRED,
					"Encryption can't be turned off — rotate to a new passphrase instead.");
			case ChannelRegistry.RekeyResult.Ok(Channel channel) -> {
				synchronized (channel) {
					// Broadcast the LIVE key-check (convergence) plus this request's wrappedKey relayed verbatim —
					// the server never inspects or stores it; members that hold the old key decrypt it to adopt the
					// new passphrase automatically (and re-verify against the live key-check, so a stale/tampered
					// blob just falls back to a manual re-entry).
					broadcaster.toAll(channel, new ServerMessage.PassphraseChanged(channel.keyCheck(), wrappedKey));
				}
				// The fact of a rotation only — never the key-check token or the wrapped blob. There is no longer an
				// encrypted/plaintext status to report: reaching here means the channel is encrypted, and a clear
				// was refused above.
				log.info("rotated the channel passphrase");
			}
		}
	}

	/// Hands channel ownership to another current member, but only on the current owner's request. Broadcasts the
	/// same `OwnerChanged` that a departure-triggered auto-election sends, so every client re-renders owner-only
	/// controls. A non-owner gets `NOT_OWNER`; a target that is not a member of the channel gets `UNKNOWN_TARGET`;
	/// a request before joining gets `NOT_IN_CHANNEL`. The global room's sentinel owner means a transfer there is
	/// refused as `NOT_OWNER`.
	///
	/// Concurrency: the owner check, the target-membership check and the owner write are one atomic step inside
	/// the registry's channel-name `computeIfPresent` span (see [ChannelRegistry#transferOwnership]), so the
	/// transfer can't race the auto-election a concurrent `leave` performs, nor hand ownership to a member that is
	/// concurrently leaving. The broadcast then runs **under the channel monitor reading the channel's LIVE
	/// owner** — the same discipline [#handleLeave] uses — over the mutated channel the registry returns (not a
	/// fresh `find()`). Reading the live owner under the monitor (rather than fanning out the request's captured
	/// `newOwnerId` lock-free) makes a transfer that races a leave-election or a second transfer CONVERGE: a
	/// broadcast delayed past a later owner change carries the current owner, so no survivor is left believing a
	/// since-departed member still owns the channel (a permanently stuck ghost owner with no corrector).
	private void handleTransferOwnership(ClientSession session, String newOwnerId) {
		String channelName = session.channelName();
		if (channelName == null) {
			sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			return;
		}
		switch (channelRegistry.transferOwnership(channelName, session.id(), newOwnerId)) {
			case ChannelRegistry.TransferResult.NotFound _ -> sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			case ChannelRegistry.TransferResult.NotOwner _ ->
					sendError(session, ErrorCode.NOT_OWNER, "Only the channel owner can transfer ownership");
			case ChannelRegistry.TransferResult.NotAMember _ -> sendError(session, ErrorCode.UNKNOWN_TARGET,
					"No member '" + newOwnerId + "' in this channel");
			case ChannelRegistry.TransferResult.Ok(Channel channel) -> {
				synchronized (channel) {
					// Fan out OwnerChanged + (if the new owner had been muted) its unmute in ONE pass.
					List<ServerMessage> events = new ArrayList<>();
					events.add(new ServerMessage.OwnerChanged(channel.ownerId()));
					// The new owner is never muted: if the previous owner had muted this member before handing it
					// ownership, unmute it now — otherwise it would be a muted owner with no way to unmute itself.
					if (unmuteOwner(channel)) {
						events.add(muteStatusOf(channel));
					}
					broadcaster.toAll(channel, events.toArray(ServerMessage[]::new));
				}
				log.info(
						"transferred ownership to {} ({})",
						newOwnerId,
						channel.member(newOwnerId) instanceof Some(ClientSession newOwner) ? newOwner.displayName() : "?");
			}
		}
	}

	/// Mutes or unmutes one member's relay audio, on the owner's request only. This is server-ENFORCED: while a
	/// member is muted, [#onAudio] drops its frames, so a client that ignores its own mute still can't be heard —
	/// the trust boundary is the relay, not the sender. A non-owner gets `NOT_OWNER`; a target that isn't a current
	/// member, or the owner itself (which can never be muted), gets `UNKNOWN_TARGET`.
	///
	/// Concurrency: the membership re-check, the mute flip, the floor release (when the muted member was the one
	/// talking) and the `MuteStatus` broadcast all run under the channel monitor — the same monitor every floor
	/// transition and the mode/owner/passphrase broadcasts take — so the mute state, a freed floor and the notice
	/// everyone sees stay consistent, and a member muted mid-transmission is dropped from the floor and told in one
	/// atomic step. Enforcement engages within one frame: for a PTT floor holder the floor release above drops its
	/// next frame at [#onAudio]'s under-monitor `holdsFloor` re-check, and [#handleRequestFloor] re-checks the mute
	/// under the monitor so a just-muted member can't reacquire the floor. The [#onAudio] gate itself is a lock-free
	/// hot-path read, so in FULL_DUPLEX a single frame already in flight when the mute lands may still be relayed
	/// (bounded, real-time) — the mute is authoritative from the following frame. Enforcement is relay-only: WebRTC
	/// media is peer-to-peer, so a WebRTC talker's mute is best-effort at its own client (it still sees itself in the
	/// `MuteStatus` snapshot and stops).
	///
	/// The owner check reads the live `ownerId`; if a concurrent ownership transfer demotes the requester in the
	/// window between the check and the mutation, its (already-authorized) mute may still land — harmless and
	/// reversible moderation by the new owner, not a privilege leak (the requester WAS owner when it acted).
	private void handleMuteMember(ClientSession session, String memberId, boolean muted) {
		if (!(requireOwnedChannel(session, "mute members") instanceof Some(Channel channel))) {
			return;
		}
		// The owner can't mute itself, and only a current member can be muted. This is the friendly fast-path error;
		// the authoritative membership test is re-done under the monitor below (a member can leave in between).
		if (memberId == null || memberId.equals(channel.ownerId()) || !(channel.member(memberId) instanceof Some<ClientSession>)) {
			sendError(session, ErrorCode.UNKNOWN_TARGET, "No member '" + memberId + "' to mute in this channel");
			return;
		}
		synchronized (channel) {
			// Re-check membership under the monitor: leave scrubs the mute set under this same monitor after
			// removing the member, so a target that left since the fast-path check is skipped here rather than
			// leaving a ghost mute id that would outlive it (see Channel.remove). A silent no-op is right — the
			// member is already gone (it got a MemberLeft), so there is nothing to mute and no error to report.
			if (!(channel.member(memberId) instanceof Some<ClientSession>)) {
				return;
			}
			if (!channel.setMuted(memberId, muted)) {
				return;   // already in that state: nothing to free, nothing to broadcast
			}
			// Fan out the fresh mute snapshot, plus the fresh floor snapshot when muting took the member off the floor
			// (holder released, or a waiter / reserved head dequeued) — in ONE pass. Mute FIRST, floor second: that is
			// the causal order, and clients rely on it (a muted holder stops its own mic on the mute snapshot, so the
			// floor snapshot that follows finds it already stopped rather than reporting a surprise release). The
			// FloorReserved trigger for a head that inherited the freed floor follows the whole pass rather than riding
			// it: it is a to-one send, and it must reach the new head only after the snapshot it derives its turn from
			// (see notifyReserved).
			List<ServerMessage> events = new ArrayList<>();
			events.add(muteStatusOf(channel));
			ClientSession reserved = null;
			if (detachFromFloorIfMuted(channel, memberId, muted)) {
				reserved = reserveFloorHead(channel, clock.instant());
				events.add(floorStatusOf(channel));
			}
			broadcaster.toAll(channel, events.toArray(ServerMessage[]::new));
			notifyReserved(reserved);
		}
		log.info(
				"{} member {} ({})",
				muted ? "muted" : "unmuted",
				memberId,
				channel.member(memberId) instanceof Some(ClientSession m) ? m.displayName() : "?");
	}

	/// Mutes or unmutes EVERY other member of the channel at once, on the owner's request. The owner is never
	/// muted. Same server enforcement as [#handleMuteMember]; a non-owner gets `NOT_OWNER`. Emits ONE
	/// `MuteStatus` snapshot for the whole change, whatever its size, and — if a muted member was on the floor —
	/// frees the floor too (via [#detachFromFloorIfMuted]) with the fresh floor snapshot riding the same fan-out.
	private void handleMuteAll(ClientSession session, boolean muted) {
		if (!(requireOwnedChannel(session, "mute members") instanceof Some(Channel channel))) {
			return;
		}
		synchronized (channel) {
			// setMutedForAllExcept flips the whole roster under the monitor and returns only the ids that changed. Those
			// ids are needed to detach each newly muted member from the floor, and to tell an idempotent no-op from a
			// real change — but NOT to build the fan-out, which is one snapshot however many members flipped. That is
			// what keeps a channel-wide mute O(N) frames rather than O(N²): a 255-member channel used to hand every
			// recipient 254 MemberMuted frames per click, a quarter of the bounded control queue whose overflow
			// disconnects a client.
			List<String> changed = channel.setMutedForAllExcept(channel.ownerId(), muted);
			// Guarded on `changed`, NOT on the event list: the list always holds the snapshot, so testing it would fan
			// out on every no-op click — exactly the toggle-spam this is meant to bound.
			if (!changed.isEmpty()) {
				boolean floorChanged = false;
				for (String memberId : changed) {
					floorChanged |= detachFromFloorIfMuted(channel, memberId, muted);
				}
				List<ServerMessage> events = new ArrayList<>();
				events.add(muteStatusOf(channel));
				// If any muted member was on the floor (holder or waiting), advance/free it and append the fresh
				// snapshot so it rides the SAME fan-out. The to-one FloorReserved for a head that inherited the floor
				// waits until after that pass — it must not overtake the snapshot its turn is derived from (see
				// notifyReserved).
				ClientSession reserved = null;
				if (floorChanged) {
					reserved = reserveFloorHead(channel, clock.instant());
					events.add(floorStatusOf(channel));
				}
				broadcaster.toAll(channel, events.toArray(ServerMessage[]::new));
				notifyReserved(reserved);
			}
		}
		log.info("{} all members", muted ? "muted" : "unmuted");
	}

	/// When a member is being MUTED, takes it off the floor entirely: released if it was the live holder, AND
	/// dequeued from the waiting line (which, if it was the reserved head, ends its claim window so the next head
	/// gets a fresh one — see [Channel#dequeueFloor]). A no-op when UNMUTING (unmuting never touches the floor).
	/// Returns whether the floor state changed, so the caller can offer the freed/advanced floor to the queue head.
	/// The caller broadcasts the `MuteStatus` snapshot itself, so a channel-wide mute batches ONE snapshot plus at
	/// most one floor snapshot into a single fan-out rather than a message per member. MUST be called while holding
	/// the channel monitor.
	private static boolean detachFromFloorIfMuted(Channel channel, String memberId, boolean muted) {
		if (!muted) {
			return false;
		}
		// A member is either the live holder or a waiter, never both, so at most one of these is true; `|` (not `||`)
		// so both are attempted regardless.
		return channel.releaseFloor(memberId) | channel.dequeueFloor(memberId);
	}

	/// Restores the "the channel owner is never muted" invariant after an ownership change — a deliberate
	/// [#handleTransferOwnership] or the leave-triggered auto-election in [#handleLeave]. If the new owner had been
	/// muted by the previous owner it would otherwise be **permanently locked out**: [#onAudio] drops its audio,
	/// [#handleRequestFloor] refuses it the floor, and it can't unmute ITSELF ([#handleMuteMember] rejects the owner
	/// as a mute target) with no one else owner to do it. Returns true if the new owner really WAS muted — so the
	/// caller batches a fresh `MuteStatus` into its own fan-out (no spurious snapshot otherwise). MUST be
	/// called under the channel monitor, after the ownership write. The global room's sentinel owner is never in the
	/// mute set, so this returns false there.
	private static boolean unmuteOwner(Channel channel) {
		return channel.setMuted(channel.ownerId(), false);
	}

	/// Locks or unlocks the channel to NEW members, on the owner's request only. Server-enforced in the atomic join
	/// (see [ChannelRegistry#joinOrCreate]); existing members are never affected. Routed through
	/// [ChannelRegistry#setLocked] so the owner check and the flag write share the bin lock a join validates its
	/// key-check under — a non-owner gets `NOT_OWNER`, and the sentinel-owned `global` room can't be locked. On
	/// success the `ChannelLocked` broadcast runs under the channel monitor over the mutated instance the registry
	/// returns (not a fresh `find()`, mirroring [#handleChangePassphrase]'s same-object discipline) reading the
	/// channel's LIVE lock state, so two back-to-back toggles converge — a delayed broadcast carries the current
	/// value rather than leaving a member gating against a stale one.
	private void handleSetLocked(ClientSession session, boolean locked) {
		String channelName = session.channelName();   // snapshot once: a concurrent leave nulling it would make setLocked(null,…) NPE
		if (channelName == null) {
			sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			return;
		}
		switch (channelRegistry.setLocked(channelName, session.id(), locked)) {
			case ChannelRegistry.LockResult.NotFound _ -> sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			case ChannelRegistry.LockResult.NotOwner _ ->
					sendError(session, ErrorCode.NOT_OWNER, "Only the channel owner can lock the channel");
			case ChannelRegistry.LockResult.Ok(Channel channel, List<ClientSession> cleared) -> {
				synchronized (channel) {
					broadcaster.toAll(channel, new ServerMessage.ChannelLocked(channel.isLocked()));
				}
				// Unlocking admitted everyone who was waiting: each completes the join with its own re-sent Join, the
				// same way an individually approved newcomer does, so there is one admission path rather than two.
				for (ClientSession waiting : cleared) {
					waiting.pendingCleared();   // their request is gone from the list, so the marker must go too
					broadcaster.toOne(waiting, new ServerMessage.JoinApproved(channel.name()));
				}
				if (!cleared.isEmpty()) {
					// The list is now empty, so refresh the owner's view of it.
					broadcaster.toOne(session, new ServerMessage.JoinRequests(channel.joinRequestInfos()));
					log.info("channel unlocked; admitted {} waiting newcomer(s)", cleared.size());
				} else {
					log.info("channel {}", locked ? "locked" : "unlocked");
				}
			}
		}
	}

	/// Turns the owner-toggleable push-to-talk floor queue on or off for the channel, on the OWNER's request only —
	/// server-enforced, never trusted to the client (like [#handleSetLocked]). A non-owner gets `NOT_OWNER` (via
	/// [#requireOwnedChannel]), so the sentinel-owned `global` room refuses it and stays unbounded/queue-off; a
	/// request before joining gets `NOT_IN_CHANNEL`. Disabling CLEARS any waiting queue and running reservation
	/// (there is nowhere to wait) — done inside [Channel#setFloorQueueEnabled] — so the following [ServerMessage.FloorStatus]
	/// shows an empty queue and dropped waiters re-derive "free/busy" from it. The flip, the clear and both
	/// broadcasts run under the channel monitor (mirroring the mode/lock/mute discipline) so they are one atomic,
	/// consistently-ordered transition; a [ServerMessage.FloorQueueChanged] renders the toggle and the
	/// [ServerMessage.FloorStatus] renders the (possibly cleared) queue.
	private void handleSetFloorQueue(ClientSession session, boolean enabled) {
		if (!(requireOwnedChannel(session, "change the floor queue") instanceof Some(Channel channel))) {
			return;
		}
		// Full-duplex has no talk floor, so a floor queue is meaningless there — refuse enabling it (mirrors
		// handleChangeMode's mode-applicability guard). Disabling is harmless, but this channel can never have a
		// non-empty queue anyway, so reject any toggle uniformly with the closest applicable code.
		if (channel.mode() == ChannelMode.FULL_DUPLEX) {
			sendError(session, ErrorCode.INVALID_MODE, "The floor queue applies only to push-to-talk channels");
			return;
		}
		synchronized (channel) {
			// The snapshot rides along ONLY when the toggle actually moved the floor — disabling with people queued
			// drops them, and they have to see that. Enabling changes who MAY wait, not who is waiting, and disabling
			// an empty queue changes nothing; sending a snapshot then fans out a message that repeats the floor
			// verbatim, and every client narrates it ("Floor is free") as though something had happened.
			if (channel.setFloorQueueEnabled(enabled)) {
				broadcaster.toAll(channel, new ServerMessage.FloorQueueChanged(enabled), floorStatusOf(channel));
			} else {
				broadcaster.toAll(channel, new ServerMessage.FloorQueueChanged(enabled));
			}
		}
		log.info("floor queue {}", enabled ? "enabled" : "disabled");
	}

	/// Arms or disarms the owner's standing "mute every arrival" rule — the counterpart to [#handleMuteAll], which
	/// is a one-shot over the members present. Server-enforced at the add ([Channel#add]), never trusted to the
	/// client. A non-owner gets `NOT_OWNER` (via [#requireOwnedChannel], which also covers the sentinel-owned
	/// `global` room), and sending it before joining gets `NOT_IN_CHANNEL`.
	///
	/// Unlike [#handleSetFloorQueue] this touches NO current state and so needs no accompanying snapshot: it changes
	/// nobody's mute (see [Channel#setMuteNewMembers]), frees no floor, and applies to nothing that has already
	/// happened. It also has no mode restriction — a full-duplex channel has no floor but mute matters there most,
	/// since every mic is open.
	private void handleSetMuteNewMembers(ClientSession session, boolean enabled) {
		if (!(requireOwnedChannel(session, "change who is muted on entry") instanceof Some(Channel channel))) {
			return;
		}
		synchronized (channel) {
			channel.setMuteNewMembers(enabled);
			broadcaster.toAll(channel, new ServerMessage.MuteNewMembersChanged(enabled));
		}
		log.info("mute-new-members {}", enabled ? "enabled" : "disabled");
	}

	/// Changes the requester's display name — the human label only. The session id, which keys the floor,
	/// ownership, WebRTC signaling and audio routing, is NOT touched, so a rename can't affect any of those.
	/// The name is validated against the same charset as a join; on success it is applied and announced to the
	/// whole channel, including the requester (its `MemberRenamed` for its own id is the confirmation that the
	/// name was accepted — on rejection it gets an `ErrorMessage` instead and shows nothing unvalidated).
	///
	/// Concurrency: the `setDisplayName` + broadcast run under the channel monitor, so they serialize with a
	/// concurrent join's roster snapshot (taken under the same monitor in [ChannelRegistry]'s join hook). A
	/// member joining at the same instant therefore either captures the new name in its `Joined` roster, or is
	/// already a member and receives this `MemberRenamed` — it can never be left showing the old name forever
	/// (the same hazard the post-removal `MemberLeft` ordering avoids for departures).
	private void handleRename(ClientSession session, String requestedDisplayName) {
		// Same canonical form as a join, for the same reason: what is compared and broadcast must be what is stored.
		// It also makes "rename me to the same name with a trailing space" the no-op it looks like, rather than a
		// broadcast the other members cannot see the point of.
		String displayName = canonicalDisplayName(requestedDisplayName);
		if (displayName == null || !DISPLAY_NAME.matcher(displayName).matches()) {
			sendError(session, ErrorCode.INVALID_DISPLAY_NAME, DISPLAY_NAME_RULE);
			return;
		}
		String previous = session.displayName();   // the old label, for the transition logged below (before overwrite)
		if (displayName.equals(previous)) {
			// A no-op rename (same name): do nothing — don't broadcast a pointless MemberRenamed (no churn for the
			// other members) and don't treat it as an error (a no-op is not a failure). The reference clients already
			// prevent a same-name rename locally (the browser disables Rename, the Java client skips it); this just
			// guards any client that doesn't, the same way a duplicate Join to the current channel is handled
			// harmlessly rather than rejected.
			return;
		}
		String channelName = session.channelName();   // snapshot once: null-check then a second read feeding find() would let a concurrent leave force find(null) → NPE
		if (channelName != null && channelRegistry.find(channelName) instanceof Some(Channel channel)) {
			synchronized (channel) {
				session.setDisplayName(displayName);
				broadcaster.toAll(channel, new ServerMessage.MemberRenamed(session.id(), displayName));
			}
			// Advance the MDC name so this line's prefix carries the NEW name, like every line after it (onMessage
			// snapshotted the old name at scope entry); the scope's restore-on-exit still cleans it up.
			RequestContext.updateDisplayName(displayName);
			log.info("renamed from {} to {}", previous, displayName);
		} else {
			// Not in a channel (or it vanished): just update the label — the next Join carries it, and there is
			// no roster to keep consistent or members to notify. (No meaningful prior name before the first join.)
			session.setDisplayName(displayName);
			RequestContext.updateDisplayName(displayName);
			log.info("renamed to {}", displayName);
		}
		// A rename does not change any channel's MEMBERSHIP, so nothing above refreshes a locked channel's waiting
		// list — yet that list renders this name. Deliberately outside the monitor span above: this takes ANOTHER
		// channel's monitor, and taking two in sequence rather than nested keeps the lock order flat.
		notifyOwnerWhereWaiting(session);
	}

	/// Re-sends the waiting-list snapshot to the owner of the channel this session is waiting to be admitted to, if
	/// any. Needed because that list shows the waiting session's display name, and the list itself did not change —
	/// so without this the owner would go on deciding about a name the newcomer no longer uses. A no-op for a session
	/// that is not waiting anywhere, which is the overwhelmingly common case.
	private void notifyOwnerWhereWaiting(ClientSession session) {
		String pending = session.pendingChannel();
		if (pending != null && channelRegistry.find(pending) instanceof Some(Channel waitingAt)) {
			notifyOwnerOfJoinRequests(waitingAt);
		}
	}
}
