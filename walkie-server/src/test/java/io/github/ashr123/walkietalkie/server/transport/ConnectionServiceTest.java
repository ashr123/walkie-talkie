package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.MutableClock;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.ratelimit.AudioRateLimiter;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Drives [ConnectionService] with fake sessions to verify channel ownership, mode adoption and the
/// owner-only mode-change broadcast.
class ConnectionServiceTest {

	private final ChannelRegistry channelRegistry = new ChannelRegistry();
	private final WalkieProperties properties = new WalkieProperties(List.of("*"), 8192, 65536, 100, 5, 300, null);
	private final ConnectionService service = new ConnectionService(
			channelRegistry, properties, new AudioRateLimiter(properties));

	/// Builds a service over the shared registry but with a hand-driven clock, so the push-to-talk floor
	/// timers (idle auto-release, max-hold) can be tested deterministically.
	private ConnectionService serviceWithClock(Clock clock, int idleSeconds, int maxHoldSeconds) {
		WalkieProperties props = new WalkieProperties(List.of("*"), 8192, 65536, 1000, idleSeconds, maxHoldSeconds, null);
		return new ConnectionService(channelRegistry, props, new AudioRateLimiter(props), clock);
	}

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
		service.onMessage(session, new ClientMessage.Join(channelName, mode, id, null));
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
	void aDuplicateJoinToTheSameChannelIsIdempotentAndDoesNotChurnMembership() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();
		bob.sent.clear();

		// Alice re-sends Join for the channel she is already in (a duplicate / retry).
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));

		assertEquals("team", firstOf(alice, ServerMessage.Joined.class).channel(),
				"a duplicate join re-sends the snapshot so the client re-syncs");
		assertTrue(bob.sent.isEmpty(),
				"the other members see no churn (no MemberLeft/MemberJoined) on a duplicate join");
		assertEquals(2, channel("team").size(), "membership is unchanged");
	}

	@Test
	void joiningADifferentChannelStillSwitches() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));

		assertEquals("other", firstOf(alice, ServerMessage.Joined.class).channel(), "joining a different channel switches");
		assertNull(channel("team"), "the previous channel is left (and dropped once empty)");
		assertEquals(1, channel("other").size());
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
	void aJoinWithAnInvalidDisplayNameIsRejected() {
		FakeClientSession session = session("sess-1");
		service.onMessage(session, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "has spaces", null));

		assertEquals("invalid_display_name", firstOf(session, ServerMessage.ErrorMessage.class).code());
		assertNull(channel("team"), "the channel is not created when the join is rejected");
	}

	@Test
	void aJoinWithAMismatchedKeyCheckIsRejected() {
		join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);   // creator establishes keyCheck = null (unencrypted)
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-X"));

		assertEquals("passphrase_mismatch", firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertEquals(1, channel("team").size(), "the mismatched joiner is not added");
	}

	@Test
	void changingToGlobalPttIsRejectedOutsideTheGlobalChannel() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangeMode(ChannelMode.GLOBAL_PTT));

		assertEquals("invalid_mode", firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, channel("team").mode(), "the mode is unchanged");
	}

	// --- additional branch coverage: validation edges and audio-relay rules that are awkward to reach over
	// --- a real socket (the WebSocket container caps the wire frame, so onAudio's own size guard, the
	// --- signaling-transport skip, and the per-recipient failure isolation are exercised directly here).

	private static FakeClientSession signaling(String id) {
		return new FakeClientSession(id, Transport.SIGNALING, id);
	}

	@Test
	void aJoinWithANullChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join(null, ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals("invalid_channel", firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnEmptyChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals("invalid_channel", firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnOverlongChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("x".repeat(65), ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals("invalid_channel", firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void channelNameValidationHappensBeforeDisplayNameValidation() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("bad name", ChannelMode.MULTI_CHANNEL_PTT, "also bad!!", null));
		assertEquals("invalid_channel", firstOf(s, ServerMessage.ErrorMessage.class).code(),
				"the channel name is validated before the display name");
	}

	@Test
	void aJoinWithANullDisplayNameIsRejectedAsInvalidDisplayName() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, null, null));
		assertEquals("invalid_display_name", firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnOverlongDisplayNameIsRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "x".repeat(33), null));
		assertEquals("invalid_display_name", firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void globalPttWithANullChannelNameStillJoinsTheGlobalChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join(null, ChannelMode.GLOBAL_PTT, "alice", null));
		assertEquals("global", firstOf(s, ServerMessage.Joined.class).channel(),
				"GLOBAL_PTT forces the name to 'global' before the null-channel check");
	}

	// --- the server-managed "global" channel: reserved name, always unencrypted, owned by no participant ----

	@Test
	void joiningTheGlobalNameInMultiChannelModeIsReservedRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("global", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals("reserved_channel", firstOf(s, ServerMessage.ErrorMessage.class).code());
		assertNull(channel("global"), "the global channel is not created by a reserved-name rejection");
	}

	@Test
	void joiningTheGlobalNameInFullDuplexIsReservedRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("global", ChannelMode.FULL_DUPLEX, "alice", null));
		assertEquals("reserved_channel", firstOf(s, ServerMessage.ErrorMessage.class).code());
		assertNull(channel("global"));
	}

	@Test
	void anEncryptedGlobalPttJoinIsRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join(null, ChannelMode.GLOBAL_PTT, "alice", "kcv-X"));
		assertEquals("encryption_not_allowed", firstOf(s, ServerMessage.ErrorMessage.class).code());
		assertNull(channel("global"), "an encrypted join never creates the global channel");
	}

	@Test
	void theGlobalChannelIsServerOwnedAndUnencrypted() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		assertEquals("server", firstOf(alice, ServerMessage.Joined.class).ownerId(),
				"the global channel is owned by the server sentinel, not the joiner");
		assertEquals("server", channel("global").ownerId());
		assertNull(channel("global").keyCheck(), "the global channel is never encrypted");
	}

	@Test
	void everyoneCanJoinTheGlobalChannelWithoutAPassphrase() {
		join("alice", null, ChannelMode.GLOBAL_PTT);
		FakeClientSession bob = join("bob", null, ChannelMode.GLOBAL_PTT);
		assertEquals("global", firstOf(bob, ServerMessage.Joined.class).channel());
		assertEquals(2, channel("global").size(), "both passphrase-less users are in the global channel");
	}

	@Test
	void aGlobalMemberCannotChangeTheMode() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		alice.sent.clear();
		service.onMessage(alice, new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));
		assertEquals("not_owner", firstOf(alice, ServerMessage.ErrorMessage.class).code(),
				"no participant owns the server-managed global channel");
		assertEquals(ChannelMode.GLOBAL_PTT, channel("global").mode(), "the global mode is fixed");
	}

	@Test
	void globalOwnershipDoesNotTransferWhenAMemberLeaves() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		FakeClientSession bob = join("bob", null, ChannelMode.GLOBAL_PTT);
		bob.sent.clear();

		service.onClose(alice);   // a member leaving must not re-elect a user as owner of the global room

		assertTrue(bob.sent.stream().noneMatch(ServerMessage.OwnerChanged.class::isInstance),
				"the global channel stays server-owned; no ownership is re-elected on a leave");
		assertEquals("server", channel("global").ownerId());
	}

	@Test
	void theGlobalChannelIsRecreatedServerOwnedAfterEmptying() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		service.onClose(alice);
		assertNull(channel("global"), "the global channel is dropped once empty");
		FakeClientSession bob = join("bob", null, ChannelMode.GLOBAL_PTT);
		assertEquals("server", firstOf(bob, ServerMessage.Joined.class).ownerId(),
				"the recreated global channel is server-owned again");
	}

	@Test
	void emptyAndOversizedAudioFramesAreDroppedAndAFrameAtTheLimitIsRelayed() {
		FakeClientSession alice = join("alice", "fd", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "fd", ChannelMode.FULL_DUPLEX);

		service.onAudio(alice, new byte[0]);          // empty -> dropped
		service.onAudio(alice, new byte[8193]);       // over the 8192 limit -> dropped
		assertEquals(0, bob.audio.size(), "empty and oversized frames are dropped");

		service.onAudio(alice, new byte[8192]);       // exactly at the limit -> relayed
		assertEquals(1, bob.audio.size());
		assertEquals(8192 + 1, bob.audio.getFirst().length, "the relayed frame is the body plus the 1-byte stream-index prefix");
	}

	@Test
	void audioFromASignalingSenderIsDroppedAndSignalingMembersAreSkipped() {
		FakeClientSession alice = join("alice", "room", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "room", ChannelMode.FULL_DUPLEX);
		FakeClientSession carol = signaling("carol");
		service.onMessage(carol, new ClientMessage.Join("room", ChannelMode.FULL_DUPLEX, "carol", null));

		byte[] frame = {1, 2, 3};
		service.onAudio(alice, frame);
		assertEquals(1, bob.audio.size(), "an audio-relay member receives the frame");
		assertEquals(0, carol.audio.size(), "a signaling member is skipped");

		service.onAudio(carol, frame);   // a signaling sender cannot relay audio
		assertEquals(1, bob.audio.size(), "audio from a signaling sender is dropped");
	}

	@Test
	void audioWithNoChannelIsDroppedWithoutException() {
		FakeClientSession s = session("never-joined");
		assertDoesNotThrow(() -> service.onAudio(s, new byte[]{1, 2, 3}));
	}

	@Test
	void aRelayFailureToOneRecipientDoesNotBlockOthers() {
		FakeClientSession alice = join("alice", "relayfail", ChannelMode.FULL_DUPLEX);
		FakeClientSession good = join("good", "relayfail", ChannelMode.FULL_DUPLEX);
		ClientSession bad = new ThrowingSession("bad");
		service.onMessage(bad, new ClientMessage.Join("relayfail", ChannelMode.FULL_DUPLEX, "bad", null));

		byte[] frame = {4, 5, 6};
		assertDoesNotThrow(() -> service.onAudio(alice, frame));
		assertEquals(1, good.audio.size(), "the healthy recipient still receives despite a failing peer");
		assertArrayEquals(frame, Arrays.copyOfRange(good.audio.getFirst(), 1, good.audio.getFirst().length),
				"the delivered frame body is intact (after stripping the stream-index prefix)");
	}

	@Test
	void releaseFloorInFullDuplexIsANoOp() {
		FakeClientSession alice = join("alice", "fd-rel", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "fd-rel", ChannelMode.FULL_DUPLEX);
		alice.sent.clear();
		bob.sent.clear();

		service.onMessage(alice, new ClientMessage.ReleaseFloor());
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorIdle.class::isInstance),
				"a full-duplex release broadcasts nothing");
	}

	@Test
	void leavingWhenNeverInAChannelIsASilentNoOp() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Leave());
		assertTrue(s.sent.isEmpty(), "leaving with no channel sends nothing — no error, no broadcast");
	}

	@Test
	void audioForAChannelThatNoLongerExistsIsDroppedWithoutException() {
		FakeClientSession s = session("orphan");
		s.joinedChannel("ghost");   // a channel that was already dropped from the registry (a leave-during-send race)
		assertDoesNotThrow(() -> service.onAudio(s, new byte[]{1, 2, 3}));
	}

	@Test
	void leavingAChannelThatNoLongerExistsIsASilentNoOp() {
		FakeClientSession s = session("orphan");
		s.joinedChannel("ghost");
		assertDoesNotThrow(() -> service.onMessage(s, new ClientMessage.Leave()));
		assertNull(channel("ghost"), "no channel is resurrected");
		assertTrue(s.sent.isEmpty(), "a vanished-channel leave broadcasts nothing");
	}

	@Test
	void aFloorRequestForAChannelThatNoLongerExistsIsIgnored() {
		FakeClientSession s = session("orphan");
		s.joinedChannel("ghost");
		service.onMessage(s, new ClientMessage.RequestFloor());
		assertTrue(s.sent.isEmpty(), "a vanished channel yields no grant and no error");
	}

	@Test
	void audioIsPrefixedWithTheSendersStreamIndex() {
		FakeClientSession alice = join("alice", "fd-sid", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "fd-sid", ChannelMode.FULL_DUPLEX);

		byte[] frame = {1, 2, 3};
		service.onAudio(alice, frame);

		byte[] received = bob.audio.getFirst();
		int aliceSid = channel("fd-sid").streamIndexOf("alice");
		assertEquals(aliceSid, received[0] & 0xFF, "the frame is prefixed with the sender's stream index");
		assertArrayEquals(frame, Arrays.copyOfRange(received, 1, received.length), "the body is the original frame");
	}

	@Test
	void membersGetDistinctStreamIndicesAnnouncedInJoinedAndMemberJoined() {
		FakeClientSession alice = join("alice", "sid-roster", ChannelMode.MULTI_CHANNEL_PTT);
		int aliceSid = channel("sid-roster").streamIndexOf("alice");
		assertEquals(aliceSid, firstOf(alice, ServerMessage.Joined.class).members().getFirst().streamId());

		join("bob", "sid-roster", ChannelMode.MULTI_CHANNEL_PTT);
		int bobSid = channel("sid-roster").streamIndexOf("bob");
		assertNotEquals(aliceSid, bobSid, "members get distinct stream indices");
		assertEquals(bobSid, firstOf(alice, ServerMessage.MemberJoined.class).member().streamId(),
				"existing members learn the newcomer's index via MemberJoined");
	}

	@Test
	void aFreedStreamIndexIsNotImmediatelyReused() {
		FakeClientSession alice = join("alice", "sid-reuse", ChannelMode.MULTI_CHANNEL_PTT);
		join("bob", "sid-reuse", ChannelMode.MULTI_CHANNEL_PTT);   // keeps the channel alive when Alice leaves
		int aliceSid = channel("sid-reuse").streamIndexOf("alice");

		service.onClose(alice);
		join("carol", "sid-reuse", ChannelMode.MULTI_CHANNEL_PTT);

		assertNotEquals(aliceSid, channel("sid-reuse").streamIndexOf("carol"),
				"a freed index is quarantined by the rotating allocator, not immediately reused");
	}

	// --- push-to-talk floor anti-hogging (idle auto-release + max-hold), driven with a fake clock ----------

	@Test
	void idleAutoReleaseReassignsTheFloorFromASilentHolder() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 5, 0);   // idle-release 5 s, max-hold off
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("ptt", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("ptt", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));

		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice takes the floor at t=0
		assertTrue(channel("ptt").holdsFloor("alice"));

		bob.sent.clear();
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // immediate retry: alice isn't idle yet
		assertEquals("alice", firstOf(bob, ServerMessage.FloorDenied.class).currentHolderId());
		assertTrue(channel("ptt").holdsFloor("alice"), "the floor is still alice's");

		clock.advance(Duration.ofSeconds(6));                    // 6 s of silence from alice
		bob.sent.clear();
		alice.sent.clear();
		svc.onMessage(bob, new ClientMessage.RequestFloor());

		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorGranted.class::isInstance),
				"bob preempts the idle holder and is granted the floor");
		assertTrue(alice.sent.stream().anyMatch(ServerMessage.FloorTaken.class::isInstance),
				"the ex-holder is told (via FloorTaken) that the floor moved, so its client stops transmitting");
		assertTrue(channel("ptt").holdsFloor("bob"));
	}

	@Test
	void maxHoldReleasesTheFloorAfterContinuousHolding() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 0, 10);   // idle-release off, max-hold 10 s
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("ptt2", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("ptt2", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds at t=0
		bob.audio.clear();
		alice.sent.clear();
		bob.sent.clear();

		svc.onAudio(alice, new byte[]{1, 2, 3});                  // within the cap -> relayed
		assertEquals(1, bob.audio.size(), "a frame within the hold cap is relayed");

		clock.advance(Duration.ofSeconds(11));                   // past the 10 s cap
		svc.onAudio(alice, new byte[]{4, 5, 6});

		assertEquals(1, bob.audio.size(), "the over-cap frame is dropped, not relayed");
		assertTrue(alice.sent.stream().anyMatch(ServerMessage.FloorIdle.class::isInstance),
				"the speaker is told its talk time was up so its client stops");
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorIdle.class::isInstance),
				"the other members are told the floor was released");
		assertFalse(channel("ptt2").holdsFloor("alice"), "the floor is freed for the next requester");
	}

	@Test
	void anActiveSpeakerRefreshingTheFloorIsNotPreempted() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 5, 0);   // idle-release 5 s, max-hold off
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("ptt-active", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("ptt-active", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice acquires at t=0
		assertTrue(channel("ptt-active").holdsFloor("alice"));

		clock.advance(Duration.ofSeconds(4));
		svc.onAudio(alice, new byte[]{1, 2, 3});                  // active speaker -> refreshes the activity mark to t=4 s

		clock.advance(Duration.ofSeconds(2));                    // t=6 s: only 2 s since the last frame (< 5 s idle window)
		bob.sent.clear();
		svc.onMessage(bob, new ClientMessage.RequestFloor());

		assertEquals("alice", firstOf(bob, ServerMessage.FloorDenied.class).currentHolderId(),
				"an active speaker's recent frame refreshes the activity mark, so bob is denied");
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"an actively-talking holder is not preempted");
		assertTrue(channel("ptt-active").holdsFloor("alice"), "the floor is still alice's");
	}

	@Test
	void aSenderOverItsAudioRateHasFramesDroppedBeforeFanOut() {
		WalkieProperties props = new WalkieProperties(List.of("*"), 8192, 65536, 2, 0, 0, null);   // 2 fps -> burst 2
		ConnectionService svc = new ConnectionService(channelRegistry, props, new AudioRateLimiter(props));
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("flood", ChannelMode.FULL_DUPLEX, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("flood", ChannelMode.FULL_DUPLEX, "bob", null));

		byte[] frame = {1, 2, 3};
		svc.onAudio(alice, frame);
		svc.onAudio(alice, frame);
		assertEquals(2, bob.audio.size(), "the burst-capacity frames are relayed");
		svc.onAudio(alice, frame);   // over the per-sender rate -> dropped before fan-out
		assertEquals(2, bob.audio.size(), "a frame past the rate cap is dropped before fan-out");
	}

	@Test
	void onCloseForgetsTheSendersRateBucketSoAReconnectStartsFull() {
		WalkieProperties props = new WalkieProperties(List.of("*"), 8192, 65536, 1, 0, 0, null);   // 1 fps -> burst 1
		ConnectionService svc = new ConnectionService(channelRegistry, props, new AudioRateLimiter(props));
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "bob", null));

		byte[] frame = {1, 2, 3};
		svc.onAudio(alice, frame);
		svc.onAudio(alice, frame);   // exhausts the 1-token bucket
		assertEquals(1, bob.audio.size(), "the second frame is over the cap and dropped");

		svc.onClose(alice);          // must evict alice's bucket
		svc.onMessage(alice, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "alice", null));   // same id reconnects
		bob.audio.clear();
		svc.onAudio(alice, frame);
		assertEquals(1, bob.audio.size(), "after onClose evicts the bucket, the reconnecting id starts from a full bucket");
	}

	/// A [ClientSession] whose audio send always fails, used to verify [ConnectionService#onAudio] isolates a
	/// single failing recipient and still delivers to the others.
	private static final class ThrowingSession implements ClientSession {

		private final String id;
		private String displayName;
		private String channelName;

		private ThrowingSession(String id) {
			this.id = id;
			this.displayName = id;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public Transport transport() {
			return Transport.AUDIO_RELAY;
		}

		@Override
		public String displayName() {
			return displayName;
		}

		@Override
		public void setDisplayName(String displayName) {
			this.displayName = displayName;
		}

		@Override
		public String channelName() {
			return channelName;
		}

		@Override
		public void joinedChannel(String channel) {
			this.channelName = channel;
		}

		@Override
		public void leftChannel() {
			this.channelName = null;
		}

		@Override
		public boolean supportsAudioRelay() {
			return true;
		}

		@Override
		public void send(ServerMessage message) {
			// control frames are irrelevant to this fake
		}

		@Override
		public void sendAudio(byte[] audio) {
			throw new RuntimeException("simulated send failure");
		}
	}
}
