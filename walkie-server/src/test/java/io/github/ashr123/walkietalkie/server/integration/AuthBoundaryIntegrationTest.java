package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;

/// The authentication boundary: which endpoints are public, the two credential transports the filter
/// accepts (`Authorization: Bearer` header and `?token=` query parameter), and that missing/garbage tokens
/// are rejected as client errors at the handshake — never reaching the WebSocket handler and never a 500.
class AuthBoundaryIntegrationTest extends WebSocketIntegrationTestSupport {

	private static boolean isClientError(int status) {
		return status >= 400 && status < 500;
	}

	@Test
	void loginMintsDistinctVerifiableTokens() throws Exception {
		String first = login();
		String second = login();
		assertFalse(first.isBlank(), "the minted token must not be blank");
		assertFalse(second.isBlank(), "the minted token must not be blank");
		assertNotEquals(first, second, "each login mints a fresh token (random nonce per token)");
	}

	@Test
	void getOnTheLoginEndpointIsNotPublic() throws Exception {
		// Only POST /api/auth/login is permitted; a GET falls through to anyRequest().authenticated().
		int status = httpStatus("/api/auth/login", null);
		assertTrue(isClientError(status), "GET on the POST-only login endpoint must be a client error, was " + status);
		assertNotEquals(200, status);
	}

	@Test
	void missingTokenAtTheAudioHandshakeIsAClientErrorNotAServerError() throws Exception {
		int status = httpStatus(AUDIO, null);
		assertTrue(isClientError(status), "an unauthenticated /ws/audio request must be a client error, was " + status);
		assertNotEquals(500, status, "a missing token must not surface as a server error");
		assertNotEquals(101, status, "the request must never complete the WebSocket upgrade");
	}

	@Test
	void theSignalingHandshakeAlsoRequiresAuth() throws Exception {
		int signal = httpStatus(SIGNAL, null);
		assertTrue(isClientError(signal), "an unauthenticated /ws/signal request must be a client error, was " + signal);
		assertEquals(httpStatus(AUDIO, null), signal, "both transports gate the handshake identically");
	}

	@Test
	void emptyAndBlankQueryTokensAreTreatedAsMissing() throws Exception {
		int missing = httpStatus(AUDIO, null);
		int empty = httpStatus(AUDIO + "?token=", null);
		int blank = httpStatus(AUDIO + "?token=%20%20", null);
		assertEquals(missing, empty, "an empty ?token= is treated exactly like no token");
		assertEquals(missing, blank, "a whitespace-only ?token= is treated exactly like no token");
	}

	@Test
	void aValidBearerHeaderIsAcceptedAtTheBoundary() throws Exception {
		// Exercises the Authorization: Bearer branch of BearerTokens. A valid token authenticates the request,
		// so the outcome differs from the unauthenticated 403 (the now-authenticated GET reaches the handshake
		// machinery, which rejects the non-upgrade request with a different status) — and is never a 500.
		int missing = httpStatus(AUDIO, null);
		int withValidHeader = httpStatus(AUDIO, "Bearer " + login());
		assertNotEquals(missing, withValidHeader, "a valid bearer header must change the outcome from unauthenticated");
		assertNotEquals(500, withValidHeader, "an accepted token must not surface as a server error");
	}

	@Test
	void aValidQueryTokenOpensAUsableAudioSession() throws Exception {
		CollectingHandler handler = new CollectingHandler();
		WebSocketSession session = connect(AUDIO, handler, login());
		try {
			send(session, new ClientMessage.Join("auth-usable", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.Joined joined = awaitType(handler.messages, ServerMessage.Joined.class);
			assertEquals("auth-usable", joined.channel(), "the authenticated session can join and is fully usable");
		} finally {
			session.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void aGarbageQueryTokenFailsTheRealWebSocketHandshake() {
		// The handshake is rejected before the handler, so execute().get() completes exceptionally.
		assertThrows(Exception.class, () -> connect(AUDIO, new CollectingHandler(), "not-a-real-token"),
				"a garbage token must fail the WebSocket handshake");
	}

	@Test
	void staticAssetsAndActuatorHealthArePublic() throws Exception {
		assertEquals(200, httpStatus("/", null), "the index page is public");
		assertEquals(200, httpStatus("/index.html", null), "the index page is public");
		assertEquals(200, httpStatus("/assets/app.js", null), "client assets are public");
		assertEquals(200, httpStatus("/actuator/health", null), "the health check is public");
	}

	@Test
	void anUnknownProtectedApiPathStillRequiresAuth() throws Exception {
		// Security is evaluated before request mapping, so anyRequest().authenticated() gates even a path that
		// has no handler: the response is the auth client error, not a 404 leaking the absence of a mapping.
		int status = httpStatus("/api/secret", null);
		assertTrue(isClientError(status), "a protected path must require auth, was " + status);
		assertNotEquals(200, status);
		assertNotEquals(500, status);
	}
}
