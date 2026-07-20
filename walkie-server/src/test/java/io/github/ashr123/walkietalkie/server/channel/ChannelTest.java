package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.None;
import io.github.ashr123.option.NoneInt;
import io.github.ashr123.option.Option;
import io.github.ashr123.option.SomeInt;
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
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null, false);
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
		Channel channel = new Channel("c", ChannelMode.GLOBAL_PTT, "alice", null, false);

		assertTrue(channel.tryAcquireFloor("alice", Instant.EPOCH));
		assertTrue(channel.releaseFloor("alice"), "the holder releases");
		assertTrue(channel.tryAcquireFloor("bob", Instant.EPOCH), "the floor is free for the next acquirer");
		assertEquals(Option.of("bob"), channel.floorHolder());
	}

	@Test
	void releaseByANonHolderIsRejected() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null, false);

		channel.tryAcquireFloor("alice", Instant.EPOCH);
		assertFalse(channel.releaseFloor("bob"), "a non-holder cannot release the floor");
		assertTrue(channel.holdsFloor("alice"), "the real holder still holds it");
	}

	@Test
	void fullDuplexAlwaysGrantsTheFloorAndTracksNoHolder() {
		Channel channel = new Channel("c", ChannelMode.FULL_DUPLEX, "owner", null, false);

		assertTrue(channel.tryAcquireFloor("alice", Instant.EPOCH));
		assertTrue(channel.tryAcquireFloor("bob", Instant.EPOCH), "full-duplex never contends for the floor");
		assertTrue(channel.holdsFloor("anyone"), "everyone may transmit in full-duplex");
		assertInstanceOf(None.class, channel.floorHolder(), "no single holder is tracked");
	}

	@Test
	void setMutedReportsWhetherItChangedAndIsMutedReflectsIt() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null, false);
		channel.add(session("bob"));

		assertFalse(channel.isMuted("bob"), "a member starts unmuted");
		assertTrue(channel.setMuted("bob", true), "the first mute is a real change");
		assertTrue(channel.isMuted("bob"));
		assertFalse(channel.setMuted("bob", true), "re-muting an already-muted member is a no-op change");
		assertTrue(channel.setMuted("bob", false), "unmuting a muted member is a real change");
		assertFalse(channel.isMuted("bob"));
		assertFalse(channel.setMuted("bob", false), "unmuting an already-unmuted member is a no-op change");
	}

	@Test
	void setMutedForAllExceptSkipsTheOwnerAndReturnsOnlyTheChangedIds() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null, false);
		channel.add(session("alice"));
		channel.add(session("bob"));
		channel.add(session("carol"));
		channel.setMuted("bob", true);   // bob is already muted, so a mute-all shouldn't report bob as changed

		assertEquals(java.util.List.of("carol"), channel.setMutedForAllExcept("alice", true),
				"mute-all skips the excepted owner and reports only the members whose state actually flipped");
		assertFalse(channel.isMuted("alice"), "the owner is never muted by mute-all");
		assertTrue(channel.isMuted("bob"));
		assertTrue(channel.isMuted("carol"));
	}

	@Test
	void removeClearsAMembersMuteState() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null, false);
		channel.add(session("bob"));
		channel.setMuted("bob", true);

		channel.remove("bob");
		assertFalse(channel.isMuted("bob"), "a mute never outlives the member (a re-used id would not inherit it)");
	}

	@Test
	void streamIndexOfIsNoneForAnUnknownSessionAndRequireFailsFast() {
		Channel channel = new Channel("c", ChannelMode.FULL_DUPLEX, "alice", null, false);
		channel.add(session("alice"));

		assertInstanceOf(SomeInt.class, channel.streamIndexOf("alice"), "a current member has an index");
		assertInstanceOf(NoneInt.class, channel.streamIndexOf("ghost"),
				"an unknown session is NoneInt — NOT SomeInt(0), which would alias onto index 0's owner");
		assertEquals(((SomeInt) channel.streamIndexOf("alice")).value(), channel.requireStreamIndex("alice"),
				"requireStreamIndex returns the assigned index for a present member");
		assertThrows(IllegalStateException.class, () -> channel.requireStreamIndex("ghost"),
				"requireStreamIndex fails fast on a missing member (an invariant breach) instead of returning 0");
	}

	// --- floor queue primitives (the "raise hand" model — see docs/FLOOR_QUEUE.md) ------------------

	private static Channel queueChannel() {
		return new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null, true);
	}

	@Test
	void enqueueAppendsInFifoOrderAndDequeueRemoves() {
		Channel channel = queueChannel();
		channel.tryAcquireFloor("alice", Instant.EPOCH);   // alice holds -> a busy floor to queue behind

		assertTrue(channel.enqueueFloor("bob"), "a newly queued member reports true");
		assertTrue(channel.enqueueFloor("carol"));
		assertFalse(channel.enqueueFloor("bob"), "an already-queued member is a no-op");
		assertFalse(channel.enqueueFloor("alice"), "the current holder cannot queue for its own floor");
		assertEquals(java.util.List.of("bob", "carol"), channel.floorQueue(), "the queue preserves FIFO order");

		assertTrue(channel.dequeueFloor("bob"), "removing a queued member reports true");
		assertFalse(channel.dequeueFloor("bob"), "removing an absent member is a no-op");
		assertEquals(java.util.List.of("carol"), channel.floorQueue());
	}

	@Test
	void aReservedFloorIsClaimableOnlyByTheHeadWhichThenLeavesTheQueue() {
		Channel channel = queueChannel();
		channel.enqueueFloor("bob");
		channel.enqueueFloor("carol");
		assertEquals("bob", channel.reserveHead(Instant.EPOCH.plusSeconds(5)), "the head is offered the free floor");
		assertEquals(Option.of("bob"), channel.reservedHolder(), "the reserved member is the head while the floor is free");

		assertFalse(channel.tryAcquireFloor("carol", Instant.EPOCH), "a non-head cannot grab a reserved floor");
		assertInstanceOf(None.class, channel.floorHolder(), "the floor stays free after a refused non-head grab");

		assertTrue(channel.tryAcquireFloor("bob", Instant.EPOCH), "the reserved head claims its turn");
		assertEquals(Option.of("bob"), channel.floorHolder());
		assertEquals(java.util.List.of("carol"), channel.floorQueue(), "the claimant leaves the queue; carol remains");
		assertEquals(Instant.EPOCH, channel.floorReservedAt(), "claiming ends the reservation (clock reset)");
	}

	@Test
	void reserveHeadStampsWhenFreeAndReturnsNullWithoutStampingWhileHeld() {
		Channel channel = queueChannel();
		assertNull(channel.reserveHead(Instant.EPOCH.plusSeconds(1)), "an empty queue has no head to reserve");

		channel.tryAcquireFloor("alice", Instant.EPOCH);   // held
		channel.enqueueFloor("bob");
		assertNull(channel.reserveHead(Instant.EPOCH.plusSeconds(3)), "no reservation is offered while the floor is held");
		assertEquals(Instant.EPOCH, channel.floorReservedAt(), "the reservation clock is NOT stamped while held");

		assertTrue(channel.releaseFloor("alice"));   // now free
		assertEquals("bob", channel.reserveHead(Instant.EPOCH.plusSeconds(9)), "the freed floor is offered to the head");
		assertEquals(Instant.EPOCH.plusSeconds(9), channel.floorReservedAt(), "reserveHead stamps the reservation clock");
	}

	@Test
	void releaseIfIdleFreesOnlyASilentHolder() {
		Channel channel = queueChannel();
		channel.tryAcquireFloor("alice", Instant.EPOCH);   // holds; activity mark at EPOCH
		channel.enqueueFloor("bob");

		assertNull(channel.releaseIfIdle(Instant.EPOCH.minusSeconds(1)),
				"a holder active more recently than the cutoff is kept");
		assertEquals("alice", channel.releaseIfIdle(Instant.EPOCH.plusSeconds(1)),
				"a holder silent since at/before the cutoff is freed for the queue");
		assertInstanceOf(None.class, channel.floorHolder(), "the floor is now free for the queue head");
	}

	@Test
	void expiredReservationHeadReturnsTheStaleHeadOnlyAfterTheWindow() {
		Channel channel = queueChannel();
		channel.enqueueFloor("bob");
		Instant reservedAt = Instant.EPOCH.plusSeconds(10);
		channel.reserveHead(reservedAt);

		assertNull(channel.expiredReservationHead(reservedAt.minusSeconds(1)),
				"a reservation younger than the cutoff has not expired");
		assertEquals("bob", channel.expiredReservationHead(reservedAt),
				"a reservation stamped at/before the cutoff is expired");
	}

	@Test
	void removeScrubsALeaverFromTheFloorQueue() {
		Channel channel = queueChannel();
		channel.add(session("bob"));
		channel.enqueueFloor("bob");
		channel.enqueueFloor("carol");

		channel.remove("bob");
		assertEquals(java.util.List.of("carol"), channel.floorQueue(),
				"a leaver is scrubbed from the queue so it can't wait for a turn it can never take");
	}

	@Test
	void disablingTheQueueClearsItAndResetsTheReservation() {
		Channel channel = queueChannel();
		channel.enqueueFloor("bob");
		channel.reserveHead(Instant.EPOCH.plusSeconds(5));

		channel.setFloorQueueEnabled(false);
		assertFalse(channel.isFloorQueueEnabled());
		assertTrue(channel.floorQueue().isEmpty(), "disabling the queue clears the waiting line");
		assertEquals(Instant.EPOCH, channel.floorReservedAt(), "disabling resets the reservation clock");
	}

	@Test
	void reserveHeadIsIdempotentWhileAReservationRuns() {
		Channel channel = queueChannel();
		channel.enqueueFloor("bob");
		channel.enqueueFloor("carol");
		assertEquals("bob", channel.reserveHead(Instant.EPOCH.plusSeconds(5)), "the head is reserved fresh");

		// A redundant reserveHead while bob's window runs is a no-op: the invariant floorReservedAt != EPOCH iff a
		// reservation runs makes it return null without moving the clock — this is what makes the unconditional
		// reserveAndNotify calls in release/leave/mute safe across the monitor gap (no backward/duplicate re-stamp).
		assertNull(channel.reserveHead(Instant.EPOCH.plusSeconds(8)),
				"reserveHead is idempotent — a running reservation is not re-stamped");
		assertEquals(Instant.EPOCH.plusSeconds(5), channel.floorReservedAt(),
				"the running reservation's clock is unchanged");
	}

	@Test
	void expiredReservationHeadNeverExpiresAnUnstampedHead() {
		Channel channel = queueChannel();
		channel.enqueueFloor("bob");
		// Floor free + queue non-empty, but no reservation stamped yet (the transient before reserveHead runs).
		assertNull(channel.expiredReservationHead(Instant.EPOCH.plusSeconds(1_000)),
				"an unstamped head is never treated as an expired reservation, so the sweep can't drop it");
	}

	@Test
	void dequeuingTheReservedHeadResetsTheReservationButAMidQueueDequeueDoesNot() {
		Channel channel = queueChannel();
		channel.enqueueFloor("bob");
		channel.enqueueFloor("carol");
		channel.enqueueFloor("dave");
		channel.reserveHead(Instant.EPOCH.plusSeconds(5));   // bob reserved

		assertTrue(channel.dequeueFloor("carol"), "carol was queued");
		assertEquals(Instant.EPOCH.plusSeconds(5), channel.floorReservedAt(),
				"a mid-queue dequeue preserves the reserved head's window");

		assertTrue(channel.dequeueFloor("bob"), "bob was the reserved head");
		assertEquals(Instant.EPOCH, channel.floorReservedAt(),
				"dequeuing the reserved head ends its window (clock reset), so the next head gets a fresh one");
		assertEquals("dave", channel.reserveHead(Instant.EPOCH.plusSeconds(9)), "the next head is now freshly reservable");
	}

	@Test
	void removingTheReservedHeadResetsTheReservationButAMidQueueRemoveDoesNot() {
		Channel channel = queueChannel();
		channel.add(session("bob"));
		channel.add(session("carol"));
		channel.enqueueFloor("bob");
		channel.enqueueFloor("carol");
		channel.reserveHead(Instant.EPOCH.plusSeconds(5));   // bob reserved

		channel.remove("carol");   // mid-queue removal (disconnect)
		assertEquals(Instant.EPOCH.plusSeconds(5), channel.floorReservedAt(),
				"removing a mid-queue member preserves the reserved head's window");

		channel.remove("bob");     // remove the reserved head
		assertEquals(Instant.EPOCH, channel.floorReservedAt(),
				"removing the reserved head ends its window (clock reset)");
	}

	@Test
	void clearFloorResetsHolderQueueAndReservation() {
		Channel channel = queueChannel();
		// A held floor with a queue behind it is fully reset.
		channel.tryAcquireFloor("alice", Instant.EPOCH);
		channel.enqueueFloor("bob");
		channel.clearFloor();
		assertInstanceOf(None.class, channel.floorHolder(), "clearFloor drops the holder");
		assertTrue(channel.floorQueue().isEmpty(), "clearFloor empties the queue");

		// A running reservation (floor free, head reserved) is also reset.
		channel.enqueueFloor("carol");
		channel.reserveHead(Instant.EPOCH.plusSeconds(5));
		assertEquals(Instant.EPOCH.plusSeconds(5), channel.floorReservedAt());
		channel.clearFloor();
		assertEquals(Instant.EPOCH, channel.floorReservedAt(), "clearFloor resets the reservation clock");
		assertTrue(channel.floorQueue().isEmpty());
	}
}
