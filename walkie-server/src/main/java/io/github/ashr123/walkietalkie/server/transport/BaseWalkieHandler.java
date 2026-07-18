package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.server.session.WebSocketClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
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
	private final Transport transport;

	protected BaseWalkieHandler(ConnectionService connectionService, MessageCodec codec, Transport transport) {
		this.connectionService = connectionService;
		this.codec = codec;
		this.transport = transport;
	}

	protected static ClientSession lookup(WebSocketSession session) {
		return (ClientSession) session.getAttributes().get(SESSION_KEY);
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
			getLogger().debug("Bad control message from {}: {}", clientSession.id(), e.getMessage());
			// Encode the parse-error reply with the handler's own codec (already held for decode above) — this is a
			// transport-boundary error for a message ConnectionService never saw, so it doesn't route via the broadcaster.
			clientSession.sendEncoded(codec.encode(new ServerMessage.ErrorMessage(ErrorCode.BAD_MESSAGE, "Could not parse control message")));
		}
	}

	@Override
	public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
		ClientSession clientSession = lookup(session);
		if (clientSession != null) {
			try {
				connectionService.onClose(clientSession, describeClose(status));
			} finally {
				clientSession.close();   // tear down the outbound pump even if onClose throws
			}
		}
		// The bearer token is stateless and self-expiring, so there is nothing to revoke here: closing the
		// WebSocket already ends the session (membership is dropped in onClose).
	}

	/// A short human description of WHY the socket closed, for the disconnect log line: a friendly label for the
	/// common WebSocket close codes (NORMAL = a clean/manual close; NO_CLOSE_FRAME = an abnormal drop with no
	/// close handshake, e.g. a network failure or a browser tab closed abruptly; POLICY_VIOLATION = a
	/// server-initiated kick such as the send-backlog termination), plus the close reason when one was given
	/// (server-initiated closes carry one, e.g. "send backlog"). Uses the [CloseStatus] named codes, not magic
	/// numbers.
	private static String describeClose(CloseStatus status) {
		// Match on the close CODE only, via CloseStatus.equalsCode, not on the whole status: a real close carries
		// a reason ("bye", "send backlog", …) while the named constants don't, so equals() — which compares code
		// AND reason — would miss them. The reason is appended separately below.
		String label;
		if (status.equalsCode(CloseStatus.NORMAL)) {
			label = "normal close";
		} else if (status.equalsCode(CloseStatus.NO_CLOSE_FRAME)) {
			label = "abnormal close — no close frame (network drop or client closed abruptly)";
		} else if (status.equalsCode(CloseStatus.GOING_AWAY)) {
			label = "going away";
		} else if (status.equalsCode(CloseStatus.POLICY_VIOLATION)) {
			label = "policy violation";
		} else if (status.equalsCode(CloseStatus.SERVER_ERROR)) {
			label = "server error";
		} else {
			label = "close code " + status.getCode();
		}
		String reason = status.getReason();
		return reason == null || reason.isBlank() ? label : label + " — " + reason;
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		getLogger().debug("Transport error on session {}: {}", session.getId(), exception.toString());
		// A transport error may precede afterConnectionClosed; close the pump now (idempotent) so it can't leak.
		ClientSession clientSession = lookup(session);
		if (clientSession != null) {
			clientSession.close();
		}
	}

	private static void closeUnauthenticated(WebSocketSession session) {
		try {
			session.close(CloseStatus.POLICY_VIOLATION.withReason("unauthenticated"));
		} catch (Exception _) {
			// best-effort
		}
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		Principal principal = session.getPrincipal();
		if (principal == null) {
			closeUnauthenticated(session);
			return;
		}
		WebSocketClientSession clientSession = new WebSocketClientSession(
				new ConcurrentWebSocketSessionDecorator(
						session,
						SEND_TIME_LIMIT_MS,
						SEND_BUFFER_LIMIT_BYTES
				),
				transport,
				(String) session.getAttributes().get(ChannelHandshakeInterceptor.HANDSHAKE_CHANNEL_ATTR)
		);
		session.getAttributes().put(SESSION_KEY, clientSession);
		// Start the outbound pump only after the session is registered, so afterConnectionClosed can always
		// find and close it (no construct-before-register leak window).
		clientSession.start();
		ConnectionService.onConnect(clientSession);
	}

	protected abstract Logger getLogger();
}
