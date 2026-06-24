package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.floor.FloorControlService;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Drives [ConnectionService] with fake sessions to verify channel ownership, mode adoption and the
/// owner-only mode-change broadcast.
class ConnectionServiceTest {

	private final ChannelRegistry channelRegistry = new ChannelRegistry();
	private final ConnectionService service = new ConnectionService(
			channelRegistry, new FloorControlService(), new WalkieProperties(List.of("*"), 8192, 65536));

	private static FakeClientSession session(String id) {
		return new FakeClientSession(id, Transport.AUDIO_RELAY, id);
	}

	private static <T extends ServerMessage> T firstOf(FakeClientSession session, Class<T> type) {
		return session.sent.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
	}

	private Channel channel(String name) {
		return channelRegistry.find(name) instanceof Some(Channel channel) ? channel : null;
	}

	private FakeClientSession join(String id, String channelName, ChannelMode mode) {
		FakeClientSession session = session(id);
		service.onMessage(session, new ClientMessage.Join(channelName, mode, id));
		return session;
	}

	@Test
	void theCreatorOwnsTheChannelAndJoinedCarriesTheOwner() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		ServerMessage.Joined joined = firstOf(alice, ServerMessage.Joined.class);
		assertEquals("alice", joined.ownerId());
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, joined.mode());
	}

	@Test
	void aLaterJoinerAdoptsTheExistingMode() {
		join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.FULL_DUPLEX);
		ServerMessage.Joined joined = firstOf(bob, ServerMessage.Joined.class);
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, joined.mode(), "the existing channel's mode wins");
		assertEquals("alice", joined.ownerId());
	}

	@Test
	void theOwnerCanChangeTheModeAndEveryoneIsNotified() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();
		bob.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));

		assertEquals(ChannelMode.FULL_DUPLEX, firstOf(alice, ServerMessage.ModeChanged.class).mode());
		assertEquals(ChannelMode.FULL_DUPLEX, firstOf(bob, ServerMessage.ModeChanged.class).mode());
		assertEquals(ChannelMode.FULL_DUPLEX, channel("team").mode());
	}

	@Test
	void aNonOwnerCannotChangeTheMode() {
		join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		bob.sent.clear();

		service.onMessage(bob, new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));

		assertEquals("not_owner", firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, channel("team").mode(), "the mode is unchanged");
	}

	@Test
	void ownershipTransfersWhenTheOwnerLeaves() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		bob.sent.clear();

		service.onClose(alice);

		assertEquals("bob", firstOf(bob, ServerMessage.OwnerChanged.class).ownerId());
		assertEquals("bob", channel("team").ownerId());
	}

	@Test
	void changingToGlobalPttIsRejectedOutsideTheGlobalChannel() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangeMode(ChannelMode.GLOBAL_PTT));

		assertEquals("invalid_mode", firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, channel("team").mode(), "the mode is unchanged");
	}
}
