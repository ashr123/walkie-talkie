package io.github.ashr123.walkietalkie.shared.protocol;

import java.util.UUID;

/// Response body of `POST /api/auth/login`. The client presents the [#token] as
/// `Authorization: Bearer <token>` (or a `?token=` query param on the WebSocket handshake, since a
/// browser cannot set headers there) and on `/api/auth/logout`. Jackson renders the [UUID] as a JSON
/// string on the wire, so the value is `.toString()`'d before it goes into a header or query parameter.
/// Shared so the server's controller and the Java client agree on the shape by construction rather than
/// by two parallel definitions.
///
/// @param userId the username the token was issued for
/// @param token  the ephemeral bearer token
public record LoginResponse(String userId, UUID token) {
}
