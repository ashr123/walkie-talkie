package io.github.ashr123.walkietalkie.server;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAmount;

/// A [Clock] whose instant the test advances by hand — java.time has no mutable clock, and `Clock.fixed`
/// can't move, so this is the minimal seam for driving time-based logic deterministically in tests.
public final class MutableClock extends Clock {

	/// `volatile` so a test can advance the clock from ONE thread (e.g. a stress test's sweep driver) while other
	/// threads read it via [#instant] with correct visibility. The single-writer discipline (only one thread calls
	/// [#advance]) keeps the read-modify-write in `advance` race-free; concurrent readers just see each published
	/// instant. Single-threaded tests are unaffected.
	private volatile Instant now;

	public MutableClock(Instant start) {
		this.now = start;
	}

	public void advance(TemporalAmount by) {
		now = now.plus(by);
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}
}
