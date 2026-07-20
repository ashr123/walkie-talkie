package io.github.ashr123.walkietalkie.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/// Tunable server settings, bound from the `walkie.*` configuration namespace.
///
/// @param allowedOrigins         permitted WebSocket origin patterns; permissive by default for local
///                               development, but should be restricted to known hosts in production
/// @param maxAudioFrameBytes     largest inbound audio frame accepted on the relay transport
/// @param maxTextMessageBytes    largest control/signaling text frame accepted
/// @param maxAudioFramesPerSecond per-sender ceiling on relayed audio frames per second; excess frames are
///                               dropped before fan-out (a flood guard — see `SessionRateLimiter`). Nominal
///                               traffic is ~50 fps (one 20 ms frame per tick), so the default leaves ample
///                               headroom for an honest client while bounding a flooder. This guard is
///                               **always on**: 0 or absent uses the default (100), it cannot be disabled
///                               (unlike the floor timers, where 0 means off)
/// @param maxControlMessagesPerSecond per-session ceiling on inbound control (text) messages per second; excess
///                               messages are dropped (the control-plane counterpart to the audio flood guard).
///                               Control traffic is sparse — a few floor presses, the odd join/rename/mode, and
///                               a WebRTC ICE-candidate burst at setup — so the default (200) is generous for an
///                               honest client while bounding a control flood. Also always on: 0/absent → default
/// @param floorIdleReleaseSeconds push-to-talk idle auto-release (relay holders only): reclaim the floor for a
///                               new requester when the current holder has sent no audio for this many seconds
///                               (0 disables). Measured from frame timing only, so it works on encrypted channels
/// @param floorMaxHoldSeconds    push-to-talk max-hold cap: force-release any holder once it has held the floor
///                               this many seconds (0 disables). Enforced by a periodic background sweep, so it
///                               is a true time cap that bounds even a holder gone silent without releasing —
///                               and, unlike idle-release, applies to WebRTC holders too
/// @param floorReservationSeconds when the owner-toggleable floor queue is on, how long the head is given to
///                               CLAIM the floor once it is offered (its turn) before the server drops it and
///                               offers the next member. A positive window — grant-to-claim needs one — so
///                               0/absent falls back to the default (10), it is not "disabled". See
///                               docs/FLOOR_QUEUE.md
/// @param floorQueueDefault      the floor-queue on/off state a NEWLY created channel adopts (its owner can then
///                               toggle it per channel via `setFloorQueue`); default false, preserving the plain
///                               busy-floor-refused behaviour
/// @param authSigningKey         HMAC-SHA512 key used to sign/verify bearer tokens, bound from
///                               `walkie.auth-signing-key` (env `WALKIE_AUTH_SIGNING_KEY`). Blank/absent means
///                               a random key is generated per process (dev only — tokens then don't survive a
///                               restart or span instances). Never hardcode a real key.
/// @param channelAffinity        multi-instance routing switch (`walkie.channel-affinity`, default `false`). When
///                               enabled, a socket may only serve a channel that this instance OWNS — the channel
///                               it was routed to at the handshake, or any channel it already hosts — and a switch
///                               to a channel owned by another instance is refused with `CHANNEL_ROUTING_MISMATCH`
///                               so the client reconnects and the router re-pins it. Off (single instance): every
///                               channel is served here, so no routing check applies and switches stay in place.
@ConfigurationProperties(prefix = "walkie")
public record WalkieProperties(
		String[] allowedOrigins,
		int maxAudioFrameBytes,
		int maxTextMessageBytes,
		long maxAudioFramesPerSecond,
		long maxControlMessagesPerSecond,
		long floorIdleReleaseSeconds,
		long floorMaxHoldSeconds,
		long floorReservationSeconds,
		boolean floorQueueDefault,
		String authSigningKey,
		boolean channelAffinity) {

	/// One second has this many nanoseconds; above this many events/second a rate limiter's per-token interval
	/// (1 s ÷ rate) would round down to zero nanoseconds and its `Duration.dividedBy` would throw, so any rate is
	/// clamped here. Far above any real audio/control rate, so legitimate values pass untouched.
	private static final long MAX_RATE_PER_SECOND = 1_000_000_000L;

	public WalkieProperties {
		if (allowedOrigins == null || allowedOrigins.length == 0) {
			allowedOrigins = new String[]{"*"};
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
		} else if (maxAudioFramesPerSecond > MAX_RATE_PER_SECOND) {
			// Keep 1 s ÷ rate ≥ 1 ns so the token bucket's perToken is never zero (which would throw on divide).
			maxAudioFramesPerSecond = MAX_RATE_PER_SECOND;
		}
		if (maxControlMessagesPerSecond <= 0) {
			maxControlMessagesPerSecond = 200;   // always-on; generous for sparse control + ICE bursts, caps a flood
		} else if (maxControlMessagesPerSecond > MAX_RATE_PER_SECOND) {
			maxControlMessagesPerSecond = MAX_RATE_PER_SECOND;
		}
		// Floor timers honor an explicit 0 (disabled); only a nonsensical negative falls back to the default.
		if (floorIdleReleaseSeconds < 0) {
			floorIdleReleaseSeconds = 5;
		}
		if (floorMaxHoldSeconds < 0) {
			floorMaxHoldSeconds = 300;   // 5 minutes
		}
		if (floorReservationSeconds <= 0) {
			// Grant-to-claim needs a positive window, so 0/blank means "use the default" (never "disabled").
			floorReservationSeconds = 10;
		}
	}
}
