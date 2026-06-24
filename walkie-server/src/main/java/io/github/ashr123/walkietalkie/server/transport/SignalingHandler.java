package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.session.Transport;
import org.springframework.stereotype.Component;

/// WebRTC signaling transport (`/ws/signal`). The server only relays join/leave, push-to-talk
/// floor coordination and SDP/ICE messages between peers; the audio itself flows peer-to-peer and
/// never reaches the server. Binary frames are \_ here (handled as a no-op by the base class).
@Component
public class SignalingHandler extends BaseWalkieHandler {

	public SignalingHandler(ConnectionService connectionService, MessageCodec codec) {
		super(connectionService, codec, Transport.SIGNALING);
	}
}
