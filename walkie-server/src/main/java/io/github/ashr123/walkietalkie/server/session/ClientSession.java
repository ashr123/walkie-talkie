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

	/// The channel this socket was routed to at the WebSocket handshake (the `channel` query param), or `null`
	/// if none was supplied. Under multi-instance channel affinity it identifies the channel this instance was
	/// picked to serve for this connection; single-instance it is informational only. Unlike [#channelName] it is
	/// fixed for the connection's lifetime (a switch changes `channelName`, not this).
	String handshakeChannel();

	void joinedChannel(String channel);

	void leftChannel();

	boolean supportsAudioRelay();

	/// Sends a control/signaling message as a JSON text frame.
	void send(ServerMessage message);

	/// Sends a raw audio frame as a binary frame.
	void sendAudio(byte[] audio);

	/// Releases per-session outbound resources (the async send pump) on disconnect. A no-op for in-memory
	/// fakes, which send synchronously.
	default void close() {
	}
}
