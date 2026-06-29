package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Adversarial concurrency stress: many sessions hammer a small set of shared channels with interleaved
/// join / leave / floor / rename / audio, to flush out races and deadlocks under contention (the integration
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
		Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();

		for (int w = 0; w < workers; w++) {
			int seed = w;
			threads.add(Thread.ofVirtual().unstarted(() -> {
				FakeClientSession me = new FakeClientSession("s-" + seed, Transport.AUDIO_RELAY, "n-" + seed);
				Random rnd = new Random(seed);
				try {
					start.await();
					for (int i = 0; i < opsPerWorker; i++) {
						switch (rnd.nextInt(6)) {
							case 0 -> service.onMessage(me, new ClientMessage.Join(
									channels[rnd.nextInt(channels.length)], ChannelMode.MULTI_CHANNEL_PTT, "n-" + seed, null));
							case 1 -> service.onMessage(me, new ClientMessage.RequestFloor());
							case 2 -> service.onMessage(me, new ClientMessage.ReleaseFloor());
							case 3 -> service.onMessage(me, new ClientMessage.Rename("n-" + seed + "-" + (i % 40)));
							case 4 -> service.onAudio(me, frame);
							case 5 -> service.onMessage(me, new ClientMessage.Leave());
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
}
