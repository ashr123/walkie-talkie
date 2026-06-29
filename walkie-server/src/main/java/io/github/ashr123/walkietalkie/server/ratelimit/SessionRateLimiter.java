package io.github.ashr123.walkietalkie.server.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Per-session flood guard: each session id gets its own [TokenBucket], and an event is admitted only when a
/// token is available, so a session exceeding the configured rate has its excess events dropped. It is
/// content-agnostic (it counts events, never inspects them). The server uses two independent instances — one
/// for relayed **audio** frames (`walkie.max-audio-frames-per-second`) and one for inbound **control** messages
/// (`walkie.max-control-messages-per-second`) — so a flood on either plane is bounded without starving the other.
public final class SessionRateLimiter {

	private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
	private final long capacity;
	private final Duration perToken;
	private final Clock clock;

	/// @param maxPerSecond sustained per-session ceiling; also the burst capacity (one second's worth), which
	///                     absorbs a brief batched delivery without penalizing an honest client
	/// @param clock        time source (injected so tests can drive it deterministically)
	public SessionRateLimiter(long maxPerSecond, Clock clock) {
		this.capacity = maxPerSecond;
		this.perToken = Duration.ofSeconds(1).dividedBy(maxPerSecond);   // one token earned every 1s / rate
		this.clock = clock;
	}

	/// Whether an event from `sessionId` is admitted now. `false` means the session is over its rate ceiling and
	/// the event should be dropped.
	public boolean tryAcquire(String sessionId) {
		Instant now = clock.instant();
		return buckets.computeIfAbsent(sessionId, _ -> new TokenBucket(capacity, perToken, now)).tryConsume(now);
	}

	/// Drops a disconnected session's bucket so the per-session map can't grow without bound.
	public void forget(String sessionId) {
		buckets.remove(sessionId);
	}
}
