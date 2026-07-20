package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Push-to-talk floor arbitration end-to-end over real sockets: the authoritative [ServerMessage.FloorStatus]
/// snapshot on grant/release, a busy-floor refusal (which now sends NOTHING — the client re-derives "busy" from
/// the last snapshot, since `FloorTaken`/`FloorIdle`/`FloorDenied` are retired), the full-duplex and global-PTT
/// variants, release/leave/disconnect by the holder vs. a non-holder, the owner-toggleable floor QUEUE lifecycle
/// (raise-hand -> reserve -> claim), and the audio-relay floor rules.
///
/// Idle auto-release is disabled here (`floor-idle-release-seconds=0`) so the wire assertions can't race the
/// once-per-second background sweep reclaiming a briefly-idle holder mid-test; the idle-release path is covered
/// deterministically with a controllable clock in the unit tests.
@TestPropertySource(properties = "walkie.floor-idle-release-seconds=0")
class FloorLifecycleIntegrationTest extends WebSocketIntegrationTestSupport {

	private static byte[] frame(String marker) {
		return ("audio-" + marker).getBytes(StandardCharsets.UTF_8);
	}

	/// Joins both sessions to a fresh channel in `mode` and returns the two selfIds [a,b]. Every real join now
	/// seeds the joiner with an authoritative FloorStatus snapshot (toOne); Alice's is consumed by the
	/// MemberJoined await, and Bob's is drained here — so per-test awaits below see the EVENT-driven FloorStatus
	/// broadcasts, not the leftover join snapshot.
	private String[] joinPair(String channel, ChannelMode mode,
	                          WebSocketSession sa, CollectingHandler a,
	                          WebSocketSession sb, CollectingHandler b) throws Exception {
		send(sa, new ClientMessage.Join(channel, mode, "Alice", null));
		ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
		send(sb, new ClientMessage.Join(channel, mode, "Bob", null));
		ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);
		awaitType(a.messages, ServerMessage.MemberJoined.class);
		awaitType(b.messages, ServerMessage.FloorStatus.class);
		return new String[]{joinedA.selfId(), joinedB.selfId()};
	}

	@Test
	void aSecondRequesterIsRefusedWhileTheFloorIsHeld() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("deny", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.FloorStatus.class).holderId(),
					"the others see who holds the floor via the snapshot");

			// With the queue off, a busy-floor request is refused and NOTHING is sent — the client keeps showing
			// "busy" from the last FloorStatus (FloorDenied is retired).
			send(sb, new ClientMessage.RequestFloor());
			assertNotReceived(b.messages, ServerMessage.FloorGranted.class);
		}
	}

	@Test
	void releaseByTheHolderBroadcastsFloorStatusAndLetsTheNextRequesterIn() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("release", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			assertEquals(ids[0], awaitType(a.messages, ServerMessage.FloorStatus.class).holderId());   // drain Alice's grant snapshot
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.FloorStatus.class).holderId());

			send(sa, new ClientMessage.ReleaseFloor());
			assertNull(awaitType(a.messages, ServerMessage.FloorStatus.class).holderId());              // drain Alice's release snapshot
			assertNull(awaitType(b.messages, ServerMessage.FloorStatus.class).holderId(), "others are told the floor is free");

			send(sb, new ClientMessage.RequestFloor());
			awaitType(b.messages, ServerMessage.FloorGranted.class);
			assertEquals(ids[1], awaitType(a.messages, ServerMessage.FloorStatus.class).holderId(),
					"the previous holder sees Bob take the floor");
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
			awaitType(a.messages, ServerMessage.FloorStatus.class);   // drain Alice's grant snapshot
			awaitType(b.messages, ServerMessage.FloorStatus.class);   // Bob sees Alice hold

			send(sb, new ClientMessage.ReleaseFloor());   // Bob does not hold it -> no-op
			assertNotReceived(a.messages, ServerMessage.FloorStatus.class);
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
			assertNotReceived(b.messages, ServerMessage.FloorStatus.class);   // no arbitration broadcast in full-duplex

			send(sb, new ClientMessage.RequestFloor());
			awaitType(b.messages, ServerMessage.FloorGranted.class);
		}
	}

	@Test
	void globalPttGrantsTheFirstRefusesTheSecondThenFreesOnRelease() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("ignored", ChannelMode.GLOBAL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(a.messages, ServerMessage.FloorStatus.class);   // drain Alice's grant snapshot
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.FloorStatus.class).holderId());

			send(sb, new ClientMessage.RequestFloor());   // busy -> refused, nothing sent
			assertNotReceived(b.messages, ServerMessage.FloorGranted.class);

			send(sa, new ClientMessage.ReleaseFloor());
			assertNull(awaitType(a.messages, ServerMessage.FloorStatus.class).holderId());   // drain Alice's release snapshot
			assertNull(awaitType(b.messages, ServerMessage.FloorStatus.class).holderId());
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
	void theHolderLeavingTheChannelFreesAndBroadcastsFloorStatus() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("holder-leave", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.FloorStatus.class).holderId());

			send(sa, new ClientMessage.Leave());
			// Emission order on the holder (also owner) leaving is now MemberLeft -> OwnerChanged -> FloorStatus:
			// the member is removed first (clearing the holder), then survivors hear it left + the re-election, then
			// the freed-floor snapshot is re-broadcast. awaitType consumes any type it skips.
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.MemberLeft.class).memberId());
			assertNull(awaitType(b.messages, ServerMessage.FloorStatus.class).holderId(), "the leaver's floor is released");
		}
	}

	@Test
	void theHolderDisconnectingWhileHoldingBroadcastsFloorStatus() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		try (WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("holder-drop", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.FloorStatus.class).holderId());

			sa.close(CloseStatus.NORMAL);   // abrupt disconnect while holding
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.MemberLeft.class).memberId());
			assertNull(awaitType(b.messages, ServerMessage.FloorStatus.class).holderId(), "the held floor is released");
		}
	}

	@Test
	void aNonHolderLeavingReBroadcastsTheUnchangedFloorStatus() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("nonholder-leave", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(a.messages, ServerMessage.FloorStatus.class);   // drain Alice's grant snapshot
			awaitType(b.messages, ServerMessage.FloorStatus.class);   // Bob sees Alice hold

			// Bob (not the holder / reserved head / queued) leaves. handleLeave re-broadcasts the floor snapshot
			// unconditionally, but the floor is UNCHANGED — Alice still holds it — so the survivor just re-syncs.
			send(sb, new ClientMessage.Leave());
			assertEquals(ids[1], awaitType(a.messages, ServerMessage.MemberLeft.class).memberId());
			assertEquals(ids[0], awaitType(a.messages, ServerMessage.FloorStatus.class).holderId(),
					"the re-broadcast snapshot still shows Alice holding — a non-holder's leave doesn't change the floor");
		}
	}

	@Test
	void theFloorQueueLetsAMemberRaiseHandBeReservedAndClaimOverTheWire() throws Exception {
		// Exercises the BRAND-NEW queue wire types — SetFloorQueue, FloorQueueChanged, FloorStatus.waiting and
		// FloorReserved — through real Jackson 3 (de)serialization. Deliberately avoids the reservation-EXPIRY
		// timer (a 10 s wall-clock window that would make an integration test slow/flaky — that path is covered
		// deterministically by the unit + stress tests): Bob claims his turn immediately, well inside the window.
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			String[] ids = joinPair("queue-wire", ChannelMode.MULTI_CHANNEL_PTT, sa, a, sb, b);

			// The owner enables the queue.
			send(sa, new ClientMessage.SetFloorQueue(true));
			assertTrue(awaitType(a.messages, ServerMessage.FloorQueueChanged.class).enabled(), "the queue is enabled");
			awaitType(a.messages, ServerMessage.FloorStatus.class);   // drain the toggle snapshot (Alice)
			awaitType(b.messages, ServerMessage.FloorQueueChanged.class);
			awaitType(b.messages, ServerMessage.FloorStatus.class);   // drain the toggle snapshot (Bob)

			// Alice grabs the floor.
			send(sa, new ClientMessage.RequestFloor());
			awaitType(a.messages, ServerMessage.FloorGranted.class);
			awaitType(a.messages, ServerMessage.FloorStatus.class);   // drain Alice's grant snapshot
			assertEquals(ids[0], awaitType(b.messages, ServerMessage.FloorStatus.class).holderId());

			// Bob raises his hand (queues) behind the busy floor.
			send(sb, new ClientMessage.RequestFloor());
			ServerMessage.FloorStatus queued = awaitType(b.messages, ServerMessage.FloorStatus.class);
			assertEquals(ids[0], queued.holderId(), "Alice still holds");
			assertEquals(List.of(ids[1]), queued.waiting(), "Bob is shown waiting in line");
			awaitType(a.messages, ServerMessage.FloorStatus.class);   // drain Alice's copy of the queue update

			// Alice releases -> Bob is reserved (his turn) and told so, with the claim window.
			send(sa, new ClientMessage.ReleaseFloor());
			assertEquals(10, awaitType(b.messages, ServerMessage.FloorReserved.class).claimSeconds(),
					"the reserved head is told its turn with the default 10 s claim window");
			ServerMessage.FloorStatus reserved = awaitType(b.messages, ServerMessage.FloorStatus.class);
			assertNull(reserved.holderId(), "the floor is free while reserved");
			assertEquals(List.of(ids[1]), reserved.waiting(), "Bob is the head being offered the floor");

			// Bob claims his turn -> granted, becomes the holder, leaves the queue.
			send(sb, new ClientMessage.RequestFloor());
			awaitType(b.messages, ServerMessage.FloorGranted.class);
			ServerMessage.FloorStatus live = awaitType(b.messages, ServerMessage.FloorStatus.class);
			assertEquals(ids[1], live.holderId(), "Bob is now live");
			assertTrue(live.waiting().isEmpty(), "Bob left the queue on claiming");
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
			awaitType(b.messages, ServerMessage.FloorStatus.class);
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
			awaitType(b.messages, ServerMessage.FloorStatus.class);

			byte[] held = frame("global");
			sendBinary(sa, held);
			assertPrefixedBody(held, b.audio.poll(5, TimeUnit.SECONDS), "the holder's global-PTT audio reaches Bob");

			sendBinary(sb, frame("bob-global"));
			assertNull(a.audio.poll(1, TimeUnit.SECONDS), "a non-holder's audio is dropped in global PTT");
		}
	}
}
