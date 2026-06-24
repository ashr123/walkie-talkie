package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.option.Option;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Minimal, in-memory token store for the demo. A successful login mints a random, ephemeral bearer
/// token mapped to a username — no secret is ever hard-coded or persisted.
///
/// In production this would be replaced by validation against a real identity provider
/// (e.g. OIDC/JWT). The [TokenAuthenticationFilter] wiring would stay the same.
@Service
public class AuthService {

	private final Map<String, String> tokenToUser = new ConcurrentHashMap<>();

	public String issueToken(String username) {
		String token = UUID.randomUUID().toString();
		tokenToUser.put(token, username);
		return token;
	}

	public Option<String> resolve(String token) {
		return Option.of(tokenToUser.get(token));
	}

	public void revoke(String token) {
		tokenToUser.remove(token);
	}
}
