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
		WalkieProperties p = new WalkieProperties(null, 0, -1, null);
		assertEquals(List.of("*"), p.allowedOrigins(), "null origins default to the wildcard");
		assertEquals(8 * 1024, p.maxAudioFrameBytes(), "a non-positive audio size falls back to 8 KiB");
		assertEquals(64 * 1024, p.maxTextMessageBytes(), "a non-positive text size falls back to 64 KiB");
		assertNull(p.authSigningKey(), "the signing key is left as-is (null drives the dev random fallback)");
	}

	@Test
	void anEmptyOriginsListAlsoDefaultsToWildcard() {
		assertEquals(List.of("*"), new WalkieProperties(List.of(), 1, 1, null).allowedOrigins());
	}

	@Test
	void keepsProvidedValues() {
		WalkieProperties p = new WalkieProperties(List.of("https://example.test"), 4096, 16384, "secret");
		assertEquals(List.of("https://example.test"), p.allowedOrigins());
		assertEquals(4096, p.maxAudioFrameBytes());
		assertEquals(16384, p.maxTextMessageBytes());
		assertEquals("secret", p.authSigningKey());
	}
}
