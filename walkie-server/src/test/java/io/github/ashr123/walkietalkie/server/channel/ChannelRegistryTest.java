package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.None;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRegistryTest {

	private final ChannelRegistry registry = new ChannelRegistry();

	private FakeClientSession session(String id) {
		return new FakeClientSession(id, Transport.AUDIO_RELAY, id);
	}

	@Test
	void reusesChannelForSameMode() {
		Channel first = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, session("a"));
		Channel second = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, session("b"));
		assertSame(first, second);
		assertEquals(2, second.size());
	}

	@Test
	void adoptsExistingModeAndOwnerOnJoin() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, session("a"));
		Channel joined = registry.joinOrCreate("team", ChannelMode.FULL_DUPLEX, session("b"));
		assertSame(created, joined);
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, joined.mode(), "the channel keeps its original mode");
		assertEquals("a", joined.ownerId(), "the creator stays the owner");
		assertEquals(2, joined.size());
	}

	@Test
	void dropsChannelOnceEmpty() {
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, session("a"));
		assertEquals(1, registry.channelCount());
		registry.leave("team", "a");
		assertEquals(0, registry.channelCount());
		assertInstanceOf(None.class, registry.find("team"));
	}
}
