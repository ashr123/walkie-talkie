package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Some;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// [BearerTokens] pulls the token from the `Authorization: Bearer` header (preferred) or, for browser
/// WebSocket handshakes that can't set headers, from the `?token=` query parameter.
class BearerTokensTest {

	@Test
	void extractsAndTrimsTheTokenFromABearerHeader() {
		assertEquals(new Some<>("abc"), BearerTokens.extract("Bearer  abc ", null));
	}

	@Test
	void aNonBearerHeaderFallsThroughToTheQueryParameter() {
		assertEquals(new Some<>("xyz"), BearerTokens.extract("Basic dXNlcjpwdw==", "xyz"));
	}

	@Test
	void usesTheQueryParameterWhenThereIsNoHeader() {
		assertEquals(new Some<>("xyz"), BearerTokens.extract(null, "  xyz  "));
	}

	@Test
	void noneWhenNeitherAUsableHeaderNorQueryParameterIsPresent() {
		assertInstanceOf(None.class, BearerTokens.extract(null, null));
		assertInstanceOf(None.class, BearerTokens.extract("Basic abc", "   "), "a non-bearer header + blank query is nothing");
	}
}
