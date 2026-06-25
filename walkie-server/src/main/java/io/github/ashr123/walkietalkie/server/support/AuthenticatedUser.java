package io.github.ashr123.walkietalkie.server.support;

/// The authenticated connection's identity — the per-connection WebSocket session id — carried via a
/// [RequestContext] scoped value for the duration of a single inbound message.
public record AuthenticatedUser(String sessionId) {
}
