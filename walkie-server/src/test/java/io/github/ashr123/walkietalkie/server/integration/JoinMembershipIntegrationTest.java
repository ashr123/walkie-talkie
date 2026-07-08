package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/// Join semantics over real sockets: the [ServerMessage.Joined] snapshot (membership, adopted owner/mode),
/// the [ServerMessage.MemberJoined] fan-out, display-name fidelity over the wire, the floor snapshot a
/// newcomer receives, GLOBAL_PTT channel-name forcing, channel-name validation, the bad-message path, a
/// cross-transport join, and the implicit leave that a re-join performs.
class JoinMembershipIntegrationTest extends WebSocketIntegrationTestSupport {

	@Test
	void joinedSnapshotIncludesAllExistingMembersAndAdoptsOwnerAndMode() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("snap", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);

			// Bob asks for FULL_DUPLEX but must adopt the existing channel's MULTI_CHANNEL_PTT and its owner.
			send(sb, new ClientMessage.Join("snap", ChannelMode.FULL_DUPLEX, "Bob", null));
			ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);

			assertEquals(ChannelMode.MULTI_CHANNEL_PTT, joinedB.mode(), "the existing channel's mode wins");
			assertEquals(joinedA.selfId(), joinedB.ownerId(), "the creator stays owner");
			Set<String> ids = joinedB.members().stream().map(MemberInfo::id).collect(Collectors.toSet());
			assertEquals(2, joinedB.members().size());
			assertTrue(ids.contains(joinedA.selfId()) && ids.contains(joinedB.selfId()),
					"the snapshot lists both members including self");
		}
	}

	@Test
	void renamingBroadcastsTheNewNameToOtherMembersOverTheWire() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("rename-room", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("rename-room", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			awaitType(b.messages, ServerMessage.Joined.class);
			awaitType(a.messages, ServerMessage.MemberJoined.class);

			// Exercises the JSON wire round-trip of the new Rename / MemberRenamed message types end-to-end.
			send(sa, new ClientMessage.Rename("Alicia"));

			ServerMessage.MemberRenamed renamed = awaitType(b.messages, ServerMessage.MemberRenamed.class);
			assertEquals(joinedA.selfId(), renamed.memberId(), "the session id is unchanged; only the label moves");
			assertEquals("Alicia", renamed.displayName());
		}
	}

	@Test
	void memberJoinedFanOutCarriesNewcomerInfoAndExcludesTheJoiner() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("fanout", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("fanout", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);

			ServerMessage.MemberJoined notice = awaitType(a.messages, ServerMessage.MemberJoined.class);
			assertEquals(joinedB.selfId(), notice.member().id(), "the existing member is told the newcomer's id");
			assertEquals("Bob", notice.member().displayName());

			// Bob is never told about his own arrival.
			assertNotReceived(b.messages, ServerMessage.MemberJoined.class);
		}
	}

	@Test
	void displayNamesSurviveOverTheWireIncludingTheAllowedPunctuation() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("names", ChannelMode.MULTI_CHANNEL_PTT, "Al.ice-1_2", null));
			awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("names", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);

			assertTrue(joinedB.members().stream().anyMatch(m -> "Al.ice-1_2".equals(m.displayName())),
					"the validated display name is preserved verbatim in the snapshot");
			assertEquals("Bob", awaitType(a.messages, ServerMessage.MemberJoined.class).member().displayName());
		}
	}

	@Test
	void aNewcomerJoiningWhileTheFloorIsHeldReceivesFloorTaken() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("held", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);

			send(sb, new ClientMessage.Join("held", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			awaitType(b.messages, ServerMessage.Joined.class);
			ServerMessage.FloorTaken taken = awaitType(b.messages, ServerMessage.FloorTaken.class);
			assertEquals(joinedA.selfId(), taken.holderId(), "the newcomer learns who currently holds the floor");
		}
	}

	@Test
	void globalPttForcesTheChannelNameSoDifferentlyNamedJoinersShareOneChannel() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("alpha", ChannelMode.GLOBAL_PTT, "Alice", null));
			ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
			assertEquals("global", joinedA.channel(), "GLOBAL_PTT forces the channel name to 'global'");

			send(sb, new ClientMessage.Join("bravo", ChannelMode.GLOBAL_PTT, "Bob", null));
			ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);
			assertEquals("global", joinedB.channel());

			// Despite the different requested names, both landed in the same channel, so Alice sees Bob.
			assertEquals(joinedB.selfId(), awaitType(a.messages, ServerMessage.MemberJoined.class).member().id());
		}
	}

	@Test
	void aJoinWithAnIllegalChannelNameIsRejectedAsInvalidChannel() throws Exception {
		CollectingHandler a = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login())) {
			send(sa, new ClientMessage.Join("bad name!", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.ErrorMessage error = awaitType(a.messages, ServerMessage.ErrorMessage.class);
			assertEquals(ErrorCode.INVALID_CHANNEL, error.code());
			assertNotReceived(a.messages, ServerMessage.Joined.class);
		}
	}

	@Test
	void aMalformedControlFrameYieldsABadMessageErrorAndKeepsTheConnection() throws Exception {
		CollectingHandler a = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login())) {
			sendRaw(sa, "{not valid json");
			assertEquals(ErrorCode.BAD_MESSAGE, awaitType(a.messages, ServerMessage.ErrorMessage.class).code());

			// The connection survives a bad frame: a subsequent valid join still works.
			send(sa, new ClientMessage.Join("recover", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			assertNotNull(awaitType(a.messages, ServerMessage.Joined.class));
		}
	}

	@Test
	void membershipIsTransportAgnosticAcrossAudioAndSignalingClients() throws Exception {
		CollectingHandler audioPeer = new CollectingHandler();
		CollectingHandler signalPeer = new CollectingHandler();
		try (WebSocketSession sAudio = connect(AUDIO, audioPeer, login());
		     WebSocketSession sSignal = connect(SIGNAL, signalPeer, login())) {
			send(sAudio, new ClientMessage.Join("mix", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.Joined joinedAudio = awaitType(audioPeer.messages, ServerMessage.Joined.class);

			send(sSignal, new ClientMessage.Join("mix", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			ServerMessage.Joined joinedSignal = awaitType(signalPeer.messages, ServerMessage.Joined.class);

			// The signaling client joined the same channel and the audio client is notified of it.
			assertTrue(joinedSignal.members().stream().anyMatch(m -> m.id().equals(joinedAudio.selfId())));
			assertEquals(joinedSignal.selfId(), awaitType(audioPeer.messages, ServerMessage.MemberJoined.class).member().id());
		}
	}

	@Test
	void reJoiningAnotherChannelLeavesTheOldOneAndReleasesAHeldFloor() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("old", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("old", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			awaitType(b.messages, ServerMessage.Joined.class);
			awaitType(a.messages, ServerMessage.MemberJoined.class);

			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			// Alice re-joins a different channel; the server leaves "old" first.
			send(sa, new ClientMessage.Join("new", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));

			// FloorIdle precedes MemberLeft: the floor is freed atomically with the release (before removal), while
			// MemberLeft is broadcast only after removal (ghost-member fix). Order is benign — independent state.
			assertNotNull(awaitType(b.messages, ServerMessage.FloorIdle.class), "her held floor is released");
			ServerMessage.MemberLeft left = awaitType(b.messages, ServerMessage.MemberLeft.class);
			assertEquals(joinedA.selfId(), left.memberId(), "the old channel is told Alice left");

			ServerMessage.Joined rejoined = awaitType(a.messages, ServerMessage.Joined.class);
			assertEquals("new", rejoined.channel());
			// Identity survives an in-place switch: it is the SAME socket, so selfId is unchanged (CLIENT_PROTOCOL
			// §3c — unlike a reconnect, which gets a fresh id). Clients rely on this (the browser keeps its mic /
			// AudioContext, the Java client its capture/playback loops) rather than treating a switch as a reconnect.
			assertEquals(joinedA.selfId(), rejoined.selfId(), "an in-place channel switch keeps the same session id");
		}
	}
}
