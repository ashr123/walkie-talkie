package io.github.ashr123.walkietalkie.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/// Tunable server settings, bound from the `walkie.*` configuration namespace.
///
/// @param allowedOrigins      permitted WebSocket origin patterns; permissive by default for local
///                            development, but should be restricted to known hosts in production
/// @param maxAudioFrameBytes  largest inbound audio frame accepted on the relay transport
/// @param maxTextMessageBytes largest control/signaling text frame accepted
/// @param authSigningKey      HMAC-SHA512 key used to sign/verify bearer tokens, bound from
///                            `walkie.auth-signing-key` (env `WALKIE_AUTH_SIGNING_KEY`). Blank/absent means
///                            a random key is generated per process (dev only — tokens then don't survive a
///                            restart or span instances). Never hardcode a real key.
@ConfigurationProperties(prefix = "walkie")
public record WalkieProperties(
		List<String> allowedOrigins,
		int maxAudioFrameBytes,
		int maxTextMessageBytes,
		String authSigningKey) {

	public WalkieProperties {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			allowedOrigins = List.of("*");
		}
		if (maxAudioFrameBytes <= 0) {
			maxAudioFrameBytes = 8 * 1024;
		}
		if (maxTextMessageBytes <= 0) {
			maxTextMessageBytes = 64 * 1024;
		}
	}
}
