package io.github.ashr123.walkietalkie.shared.protocol;

/// Which media plane a session — and therefore its channel — uses.
///
/// It lives in the shared protocol rather than in the server's session package because it travels on the wire:
/// [ClientMessage.ChangeTransport] and [ServerMessage.TransportChanged] name it, so a channel can move between
/// media planes without anyone reconnecting.
///
/// It is a property of the SESSION, seeded by which endpoint was dialled (`/ws/audio` or `/ws/signal`) and
/// mutable thereafter — the two endpoints speak the identical control protocol, so nothing about a live socket
/// needs to change when the media plane does. And it is a property of the CHANNEL, because the two planes never
/// meet: the relay fan-out skips a signaling member and a signaling sender's frames are dropped on arrival, so a
/// channel carrying both would be a full roster with working floor control and no audio at all.
public enum Transport {

	/// Server relays raw audio frames between members (WebSocket binary).
	AUDIO_RELAY,

	/// Server relays only WebRTC signaling; the media itself flows peer-to-peer.
	SIGNALING
}
