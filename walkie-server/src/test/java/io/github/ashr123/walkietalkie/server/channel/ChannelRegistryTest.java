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
		Channel first = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"));
		Channel second = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		assertSame(first, second);
		assertEquals(2, second.size());
	}

	@Test
	void adoptsExistingModeAndOwnerOnJoin() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"));
		Channel joined = registry.joinOrCreate("team", ChannelMode.FULL_DUPLEX, null, session("b"));
		assertSame(created, joined);
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, joined.mode(), "the channel keeps its original mode");
		assertEquals("a", joined.ownerId(), "the creator stays the owner");
		assertEquals(2, joined.size());
	}

	@Test
	void dropsChannelOnceEmpty() {
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"));
		assertEquals(1, registry.channelCount());
		registry.leave("team", "a");
		assertEquals(0, registry.channelCount());
		assertInstanceOf(None.class, registry.find("team"));
	}

	@Test
	void refusesAJoinerWhoseKeyCheckDoesNotMatch() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"));
		assertNotNull(created, "the creator establishes the channel's key-check");

		assertNull(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-B", session("b")),
				"a different key-check (wrong passphrase) is refused");
		assertNull(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("c")),
				"an unencrypted joiner is refused from an encrypted channel");
		assertEquals(1, created.size(), "refused joiners are not added");

		assertSame(created, registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("d")),
				"a matching key-check joins normally");
		assertEquals(2, created.size());
	}
}
