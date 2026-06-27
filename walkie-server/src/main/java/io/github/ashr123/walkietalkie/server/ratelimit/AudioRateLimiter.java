package io.github.ashr123.walkietalkie.server.ratelimit;

import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Per-sender flood guard for the audio relay. Each session gets its own [TokenBucket]; a frame is admitted
/// only when a token is available, so a sender that exceeds the configured frame rate has its excess frames
/// dropped *before* fan-out — sparing the channel the N-way amplification and the recipients the wasted decode
/// work. It is content-agnostic (it counts frames, never inspects them), so it applies identically to
/// end-to-end-encrypted channels. The matching per-frame *size* bound is `walkie.max-audio-frame-bytes`.
@Component
public class AudioRateLimiter {

	private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
	private final long capacity;
	private final Duration perToken;
	private final Clock clock;

	@Autowired
	public AudioRateLimiter(WalkieProperties properties) {
		this(properties.maxAudioFramesPerSecond(), Clock.systemUTC());
	}

	/// @param maxFramesPerSecond sustained per-sender ceiling on relayed frames; also the burst capacity (one
	///                           second's worth), which absorbs a brief network-batched delivery without
	///                           penalizing an honest client — nominal traffic is ~50 fps (one 20 ms frame per
	///                           tick), so this sits well above any legitimate sender
	/// @param clock              time source (injected so tests can drive it deterministically)
	AudioRateLimiter(long maxFramesPerSecond, Clock clock) {
		this.capacity = maxFramesPerSecond;
		this.perToken = Duration.ofSeconds(1).dividedBy(maxFramesPerSecond);   // one token earned every 1s / rate
		this.clock = clock;
	}

	/// Whether a frame from `sessionId` is admitted now. `false` means the sender is over its rate ceiling and
	/// the frame should be dropped.
	public boolean tryAcquire(String sessionId) {
		Instant now = clock.instant();
		return buckets.computeIfAbsent(sessionId, _ -> new TokenBucket(capacity, perToken, now)).tryConsume(now);
	}

	/// Drops a disconnected session's bucket so the per-session map can't grow without bound.
	public void forget(String sessionId) {
		buckets.remove(sessionId);
	}
}
