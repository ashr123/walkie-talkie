package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.security.AuthService;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;

/// Transport where the server relays raw audio: clients stream PCM audio as binary frames and the
/// server fans each frame out to the other members of the channel (`/ws/audio`).
@Component
public class AudioRelayHandler extends BaseWalkieHandler {

	public AudioRelayHandler(ConnectionService connectionService, MessageCodec codec, AuthService authService) {
		super(connectionService, codec, authService, Transport.AUDIO_RELAY);
	}

	@Override
	protected void handleBinaryMessage(@NonNull WebSocketSession session, @NonNull BinaryMessage message) {
		ClientSession clientSession = lookup(session);
		if (clientSession == null) {
			return;
		}
		ByteBuffer payload = message.getPayload();
		byte[] audio = new byte[payload.remaining()];
		payload.get(audio);
		connectionService.onAudio(clientSession, audio);
	}
}
