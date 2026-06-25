package io.github.ashr123.walkietalkie.shared.protocol;

/// Response body of `POST /api/auth/login`. The client presents the [#token] as
/// `Authorization: Bearer <token>` (or a `?token=` query param on the WebSocket handshake, since a
/// browser cannot set headers there). The token is an opaque, server-signed string: the server verifies
/// it cryptographically at the handshake and keeps no record of it (stateless auth), so there is nothing
/// to look up or revoke — closing the socket ends the session. Shared so the server's controller and the
/// Java client agree on the shape by construction rather than by two parallel definitions.
///
/// @param token the ephemeral, signed bearer token
public record LoginResponse(String token) {
}
