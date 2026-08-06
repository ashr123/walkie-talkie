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

	/// The name of the LOCKED channel this session is waiting to be admitted to, or `null` if it is not waiting
	/// anywhere. Distinct from [#channelName]: a session waiting at one channel's door may still be a full member
	/// of another, and waiting alone makes it a member of nothing.
	///
	/// Single-valued on purpose. It enforces "at most one outstanding request per session" (knocking elsewhere
	/// withdraws the first), and — the reason it exists at all — it lets the disconnect path find and scrub the
	/// request in O(1). Without it a waiting session's departure would leak its entry: teardown reconciles by
	/// [#channelName], which is exactly what a waiting session does NOT have.
	String pendingChannel();

	/// Records that this session is waiting to be admitted to `channel`.
	void pendingIn(String channel);

	/// Clears the waiting marker — the request was admitted, denied, withdrawn, or its channel disappeared.
	void pendingCleared();

	boolean supportsAudioRelay();

	/// Whether this session takes part in WebRTC signaling — the counterpart of [#supportsAudioRelay].
	///
	/// It exists to close an asymmetry: the AUDIO path is transport-gated in both directions (a signaling session's
	/// frames are dropped on arrival, and the fan-out skips signaling members), while the signaling path was gated
	/// in NEITHER. A relay member could therefore be handed an `offer`, and the browser answered it and attached
	/// its microphone to a peer connection — media outside the floor, owner-mute and passphrase-E2EE gates that all
	/// live on the relay frame path. Both clients now refuse such a message; this is the server-side belt so a
	/// misbehaving or older client cannot be put in that position at all.
	///
	/// A `default` method rather than an abstract one: it is derivable from [#transport], so no implementation —
	/// including the test doubles — needs to answer it separately.
	default boolean supportsSignaling() {
		return transport() == Transport.SIGNALING;
	}

	/// Whether this session has been torn down (its socket gone). Unlike [#channelName] (which is also null before
	/// the first join), this is a lifecycle-wide signal, so a control-path caller can drop a late frame from an
	/// already-closed session before it resurrects per-session state (e.g. a [SessionRateLimiter] bucket after
	/// [io.github.ashr123.walkietalkie.server.transport.ConnectionService#onClose] forgot it). Phrased in the CLOSED
	/// sense because that is the sense every caller and both implementations hold — the guards read it directly and
	/// the implementations return their `closed` flag, so nobody negates anything. In-memory fakes send
	/// synchronously and are never torn down, so the default reports false.
	default boolean isClosed() {
		return false;
	}

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
