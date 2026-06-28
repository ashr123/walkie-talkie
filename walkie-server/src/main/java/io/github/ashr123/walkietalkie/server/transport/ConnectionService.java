package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.ratelimit.AudioRateLimiter;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.support.RequestContext;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// Transport-agnostic heart of the server. Both WebSocket handlers feed decoded control messages and
/// raw audio frames here; this class owns membership, push-to-talk floor arbitration, audio fan-out
/// and WebRTC signaling relay. It never touches a `WebSocketSession` directly, which keeps it
/// unit-testable with fake [ClientSession] instances.
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
	private final AudioRateLimiter audioRateLimiter;
	private final Clock clock;
	private final Duration floorIdleRelease;
	private final Duration floorMaxHold;

	@Autowired
	public ConnectionService(ChannelRegistry channelRegistry,
	                         WalkieProperties properties,
	                         AudioRateLimiter audioRateLimiter) {
		this(channelRegistry, properties, audioRateLimiter, Clock.systemUTC());
	}

	/// Package-private seam: lets tests drive the push-to-talk floor timers with a controllable clock instead
	/// of wall time.
	ConnectionService(ChannelRegistry channelRegistry,
	                  WalkieProperties properties,
	                  AudioRateLimiter audioRateLimiter,
	                  Clock clock) {
		this.channelRegistry = channelRegistry;
		this.properties = properties;
		this.audioRateLimiter = audioRateLimiter;
		this.clock = clock;
		this.floorIdleRelease = Duration.ofSeconds(properties.floorIdleReleaseSeconds());
		this.floorMaxHold = Duration.ofSeconds(properties.floorMaxHoldSeconds());
	}

	public static void onConnect(ClientSession session) {
		log.info("Connected: session={} transport={}", session.id(), session.transport());
	}

	/// Handles one decoded control message. The caller's identity is bound for the dynamic scope of the
	/// call (a Java 25 [ScopedValue]) and surfaced on the log lines emitted while handling it (via the
	/// MDC) — see [RequestContext#runAs]. The audio relay path ({@link #onAudio}) is deliberately not
	/// scoped, to avoid per-frame MDC churn.
	public void onMessage(ClientSession session, ClientMessage message) {
		RequestContext.runAs(session.id(), () -> dispatch(session, message));
	}

	private void dispatch(ClientSession session, ClientMessage message) {
		switch (message) {
			case ClientMessage.Join join -> handleJoin(session, join);
			case ClientMessage.Leave _ -> handleLeave(session);
			case ClientMessage.RequestFloor _ -> handleRequestFloor(session);
			case ClientMessage.ReleaseFloor _ -> handleReleaseFloor(session);
			case ClientMessage.ChangeMode(ChannelMode mode) -> handleChangeMode(session, mode);
			case ClientMessage.Offer(String target, String sdp) ->
					relaySignal(session, target, new ServerMessage.SignalOffer(session.id(), sdp));
			case ClientMessage.Answer(String target, String sdp) ->
					relaySignal(session, target, new ServerMessage.SignalAnswer(session.id(), sdp));
			case ClientMessage.IceCandidate(String target, String candidate, String sdpMid, Integer sdpMLineIndex) ->
					relaySignal(session, target, new ServerMessage.SignalIce(session.id(), candidate, sdpMid, sdpMLineIndex));
		}
	}

	private static void safeSend(ClientSession session, ServerMessage message) {
		try {
			session.send(message);
		} catch (RuntimeException e) {
			log.debug("Control send to {} failed: {}", session.id(), e.getMessage());
		}
	}

	private void handleJoin(ClientSession session, ClientMessage.Join join) {
		ChannelMode mode = join.mode();
		String requested = mode == ChannelMode.GLOBAL_PTT ? GLOBAL_CHANNEL : join.channel();

		// Connect guard: a duplicate Join for the channel this session is already in is idempotent — re-send
		// the current snapshot so the client re-syncs, but do NOT churn membership (no leave/rejoin, no
		// MemberLeft + MemberJoined flicker for the other members). A Join for a different channel still switches.
		if (requested != null && requested.equals(session.channelName())
				&& channelRegistry.find(requested) instanceof Some(Channel current)) {
			session.send(new ServerMessage.Joined(
					session.id(), current.name(), current.mode(), current.ownerId(), current.memberInfos()));
			return;
		}

		if (session.channelName() != null) {
			handleLeave(session);
		}

		if (requested == null || !CHANNEL_NAME.matcher(requested).matches()) {
			session.send(new ServerMessage.ErrorMessage("invalid_channel",
					"Channel name must match " + CHANNEL_NAME.pattern()));
			return;
		}
		if (join.displayName() == null || !DISPLAY_NAME.matcher(join.displayName()).matches()) {
			session.send(new ServerMessage.ErrorMessage("invalid_display_name",
					"Display name must match " + DISPLAY_NAME.pattern()));
			return;
		}
		// The "global" channel is the server-managed broadcast room: reachable ONLY via global push-to-talk,
		// and never end-to-end encrypted — so anyone can join it (there is no shared passphrase to know).
		if (GLOBAL_CHANNEL.equals(requested) && mode != ChannelMode.GLOBAL_PTT) {
			session.send(new ServerMessage.ErrorMessage("reserved_channel",
					"'" + GLOBAL_CHANNEL + "' is reserved — use Single global push-to-talk to join it."));
			return;
		}
		if (mode == ChannelMode.GLOBAL_PTT && join.keyCheck() != null) {
			session.send(new ServerMessage.ErrorMessage("encryption_not_allowed",
					"The global channel can't be end-to-end encrypted — clear the passphrase to join it."));
			return;
		}
		session.setDisplayName(join.displayName());

		// Emit the joiner's initial state — its Joined snapshot then, if the floor is held, the FloorTaken hint —
		// from INSIDE the registry's add monitor span (see joinOrCreate's onJoinUnderLock). Sending it there,
		// atomically with the joiner becoming broadcast-eligible, serializes it with floor transitions: a
		// concurrent release can't slip a FloorIdle in before this hint (which would orphan it on a now-free
		// floor), and a concurrent grant/preempt can't leave the hint naming a stale holder — the joiner instead
		// receives the grant's own FloorTaken right after, via the normal broadcast, and converges on the truth.
		Consumer<ChannelRegistry.JoinResult> emitInitialState = joined -> {
			Channel joinedChannel = joined.channel();
			session.joinedChannel(joinedChannel.name());
			safeSend(session, new ServerMessage.Joined(session.id(), joinedChannel.name(),
					joinedChannel.mode(), joinedChannel.ownerId(), joined.roster()));
			if (joined.floorHolder() instanceof Some(String holder)) {
				safeSend(session, new ServerMessage.FloorTaken(holder));
			}
		};

		// Global is server-owned (sentinel owner) and forced unencrypted (null key-check); every other channel
		// is owned by its creator and adopts the joiner's key-check.
		ChannelRegistry.JoinResult joined = mode == ChannelMode.GLOBAL_PTT
				? channelRegistry.joinOrCreate(requested, mode, null, session, GLOBAL_CHANNEL_OWNER, emitInitialState)
				: channelRegistry.joinOrCreate(requested, mode, join.keyCheck(), session, emitInitialState);
		if (joined == null) {
			session.send(new ServerMessage.ErrorMessage("passphrase_mismatch",
					"This channel is using a different encryption passphrase (or none) — you can't join it."));
			return;
		}
		Channel channel = joined.channel();

		// Tell the OTHER members about the joiner. This is intentionally OUTSIDE the registry lock: it concerns
		// the joiner's visibility to others, not the joiner's own floor view, so it needs no floor serialization.
		ServerMessage notice = new ServerMessage.MemberJoined(
				new MemberInfo(session.id(), session.displayName(), channel.streamIndexOf(session.id())));
		channel.forEachOther(session.id(), other -> safeSend(other, notice));

		log.info("session={} ({}) joined channel={} mode={}",
				session.id(), session.displayName(), channel.name(), channel.mode());
	}

	private void handleLeave(ClientSession session) {
		String channelName = session.channelName();
		if (channelName == null) {
			return;
		}
		Channel channel = channelRegistry.find(channelName) instanceof Some(Channel found) ? found : null;
		if (channel != null) {
			// Free the leaver's floor (if held) and tell the others, under the floor monitor so this FloorIdle
			// can't race a new holder's grant. Capture wasHolding HERE — registry.leave (below) also clears the
			// floor as it removes the member, so reading it afterwards would always be false. (Must NOT call
			// channelRegistry.leave while holding this monitor — see the lock-order note on Channel.)
			synchronized (channel) {
				if (channel.releaseFloor(session.id())) {
					ServerMessage idle = new ServerMessage.FloorIdle();
					channel.forEachOther(session.id(), other -> safeSend(other, idle));
				}
			}
		}
		// Remove the member + re-elect an owner atomically in the registry, THEN announce — broadcasting MemberLeft
		// only AFTER the removal closes the ghost-member window: a member joining between an earlier broadcast and
		// the removal could otherwise snapshot a roster still containing the leaver yet never receive its MemberLeft.
		boolean ownerChanged = channelRegistry.leave(channelName, session.id()) instanceof Some(String _);
		if (channel != null) {
			// Announce to the survivors of the SAME channel object the leave acted on — NOT a fresh find()-by-name,
			// which could resolve a dropped-and-recreated same-named channel and notify its members instead.
			synchronized (channel) {
				ServerMessage left = new ServerMessage.MemberLeft(session.id());
				channel.forEachOther(session.id(), other -> safeSend(other, left));
				if (ownerChanged) {
					// Read the CURRENT owner (not the value leave returned) under the monitor: when two owners
					// leave back-to-back, the monitor orders the OwnerChanged broadcasts and each carries the
					// latest elected owner, so a survivor converges on the real owner rather than ending up
					// believing it is a member who has already left.
					ServerMessage ownerMsg = new ServerMessage.OwnerChanged(channel.ownerId());
					channel.forEachOther(session.id(), other -> safeSend(other, ownerMsg));
				}
			}
		}
		session.leftChannel();
	}

	private void handleRequestFloor(ClientSession session) {
		Channel channel = requireChannel(session);
		if (channel == null) {
			return;
		}
		if (channel.mode() == ChannelMode.FULL_DUPLEX) {
			session.send(new ServerMessage.FloorGranted());
			return;
		}
		Instant now = clock.instant();
		// Acquire/preempt AND the grant broadcast happen under the floor monitor so the FloorGranted/FloorTaken
		// can't interleave with a concurrent release's FloorIdle (which would otherwise reach the new holder).
		synchronized (channel) {
			if (channel.tryAcquireFloor(session.id(), now)) {
				grantFloor(channel, session);
				return;
			}
			// Denied — name the current holder (or null if it freed up between our failed acquire and this read).
			String currentHolderId = channel.floorHolder() instanceof Some(String holder) ? holder : null;
			if (preemptIfIdle(channel, session, currentHolderId, now)) {
				log.info("session={} preempted idle floor holder={} on channel={}",
						session.id(), currentHolderId, channel.name());
				grantFloor(channel, session);
			} else {
				session.send(new ServerMessage.FloorDenied(currentHolderId));
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

	/// Confirms the (already-acquired) floor to `session` and announces the new holder to everyone else (the
	/// acquire/activity marks were stamped atomically with the swap in [Channel]). The `FloorTaken` broadcast
	/// doubles as the notice to a just-preempted ex-holder that the floor is no longer theirs.
	private static void grantFloor(Channel channel, ClientSession session) {
		session.send(new ServerMessage.FloorGranted());
		ServerMessage taken = new ServerMessage.FloorTaken(session.id());
		channel.forEachOther(session.id(), other -> safeSend(other, taken));
	}

	private void handleReleaseFloor(ClientSession session) {
		Channel channel = requireChannel(session);
		if (channel == null || channel.mode() == ChannelMode.FULL_DUPLEX) {
			return;
		}
		synchronized (channel) {
			if (channel.releaseFloor(session.id())) {
				ServerMessage idle = new ServerMessage.FloorIdle();
				channel.forEachOther(session.id(), other -> safeSend(other, idle));
			}
		}
	}

	/// Scheduled safety net for the push-to-talk max-hold cap. onAudio enforces the cap lazily on the holder's
	/// next frame; this sweep also reclaims a holder that has STOPPED sending frames without releasing — the case
	/// onAudio can't see — so the floor is freed even with idle auto-release disabled and no other member
	/// contending. It keys off hold time only (never audio content), so it bounds **any** holder, including a
	/// WebRTC member whose media never reaches the server. No-op while max-hold is disabled (0); the per-channel
	/// release runs under the channel monitor, so it can't race a concurrent grant (and at most one of the sweep /
	/// onAudio releases the same hold).
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
	void releaseExpiredFloors() {
		if (floorMaxHold.isZero()) {
			return;
		}
		Instant cutoff = clock.instant().minus(floorMaxHold);
		for (Channel channel : channelRegistry.channels()) {
			// Release-and-notify under the floor monitor so a member acquiring the freed floor can't receive the
			// sweep's stray FloorIdle. forEach notifies the whole channel incl. the (ex-)holder so its client stops.
			synchronized (channel) {
				String holder = channel.releaseIfExpired(cutoff);
				if (holder != null) {
					ServerMessage idle = new ServerMessage.FloorIdle();
					channel.forEach(member -> safeSend(member, idle));
					log.info("session={} reached the max floor-hold time on channel={}; floor released by sweep",
							holder, channel.name());
				}
			}
		}
	}

	/// Relays a raw audio frame to the other relay-capable members of the sender's channel. The frame
	/// is dropped when the sender is not currently authorized to talk (push-to-talk floor not held) or
	/// when it violates the configured size bounds.
	public void onAudio(ClientSession session, byte[] audio) {
		if (!session.supportsAudioRelay()
				|| audio.length == 0
				|| audio.length > properties.maxAudioFrameBytes()
				|| session.channelName() == null
				|| !(channelRegistry.find(session.channelName()) instanceof Some(Channel channel))
				|| !channel.holdsFloor(session.id())) {
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
					// Talk-time limit reached: free the floor and tell the whole channel (incl. the (ex-)speaker
					// so its client stops transmitting and resets); the speaker must re-request to continue.
					ServerMessage idle = new ServerMessage.FloorIdle();
					channel.forEach(member -> safeSend(member, idle));
					log.info("session={} reached the max floor-hold time on channel={}; floor released",
							session.id(), channel.name());
					return;
				}
				channel.markFloorActivity(now);
			}
		}
		// A late frame from a session that left/closed since the entry gate must not resurrect a rate-limiter
		// bucket after onClose's forget(); re-check liveness before computeIfAbsent. (For PTT the holdsFloor
		// re-check above already covers it; this also covers full-duplex.)
		if (session.channelName() == null) {
			return;
		}
		// Per-sender flood guard: drop frames from a sender exceeding the configured rate BEFORE fan-out, so a
		// flooder can't amplify cost across the channel (N recipients) or force excess decode work. This counts
		// frames without inspecting them, so it works on end-to-end-encrypted channels too (see AudioRateLimiter).
		if (!audioRateLimiter.tryAcquire(session.id())) {
			return;
		}
		// Tag the fan-out with the sender's per-channel stream index so receivers can demultiplex talkers;
		// every client decodes per sender and mixes locally (see docs/CLIENT_PROTOCOL.md §5).
		byte[] prefixed = prefixedFrame(channel.streamIndexOf(session.id()), audio);
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

	/// Prepends the 1-byte stream index to a relayed audio frame: `[sid][body]`. The body (plaintext
	/// `[tag][payload]` or the E2EE `[scheme][IV][ct]` envelope) is copied verbatim — the server never
	/// inspects it, and the index sits outside any encryption.
	private static byte[] prefixedFrame(int streamIndex, byte[] body) {
		byte[] out = new byte[body.length + 1];
		out[0] = (byte) streamIndex;
		System.arraycopy(body, 0, out, 1, body.length);
		return out;
	}

	public void onClose(ClientSession session) {
		handleLeave(session);
		audioRateLimiter.forget(session.id());
		log.info("Disconnected: session={}", session.id());
	}

	private void relaySignal(ClientSession session, String targetId, ServerMessage message) {
		Channel channel = requireChannel(session);
		if (channel == null) {
			return;
		}
		if (channel.member(targetId) instanceof Some(ClientSession target))
			safeSend(target, message);
		else
			session.send(new ServerMessage.ErrorMessage("unknown_target",
					"No member '" + targetId + "' in this channel"));
	}

	private Channel requireChannel(ClientSession session) {
		String name = session.channelName();
		if (name == null) {
			session.send(new ServerMessage.ErrorMessage("not_in_channel", "Join a channel first"));
			return null;
		}
		return channelRegistry.find(name) instanceof Some(Channel channel) ?
				channel :
				null;
	}

	/// Changes the current channel's mode, but only for its owner. Clears the floor and broadcasts the
	/// new mode to every member so their controls update; a non-owner gets a `not_owner` error.
	private void handleChangeMode(ClientSession session, ChannelMode mode) {
		Channel channel = requireChannel(session);
		if (channel == null) {
			return;
		}
		if (!session.id().equals(channel.ownerId())) {
			session.send(new ServerMessage.ErrorMessage("not_owner", "Only the channel owner can change the mode"));
			return;
		}
		if (mode == ChannelMode.GLOBAL_PTT && !channel.name().equals(GLOBAL_CHANNEL)) {
			session.send(new ServerMessage.ErrorMessage("invalid_mode", "Global PTT applies only to the 'global' channel"));
			return;
		}
		if (channel.mode() == mode) {
			return;
		}
		// Mode switch + floor reset + the broadcast happen under the floor monitor, so the FloorIdle can't race a
		// concurrent grant and the mode/floor everyone sees is consistent.
		ServerMessage modeChanged = new ServerMessage.ModeChanged(mode);
		ServerMessage floorIdle = new ServerMessage.FloorIdle();
		synchronized (channel) {
			channel.setMode(mode);
			channel.clearFloor();
			// Broadcast to everyone (incl. the owner): the new mode and a floor reset, so any 'talking'
			// indicator is superseded and a fresh push-to-talk floor is available.
			channel.forEach(member -> {
				safeSend(member, modeChanged);
				safeSend(member, floorIdle);
			});
		}
		log.info("session={} changed channel={} mode to {}", session.id(), channel.name(), mode);
	}
}
