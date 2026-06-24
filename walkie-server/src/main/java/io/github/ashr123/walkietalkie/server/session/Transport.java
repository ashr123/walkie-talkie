package io.github.ashr123.walkietalkie.server.session;

/// Which communication mode a connection uses.
public enum Transport {

	/// Server relays raw audio frames between members (WebSocket binary).
	AUDIO_RELAY,

	/// Server relays only WebRTC signaling; the media itself flows peer-to-peer.
	SIGNALING
}
