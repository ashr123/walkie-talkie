package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Owner-only mode changes and their side effects, plus ownership transfer when the owner departs. The
/// owner-gating and ownership election are transport-agnostic, so one case runs over /ws/signal.
class ModeOwnershipIntegrationTest extends WebSocketIntegrationTestSupport {

	/// Joins Alice (owner) then Bob to a fresh channel, draining Alice's MemberJoined; returns [aliceId,bobId].
	private String[] joinPair(String path, String channel, ChannelMode mode,
	                          WebSocketSession sa, CollectingHandler a,
	                          WebSocketSession sb, CollectingHandler b) throws Exception {
		send(sa, new ClientMessage.Join(channel, mode, "Alice", null));
		ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
		send(sb, new ClientMessage.Join(channel, mode, "Bob", null));
		ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);
		awaitType(a.messages, ServerMessage.MemberJoined.class);
		return new String[]{joinedA.selfId(), joinedB.selfId()};
	}

	@Test
	void theOwnerChangingModeBroadcastsModeChangedAndFloorIdleToEveryoneIncludingSelf() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			joinPair(AUDIO, "mode-bcast", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));

			assertEquals(ChannelMode.FULL_DUPLEX, awaitType(a.messages, ServerMessage.ModeChanged.class).mode());
			assertNotNull(awaitType(a.messages, ServerMessage.FloorIdle.class), "the owner is reset too");
			assertEquals(ChannelMode.FULL_DUPLEX, awaitType(b.messages, ServerMessage.ModeChanged.class).mode());
			assertNotNull(awaitType(b.messages, ServerMessage.FloorIdle.class));
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void changingModeClearsAHeldFloorSoTheNextRequestIsGranted() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			joinPair(AUDIO, "mode-clear", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			// Bob takes the floor.
			send(sb, new ClientMessage.RequestFloor());
			awaitType(b.messages, ServerMessage.FloorGranted.class);
			awaitType(a.messages, ServerMessage.FloorTaken.class);

			// The owner cycles the mode away and back; each change clears the floor.
			send(sa, new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));
			awaitType(a.messages, ServerMessage.ModeChanged.class);
			send(sa, new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));
			awaitType(a.messages, ServerMessage.ModeChanged.class);

			// Alice can now grab the floor — proof Bob's hold was cleared (otherwise she'd be denied).
			send(sa, new ClientMessage.RequestFloor());
			assertNotNull(awaitType(a.messages, ServerMessage.FloorGranted.class));
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void changingToTheSameModeIsANoOp() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			joinPair(AUDIO, "mode-same", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));   // already this mode
			assertNotReceived(a.messages, ServerMessage.ModeChanged.class);
			assertNotReceived(b.messages, ServerMessage.ModeChanged.class);
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void theGlobalChannelIsServerManagedAndItsModeCannotBeChanged() throws Exception {
		CollectingHandler a = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		try {
			send(sa, new ClientMessage.Join("anything", ChannelMode.GLOBAL_PTT, "Alice", null));
			ServerMessage.Joined joined = awaitType(a.messages, ServerMessage.Joined.class);
			assertEquals("global", joined.channel());
			assertEquals("server", joined.ownerId(), "the global channel is server-owned, not owned by the joiner");

			// No participant owns the server-managed global room, so its mode is fixed: a ChangeMode is
			// rejected as not_owner rather than broadcasting a ModeChanged.
			send(sa, new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));
			assertEquals("not_owner", awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void changingModeWithoutAChannelIsNotInChannel() throws Exception {
		CollectingHandler a = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		try {
			send(sa, new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));
			assertEquals("not_in_channel", awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void theOwnerDepartingTransfersOwnershipAndAnnouncesIt() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			String[] ids = joinPair(AUDIO, "owner-leave", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.Leave());
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.MemberLeft.class).memberId());
			assertEquals(ids[1], awaitType(b.messages, ServerMessage.OwnerChanged.class).ownerId(),
					"the sole remaining member becomes the new owner");
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void aNonOwnerDepartingDoesNotChangeOwnership() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			String[] ids = joinPair(AUDIO, "nonowner-leave", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sb, new ClientMessage.Leave());   // Bob is not the owner
			assertEquals(ids[1], awaitType(a.messages, ServerMessage.MemberLeft.class).memberId());
			assertNotReceived(a.messages, ServerMessage.OwnerChanged.class);
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void anOwnerLeavingWhileHoldingTheFloorBroadcastsBothFloorIdleAndOwnerChanged() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			String[] ids = joinPair(AUDIO, "owner-floor-leave", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			send(sa, new ClientMessage.Leave());
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.MemberLeft.class).memberId());
			assertNotNull(awaitType(b.messages, ServerMessage.FloorIdle.class), "the held floor is released");
			assertEquals(ids[1], awaitType(b.messages, ServerMessage.OwnerChanged.class).ownerId());
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void aReElectedOwnerCanChangeModeWhileaLaterNonOwnerCannot() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		CollectingHandler c = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		WebSocketSession sc = connect(AUDIO, c, login());
		try {
			String[] ids = joinPair(AUDIO, "reelect", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			// Alice leaves; Bob is the only remaining member, so he is elected owner.
			send(sa, new ClientMessage.Leave());
			assertEquals(ids[1], awaitType(b.messages, ServerMessage.OwnerChanged.class).ownerId());

			// The new owner may change the mode.
			send(sb, new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));
			assertEquals(ChannelMode.FULL_DUPLEX, awaitType(b.messages, ServerMessage.ModeChanged.class).mode());

			// A member who joins afterwards is not the owner and cannot.
			send(sc, new ClientMessage.Join("reelect", ChannelMode.FULL_DUPLEX, "Carol", null));
			awaitType(c.messages, ServerMessage.Joined.class);
			send(sc, new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));
			assertEquals("not_owner", awaitType(c.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
			sc.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void ownershipTransferIsTransportAgnosticOverSignaling() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		WebSocketSession sb = connect(SIGNAL, b, login());
		try {
			String[] ids = joinPair(SIGNAL, "owner-signal", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.Leave());
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.MemberLeft.class).memberId());
			assertEquals(ids[1], awaitType(b.messages, ServerMessage.OwnerChanged.class).ownerId());
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}
}
