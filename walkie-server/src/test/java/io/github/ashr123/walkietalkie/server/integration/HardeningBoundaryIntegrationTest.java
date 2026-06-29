package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Boundary-hardening over real sockets, with a **restricted** `Origin` and a **small** inbound text cap (set
/// here, unlike the wildcard/large defaults the other integration tests use): a cross-origin handshake is
/// rejected (the anti-CSWSH control), and an over-cap control frame is refused at the transport.
@TestPropertySource(properties = {
		"walkie.allowed-origins=https://walkie.example",
		"walkie.max-text-message-bytes=256"
})
class HardeningBoundaryIntegrationTest extends WebSocketIntegrationTestSupport {

	private static final String ALLOWED_ORIGIN = "https://walkie.example";

	private static WebSocketHttpHeaders origin(String value) {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.setOrigin(value);
		return headers;
	}

	@Test
	void aHandshakeFromADisallowedOriginIsRejected() throws Exception {
		String token = login();
		// Even with a perfectly valid token, a handshake whose Origin isn't in walkie.allowed-origins must fail —
		// this is the sole anti-CSWSH (cross-site WebSocket hijacking) control. The failed upgrade surfaces as the
		// connect future completing exceptionally.
		assertThrows(ExecutionException.class,
				() -> connect(AUDIO, new CollectingHandler(), token, origin("https://evil.example")),
				"a handshake from a disallowed Origin must be rejected at the upgrade");
	}

	@Test
	void aHandshakeFromTheAllowedOriginIsAccepted() throws Exception {
		try (WebSocketSession session = connect(AUDIO, new CollectingHandler(), login(), origin(ALLOWED_ORIGIN))) {
			assertTrue(session.isOpen(), "a handshake from the configured Origin is accepted");
		}
	}

	@Test
	void anOverCapControlFrameClosesTheSession() throws Exception {
		CollectingHandler handler = new CollectingHandler();
		try (WebSocketSession session = connect(AUDIO, handler, login(), origin(ALLOWED_ORIGIN))) {
			// A normal (small) control frame works under the cap.
			send(session, new ClientMessage.Join("cap", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			awaitType(handler.messages, ServerMessage.Joined.class);

			// A text frame past walkie.max-text-message-bytes (256 here) is refused by the transport, which closes
			// the session rather than buffering an unbounded message.
			sendRaw(session, "x".repeat(1024));
			assertTrue(awaitClosed(session), "an over-cap control frame closes the session");
		}
	}
}
