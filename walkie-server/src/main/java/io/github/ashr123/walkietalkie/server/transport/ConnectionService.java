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
	private static final Pattern CHANNEL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final Pattern DISPLAY_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
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
	/// [ServerMessage.FloorReserved] so its client can run the countdown (see docs/FLOOR_QUEUE.md).
	private final Duration floorReservation;

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
		this.floorIdleRelease = Duration.ofSeconds(properties.floorIdleReleaseSeconds());
		this.floorMaxHold = Duration.ofSeconds(properties.floorMaxHoldSeconds());
		this.floorReservation = Duration.ofSeconds(properties.floorReservationSeconds());
	}

	public static void onConnect(ClientSession session) {
		// Scope the lifecycle line so it carries the session id (and the name, once known) via the MDC, like the
		// per-message lines. At connect the client hasn't joined yet, so the name is still blank.
		try (RequestContext.Scope _ = RequestContext.scope(session)) {
			log.info("connected (transport={})", session.transport());
		}
	}

	/// Handles one decoded control message. The caller's identity is bound for the dynamic scope of the call and
	/// surfaced on the log lines emitted while handling it (via the MDC) — see [RequestContext#scope]. The audio
	/// relay path ([#onAudio]) is deliberately not scoped, to avoid per-frame MDC churn.
	public void onMessage(ClientSession session, ClientMessage message) {
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
			case ClientMessage.SetFloorQueue(boolean enabled) -> handleSetFloorQueue(session, enabled);
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
		String requested = join.mode() == ChannelMode.GLOBAL_PTT ? GLOBAL_CHANNEL : join.channel();

		// Connect guard: a duplicate Join for the channel this session is already in is idempotent — re-send
		// the current snapshot so the client re-syncs, but do NOT churn membership (no leave/rejoin, no
		// MemberLeft + MemberJoined flicker for the other members). A Join for a different channel still switches.
		if (requested != null
				&& requested.equals(session.channelName())
				&& channelRegistry.find(requested) instanceof Some(Channel current)) {
			broadcaster.toOne(
					session,
					new ServerMessage.Joined(
							session.id(),
							current.name(),
							current.mode(),
							current.ownerId(),
							current.isLocked(),
							current.isFloorQueueEnabled(),
							current.memberInfos()
					)
			);
			return;
		}

		// Validate the switch TARGET before leaving the current channel, so a bad request (typo'd channel name,
		// invalid display name, or reserved/encryption misuse) is refused WITHOUT dropping the client from the
		// channel it is already in. Only a passphrase mismatch can still drop a switcher — it is detectable only
		// by the atomic joinOrCreate below, which necessarily runs after the leave.
		if (requested == null || !CHANNEL_NAME.matcher(requested).matches()) {
			sendError(session, ErrorCode.INVALID_CHANNEL,
					"Channel name must match " + CHANNEL_NAME.pattern());
			return;
		}
		if (join.displayName() == null || !DISPLAY_NAME.matcher(join.displayName()).matches()) {
			sendError(session, ErrorCode.INVALID_DISPLAY_NAME,
					"Display name must match " + DISPLAY_NAME.pattern());
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
		// Channel-affinity (multi-instance): this socket may only serve a channel THIS instance owns — the channel
		// it was routed to at the handshake, or one it already hosts (a live local Channel proves that channel
		// routes here, by the affinity invariant). A switch to a channel owned by another instance is refused so
		// the client reconnects and the router re-pins it. Off (single instance): every channel is served here, so
		// this is skipped and switches stay in place. (The idempotent re-Join of the current channel returned above,
		// so it never reaches here.)
		if (properties.channelAffinity()
				&& !requested.equals(session.handshakeChannel())
				&& !(channelRegistry.find(requested) instanceof Some(Channel _))) {
			sendError(session, ErrorCode.CHANNEL_ROUTING_MISMATCH,
					"'" + requested + "' is served by another instance — reconnect to switch to it.");
			return;
		}

		// Switching channels: leave the current one only after the target passed the validations above.
		if (session.channelName() != null) {
			handleLeave(session);
		}
		session.setDisplayName(join.displayName());
		// The name is only known now (after validation), but onMessage snapshotted the MDC name at scope entry
		// when it was still blank — advance it so this handler's lines (the "joined" line below) carry name=...
		// instead of name=-. The scope's restore-on-exit still cleans it up.
		RequestContext.updateDisplayName(join.displayName());

		// Emit the joiner's initial state — its Joined snapshot then an authoritative FloorStatus snapshot — from
		// INSIDE the registry's add monitor span (see joinOrCreate's onJoinUnderLock). Sending it there, atomically
		// with the joiner becoming broadcast-eligible, serializes it with floor transitions: a concurrent
		// release/grant/reserve can't land a floor broadcast that races this hint (leaving the joiner seeing a stale
		// holder or an out-of-date queue) — any subsequent change reaches the now-eligible joiner via the normal
		// broadcast and it converges on the truth. Unconditional: FloorStatus renders the whole floor UI (holder +
		// queue), so it seeds the joiner even when the floor is free (holderId == null, empty queue).
		Consumer<ChannelRegistry.JoinResult> emitInitialState = joined -> {
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
		ChannelRegistry.JoinResult joined = join.mode() == ChannelMode.GLOBAL_PTT
				? channelRegistry.joinOrCreate(requested, join.mode(), null, session, GLOBAL_CHANNEL_OWNER, false, emitInitialState)
				: channelRegistry.joinOrCreate(requested, join.mode(), join.keyCheck(), session, properties.floorQueueDefault(), emitInitialState);
		if (joined == null) {
			// The atomic join refused to add us. joinOrCreate checks, in order, lock -> capacity -> key-check, so
			// attribute the reason by re-reading the target. The enforcement itself was atomic inside joinOrCreate;
			// this re-read only picks which equally-true "can't join" message to show, so a state change in the
			// instant after the failed join at worst shows another true reason — never a wrong admit/reject.
			switch (channelRegistry.find(requested)) {
				case Some(Channel target) when target.isLocked() -> sendError(
						session,
						ErrorCode.CHANNEL_LOCKED,
						"This channel is locked by its owner — you can't join it right now."
				);
				case Some(Channel target) when target.isFull() -> sendError(
						session,
						ErrorCode.CHANNEL_FULL,
						"This channel is full — it has reached its member limit."
				);
				default -> sendError(
						session,
						ErrorCode.PASSPHRASE_MISMATCH,
						"This channel is using a different encryption passphrase (or none) — you can't join it."
				);
			}
			return;
		}
		// Advance the MDC channel so this handler's "joined" line (and anything after it in this scope) is tagged
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
						false
				))
		);

		// Identity (session + name) and the channel are carried by the MDC prefix now (the name advanced above,
		// the channel just updated). "created" when this join brought the channel into being; else "joined".
		log.info("{} mode={}", joined.created() ? "created" : "joined", joined.channel().mode());
	}

	private void handleLeave(ClientSession session) {
		if (session.channelName() == null) {
			return;
		}
		Option<Channel> channelBeforeLeave = channelRegistry.find(session.channelName());
		// Remove the member + re-elect an owner atomically in the registry, THEN announce — broadcasting MemberLeft
		// only AFTER the removal closes the ghost-member window: a member joining between an earlier broadcast and
		// the removal could otherwise snapshot a roster still containing the leaver yet never receive its MemberLeft.
		// The registry mutate (via Channel.remove) also tears the leaver off the floor: it clears the holder, scrubs
		// it from the queue, and — if it was the reserved head — ends that reservation (resets the clock to EPOCH so
		// the next head gets a fresh window). We must NOT hold the channel monitor across channelRegistry.leave (the
		// registry takes its bin lock then this monitor, so the reverse order deadlocks — see the lock-order note on
		// Channel), so the floor teardown runs AFTER the removal, on LIVE state.
		boolean ownerChanged = channelRegistry.leave(session.channelName(), session.id()) instanceof Some(String _);
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
						events.add(new ServerMessage.MemberMuted(newOwnerId, false));
					}
					log.info(
							"ownership transferred to session={} ({})",
							newOwnerId,
							channel.member(newOwnerId) instanceof Some(ClientSession newOwner)
									? newOwner.displayName()
									: "?"
					);
				}
				// Floor teardown on LIVE state, run UNCONDITIONALLY. This is safe despite the monitor gap around
				// channelRegistry.leave precisely because reserveHead is IDEMPOTENT: it (re-)reserves + notifies the
				// head only when the floor is free with NO running window, so it never re-stamps (never moves
				// backward) a reservation a concurrent sweep may already have started for the current head, and the
				// sweep's EPOCH guard stops it dropping a head this leave is about to reserve. The snapshot rides the
				// same fan-out and always re-syncs the survivors (an unchanged floor just re-sends it — harmless).
				reserveAndNotify(channel, clock.instant());
				events.add(floorStatusOf(channel));
				broadcaster.toOthers(channel, session.id(), events.toArray(ServerMessage[]::new));
			}
		}
		log.info("left");   // the channel left is in the MDC prefix; clear it for any later line in this scope
		RequestContext.updateChannel(null);
		session.leftChannel();
	}

	private void handleRequestFloor(ClientSession session) {
		if (!(requireChannel(session) instanceof Some(Channel channel))) {
			return;
		}
		if (channel.isMuted(session.id())) {
			// The owner muted this member: refuse the floor outright so a muted member can't seize and HOLD it
			// (which would block everyone else in a PTT channel even though onAudio drops the muted member's frames).
			// A conforming client never asks — its talk control is disabled on MemberMuted — so this is the server
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
				// floor next frees — the release/leave paths and the idle-release sweep do that via reserveAndNotify.
				channel.enqueueFloor(session.id());
				broadcastFloorStatus(channel);
				log.debug("queued for the floor");
				return;
			}
			// Queue off: the pre-queue behaviour. Try to reclaim an idle holder; else the request is refused and
			// NOTHING is sent — the client already shows "busy" from the last FloorStatus (FloorDenied is retired).
			String currentHolderId = channel.floorHolder() instanceof Some(String holder) ? holder : null;
			if (preemptIfIdle(channel, session, currentHolderId, now)) {
				log.info("preempted idle floor holder={}", currentHolderId);
				grantFloor(channel, session);
			} else {
				log.debug("denied the floor (held by session={})", currentHolderId);
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

	/// Broadcasts the current [#floorStatusOf] snapshot to the whole channel. Call under `synchronized(channel)` so
	/// the snapshot is consistent and its fan-out is ordered with the floor transition that triggered it.
	private void broadcastFloorStatus(Channel channel) {
		broadcaster.toAll(channel, floorStatusOf(channel));
	}

	/// The FREE -> RESERVED transition: offer the freed floor to the queue head and, if that head is still a
	/// member, send it the imperative [ServerMessage.FloorReserved] "your turn — start the claim countdown"
	/// trigger. A no-op when the floor is held or the queue is empty. The caller invokes this EXACTLY ONCE per
	/// floor-free transition (release/decline/leave/idle/max-hold/expiry) so it never re-stamps a running
	/// reservation — see [Channel#reserveHead]. MUST be called under `synchronized(channel)`; the caller
	/// re-broadcasts [#broadcastFloorStatus] so everyone else sees the new reserved head as the queue head.
	private void reserveAndNotify(Channel channel, Instant now) {
		String head = channel.reserveHead(now);
		if (head != null && channel.member(head) instanceof Some(ClientSession reserved)) {
			broadcaster.toOne(reserved, new ServerMessage.FloorReserved(floorReservation.toSeconds()));
		}
	}

	private void handleReleaseFloor(ClientSession session) {
		if (!(requireChannel(session) instanceof Some(Channel channel)) || channel.mode() == ChannelMode.FULL_DUPLEX) {
			return;
		}
		Instant now = clock.instant();
		synchronized (channel) {
			if (channel.releaseFloor(session.id())) {
				// The live holder gave up the floor: offer it to the queue head (if any) and re-broadcast.
				reserveAndNotify(channel, now);
				broadcastFloorStatus(channel);
				log.debug("released the floor");
				return;
			}
			// Not the holder — a waiter leaving the line, or the reserved head declining its turn. dequeueFloor
			// resets the reservation clock IFF this caller was the reserved head (so the next head gets a fresh
			// window; a mid-queue leave keeps the running head's window). reserveAndNotify is then unconditional but
			// IDEMPOTENT: it re-reserves + notifies only when the floor is free with an unstamped head — i.e. only
			// when the head genuinely changed — so a mid-queue leave is a reserve no-op that never re-stamps.
			if (channel.dequeueFloor(session.id())) {
				reserveAndNotify(channel, now);
				broadcastFloorStatus(channel);
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
							log.info("session={} ({}) reached the max floor-hold time; floor released by sweep",
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
							&& !channel.floorQueue().isEmpty()
							&& channel.floorHolder() instanceof Some(String holderId)
							&& channel.member(holderId) instanceof Some(ClientSession holder)
							&& holder.supportsAudioRelay()) {
						String released = channel.releaseIfIdle(now.minus(floorIdleRelease));
						if (released != null) {
							freed = true;
							log.info("session={} ({}) idle past the release window; floor offered to the queue",
									released, holder.displayName());
						}
					}
					// 3. Whatever freed the floor above, offer it to the queue head and re-broadcast the snapshot.
					if (freed) {
						reserveAndNotify(channel, now);
						broadcastFloorStatus(channel);
					}
					// 4. Reservation-expiry: a reserved head that missed its claim window is dropped, and the floor is
					// offered to the next in line — if anyone was behind it. Runs last so a reservation freshly
					// stamped at `now` by steps 1–3 is not itself treated as expired.
					String missed = channel.expiredReservationHead(now.minus(floorReservation));
					if (missed != null) {
                        channel.dequeueFloor(missed);
						reserveAndNotify(channel, now);
						broadcastFloorStatus(channel);
						// reserveAndNotify stamps a fresh reservation on the next head IFF one was behind the dropped
						// member: a present reservedHolder() means the floor was handed on; an absent one means the
						// queue emptied and the floor is simply free. The {} tail reflects which.
						log.info(
								"session={} ({}) missed its floor reservation; {}",
								missed,
								channel.member(missed) instanceof Some(ClientSession m) ? m.displayName() : "?",
								channel.reservedHolder() instanceof Some(String _)
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
	public void onAudio(ClientSession session, byte[] audio) {
		if (!session.supportsAudioRelay()
				|| audio.length == 0
				|| audio.length > properties.maxAudioFrameBytes()
				|| session.channelName() == null
				|| !(channelRegistry.find(session.channelName()) instanceof Some(Channel channel))
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
					// Talk-time limit reached: free the floor, offer it to the queue head (if any), and re-broadcast
					// the snapshot to the whole channel (incl. the (ex-)speaker so its client stops transmitting and
					// resets); the speaker must re-request to continue.
					reserveAndNotify(channel, now);
					broadcastFloorStatus(channel);
					// onAudio is intentionally not session-scoped (no per-frame MDC churn), so the holder's id + name
					// are logged inline; the channel is mirrored into the MDC (channelScope) so this line carries
					// channel=… like the handler lines rather than repeating it in the message.
					try (RequestContext.Scope _ = RequestContext.channelScope(channel)) {
						log.info("session={} ({}) reached the max floor-hold time; floor released",
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
						log.debug("Audio relay to {} failed: {}", other.id(), e.getMessage());
					}
				}
			});
		}
		// The sender's stream index was freed (it is leaving) — drop this straggler frame.
	}

	/// Prepends the 1-byte stream index to a relayed audio frame: `[sid][body]`. The body (plaintext
	/// `[tag][payload]` or the E2EE `[scheme][IV][ct]` envelope) is copied verbatim — the server never
	/// inspects it, and the index sits outside any encryption.
	private static byte[] prefixedFrame(int streamIndex, byte[] body) {
		byte[] out = new byte[body.length + 1];
		out[0] = (byte) streamIndex;
		System.arraycopy(body, 0, out, 1, body.length);
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
			audioRateLimiter.forget(session.id());
			controlRateLimiter.forget(session.id());
			log.info("disconnected ({})", closeReason);
		}
	}

	private void relaySignal(ClientSession session, String targetId, ServerMessage message) {
		if (!(requireChannel(session) instanceof Some(Channel channel))) {
			return;
		}
		if (channel.member(targetId) instanceof Some(ClientSession target))
			broadcaster.toOne(target, message);
		else
			sendError(session, ErrorCode.UNKNOWN_TARGET,
					"No member '" + targetId + "' in this channel");
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
	/// learns the passphrase — the request carries only the key-check derived from the new one (or `null` to make
	/// the channel unencrypted) — so all it does is record the new key-check and broadcast a `PassphraseChanged`
	/// to every member (including the owner) so each client re-derives its key from the new passphrase, obtained
	/// out-of-band exactly as the original was. A non-owner gets `NOT_OWNER`; a request before joining gets
	/// `NOT_IN_CHANNEL`. The server-managed `global` room has the sentinel owner, so a rotation there is refused
	/// as `NOT_OWNER` — it stays the unencrypted broadcast room.
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
		if (session.channelName() == null) {
			sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			return;
		}
		switch (channelRegistry.changePassphrase(session.channelName(), session.id(), keyCheck)) {
			case ChannelRegistry.RekeyResult.NotFound _ -> sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			case ChannelRegistry.RekeyResult.NotOwner _ ->
					sendError(session, ErrorCode.NOT_OWNER, "Only the channel owner can change the passphrase");
			case ChannelRegistry.RekeyResult.Ok(Channel channel) -> {
				synchronized (channel) {
					// Broadcast the LIVE key-check (convergence) plus this request's wrappedKey relayed verbatim —
					// the server never inspects or stores it; members that hold the old key decrypt it to adopt the
					// new passphrase automatically (and re-verify against the live key-check, so a stale/tampered
					// blob just falls back to a manual re-entry).
					broadcaster.toAll(channel, new ServerMessage.PassphraseChanged(channel.keyCheck(), wrappedKey));
				}
				// Log the encrypted/plaintext STATUS only — never the key-check token or the wrapped blob.
				log.info("changed passphrase (now {})", keyCheck == null ? "unencrypted" : "encrypted");
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
						events.add(new ServerMessage.MemberMuted(channel.ownerId(), false));
					}
					broadcaster.toAll(channel, events.toArray(ServerMessage[]::new));
				}
				log.info("transferred ownership to {}", newOwnerId);
			}
		}
	}

	/// Mutes or unmutes one member's relay audio, on the owner's request only. This is server-ENFORCED: while a
	/// member is muted, [#onAudio] drops its frames, so a client that ignores its own mute still can't be heard —
	/// the trust boundary is the relay, not the sender. A non-owner gets `NOT_OWNER`; a target that isn't a current
	/// member, or the owner itself (which can never be muted), gets `UNKNOWN_TARGET`.
	///
	/// Concurrency: the membership re-check, the mute flip, the floor release (when the muted member was the one
	/// talking) and the `MemberMuted` broadcast all run under the channel monitor — the same monitor every floor
	/// transition and the mode/owner/passphrase broadcasts take — so the mute state, a freed floor and the notice
	/// everyone sees stay consistent, and a member muted mid-transmission is dropped from the floor and told in one
	/// atomic step. Enforcement engages within one frame: for a PTT floor holder the floor release above drops its
	/// next frame at [#onAudio]'s under-monitor `holdsFloor` re-check, and [#handleRequestFloor] re-checks the mute
	/// under the monitor so a just-muted member can't reacquire the floor. The [#onAudio] gate itself is a lock-free
	/// hot-path read, so in FULL_DUPLEX a single frame already in flight when the mute lands may still be relayed
	/// (bounded, real-time) — the mute is authoritative from the following frame. Enforcement is relay-only: WebRTC
	/// media is peer-to-peer, so a WebRTC talker's mute is best-effort at its own client (it still gets `MemberMuted`
	/// and stops).
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
		if (memberId == null || memberId.equals(channel.ownerId()) || !(channel.member(memberId) instanceof Some(ClientSession _))) {
			sendError(session, ErrorCode.UNKNOWN_TARGET, "No member '" + memberId + "' to mute in this channel");
			return;
		}
		synchronized (channel) {
			// Re-check membership under the monitor: leave scrubs the mute set under this same monitor after
			// removing the member, so a target that left since the fast-path check is skipped here rather than
			// leaving a ghost mute id that would outlive it (see Channel.remove). A silent no-op is right — the
			// member is already gone (it got a MemberLeft), so there is nothing to mute and no error to report.
			if (!(channel.member(memberId) instanceof Some(ClientSession _))) {
				return;
			}
			if (!channel.setMuted(memberId, muted)) {
				return;   // already in that state: nothing to free, nothing to broadcast
			}
			// Fan out the mute state change, plus the fresh floor snapshot when muting took the member off the floor
			// (holder released, or a waiter / reserved head dequeued) — in ONE pass.
			List<ServerMessage> events = new ArrayList<>();
			events.add(new ServerMessage.MemberMuted(memberId, muted));
			if (detachFromFloorIfMuted(channel, memberId, muted)) {
				reserveAndNotify(channel, clock.instant());
				events.add(floorStatusOf(channel));
			}
			broadcaster.toAll(channel, events.toArray(ServerMessage[]::new));
		}
		log.info("{} member {}", muted ? "muted" : "unmuted", memberId);
	}

	/// Mutes or unmutes EVERY other member of the channel at once, on the owner's request. The owner is never
	/// muted. Same server enforcement as [#handleMuteMember]; a non-owner gets `NOT_OWNER`. Emits one `MemberMuted`
	/// per member whose state actually changed — all fanned out together in a SINGLE pass, not one broadcast each.
	/// If a muted member was on the floor, its floor is freed too (via [#detachFromFloorIfMuted]) and the fresh
	/// snapshot rides the same fan-out.
	private void handleMuteAll(ClientSession session, boolean muted) {
		if (!(requireOwnedChannel(session, "mute members") instanceof Some(Channel channel))) {
			return;
		}
		synchronized (channel) {
			// setMutedForAllExcept flips the whole roster under the monitor and returns only the ids that changed;
			// collect exactly one MemberMuted per genuine transition and fan them ALL out in a SINGLE pass. The old
			// per-member broadcast was one full member-iteration each — O(N²) for an N-member channel.
			List<ServerMessage> events = new ArrayList<>();
			boolean floorChanged = false;
			for (String memberId : channel.setMutedForAllExcept(channel.ownerId(), muted)) {
				floorChanged |= detachFromFloorIfMuted(channel, memberId, muted);
				events.add(new ServerMessage.MemberMuted(memberId, muted));
			}
			// If any muted member was on the floor (holder or waiting), advance/free it and append the fresh snapshot
			// so it rides the SAME fan-out.
			if (floorChanged) {
				reserveAndNotify(channel, clock.instant());
				events.add(floorStatusOf(channel));
			}
			if (!events.isEmpty()) {   // nothing actually changed (idempotent) -> no fan-out at all
				broadcaster.toAll(channel, events.toArray(ServerMessage[]::new));
			}
		}
		log.info("{} all members", muted ? "muted" : "unmuted");
	}

	/// When a member is being MUTED, takes it off the floor entirely: released if it was the live holder, AND
	/// dequeued from the waiting line (which, if it was the reserved head, ends its claim window so the next head
	/// gets a fresh one — see [Channel#dequeueFloor]). A no-op when UNMUTING (unmuting never touches the floor).
	/// Returns whether the floor state changed, so the caller can offer the freed/advanced floor to the queue head.
	/// The caller broadcasts the `MemberMuted` itself so it can BATCH several into one fan-out — see [#handleMuteAll],
	/// where a per-member broadcast would be one full member-iteration each (O(N²) for an N-member channel). MUST be
	/// called while holding the channel monitor.
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
	/// caller batches a `MemberMuted(owner, false)` into its own fan-out (no spurious notice otherwise). MUST be
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
		if (session.channelName() == null) {
			sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			return;
		}
		switch (channelRegistry.setLocked(session.channelName(), session.id(), locked)) {
			case ChannelRegistry.LockResult.NotFound _ -> sendError(session, ErrorCode.NOT_IN_CHANNEL, "Join a channel first");
			case ChannelRegistry.LockResult.NotOwner _ ->
					sendError(session, ErrorCode.NOT_OWNER, "Only the channel owner can lock the channel");
			case ChannelRegistry.LockResult.Ok(Channel channel) -> {
				synchronized (channel) {
					broadcaster.toAll(channel, new ServerMessage.ChannelLocked(channel.isLocked()));
				}
				log.info("channel {}", locked ? "locked" : "unlocked");
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
			channel.setFloorQueueEnabled(enabled);   // clears the queue + reservation when disabling
			broadcaster.toAll(channel, new ServerMessage.FloorQueueChanged(enabled), floorStatusOf(channel));
		}
		log.info("floor queue {}", enabled ? "enabled" : "disabled");
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
	private void handleRename(ClientSession session, String displayName) {
		if (displayName == null || !DISPLAY_NAME.matcher(displayName).matches()) {
			sendError(session, ErrorCode.INVALID_DISPLAY_NAME,
					"Display name must match " + DISPLAY_NAME.pattern());
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
		if (session.channelName() != null && channelRegistry.find(session.channelName()) instanceof Some(Channel channel)) {
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
	}
}
