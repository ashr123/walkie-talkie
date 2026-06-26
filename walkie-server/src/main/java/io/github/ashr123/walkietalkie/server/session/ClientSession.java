package io.github.ashr123.walkietalkie.server.session;

import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;

/// A connected participant, abstracted away from the underlying WebSocket so that the channel and
/// connection logic can be unit-tested with simple fakes.
public interface ClientSession {

	String id();

	Transport transport();

	String displayName();

	void setDisplayName(String displayName);

	/// The channel currently joined, or `null` if not in a channel.
	String channelName();

	void joinedChannel(String channel);

	void leftChannel();

	boolean supportsAudioRelay();

	/// The relay-audio framing version this connection advertised at join: 0 = legacy (un-prefixed), 1 =
	/// SID-prefixed multi-stream. The server frames each recipient's outbound audio accordingly.
	int relayFraming();

	void setRelayFraming(int relayFraming);

	/// Sends a control/signaling message as a JSON text frame.
	void send(ServerMessage message);

	/// Sends a raw audio frame as a binary frame.
	void sendAudio(byte[] audio);

	/// Releases per-session outbound resources (the async send pump) on disconnect. A no-op for in-memory
	/// fakes, which send synchronously.
	default void close() {
	}
}
