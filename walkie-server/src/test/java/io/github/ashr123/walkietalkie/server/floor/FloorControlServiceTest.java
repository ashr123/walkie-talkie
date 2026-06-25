package io.github.ashr123.walkietalkie.server.floor;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FloorControlServiceTest {

	private final FloorControlService service = new FloorControlService();

	private FakeClientSession session(String id) {
		return new FakeClientSession(id, Transport.AUDIO_RELAY, id);
	}

	@Test
	void grantsFloorToFirstRequesterAndDeniesSecond() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null);
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		channel.add(alice);
		channel.add(bob);

		assertInstanceOf(FloorResult.Granted.class, service.requestFloor(channel, alice));

		FloorResult second = service.requestFloor(channel, bob);
		FloorResult.Denied denied = assertInstanceOf(FloorResult.Denied.class, second);
		assertEquals("alice", denied.currentHolderId());
		assertTrue(channel.holdsFloor("alice"));
		assertFalse(channel.holdsFloor("bob"));
	}

	@Test
	void releaseLetsTheNextSpeakerIn() {
		Channel channel = new Channel("c", ChannelMode.GLOBAL_PTT, "alice", null);
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");

		assertInstanceOf(FloorResult.Granted.class, service.requestFloor(channel, alice));
		assertTrue(service.releaseFloor(channel, alice));
		assertInstanceOf(FloorResult.Granted.class, service.requestFloor(channel, bob));
		assertEquals(new Some<>("bob"), channel.floorHolder());
	}

	@Test
	void releaseByNonHolderIsRejected() {
		Channel channel = new Channel("c", ChannelMode.MULTI_CHANNEL_PTT, "alice", null);
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");

		service.requestFloor(channel, alice);
		assertFalse(service.releaseFloor(channel, bob));
		assertTrue(channel.holdsFloor("alice"));
	}

	@Test
	void fullDuplexGrantsEveryoneAndTracksNoHolder() {
		Channel channel = new Channel("c", ChannelMode.FULL_DUPLEX, "alice", null);
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");

		assertInstanceOf(FloorResult.Granted.class, service.requestFloor(channel, alice));
		assertInstanceOf(FloorResult.Granted.class, service.requestFloor(channel, bob));
		assertTrue(channel.holdsFloor("alice"));
		assertTrue(channel.holdsFloor("bob"));
		assertInstanceOf(None.class, channel.floorHolder());
	}

	@Test
	void deniesWithoutAHolderWhenAcquireFailsButTheFloorReadsAsFree() {
		// The release-between-acquire-and-read race: tryAcquireFloor fails, yet the floor already reads empty,
		// so the denial can't name a holder. Forced here with a mocked channel since it can't occur single-threaded.
		Channel channel = mock(Channel.class);
		when(channel.mode()).thenReturn(ChannelMode.MULTI_CHANNEL_PTT);
		when(channel.tryAcquireFloor(any())).thenReturn(false);
		when(channel.floorHolder()).thenReturn(None.instance());

		FloorResult.Denied denied = assertInstanceOf(FloorResult.Denied.class,
				service.requestFloor(channel, session("alice")));
		assertNull(denied.currentHolderId(), "the denial carries no holder when the floor read back empty");
	}
}
