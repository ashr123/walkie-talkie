package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Option;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/// Channel-level talk-floor semantics, exercised directly on [Channel] (the floor arbitration that used to
/// live in a separate `FloorControlUtil` is now `Channel.tryAcquireFloor` / `releaseFloor` called under the
/// channel monitor by the service). Covers acquire/deny, release, non-holder release, and the full-duplex
/// short-circuit (no single holder is tracked).
class ChannelTest {

	private static FakeClientSession session(String id) {
		return new FakeClientSession(id, Transport.AUDIO_RELAY, id);
	}

	@Test
	void grantsTheFloorToTheFirstAcquirerAndDeniesTheSecond() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null);
		channel.add(session("alice"));
		channel.add(session("bob"));

		assertTrue(channel.tryAcquireFloor("alice", Instant.EPOCH), "the first acquirer takes the floor");
		assertFalse(channel.tryAcquireFloor("bob", Instant.EPOCH), "a second acquirer is denied while held");
		assertEquals(Option.of("alice"), channel.floorHolder());
		assertTrue(channel.holdsFloor("alice"));
		assertFalse(channel.holdsFloor("bob"));
	}

	@Test
	void releaseLetsTheNextAcquirerIn() {
		Channel channel = new Channel("c", ChannelMode.GLOBAL_PTT, "alice", null);

		assertTrue(channel.tryAcquireFloor("alice", Instant.EPOCH));
		assertTrue(channel.releaseFloor("alice"), "the holder releases");
		assertTrue(channel.tryAcquireFloor("bob", Instant.EPOCH), "the floor is free for the next acquirer");
		assertEquals(Option.of("bob"), channel.floorHolder());
	}

	@Test
	void releaseByANonHolderIsRejected() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null);

		channel.tryAcquireFloor("alice", Instant.EPOCH);
		assertFalse(channel.releaseFloor("bob"), "a non-holder cannot release the floor");
		assertTrue(channel.holdsFloor("alice"), "the real holder still holds it");
	}

	@Test
	void fullDuplexAlwaysGrantsTheFloorAndTracksNoHolder() {
		Channel channel = new Channel("c", ChannelMode.FULL_DUPLEX, "owner", null);

		assertTrue(channel.tryAcquireFloor("alice", Instant.EPOCH));
		assertTrue(channel.tryAcquireFloor("bob", Instant.EPOCH), "full-duplex never contends for the floor");
		assertTrue(channel.holdsFloor("anyone"), "everyone may transmit in full-duplex");
		assertInstanceOf(None.class, channel.floorHolder(), "no single holder is tracked");
	}
}
