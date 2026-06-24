package io.github.ashr123.walkietalkie.server.floor;

/// Outcome of a floor (push-to-talk) request, consumed with pattern-matching `switch`.
public sealed interface FloorResult {

	/// The requester may now transmit.
	record Granted() implements FloorResult {
	}

	/// The floor is held by someone else.
	record Denied(String currentHolderId) implements FloorResult {
	}
}
