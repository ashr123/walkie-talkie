package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Minimal, in-memory token store for the demo. A successful login mints a random, ephemeral bearer
/// token — a [UUID] — mapped to a username; no secret is ever hard-coded or persisted.
///
/// The token is a `UUID` *internally* — the store's key and [#issueToken]'s return type — but is an
/// **opaque string** everywhere outside this class: on the wire it travels as a JSON string and comes
/// back in an `Authorization: Bearer` header or `?token=` query parameter, which are string-native.
/// [#resolve] and [#revoke] therefore accept the raw presented value and parse it here, at the trust
/// boundary, so a malformed token is rejected cleanly (yields [None] / a no-op) instead of crashing the
/// request.
///
/// In production this would be replaced by validation against a real identity provider (e.g. OIDC/JWT).
/// The read path ([#resolve]), and so the [TokenAuthenticationFilter] wiring, would carry over unchanged.
/// Revocation ([#revoke] — used by logout and on disconnect) is a property of *this* in-memory store,
/// though: a stateless JWT is not revoked by forgetting a map entry, so that path would then need an
/// explicit denylist/expiry strategy.
@Service
public class AuthService {

	private final Map<UUID, String> tokenToUser = new ConcurrentHashMap<>();

	public UUID issueToken(String username) {
		UUID token = UUID.randomUUID();
		tokenToUser.put(token, username);
		return token;
	}

	/// Resolves the user behind a presented token. The value arrives as the raw header/query string, so
	/// anything that is not a well-formed [UUID] (or is unknown/revoked) yields [None] rather than an error.
	public Option<String> resolve(String token) {
		return parse(token)
				.map(tokenToUser::get);
	}

	public void revoke(String token) {
		if (parse(token) instanceof Some(UUID id))
			tokenToUser.remove(id);
	}

	/// Parses a presented token into a [UUID], or [None] when it is not a well-formed one. [UUID#fromString]
	/// throws on malformed input, which at this trust boundary must become a clean rejection, not a 500.
	private static Option<UUID> parse(String token) {
		try {
			return Option.of(UUID.fromString(token));
		} catch (IllegalArgumentException _) {
			return None.instance();
		}
	}
}
