package io.github.ashr123.walkietalkie.server.support;

/// The authenticated identity behind a connection, carried via a [RequestContext] scoped value
/// for the duration of a single inbound message.
public record AuthenticatedUser(String userId) {
}
