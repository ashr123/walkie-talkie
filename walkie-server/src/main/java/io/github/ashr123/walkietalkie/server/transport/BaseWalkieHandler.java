package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.server.session.WebSocketClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.security.Principal;

/// Shared connect/text/close plumbing for both transports. Subclasses only differ in their
/// [Transport] kind and in whether they consume binary (audio) frames.
public abstract class BaseWalkieHandler extends AbstractWebSocketHandler {

	protected static final String SESSION_KEY = "walkie.session";
	private static final int SEND_TIME_LIMIT_MS = 10_000;
	private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
	protected final ConnectionService connectionService;
	protected final MessageCodec codec;
	private final Logger log = LoggerFactory.getLogger(getClass());
	private final Transport transport;

	protected BaseWalkieHandler(ConnectionService connectionService, MessageCodec codec, Transport transport) {
		this.connectionService = connectionService;
		this.codec = codec;
		this.transport = transport;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		Principal principal = session.getPrincipal();
		if (principal == null) {
			closeUnauthenticated(session);
			return;
		}
		ClientSession clientSession = new WebSocketClientSession(
				new ConcurrentWebSocketSessionDecorator(
						session,
						SEND_TIME_LIMIT_MS,
						SEND_BUFFER_LIMIT_BYTES
				),
				codec,
				transport
		);
		session.getAttributes().put(SESSION_KEY, clientSession);
		connectionService.onConnect(clientSession);
	}

	@Override
	protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
		ClientSession clientSession = lookup(session);
		if (clientSession == null) {
			return;
		}
		try {
			connectionService.onMessage(clientSession, codec.decode(message.getPayload()));
		} catch (RuntimeException e) {
			log.debug("Bad control message from {}: {}", clientSession.id(), e.getMessage());
			clientSession.send(new ServerMessage.ErrorMessage("bad_message", "Could not parse control message"));
		}
	}

	@Override
	public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
		ClientSession clientSession = lookup(session);
		if (clientSession != null) {
			connectionService.onClose(clientSession);
		}
		// The bearer token is stateless and self-expiring, so there is nothing to revoke here: closing the
		// WebSocket already ends the session (membership is dropped in onClose).
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.debug("Transport error on session {}: {}", session.getId(), exception.toString());
	}

	protected ClientSession lookup(WebSocketSession session) {
		return (ClientSession) session.getAttributes().get(SESSION_KEY);
	}

	private void closeUnauthenticated(WebSocketSession session) {
		try {
			session.close(CloseStatus.POLICY_VIOLATION.withReason("unauthenticated"));
		} catch (Exception _) {
			// best-effort
		}
	}
}
