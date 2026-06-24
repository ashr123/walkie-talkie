package io.github.ashr123.walkietalkie.server.session;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;

/// A connected participant, abstracted away from the underlying WebSocket so that the channel and
/// connection logic can be unit-tested with simple fakes.
public interface ClientSession {

	String id();

	String userId();

	Transport transport();

	String displayName();

	void setDisplayName(String displayName);

	/// The channel currently joined, or `null` if not in a channel.
	String channelName();

	/// The mode of the channel currently joined, or `null` if not in a channel.
	ChannelMode channelMode();

	void joinedChannel(String channel, ChannelMode mode);

	void leftChannel();

	boolean supportsAudioRelay();

	/// Sends a control/signaling message as a JSON text frame.
	void send(ServerMessage message);

	/// Sends a raw audio frame as a binary frame.
	void sendAudio(byte[] audio);

	void close(String reason);

	default MemberInfo toMemberInfo() {
		return new MemberInfo(id(), displayName());
	}
}
