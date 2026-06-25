package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.floor.FloorControlService;
import io.github.ashr123.walkietalkie.server.floor.FloorResult;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.support.RequestContext;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

	private final ChannelRegistry channelRegistry;
	private final FloorControlService floorControl;
	private final WalkieProperties properties;

	public ConnectionService(ChannelRegistry channelRegistry,
	                         FloorControlService floorControl,
	                         WalkieProperties properties) {
		this.channelRegistry = channelRegistry;
		this.floorControl = floorControl;
		this.properties = properties;
	}

	public void onConnect(ClientSession session) {
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

	private void handleJoin(ClientSession session, ClientMessage.Join join) {
		if (session.channelName() != null) {
			handleLeave(session);
		}

		ChannelMode mode = join.mode();
		String requested = mode == ChannelMode.GLOBAL_PTT ? GLOBAL_CHANNEL : join.channel();
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
		session.setDisplayName(join.displayName());

		Channel channel = channelRegistry.joinOrCreate(requested, mode, join.keyCheck(), session);
		if (channel == null) {
			session.send(new ServerMessage.ErrorMessage("passphrase_mismatch",
					"This channel is using a different encryption passphrase (or none) — you can't join it."));
			return;
		}

		session.joinedChannel(channel.name());
		session.send(new ServerMessage.Joined(
				session.id(), channel.name(), channel.mode(), channel.ownerId(), channel.memberInfos()));

		ServerMessage.MemberJoined notice = new ServerMessage.MemberJoined(session.toMemberInfo());
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
			ServerMessage.MemberLeft left = new ServerMessage.MemberLeft(session.id());
			channel.forEachOther(session.id(), other -> safeSend(other, left));
			if (wasHolding) {
				ServerMessage.FloorIdle idle = new ServerMessage.FloorIdle();
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
		switch (floorControl.requestFloor(channel, session)) {
			case FloorResult.Granted _ -> {
				session.send(new ServerMessage.FloorGranted());
				ServerMessage.FloorTaken taken = new ServerMessage.FloorTaken(session.id());
				channel.forEachOther(session.id(), other -> safeSend(other, taken));
			}
			case FloorResult.Denied(String currentHolderId) ->
					session.send(new ServerMessage.FloorDenied(currentHolderId));
		}
	}

	private void handleReleaseFloor(ClientSession session) {
		Channel channel = requireChannel(session);
		if (channel == null || channel.mode() == ChannelMode.FULL_DUPLEX) {
			return;
		}
		if (floorControl.releaseFloor(channel, session)) {
			ServerMessage.FloorIdle idle = new ServerMessage.FloorIdle();
			channel.forEachOther(session.id(), other -> safeSend(other, idle));
		}
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
		ServerMessage.ModeChanged modeChanged = new ServerMessage.ModeChanged(mode);
		ServerMessage.FloorIdle floorIdle = new ServerMessage.FloorIdle();
		session.send(modeChanged);
		session.send(floorIdle);
		channel.forEachOther(session.id(), other -> {
			safeSend(other, modeChanged);
			safeSend(other, floorIdle);
		});
		log.info("session={} changed channel={} mode to {}", session.id(), channel.name(), mode);
	}

	/// Relays a raw audio frame to the other relay-capable members of the sender's channel. The frame
	/// is dropped when the sender is not currently authorized to talk (push-to-talk floor not held) or
	/// when it violates the configured size bounds.
	public void onAudio(ClientSession session, byte[] audio) {
		if (!session.supportsAudioRelay() ||
				audio.length == 0 ||
				audio.length > properties.maxAudioFrameBytes()) {
			return;
		}
		String channelName = session.channelName();
		if (channelName == null) {
			return;
		}
		if (!(channelRegistry.find(channelName) instanceof Some(Channel channel))
				|| !channel.holdsFloor(session.id())) {
			return;
		}
		channel.forEachOther(session.id(), other -> {
			if (other.supportsAudioRelay()) {
				try {
					other.sendAudio(audio);
				} catch (RuntimeException e) {
					log.debug("Audio relay to {} failed: {}", other.id(), e.getMessage());
				}
			}
		});
	}

	public void onClose(ClientSession session) {
		handleLeave(session);
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

	private void safeSend(ClientSession session, ServerMessage message) {
		try {
			session.send(message);
		} catch (RuntimeException e) {
			log.debug("Control send to {} failed: {}", session.id(), e.getMessage());
		}
	}
}
