package io.github.ashr123.walkietalkie.server.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/// The compact constructor of [WalkieProperties] fills in safe defaults for absent or non-positive values
/// while leaving valid configuration untouched.
class WalkiePropertiesTest {

	@Test
	void appliesDefaultsForNullOrNonPositiveValues() {
		WalkieProperties p = new WalkieProperties(
				null,
				0,
				-1,
				0,
				0,
				-1,
				-1,
				10,
				false, 0,
				null,
				false, Duration.ZERO);
		assertArrayEquals(new String[]{"*"}, p.allowedOrigins(), "null origins default to the wildcard");
		assertEquals(8 * 1024, p.maxAudioFrameBytes(), "a non-positive audio size falls back to 8 KiB");
		assertEquals(16 * 1024, p.maxTextMessageBytes(), "a non-positive text size falls back to 16 KiB");
		assertEquals(100, p.maxAudioFramesPerSecond(), "a non-positive frame rate falls back to 100 fps");
		assertEquals(200, p.maxControlMessagesPerSecond(), "a non-positive control rate falls back to 200 msg/s");
		assertEquals(5, p.floorIdleReleaseSeconds(), "a negative idle-release falls back to 5 s");
		assertEquals(300, p.floorMaxHoldSeconds(), "a negative max-hold falls back to 300 s");
		assertNull(p.authSigningKey(), "the signing key is left as-is (null drives the dev random fallback)");
	}

	@Test
	void anEmptyOriginsListAlsoDefaultsToWildcard() {
		assertArrayEquals(
				new String[]{"*"},
				new WalkieProperties(
						new String[0],
						1,
						1,
						1,
						1,
						1,
						1,
						1,
						false, 0,
						null,
						false, Duration.ZERO)
						.allowedOrigins()
		);
	}

	@Test
	void keepsProvidedValues() {
		WalkieProperties p = new WalkieProperties(
				new String[]{"https://example.test"},
				4096,
				16384,
				200,
				250,
				7,
				42,
				15,
				true, 0,
				"secret",
				false, Duration.ZERO);
		assertArrayEquals(new String[]{"https://example.test"}, p.allowedOrigins());
		assertEquals(4096, p.maxAudioFrameBytes());
		assertEquals(16384, p.maxTextMessageBytes());
		assertEquals(200, p.maxAudioFramesPerSecond());
		assertEquals(250, p.maxControlMessagesPerSecond());
		assertEquals(7, p.floorIdleReleaseSeconds());
		assertEquals(42, p.floorMaxHoldSeconds());
		assertEquals(15, p.floorReservationSeconds(), "a positive reservation window is kept as-is");
		// floorQueueDefault passthrough is asserted once, in the dedicated defaults test below (no duplication).
		assertEquals("secret", p.authSigningKey());
	}

	@Test
	void floorTimersHonorAnExplicitZeroToDisable() {
		WalkieProperties p = new WalkieProperties(
				new String[]{"*"},
				1,
				1,
				1,
				1,
				0,
				0,
				10,
				false, 0,
				null,
				false, Duration.ZERO);
		assertEquals(0, p.floorIdleReleaseSeconds(), "0 disables idle auto-release (not coerced to the default)");
		assertEquals(0, p.floorMaxHoldSeconds(), "0 disables the max-hold cap (not coerced to the default)");
	}

	@Test
	void theReservationWindowDefaultsWhenNonPositiveAndTheQueueDefaultPassesThrough() {
		// Grant-to-claim needs a positive window, so — UNLIKE the idle/max-hold timers, where 0 means "off" — a
		// 0 or negative reservation is coerced to the 10 s default rather than disabling anything. The queue
		// on/off default is a plain flag with no coercion, so it passes through verbatim.
		assertEquals(10, new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, 0, false, 0, null, false, Duration.ZERO).floorReservationSeconds(),
				"a zero reservation window falls back to the 10 s default");
		assertEquals(10, new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, -1, false, 0, null, false, Duration.ZERO).floorReservationSeconds(),
				"a negative reservation window falls back to the 10 s default");
		assertTrue(new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, 10, true, 0, null, false, Duration.ZERO).floorQueueDefault(),
				"floorQueueDefault=true passes through");
		assertFalse(new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, 10, false, 0, null, false, Duration.ZERO).floorQueueDefault(),
				"floorQueueDefault=false passes through");
	}

	@Test
	void theKeepaliveHonoursAnExplicitZeroButDefaultsWhenAbsentOrNegative() {
		// A Duration binds as NULL when the property is absent — unlike a `long`, which would arrive as 0 and be
		// indistinguishable from an explicit "off" — so absent and negative both fall back while ZERO is kept.
		// The default has to stay under the tightest idle timeout in the deployment story: nginx's 60 s
		// proxy_read_timeout, and a Cloudflare tunnel's ~100 s.
		assertEquals(Duration.ofSeconds(30), new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, 10, false, 0, null, false, null).keepalivePingInterval(),
				"an ABSENT keepalive (null, not 0) falls back to the 30 s default");
		assertEquals(Duration.ofSeconds(30), new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, 10, false, 0, null, false, Duration.ofSeconds(-1))
				.keepalivePingInterval(), "a negative keepalive falls back to the 30 s default");
		assertEquals(Duration.ZERO, new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, 10, false, 0, null, false, Duration.ZERO)
				.keepalivePingInterval(), "0 disables the keepalive (not coerced to the default)");
		assertEquals(Duration.ofSeconds(45), new WalkieProperties(
				new String[]{"*"}, 1, 1, 1, 1, 5, 300, 10, false, 0, null, false, Duration.ofSeconds(45))
				.keepalivePingInterval(), "a positive keepalive is kept as-is");
	}

	@Test
	void anAbsurdlyHighRateIsClampedSoTheTokenIntervalCannotRoundToZero() {
		// 1 s / rate must stay >= 1 ns, else a rate limiter's Duration.dividedBy(perToken) throws. Anything above
		// 1e9 per second is clamped to 1e9 (still far above any real audio/control rate). Both rates are clamped.
		WalkieProperties p = new WalkieProperties(
				new String[]{"*"},
				1,
				1,
				2_000_000_000L,
				2_000_000_000L,
				5,
				300,
				10,
				false, 0,
				null,
				false, Duration.ZERO);
		assertEquals(1_000_000_000L, p.maxAudioFramesPerSecond(), "audio rates above 1e9 are clamped to 1e9");
		assertEquals(1_000_000_000L, p.maxControlMessagesPerSecond(), "control rates above 1e9 are clamped to 1e9");
	}
}
