package io.github.ashr123.walkietalkie.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/// Tunable server settings, bound from the `walkie.*` configuration namespace.
///
/// @param allowedOrigins      permitted WebSocket origin patterns; permissive by default for local
///                            development, but should be restricted to known hosts in production
/// @param maxAudioFrameBytes  largest inbound audio frame accepted on the relay transport
/// @param maxTextMessageBytes largest control/signaling text frame accepted
@ConfigurationProperties(prefix = "walkie")
public record WalkieProperties(
		List<String> allowedOrigins,
		int maxAudioFrameBytes,
		int maxTextMessageBytes) {

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
