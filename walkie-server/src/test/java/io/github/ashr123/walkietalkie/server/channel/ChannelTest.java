package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.None;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Channel-level floor semantics that the service tests don't reach directly — in particular that
/// `tryAcquireFloor` short-circuits to a grant in full-duplex (its only caller, [io.github.ashr123.walkietalkie.server.floor.FloorControlService],
/// already guards that mode, so this self-contained guarantee is only observable by calling it directly).
class ChannelTest {

	@Test
	void fullDuplexAlwaysGrantsTheFloorAndTracksNoHolder() {
		Channel channel = new Channel("c", ChannelMode.FULL_DUPLEX, "owner", null);

		assertTrue(channel.tryAcquireFloor("alice"));
		assertTrue(channel.tryAcquireFloor("bob"), "full-duplex never contends for the floor");
		assertTrue(channel.holdsFloor("anyone"), "everyone may transmit in full-duplex");
		assertInstanceOf(None.class, channel.floorHolder(), "no single holder is tracked");
	}
}
