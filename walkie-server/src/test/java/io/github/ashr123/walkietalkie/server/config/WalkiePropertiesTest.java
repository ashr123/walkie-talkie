package io.github.ashr123.walkietalkie.server.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// The compact constructor of [WalkieProperties] fills in safe defaults for absent or non-positive values
/// while leaving valid configuration untouched.
class WalkiePropertiesTest {

	@Test
	void appliesDefaultsForNullOrNonPositiveValues() {
		WalkieProperties p = new WalkieProperties(null, 0, -1, 0, -1, -1, null);
		assertEquals(List.of("*"), p.allowedOrigins(), "null origins default to the wildcard");
		assertEquals(8 * 1024, p.maxAudioFrameBytes(), "a non-positive audio size falls back to 8 KiB");
		assertEquals(64 * 1024, p.maxTextMessageBytes(), "a non-positive text size falls back to 64 KiB");
		assertEquals(100, p.maxAudioFramesPerSecond(), "a non-positive frame rate falls back to 100 fps");
		assertEquals(5, p.floorIdleReleaseSeconds(), "a negative idle-release falls back to 5 s");
		assertEquals(300, p.floorMaxHoldSeconds(), "a negative max-hold falls back to 300 s");
		assertNull(p.authSigningKey(), "the signing key is left as-is (null drives the dev random fallback)");
	}

	@Test
	void anEmptyOriginsListAlsoDefaultsToWildcard() {
		assertEquals(List.of("*"), new WalkieProperties(List.of(), 1, 1, 1, 1, 1, null).allowedOrigins());
	}

	@Test
	void keepsProvidedValues() {
		WalkieProperties p = new WalkieProperties(List.of("https://example.test"), 4096, 16384, 200, 7, 42, "secret");
		assertEquals(List.of("https://example.test"), p.allowedOrigins());
		assertEquals(4096, p.maxAudioFrameBytes());
		assertEquals(16384, p.maxTextMessageBytes());
		assertEquals(200, p.maxAudioFramesPerSecond());
		assertEquals(7, p.floorIdleReleaseSeconds());
		assertEquals(42, p.floorMaxHoldSeconds());
		assertEquals("secret", p.authSigningKey());
	}

	@Test
	void floorTimersHonorAnExplicitZeroToDisable() {
		WalkieProperties p = new WalkieProperties(List.of("*"), 1, 1, 1, 0, 0, null);
		assertEquals(0, p.floorIdleReleaseSeconds(), "0 disables idle auto-release (not coerced to the default)");
		assertEquals(0, p.floorMaxHoldSeconds(), "0 disables the max-hold cap (not coerced to the default)");
	}

	@Test
	void anAbsurdlyHighAudioFrameRateIsClampedSoTheTokenIntervalCannotRoundToZero() {
		// 1 s / rate must stay >= 1 ns, else the rate limiter's Duration.dividedBy(perToken) throws. Anything
		// above 1e9 fps is clamped to 1e9 (still far above any real audio rate).
		WalkieProperties p = new WalkieProperties(List.of("*"), 1, 1, 2_000_000_000L, 5, 300, null);
		assertEquals(1_000_000_000L, p.maxAudioFramesPerSecond(), "rates above 1e9 fps are clamped to 1e9");
	}
}
