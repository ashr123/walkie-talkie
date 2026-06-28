package io.github.ashr123.walkietalkie.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/// Tunable server settings, bound from the `walkie.*` configuration namespace.
///
/// @param allowedOrigins         permitted WebSocket origin patterns; permissive by default for local
///                               development, but should be restricted to known hosts in production
/// @param maxAudioFrameBytes     largest inbound audio frame accepted on the relay transport
/// @param maxTextMessageBytes    largest control/signaling text frame accepted
/// @param maxAudioFramesPerSecond per-sender ceiling on relayed audio frames per second; excess frames are
///                               dropped before fan-out (a flood guard — see `AudioRateLimiter`). Nominal
///                               traffic is ~50 fps (one 20 ms frame per tick), so the default leaves ample
///                               headroom for an honest client while bounding a flooder. This guard is
///                               **always on**: 0 or absent uses the default (100), it cannot be disabled
///                               (unlike the floor timers, where 0 means off)
/// @param floorIdleReleaseSeconds push-to-talk idle auto-release (relay holders only): reclaim the floor for a
///                               new requester when the current holder has sent no audio for this many seconds
///                               (0 disables). Measured from frame timing only, so it works on encrypted channels
/// @param floorMaxHoldSeconds    push-to-talk max-hold cap: force-release any holder once it has held the floor
///                               this many seconds (0 disables). Enforced by a periodic background sweep, so it
///                               is a true time cap that bounds even a holder gone silent without releasing —
///                               and, unlike idle-release, applies to WebRTC holders too
/// @param authSigningKey         HMAC-SHA512 key used to sign/verify bearer tokens, bound from
///                               `walkie.auth-signing-key` (env `WALKIE_AUTH_SIGNING_KEY`). Blank/absent means
///                               a random key is generated per process (dev only — tokens then don't survive a
///                               restart or span instances). Never hardcode a real key.
@ConfigurationProperties(prefix = "walkie")
public record WalkieProperties(
		List<String> allowedOrigins,
		int maxAudioFrameBytes,
		int maxTextMessageBytes,
		long maxAudioFramesPerSecond,
		long floorIdleReleaseSeconds,
		long floorMaxHoldSeconds,
		String authSigningKey) {

	/// One second has this many nanoseconds; above this many frames/second the rate limiter's per-token interval
	/// (1 s ÷ rate) would round down to zero nanoseconds and its `Duration.dividedBy` would throw, so the rate is
	/// clamped here. Far above any real audio rate (nominal is ~50 fps), so legitimate values pass untouched.
	private static final long MAX_AUDIO_FRAMES_PER_SECOND = 1_000_000_000L;

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
		if (maxAudioFramesPerSecond <= 0) {
			// Always-on flood guard: 0/blank means "use the default", never "disable" (unlike the floor timers
			// below). ~2x the nominal 50 fps (one 20 ms frame per tick): generous for an honest client, a cap for a flooder.
			maxAudioFramesPerSecond = 100;
		} else if (maxAudioFramesPerSecond > MAX_AUDIO_FRAMES_PER_SECOND) {
			// Keep 1 s ÷ rate ≥ 1 ns so the token bucket's perToken is never zero (which would throw on divide).
			maxAudioFramesPerSecond = MAX_AUDIO_FRAMES_PER_SECOND;
		}
		// Floor timers honor an explicit 0 (disabled); only a nonsensical negative falls back to the default.
		if (floorIdleReleaseSeconds < 0) {
			floorIdleReleaseSeconds = 5;
		}
		if (floorMaxHoldSeconds < 0) {
			floorMaxHoldSeconds = 300;   // 5 minutes
		}
	}
}
