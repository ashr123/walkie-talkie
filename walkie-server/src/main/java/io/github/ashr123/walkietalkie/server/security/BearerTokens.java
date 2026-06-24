package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Option;

/// Pulls the bearer token out of a request: the `Authorization: Bearer <token>` header, or a
/// `token` query parameter (browsers cannot set headers on a WebSocket handshake).
final class BearerTokens {

	private static final String BEARER_PREFIX = "Bearer ";

	private BearerTokens() {
	}

	static Option<String> extract(String authorizationHeader, String tokenParameter) {
		return authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)
				? Option.of(authorizationHeader.substring(BEARER_PREFIX.length()).trim())
				: tokenParameter != null && !tokenParameter.isBlank()
				  ? Option.of(tokenParameter.trim())
				  : None.instance();
	}
}
