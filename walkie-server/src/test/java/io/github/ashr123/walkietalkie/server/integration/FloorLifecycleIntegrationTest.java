package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Push-to-talk floor arbitration end-to-end: grant/deny/release broadcasts and their payloads, the
/// full-duplex and global-PTT variants, release/leave/disconnect by the holder vs. a non-holder, and the
/// audio-relay floor rules (only the holder's audio is fanned out; full-duplex fans out without a floor).
class FloorLifecycleIntegrationTest extends WebSocketIntegrationTestSupport {

	private static byte[] frame(String marker) {
		return ("audio-" + marker).getBytes(StandardCharsets.UTF_8);
	}

	/// Joins both sessions to a fresh channel in `mode` and returns the two selfIds [a,b].
	private String[] joinPair(String channel, ChannelMode mode,
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
	void aSecondRequesterIsDeniedWithTheCurrentHolderId() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("deny", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			send(sb, new ClientMessage.RequestFloor());
			ServerMessage.FloorDenied denied = awaitType(b.messages, ServerMessage.FloorDenied.class);
			assertEquals(ids[0], denied.currentHolderId(), "the denial names the current holder");
		}
	}

	@Test
	void releaseByTheHolderBroadcastsFloorIdleAndLetsTheNextRequesterIn() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("release", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			send(sa, new ClientMessage.ReleaseFloor());
			assertNotNull(awaitType(b.messages, ServerMessage.FloorIdle.class), "others are told the floor is free");

			send(sb, new ClientMessage.RequestFloor());
			awaitType(b.messages, ServerMessage.FloorGranted.class);
			assertEquals(ids[1], awaitType(a.messages, ServerMessage.FloorTaken.class).holderId());
		}
	}

	@Test
	void releaseByANonHolderIsANoOp() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			joinPair("noop-release", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			send(sb, new ClientMessage.ReleaseFloor());   // Bob does not hold it
			assertNotReceived(a.messages, ServerMessage.FloorIdle.class);
		}
	}

	@Test
	void fullDuplexAutoGrantsWithoutBroadcastingAndBothMembersMayRequest() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			joinPair("fd-floor", ChannelMode.FULL_DUPLEX, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			assertNotReceived(b.messages, ServerMessage.FloorTaken.class);   // no arbitration broadcast in full-duplex

			send(sb, new ClientMessage.RequestFloor());
			awaitType(b.messages, ServerMessage.FloorGranted.class);
		}
	}

	@Test
	void globalPttGrantsTheFirstDeniesTheSecondThenFreesOnRelease() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("ignored", ChannelMode.GLOBAL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			send(sb, new ClientMessage.RequestFloor());
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.FloorDenied.class).currentHolderId());

			send(sa, new ClientMessage.ReleaseFloor());
			awaitType(b.messages, ServerMessage.FloorIdle.class);
			send(sb, new ClientMessage.RequestFloor());
			awaitType(b.messages, ServerMessage.FloorGranted.class);
		}
	}

	@Test
	void requestingOrReleasingTheFloorWithoutAChannelIsNotInChannel() throws Exception {
		CollectingHandler a = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login())) {
			send(sa, new ClientMessage.RequestFloor());
			assertEquals(ErrorCode.NOT_IN_CHANNEL, awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
			send(sa, new ClientMessage.ReleaseFloor());
			assertEquals(ErrorCode.NOT_IN_CHANNEL, awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		}
	}

	@Test
	void theHolderLeavingTheChannelFreesAndBroadcastsTheFloor() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("holder-leave", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			send(sa, new ClientMessage.Leave());
			// FloorIdle precedes MemberLeft — see the disconnect test above for why this order is correct + benign.
			assertNotNull(awaitType(b.messages, ServerMessage.FloorIdle.class), "the leaver's floor is released");
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.MemberLeft.class).memberId());
		}
	}

	@Test
	void theHolderDisconnectingWhileHoldingBroadcastsFloorIdle() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		try (WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("holder-drop", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			sa.close(CloseStatus.NORMAL);   // abrupt disconnect while holding
			// FloorIdle precedes MemberLeft: the floor is freed and announced atomically with the release (before
			// the member is removed), whereas MemberLeft is broadcast only after removal to avoid a ghost-member
			// race. The order is irrelevant to clients — the two update independent state, and FloorIdle has no id.
			assertNotNull(awaitType(b.messages, ServerMessage.FloorIdle.class));
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.MemberLeft.class).memberId());
		}
	}

	@Test
	void aNonHolderLeavingDoesNotBroadcastFloorIdle() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("nonholder-leave", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			send(sb, new ClientMessage.Leave());   // Bob is not the holder
			assertEquals(ids[1], awaitType(a.messages, ServerMessage.MemberLeft.class).memberId());
			assertNotReceived(a.messages, ServerMessage.FloorIdle.class);
		}
	}

	@Test
	void audioIsRelayedOnlyWhileTheSenderHoldsTheFloor() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			joinPair("audio-ptt", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);

			// No floor held -> dropped.
			sendBinary(sa, frame("nofloor"));
			assertNull(b.audio.poll(1, TimeUnit.SECONDS), "audio with no floor is dropped");

			// Alice holds the floor -> her audio reaches Bob byte-for-byte.
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);
			byte[] held = frame("held");
			sendBinary(sa, held);
			assertPrefixedBody(held, b.audio.poll(5, TimeUnit.SECONDS), "the holder's audio reaches Bob");

			// Bob does not hold the floor -> his audio is dropped.
			sendBinary(sb, frame("bob"));
			assertNull(a.audio.poll(1, TimeUnit.SECONDS), "a non-holder's audio is dropped");
		}
	}

	@Test
	void fullDuplexFansOutBothWaysWithoutAFloorAndNeverEchoesTheSender() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			joinPair("audio-fd", ChannelMode.FULL_DUPLEX, sa, a, sb, b);

			byte[] fromA = frame("fromA");
			sendBinary(sa, fromA);
			assertPrefixedBody(fromA, b.audio.poll(5, TimeUnit.SECONDS), "full-duplex fans out without a floor");
			assertNull(a.audio.poll(1, TimeUnit.SECONDS), "the sender never hears its own frame");

			byte[] fromB = frame("fromB");
			sendBinary(sb, fromB);
			assertPrefixedBody(fromB, a.audio.poll(5, TimeUnit.SECONDS), "the other direction works too");
		}
	}

	@Test
	void globalPttFansOutTheHolderAudioAndDropsANonHolder() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			joinPair("ignored2", ChannelMode.GLOBAL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(b.messages, ServerMessage.FloorTaken.class);

			byte[] held = frame("global");
			sendBinary(sa, held);
			assertPrefixedBody(held, b.audio.poll(5, TimeUnit.SECONDS), "the holder's global-PTT audio reaches Bob");

			sendBinary(sb, frame("bob-global"));
			assertNull(a.audio.poll(1, TimeUnit.SECONDS), "a non-holder's audio is dropped in global PTT");
		}
	}
}
