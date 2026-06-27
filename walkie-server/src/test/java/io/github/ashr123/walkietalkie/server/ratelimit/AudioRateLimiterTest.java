package io.github.ashr123.walkietalkie.server.ratelimit;

import io.github.ashr123.walkietalkie.server.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the per-sender flood guard with a hand-driven clock: bursts are bounded, refill resumes over
/// time, each sender has an independent bucket, and a forgotten (disconnected) sender resets cleanly.
class AudioRateLimiterTest {

	@Test
	void admitsUpToTheBurstThenDropsUntilRefill() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		AudioRateLimiter limiter = new AudioRateLimiter(10, clock);   // 10 fps -> burst 10, refill 10/s
		for (int i = 0; i < 10; i++) {
			assertTrue(limiter.tryAcquire("a"), "the first second's worth bursts through");
		}
		assertFalse(limiter.tryAcquire("a"), "the over-rate frame is dropped");
		clock.advance(Duration.ofSeconds(1));                        // one second later -> 10 tokens back
		assertTrue(limiter.tryAcquire("a"));
	}

	@Test
	void bucketsArePerSession() {
		AudioRateLimiter limiter = new AudioRateLimiter(2, new MutableClock(Instant.EPOCH));
		assertTrue(limiter.tryAcquire("a"));
		assertTrue(limiter.tryAcquire("a"));
		assertFalse(limiter.tryAcquire("a"), "'a' is over its own limit");
		assertTrue(limiter.tryAcquire("b"), "'b' has its own independent bucket");
		assertTrue(limiter.tryAcquire("b"));
		assertFalse(limiter.tryAcquire("b"));
	}

	@Test
	void forgetResetsASenderBucket() {
		AudioRateLimiter limiter = new AudioRateLimiter(1, new MutableClock(Instant.EPOCH));
		assertTrue(limiter.tryAcquire("a"));
		assertFalse(limiter.tryAcquire("a"));
		limiter.forget("a");
		assertTrue(limiter.tryAcquire("a"), "a reconnecting session id starts from a fresh, full bucket");
	}
}
