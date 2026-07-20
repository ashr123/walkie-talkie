package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.MutableClock;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/// Adversarial concurrency stress: many sessions hammer a small set of shared channels with interleaved
/// join / leave / floor / rename / audio / passphrase-rotation / ownership-transfer, to flush out races and deadlocks under contention (the integration
/// tests are otherwise sequential). Each worker owns ONE session — so a single session is never touched by two
/// threads (a real connection isn't) — and contention is concentrated on the channel registry and the per-channel
/// monitors. The bar: no operation throws, the run finishes (no deadlock — see [Timeout]), and once every
/// session has closed, no channel is leaked.
class ConcurrencyStressTest {

	private static final MessageBroadcaster BROADCASTER =
			new MessageBroadcaster(new MessageCodec(JsonMapper.shared()));

	@Test
	@Timeout(60)
	void concurrentJoinLeaveFloorRenameAndAudioStaysConsistentAndDeadlockFree() throws Exception {
		ChannelRegistry registry = new ChannelRegistry();
		// Rates set effectively-unlimited so the flood guards don't drop ops (we want full contention); floor
		// timers off so behavior depends only on the operations, not wall-clock.
		ConnectionService service = new ConnectionService(
				registry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						1_000_000,
						1_000_000,
						0,
						0,
						10,
						false,
						null,
						false
				),
				BROADCASTER
		);

		int workers = 16;
		int opsPerWorker = 3_000;
		String[] channels = {"alpha", "beta", "gamma", "delta"};   // few channels, many sessions -> high contention
		byte[] frame = {1, 2, 3};
		Collection<Throwable> errors = new ConcurrentLinkedQueue<>();
		CountDownLatch start = new CountDownLatch(1);
		Collection<Thread> threads = new ArrayList<>();

		for (int w = 0; w < workers; w++) {
			int seed = w;
			threads.add(Thread.ofVirtual().unstarted(() -> {
				ClientSession me = new FakeClientSession("s-" + seed, Transport.AUDIO_RELAY, "n-" + seed);
				Random rnd = new Random(seed);
				try {
					start.await();
					for (int i = 0; i < opsPerWorker; i++) {
						switch (rnd.nextInt(8)) {
							case 0 -> service.onMessage(me, new ClientMessage.Join(
									channels[rnd.nextInt(channels.length)], ChannelMode.MULTI_CHANNEL_PTT, "n-" + seed, null));
							case 1 -> service.onMessage(me, new ClientMessage.RequestFloor());
							case 2 -> service.onMessage(me, new ClientMessage.ReleaseFloor());
							case 3 -> service.onMessage(me, new ClientMessage.Rename("n-" + seed + "-" + i % 40));
							case 4 -> service.onAudio(me, frame);
							case 5 -> service.onMessage(me, new ClientMessage.Leave());
							// Passphrase rotation races joins/leaves on the SAME channel: only the channel's current
							// owner succeeds (others get NOT_OWNER, no throw), so the registry's key-check write must
							// stay serialized with join validation under the channel-name bin lock. A null key-check
							// flips the channel back to unencrypted, keeping joins (which present null) succeeding.
							case 6 -> service.onMessage(me, new ClientMessage.ChangePassphrase(
									rnd.nextInt(3) == 0 ? null : "kcv-" + i % 4, null));
							// Ownership transfer races the auto-election a concurrent leave performs AND the case-6
							// rotation (both write under the same channel-name bin lock). Half the time target SELF —
							// guaranteed a current member, so when this worker currently owns its channel the OK
							// owner-write actually fires and interleaves with concurrent rotations; the other half
							// target a random id to exercise the NOT_OWNER / UNKNOWN_TARGET paths. None may throw.
							case 7 -> service.onMessage(me, new ClientMessage.TransferOwnership(
									rnd.nextBoolean() ? me.id() : "s-" + rnd.nextInt(workers)));
						}
					}
				} catch (Throwable ex) {
					errors.add(ex);
				} finally {
					// Drain this session out of whatever channel it ended in, so channels drop to empty.
					try {
						service.onClose(me, "stress end");
					} catch (Throwable ex) {
						errors.add(ex);
					}
				}
			}));
		}

		threads.forEach(Thread::start);
		start.countDown();   // release all workers at once for maximum interleaving
		for (Thread t : threads) {
			t.join();
		}

		assertTrue(errors.isEmpty(),
				() -> "concurrent operations threw " + errors.size() + " error(s): "
						+ errors.stream().map(Throwable::toString).distinct().collect(Collectors.joining("; ")));
		assertEquals(0, registry.channelCount(),
				"every channel is dropped once empty — no channel is leaked after all sessions close");
	}

	/// Convergence under contention: many threads concurrently rotate the passphrase and transfer ownership on
	/// ONE channel that nobody leaves (so every OwnerChanged/PassphraseChanged a member receives is for that
	/// channel). Owner-only ops succeed only from whichever thread currently owns, so rotation broadcasts race
	/// transfer broadcasts. The invariant the bar checks beyond no-throw: the LAST OwnerChanged / PassphraseChanged
	/// each member received must equal the channel's FINAL ownerId / keyCheck. A broadcast that fans out a stale
	/// captured value (instead of the channel's live field under the monitor) lets a member's last-seen value
	/// disagree with the field — the exact ghost-owner / stale-key-check-gate desync this guards.
	@Test
	@Timeout(60)
	void concurrentRotationsAndTransfersConvergeOnTheFinalOwnerAndKeyCheck() throws Exception {
		ChannelRegistry registry = new ChannelRegistry();
		ConnectionService service = new ConnectionService(
				registry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						1_000_000,
						1_000_000,
						0,
						0,
						10,
						false,
						null,
						false
				),
				BROADCASTER
		);

		int members = 8;
		int opsPerWorker = 2_000;
		List<FakeClientSession> sessions = new ArrayList<>();
		// All members join ONE encrypted channel and STAY — the first creates it, the rest match its key-check.
		for (int i = 0; i < members; i++) {
			FakeClientSession s = new FakeClientSession("m-" + i, Transport.AUDIO_RELAY, "m-" + i);
			service.onMessage(s, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "m-" + i, "kcv-seed"));
			sessions.add(s);
		}

		Collection<Throwable> errors = new ConcurrentLinkedQueue<>();
		CountDownLatch start = new CountDownLatch(1);
		Collection<Thread> threads = new ArrayList<>();
		for (int w = 0; w < members; w++) {
			FakeClientSession me = sessions.get(w);
			int seed = w;
			threads.add(Thread.ofVirtual().unstarted(() -> {
				Random rnd = new Random(seed);
				try {
					start.await();
					for (int i = 0; i < opsPerWorker; i++) {
						// Owner-only ops: each succeeds only while THIS member is the owner, so the owner and the
						// key-check change from whichever thread currently owns — concurrent broadcasts race.
						if (rnd.nextBoolean()) {
							service.onMessage(me, new ClientMessage.ChangePassphrase("kcv-" + seed + "-" + i, null));
						} else {
							service.onMessage(me, new ClientMessage.TransferOwnership("m-" + rnd.nextInt(members)));
						}
					}
				} catch (Throwable ex) {
					errors.add(ex);
				}
			}));
		}
		threads.forEach(Thread::start);
		start.countDown();
		for (Thread t : threads) {
			t.join();
		}

		assertTrue(errors.isEmpty(),
				() -> "concurrent rotate/transfer threw: " + errors.stream().map(Throwable::toString).distinct().collect(Collectors.joining("; ")));
		Channel team = registry.find("team") instanceof Some(Channel c) ? c : null;
		assertNotNull(team, "the channel survives — nobody left");
		String finalOwner = team.ownerId();
		String finalKeyCheck = team.keyCheck();
		for (FakeClientSession s : sessions) {
			s.sent.stream().filter(ServerMessage.OwnerChanged.class::isInstance).map(ServerMessage.OwnerChanged.class::cast)
					.reduce((_, b) -> b).ifPresent(last -> assertEquals(finalOwner, last.ownerId(),
							"member " + s.id() + "'s last OwnerChanged must converge on the channel's final owner"));
			s.sent.stream().filter(ServerMessage.PassphraseChanged.class::isInstance).map(ServerMessage.PassphraseChanged.class::cast)
					.reduce((_, b) -> b).ifPresent(last -> assertEquals(finalKeyCheck, last.keyCheck(),
							"member " + s.id() + "'s last PassphraseChanged must converge on the channel's final key-check"));
		}
	}

	/// Adversarial stress of the push-to-talk floor QUEUE, now WITH the scheduled sweep running against a
	/// controllable clock: many sessions hammer queue-enabled channels with interleaved request (grab / claim /
	/// enqueue), release (release / dequeue / decline), leave (incl. the holder or the reserved head leaving),
	/// audio and owner toggles, while a separate driver thread advances the clock and calls `releaseExpiredFloors`
	/// — so the reservation-expiry, idle-release and max-hold paths interleave with concurrent enqueue/claim/leave
	/// under contention. The channels are created with the server-wide floor-queue default ON, so a channel
	/// dropped-and-recreated mid-run comes back queue-enabled, and the timers are non-zero so the sweep does real
	/// work.
	///
	/// The bar (as with the other stress tests): no operation throws — a queue mutation racing a holder swap, a
	/// leave, or a sweep never corrupts state or trips an assertion inside `Channel` — the run finishes (no
	/// deadlock; bin→monitor order is never inverted), and once every session closes, the holder AND the
	/// reservation are torn down with the channel (no channel leaked). The finer invariants (at most one holder —
	/// structural, a single `volatile` field; a running reservation never re-stamped/moved backward — enforced by
	/// the idempotent `reserveHead` + EPOCH guard) are pinned deterministically by the unit tests; asserting them
	/// dynamically here would be racy (a worker reading a slightly stale `MutableClock` can legitimately stamp a
	/// fresh reservation at an earlier instant than the driver's last advance), so this test guards the properties
	/// that ARE deterministic under contention: no throw, no deadlock, no leak.
	@Test
	@Timeout(60)
	void concurrentQueueOpsUnderTheSweepStayConsistentAndDeadlockFree() throws Exception {
		ChannelRegistry registry = new ChannelRegistry();
		// NON-EPOCH clock base (a reservation stamped at EPOCH would collide with the no-reservation sentinel).
		MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(1_000));
		// floorQueueDefault = TRUE (9th positional arg): every channel created this run starts queue-enabled, so the
		// enqueue path is exercised without a separate SetFloorQueue step. Rates unlimited; timers NON-ZERO
		// (idle 5 s, max-hold 10 s, reservation 10 s) so the sweep actually reclaims/advances the floor.
		ConnectionService service = new ConnectionService(
				registry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						1_000_000,
						1_000_000,
						5,
						10,
						10,
						true,
						null,
						false
				),
				BROADCASTER,
				clock
		);

		int workers = 16;
		int opsPerWorker = 3_000;
		String[] channels = {"alpha", "beta"};   // fewer channels, many sessions -> heavy queue contention
		byte[] frame = {1, 2, 3};
		Collection<Throwable> errors = new ConcurrentLinkedQueue<>();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(workers);
		Collection<Thread> threads = new ArrayList<>();

		for (int w = 0; w < workers; w++) {
			int seed = w;
			threads.add(Thread.ofVirtual().unstarted(() -> {
				ClientSession me = new FakeClientSession("s-" + seed, Transport.AUDIO_RELAY, "n-" + seed);
				Random rnd = new Random(seed);
				try {
					start.await();
					for (int i = 0; i < opsPerWorker; i++) {
						switch (rnd.nextInt(7)) {
							case 0 -> service.onMessage(me, new ClientMessage.Join(
									channels[rnd.nextInt(channels.length)], ChannelMode.MULTI_CHANNEL_PTT, "n-" + seed, null));
							// Request interpreted by state: grab a free floor, claim a reserved turn, or enqueue behind
							// a busy one — all under the channel monitor with the holder swap.
							case 1, 2 -> service.onMessage(me, new ClientMessage.RequestFloor());
							// Release interpreted by state: release the held floor, leave the queue, or decline a turn.
							case 3 -> service.onMessage(me, new ClientMessage.ReleaseFloor());
							case 4 -> service.onMessage(me, new ClientMessage.Leave());   // may drop the holder / reserved head
							case 5 -> service.onAudio(me, frame);
							// Owner-only toggle: only the current owner succeeds (others get NOT_OWNER, no throw);
							// disabling clears the queue + reservation mid-contention, re-enabling reopens it.
							case 6 -> service.onMessage(me, new ClientMessage.SetFloorQueue(rnd.nextBoolean()));
						}
					}
				} catch (Throwable ex) {
					errors.add(ex);
				} finally {
					try {
						service.onClose(me, "stress end");
					} catch (Throwable ex) {
						errors.add(ex);
					} finally {
						done.countDown();
					}
				}
			}));
		}

		threads.forEach(Thread::start);
		start.countDown();
		// Single-writer clock driver: only THIS thread advances the clock, so the volatile read-modify-write in
		// advance() stays race-free; workers only READ the clock. Advancing 2 s per pass repeatedly crosses the
		// idle/max-hold/reservation windows, so the sweep interleaves reclaim/advance with the workers' mutations.
		while (!done.await(1, TimeUnit.MILLISECONDS)) {
			clock.advance(Duration.ofSeconds(2));
			service.releaseExpiredFloors();
		}
		for (Thread t : threads) {
			t.join();
		}
		service.releaseExpiredFloors();   // a final sweep after quiescence must also not throw

		assertTrue(errors.isEmpty(),
				() -> "concurrent queue operations under the sweep threw " + errors.size() + " error(s): "
						+ errors.stream().map(Throwable::toString).distinct().collect(Collectors.joining("; ")));
		assertEquals(0, registry.channelCount(),
				"every channel is dropped once empty — no channel (nor its floor/queue/reservation state) is leaked");
	}
}
