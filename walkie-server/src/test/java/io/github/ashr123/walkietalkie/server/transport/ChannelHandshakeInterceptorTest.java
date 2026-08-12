package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Pins the handshake's routing key against the one value it has to equal: the canonical channel a `Join` carries.
///
/// The two are compared in `ConnectionService.handleJoin` under channel affinity, and a mismatch is answered with
/// `CHANNEL_ROUTING_MISMATCH` — which the client responds to by reconnecting. So a routing key that can never equal
/// its own Join is not a cosmetic defect: it is an endless reconnect for a channel whose name looks identical on both
/// sides. Everything here is that one equality, from both clients' encodings.
class ChannelHandshakeInterceptorTest {

	/// Only `channelAffinity` matters here; every other component is a value this interceptor never reads.
	private static WalkieProperties properties(boolean channelAffinity) {
		return new WalkieProperties(null, 0, 0, 0, 0, Duration.ofSeconds(-1), Duration.ofSeconds(-1),
				Duration.ZERO, false, 0, null, channelAffinity, Duration.ZERO);
	}

	private record Handshake(boolean allowed, Object routingKey) {
	}

	private static Handshake intercept(String queryString, boolean channelAffinity, MockHttpServletResponse response) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/audio");
		request.setQueryString(queryString);
		Map<String, Object> attributes = new HashMap<>();
		boolean allowed = new ChannelHandshakeInterceptor(properties(channelAffinity)).beforeHandshake(
				new ServletServerHttpRequest(request),
				new ServletServerHttpResponse(response),
				new TextWebSocketHandler(),
				attributes
		);
		return new Handshake(allowed, attributes.get(ChannelHandshakeInterceptor.HANDSHAKE_CHANNEL_ATTR));
	}

	private static Object routingKey(String queryString) {
		return intercept(queryString, false, new MockHttpServletResponse()).routingKey();
	}

	@Test
	void anEncodedNameArrivesAsTheNameItselfRatherThanAsItsEncoding() {
		// The defect this exists for: the query value was read still percent-encoded, so a Hebrew channel was stored
		// as "%D7%94%D7%97%D7%93%D7%A8" and compared against the text "החדר" — never equal, for any name that needs
		// encoding at all.
		assertEquals("החדר", routingKey("channel=%D7%94%D7%97%D7%93%D7%A8"));
		// The same name from each client, which encode a space differently: URLEncoder writes `+`, the browser's
		// encodeURIComponent writes `%20`. Both are that one channel.
		assertEquals("my room", routingKey("channel=my+room"));
		assertEquals("my room", routingKey("channel=my%20room"));
		// An ASCII name needs no encoding and must be untouched by any of this.
		assertEquals("team1", routingKey("channel=team1&token=irrelevant"));
	}

	@Test
	void itReducesTheNameExactlyAsAJoinIsReduced() {
		// Decoding alone is not enough: the value is compared against a CANONICALISED Join, so it goes through the
		// same function — whitespace collapse included. A smaller copy of that reduction is the same bug one step on.
		Object collapsed = routingKey("channel=my++room");
		assertEquals(ConnectionService.canonicalChannelName("my  room"), collapsed,
				"the handshake key and the Join must be reduced by the same function, not by two similar ones");
		assertEquals("my room", collapsed);
		// U+05E9 + U+05C1 (decomposed) against U+FB2A (the precomposed presentation form) — the exact pair
		// ConnectionService cites as its reason for normalising, since the two render identically.
		assertEquals(routingKey("channel=%D7%A9%D7%81%D7%9C%D7%95%D7%9D"),
				routingKey("channel=%EF%AC%AA%D7%9C%D7%95%D7%9D"),
				"the same word spelled two ways must not be two channels");
	}

	@Test
	void affinityRequiresARoutingKeyAndRefusesAHandshakeWithout() {
		MockHttpServletResponse refused = new MockHttpServletResponse();
		Handshake withAffinity = intercept(null, true, refused);
		assertFalse(withAffinity.allowed(), "a handshake with no routing key cannot have been routed to this instance");
		assertNull(withAffinity.routingKey());
		assertEquals(HttpStatus.BAD_REQUEST.value(), refused.getStatus());

		MockHttpServletResponse allowed = new MockHttpServletResponse();
		Handshake singleInstance = intercept(null, false, allowed);
		assertTrue(singleInstance.allowed(), "single instance: every channel is served here, so the key is optional");
		assertNull(singleInstance.routingKey());
		assertEquals(HttpStatus.OK.value(), allowed.getStatus());
	}
}
