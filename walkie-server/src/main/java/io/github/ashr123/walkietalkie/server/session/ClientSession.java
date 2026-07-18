package io.github.ashr123.walkietalkie.server.session;

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

	/// Enqueues an already-serialized control message (its JSON wire form) as a text frame. All control goes out
	/// through [io.github.ashr123.walkietalkie.server.transport.MessageBroadcaster], which owns the codec and
	/// encodes ONCE (so a channel fan-out costs one encode, not one per recipient); this session is a dumb sink
	/// that never touches the wire format itself.
	void sendEncoded(String encoded);

	/// Sends a raw audio frame as a binary frame.
	void sendAudio(byte[] audio);

	/// Releases per-session outbound resources (the async send pump) on disconnect. A no-op for in-memory
	/// fakes, which send synchronously.
	default void close() {
	}
}
