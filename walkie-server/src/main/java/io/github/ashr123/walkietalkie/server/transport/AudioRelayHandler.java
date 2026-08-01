package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;


/// Transport where the server relays raw audio: clients stream PCM audio as binary frames and the
/// server fans each frame out to the other members of the channel (`/ws/audio`).
@Component
public class AudioRelayHandler extends BaseWalkieHandler {
	private static final Logger log = LoggerFactory.getLogger(AudioRelayHandler.class);

	public AudioRelayHandler(ConnectionService connectionService, MessageCodec codec, WalkieProperties properties) {
		super(connectionService, codec, Transport.AUDIO_RELAY, properties);
	}

	@Override
	protected void handleBinaryMessage(@NonNull WebSocketSession session, @NonNull BinaryMessage message) {
		ClientSession clientSession = lookup(session);
		if (clientSession == null) {
			return;
		}
		// Hand the payload over as-is: ConnectionService copies it (once, into the prefixed frame) only after the
		// frame has passed every gate, so a dropped frame costs no copy at all. Safe because that copy happens
		// synchronously inside this call, which is the contract to keep — Tomcat does give us a private per-message
		// buffer, but the Jakarta API promises nothing about how long the argument stays valid, so nothing here may
		// outlive the call.
		connectionService.onAudio(clientSession, message.getPayload());
	}

	@Override
	protected Logger getLogger() {
		return log;
	}
}
