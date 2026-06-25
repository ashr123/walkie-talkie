package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/// The handler's defensive plumbing, driven with a mocked [WebSocketSession]: an unauthenticated handshake
/// is closed (and a failure while closing is swallowed), and frames/closures that arrive without a bound
/// [io.github.ashr123.walkietalkie.server.session.ClientSession] are ignored rather than throwing. These
/// branches sit before the [ConnectionService] is ever reached, so they can't be exercised over a real,
/// security-authenticated socket — hence the direct unit test.
class BaseWalkieHandlerTest {

	private final ConnectionService connectionService = mock(ConnectionService.class);
	private final AudioRelayHandler handler = new AudioRelayHandler(connectionService, mock(MessageCodec.class));

	@Test
	void anUnauthenticatedHandshakeIsClosedWithAPolicyViolationAndNotRegistered() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getPrincipal()).thenReturn(null);

		handler.afterConnectionEstablished(session);

		verify(session).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
		verify(connectionService, never()).onConnect(any());
	}

	@Test
	void aFailureWhileClosingAnUnauthenticatedSessionIsSwallowed() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getPrincipal()).thenReturn(null);
		doThrow(new IOException("close failed")).when(session).close(any(CloseStatus.class));

		assertDoesNotThrow(() -> handler.afterConnectionEstablished(session));
		verify(connectionService, never()).onConnect(any());
	}

	@Test
	void aTextFrameWithNoBoundSessionIsIgnored() {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getAttributes()).thenReturn(new HashMap<>());   // no SESSION_KEY -> lookup returns null

		assertDoesNotThrow(() -> handler.handleTextMessage(session, new TextMessage("{}")));
		verify(connectionService, never()).onMessage(any(), any());
	}

	@Test
	void aBinaryFrameWithNoBoundSessionIsIgnored() {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getAttributes()).thenReturn(new HashMap<>());

		assertDoesNotThrow(() -> handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{1, 2, 3})));
		verify(connectionService, never()).onAudio(any(), any());
	}

	@Test
	void aCloseWithNoBoundSessionIsIgnored() {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getAttributes()).thenReturn(new HashMap<>());

		assertDoesNotThrow(() -> handler.afterConnectionClosed(session, CloseStatus.NORMAL));
		verify(connectionService, never()).onClose(any());
	}

	@Test
	void aTransportErrorIsLoggedAndSwallowed() {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getId()).thenReturn("sess-1");

		assertDoesNotThrow(() -> handler.handleTransportError(session, new RuntimeException("boom")));
	}
}
