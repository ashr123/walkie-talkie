package io.github.ashr123.walkietalkie.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the token-bucket refill/consume math with hand-supplied instants (no real time), so the rate-limit
/// behavior is deterministic. Integer token accrual via Duration division means no float boundaries to dodge.
class TokenBucketTest {

	private static final Instant T0 = Instant.EPOCH;
	private static final Duration TEN_MS = Duration.ofMillis(10);   // 1 token / 10 ms = 100 tokens/s

	@Test
	void startsFullAndAdmitsUpToCapacity() {
		TokenBucket bucket = new TokenBucket(3, TEN_MS, T0);
		assertTrue(bucket.tryConsume(T0));
		assertTrue(bucket.tryConsume(T0));
		assertTrue(bucket.tryConsume(T0));
		assertFalse(bucket.tryConsume(T0), "a fourth immediate consume exceeds the capacity of 3");
	}

	@Test
	void refillsProportionallyToElapsedTime() {
		TokenBucket bucket = new TokenBucket(5, TEN_MS, T0);
		for (int i = 0; i < 5; i++) {
			assertTrue(bucket.tryConsume(T0));
		}
		assertFalse(bucket.tryConsume(T0), "bucket drained");
		// 25 ms later -> exactly two whole tokens (25 ms / 10 ms): admit two, deny the third
		Instant later = T0.plusMillis(25);
		assertTrue(bucket.tryConsume(later));
		assertTrue(bucket.tryConsume(later));
		assertFalse(bucket.tryConsume(later), "only two whole tokens accrued in 25 ms");
	}

	@Test
	void doesNotAccrueBeyondCapacity() {
		TokenBucket bucket = new TokenBucket(5, TEN_MS, T0);
		for (int i = 0; i < 5; i++) {
			assertTrue(bucket.tryConsume(T0));
		}
		Instant anHourLater = T0.plus(Duration.ofHours(1));
		for (int i = 0; i < 5; i++) {
			assertTrue(bucket.tryConsume(anHourLater), "may burst up to capacity after a long idle");
		}
		assertFalse(bucket.tryConsume(anHourLater), "accrual is capped at capacity, not unbounded");
	}
}
