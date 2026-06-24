package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Some;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AuthServiceTest {

	private final AuthService authService = new AuthService();

	@Test
	void issuedTokenResolvesUntilRevoked() {
		UUID token = authService.issueToken("alice");
		// The token round-trips through a string, exactly as it does over HTTP.
		assertEquals(new Some<>("alice"), authService.resolve(token.toString()), "a freshly issued token resolves to its user");

		authService.revoke(token.toString());
		assertInstanceOf(None.class, authService.resolve(token.toString()), "a revoked token no longer resolves");
	}

	@Test
	void aMalformedTokenIsRejectedCleanly() {
		// A value that is not a well-formed UUID must not blow up the parse: it resolves to None and a
		// revoke of it is a harmless no-op (the trust boundary turns bad input into a clean rejection).
		assertInstanceOf(None.class, authService.resolve("not-a-uuid"), "garbage never resolves");
		authService.revoke("not-a-uuid");
	}
}
