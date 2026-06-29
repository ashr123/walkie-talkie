package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Adversarial concurrency stress: many sessions hammer a small set of shared channels with interleaved
/// join / leave / floor / rename / audio / passphrase-rotation / ownership-transfer, to flush out races and deadlocks under contention (the integration
/// tests are otherwise sequential). Each worker owns ONE session — so a single session is never touched by two
/// threads (a real connection isn't) — and contention is concentrated on the channel registry and the per-channel
/// monitors. The bar: no operation throws, the run finishes (no deadlock — see [Timeout]), and once every
/// session has closed, no channel is leaked.
class ConcurrencyStressTest {

	@Test
	@Timeout(60)
	void concurrentJoinLeaveFloorRenameAndAudioStaysConsistentAndDeadlockFree() throws Exception {
		ChannelRegistry registry = new ChannelRegistry();
		// Rates set effectively-unlimited so the flood guards don't drop ops (we want full contention); floor
		// timers off so behavior depends only on the operations, not wall-clock.
		WalkieProperties props = new WalkieProperties(List.of("*"), 8192, 65536, 1_000_000, 1_000_000, 0, 0, null);
		ConnectionService service = new ConnectionService(registry, props);

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
							case 3 -> service.onMessage(me, new ClientMessage.Rename("n-" + seed + "-" + (i % 40)));
							case 4 -> service.onAudio(me, frame);
							case 5 -> service.onMessage(me, new ClientMessage.Leave());
							// Passphrase rotation races joins/leaves on the SAME channel: only the channel's current
							// owner succeeds (others get not_owner, no throw), so the registry's key-check write must
							// stay serialized with join validation under the channel-name bin lock. A null key-check
							// flips the channel back to unencrypted, keeping joins (which present null) succeeding.
							case 6 -> service.onMessage(me, new ClientMessage.ChangePassphrase(
									rnd.nextInt(3) == 0 ? null : "kcv-" + (i % 4)));
							// Ownership transfer races the auto-election a concurrent leave performs AND the case-6
							// rotation (both write under the same channel-name bin lock). Half the time target SELF —
							// guaranteed a current member, so when this worker currently owns its channel the OK
							// owner-write actually fires and interleaves with concurrent rotations; the other half
							// target a random id to exercise the not_owner / unknown_target paths. None may throw.
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
		WalkieProperties props = new WalkieProperties(List.of("*"), 8192, 65536, 1_000_000, 1_000_000, 0, 0, null);
		ConnectionService service = new ConnectionService(registry, props);

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
							service.onMessage(me, new ClientMessage.ChangePassphrase("kcv-" + seed + "-" + i));
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
					.reduce((a, b) -> b).ifPresent(last -> assertEquals(finalOwner, last.ownerId(),
							"member " + s.id() + "'s last OwnerChanged must converge on the channel's final owner"));
			s.sent.stream().filter(ServerMessage.PassphraseChanged.class::isInstance).map(ServerMessage.PassphraseChanged.class::cast)
					.reduce((a, b) -> b).ifPresent(last -> assertEquals(finalKeyCheck, last.keyCheck(),
							"member " + s.id() + "'s last PassphraseChanged must converge on the channel's final key-check"));
		}
	}
}
