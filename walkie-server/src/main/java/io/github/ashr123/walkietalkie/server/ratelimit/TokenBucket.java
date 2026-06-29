package io.github.ashr123.walkietalkie.server.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.Temporal;

/// A classic token bucket: tokens accrue at a fixed rate up to a fixed capacity, and each admitted event
/// spends one. It is refilled lazily from an externally supplied [Instant] on each check, so it holds no timer
/// of its own and is trivial to test deterministically. [#tryConsume] is synchronized, so a single bucket may
/// be safely shared across the (virtual) threads that could handle one sender's frames.
final class TokenBucket {

	private final long capacity;
	private final Duration perToken;   // time to earn one token (the inverse of the refill rate)
	private long tokens;
	private Instant lastRefill;

	/// @param capacity maximum tokens held — the largest instantaneous burst allowed
	/// @param perToken how long it takes to earn one token (1 ÷ rate)
	/// @param now      the current instant; the bucket starts full as of this moment
	TokenBucket(long capacity, Duration perToken, Instant now) {
		this.capacity = capacity;
		this.perToken = perToken;
		this.tokens = capacity;
		this.lastRefill = now;
	}

	/// Adds the whole tokens earned since the last check, then spends one if available. Returns whether the
	/// event is admitted.
	synchronized boolean tryConsume(Temporal now) {
		long earned = Duration.between(lastRefill, now).dividedBy(perToken);
		if (earned > 0) {
			tokens = Math.min(capacity, tokens + earned);
			lastRefill = lastRefill.plus(perToken.multipliedBy(earned));   // carry the sub-token remainder forward
		}
		if (tokens >= 1) {
			tokens--;
			return true;
		}
		return false;
	}
}
