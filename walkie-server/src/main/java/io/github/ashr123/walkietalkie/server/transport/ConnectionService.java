package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.ratelimit.AudioRateLimiter;
import io.github.ashr123.walkietalkie.server.floor.FloorControlUtil;
import io.github.ashr123.walkietalkie.server.floor.FloorResult;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.support.RequestContext;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

		// Global is server-owned (sentinel owner) and forced unencrypted (null key-check); every other channel
		// is owned by its creator and adopts the joiner's key-check.
		Channel channel = mode == ChannelMode.GLOBAL_PTT
				? channelRegistry.joinOrCreate(requested, mode, null, session, GLOBAL_CHANNEL_OWNER)
				: channelRegistry.joinOrCreate(requested, mode, join.keyCheck(), session);
		if (channel == null) {
			session.send(new ServerMessage.ErrorMessage("passphrase_mismatch",
					"This channel is using a different encryption passphrase (or none) — you can't join it."));
			return;
		}

		session.joinedChannel(channel.name());
		session.send(new ServerMessage.Joined(
				session.id(), channel.name(), channel.mode(), channel.ownerId(), channel.memberInfos()));

		ServerMessage notice = new ServerMessage.MemberJoined(
				new MemberInfo(session.id(), session.displayName(), channel.streamIndexOf(session.id())));
		channel.forEachOther(session.id(), other -> safeSend(other, notice));

		if (channel.floorHolder() instanceof Some(String holder)) {
			session.send(new ServerMessage.FloorTaken(holder));
		}

		log.info("session={} ({}) joined channel={} mode={}",
				session.id(), session.displayName(), channel.name(), channel.mode());
	}

	private void handleLeave(ClientSession session) {
		String channelName = session.channelName();
		if (channelName == null) {
			return;
		}
		if (channelRegistry.find(channelName) instanceof Some(Channel channel)) {
			boolean wasHolding = channel.releaseFloor(session.id());
			ServerMessage left = new ServerMessage.MemberLeft(session.id());
			channel.forEachOther(session.id(), other -> safeSend(other, left));
			if (wasHolding) {
				ServerMessage idle = new ServerMessage.FloorIdle();
				channel.forEachOther(session.id(), other -> safeSend(other, idle));
			}
		}
		// Removal and ownership re-election are atomic in the registry; announce a new owner (if one was
		// elected) to the members that remain after the leaver is gone.
		if (channelRegistry.leave(channelName, session.id()) instanceof Some(String newOwner)
				&& channelRegistry.find(channelName) instanceof Some(Channel channel)) {
			channel.forEachOther(session.id(), other -> safeSend(other, new ServerMessage.OwnerChanged(newOwner)));
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
		switch (FloorControlUtil.requestFloor(channel, session)) {
			case FloorResult.Granted _ -> grantFloor(channel, session);
			// Idle auto-release: reclaim the floor for this requester if the current holder has gone silent
			// past the configured window. "Silent" = no relayed frame for that long, which the server sees
			// from frame timing without decoding audio — so it works on encrypted channels too. A holder who
			// keeps talking is never idle and is instead bounded by the max-hold cap in onAudio. Restricted
			// to a relay holder: a WebRTC holder's media flows peer-to-peer and never reaches onAudio, so the
			// server has no activity signal for it and must not preempt an active WebRTC speaker as "idle".
			case FloorResult.Denied(String currentHolderId) when !floorIdleRelease.isZero()
					&& currentHolderId != null
					&& !currentHolderId.equals(session.id())
					&& channel.member(currentHolderId) instanceof Some(ClientSession holder)
					&& holder.supportsAudioRelay()
					&& channel.preemptFloorIfIdle(currentHolderId, session.id(),
					clock.instant().minus(floorIdleRelease)) -> {
				log.info("session={} preempted idle floor holder={} on channel={}",
						session.id(), currentHolderId, channel.name());
				grantFloor(channel, session);
			}
			case FloorResult.Denied(String currentHolderId) ->
					session.send(new ServerMessage.FloorDenied(currentHolderId));
		}
	}

	/// Confirms the (already-acquired) floor to `session`: stamps the acquire time for the hold timers and
	/// announces the new holder to everyone else. The `FloorTaken` broadcast doubles as the notice to a
	/// just-preempted ex-holder that the floor is no longer theirs.
	private void grantFloor(Channel channel, ClientSession session) {
		channel.markFloorAcquired(clock.instant());
		session.send(new ServerMessage.FloorGranted());
		ServerMessage taken = new ServerMessage.FloorTaken(session.id());
		channel.forEachOther(session.id(), other -> safeSend(other, taken));
	}

	private void handleReleaseFloor(ClientSession session) {
		Channel channel = requireChannel(session);
		if (channel == null || channel.mode() == ChannelMode.FULL_DUPLEX) {
			return;
		}
		if (FloorControlUtil.releaseFloor(channel, session)) {
			ServerMessage idle = new ServerMessage.FloorIdle();
			channel.forEachOther(session.id(), other -> safeSend(other, idle));
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
		// Per-sender flood guard: drop frames from a sender exceeding the configured rate BEFORE fan-out, so a
		// flooder can't amplify cost across the channel (N recipients) or force excess decode work. This counts
		// frames without inspecting them, so it works on end-to-end-encrypted channels too (see AudioRateLimiter).
		if (!audioRateLimiter.tryAcquire(session.id())) {
			return;
		}
		// Push-to-talk hold limits (no-op in full-duplex, which has no single holder). The sender is the floor
		// holder here (the gate above checked holdsFloor). Max-hold caps continuous talk; otherwise we refresh
		// the activity mark so idle auto-release measures silence from this frame. Both key off frame timing,
		// never audio content, so they hold on encrypted channels too.
		if (channel.mode() != ChannelMode.FULL_DUPLEX) {
			Instant now = clock.instant();
			if (!floorMaxHold.isZero()
					&& Duration.between(channel.floorAcquiredAt(), now).compareTo(floorMaxHold) >= 0) {
				// Talk-time limit reached. Release ONLY if we still hold the floor: a concurrent RequestFloor may
				// have legitimately handed it to someone else between this check and here, and a blind clear +
				// broadcast would yank the floor from that new holder (a stray FloorIdle stopping their client).
				// On a lost CAS, just drop this frame silently. On success, notify the channel INCLUDING the
				// (ex-)speaker so its client stops transmitting and resets; the speaker must re-request.
				if (channel.releaseFloor(session.id())) {
					ServerMessage idle = new ServerMessage.FloorIdle();
					session.send(idle);
					channel.forEachOther(session.id(), other -> safeSend(other, idle));
					log.info("session={} reached the max floor-hold time on channel={}; floor released",
							session.id(), channel.name());
				}
				return;
			}
			channel.markFloorActivity(now);
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
		channel.setMode(mode);
		channel.clearFloor();
		// Broadcast to everyone (incl. the owner): the new mode and a floor reset, so any 'talking'
		// indicator is superseded and a fresh push-to-talk floor is available.
		ServerMessage modeChanged = new ServerMessage.ModeChanged(mode);
		ServerMessage floorIdle = new ServerMessage.FloorIdle();
		session.send(modeChanged);
		session.send(floorIdle);
		channel.forEachOther(session.id(), other -> {
			safeSend(other, modeChanged);
			safeSend(other, floorIdle);
		});
		log.info("session={} changed channel={} mode to {}", session.id(), channel.name(), mode);
	}
}
