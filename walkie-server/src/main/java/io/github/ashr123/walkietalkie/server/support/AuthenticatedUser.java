package io.github.ashr123.walkietalkie.server.support;

/// The authenticated connection's identity, carried via a [RequestContext] scoped value for the duration of a
/// single inbound message: the per-connection WebSocket session id (the routing identity) plus the current
/// `displayName` (the human-friendly label, blank until the client has joined a channel) — both surfaced on
/// the log lines emitted while handling that message.
public record AuthenticatedUser(String sessionId, String displayName) {
}
