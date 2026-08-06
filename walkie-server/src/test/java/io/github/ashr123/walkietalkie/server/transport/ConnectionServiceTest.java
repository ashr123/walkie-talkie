package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.MutableClock;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.channel.ChannelRegistry;
import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.server.TestKeyChecks;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.JoinRequestInfo;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Drives [ConnectionService] with fake sessions to verify channel ownership, mode adoption and the
/// owner-only mode-change broadcast.
class ConnectionServiceTest {

	// A real broadcaster over a throwaway JsonMapper: the fan-out serializes once and FakeClientSession records
	// the typed message it carries, so the encoded JSON is irrelevant to assertions (no round-trip in tests).
	private static final MessageBroadcaster BROADCASTER =
			new MessageBroadcaster(new MessageCodec(JsonMapper.shared()));

	private final ChannelRegistry channelRegistry = new ChannelRegistry();
	private final ConnectionService service = new ConnectionService(
			channelRegistry,
			// Control rate set effectively-unlimited (1_000_000) so the control-plane flood guard never throttles the
			// handful of control messages an ordinary test sends; the dedicated control-flood test uses a low rate.
			new WalkieProperties(
					new String[]{"*"},
					8192,
					65536,
					100,
					1_000_000,
					Duration.ofSeconds(5),
					Duration.ofSeconds(300),
					Duration.ofSeconds(10),
					false, 0,
					null, false, Duration.ZERO),
			BROADCASTER
	);

	/// Builds a service over the shared registry but with a hand-driven clock, so the push-to-talk floor
	/// timers (idle auto-release, max-hold) — and the rate limiters — are all tested against the same deterministic
	/// clock rather than wall time. Control rate is left effectively-unlimited so a fixed clock (no token refill)
	/// doesn't throttle the test's own control messages.
	private ConnectionService serviceWithClock(Clock clock, int idleSeconds, int maxHoldSeconds) {
		return new ConnectionService(
				channelRegistry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						1000,
						1_000_000,
						Duration.ofSeconds(idleSeconds),
						Duration.ofSeconds(maxHoldSeconds),
						Duration.ofSeconds(10),
						false, 0,
						null, false, Duration.ZERO),
				BROADCASTER,
				clock
		);
	}

	private static FakeClientSession session(String id) {
		return new FakeClientSession(id, Transport.AUDIO_RELAY, id);
	}

	private static <T extends ServerMessage> T firstOf(FakeClientSession session, Class<T> type) {
		return session.sent.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
	}

	/// The LAST message of a type a session received — the value it would currently believe, given a recipient's
	/// strictly-ordered (FIFO) mailbox. Used to assert convergence after multiple owner/passphrase changes.
	private static <T extends ServerMessage> T lastOf(FakeClientSession session, Class<T> type) {
		return session.sent.stream().filter(type::isInstance).map(type::cast).reduce((_, b) -> b).orElseThrow();
	}

	/// The channel with `name`, which the caller expects to exist — fails the test with a clear message if it
	/// doesn't, so callers can dereference the result without a null check (a missing channel surfaces as a
	/// readable assertion, not a bare NPE). Use [#channelExists] to assert a channel is absent.
	private Channel channel(String name) {
		return channelRegistry.find(name) instanceof Some(Channel channel)
				? channel
				: fail("expected channel '" + name + "' to exist");
	}

	private static boolean received(FakeClientSession session, ErrorCode code) {
		return session.sent.stream().anyMatch(m -> m instanceof ServerMessage.ErrorMessage(
				ErrorCode c, _
		) && c == code);
	}

	private FakeClientSession join(String id, String channelName, ChannelMode mode) {
		FakeClientSession session = session(id);
		service.onMessage(session, new ClientMessage.Join(channelName, mode, id, TestKeyChecks.keyCheckFor(mode)));
		return session;
	}

	// --- channel affinity (multi-instance routing) -------------------------------------------------

	/// A service with `channelAffinity` ON, over the shared registry — so the routing invariant is exercised.
	private ConnectionService affinityService() {
		return new ConnectionService(
				channelRegistry,
				new WalkieProperties(new String[]{"*"}, 8192, 65536, 100, 1_000_000, Duration.ofSeconds(5), Duration.ofSeconds(300), Duration.ofSeconds(10), false, 0, null, true, Duration.ZERO), BROADCASTER);
	}

	/// Whether a channel with `name` currently exists — the absence counterpart to [#channel], for asserting a
	/// channel was never created or was dropped once empty (where [#channel] would instead fail the test).
	private boolean channelExists(String name) {
		return channelRegistry.find(name) instanceof Some<Channel>;
	}

	@Test
	void channelAffinityAllowsJoiningTheHandshakeChannel() {
		ConnectionService svc = affinityService();
		FakeClientSession alice = session("alice");
		alice.setHandshakeChannel("team1");   // the router pinned this socket to team1
		svc.onMessage(alice, new ClientMessage.Join("team1", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		assertEquals("team1", alice.channelName());
		assertFalse(received(alice, ErrorCode.CHANNEL_ROUTING_MISMATCH));
	}

	@Test
	void channelAffinityAllowsSwitchingToAChannelThisInstanceAlreadyHosts() {
		ConnectionService svc = affinityService();
		FakeClientSession bob = session("bob");
		bob.setHandshakeChannel("team2");
		svc.onMessage(bob, new ClientMessage.Join("team2", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));   // team2 now hosted here
		FakeClientSession alice = session("alice");
		alice.setHandshakeChannel("team1");
		svc.onMessage(alice, new ClientMessage.Join("team1", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.Join("team2", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));   // co-located switch
		assertEquals("team2", alice.channelName());
		assertFalse(received(alice, ErrorCode.CHANNEL_ROUTING_MISMATCH));
	}

	@Test
	void channelAffinityRefusesSwitchingToAChannelOwnedByAnotherInstance() {
		ConnectionService svc = affinityService();
		FakeClientSession alice = session("alice");
		alice.setHandshakeChannel("team1");
		svc.onMessage(alice, new ClientMessage.Join("team1", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		// team9 is neither the handshake channel nor hosted here → this socket can't serve it.
		svc.onMessage(alice, new ClientMessage.Join("team9", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		assertTrue(received(alice, ErrorCode.CHANNEL_ROUTING_MISMATCH));
		assertEquals("team1", alice.channelName(), "the rejected switch must not drop the client from its channel");
		assertFalse(channelExists("team9"), "the wrong-instance channel must not be created here");
	}

	@Test
	void withoutChannelAffinityASwitchToAnyChannelIsAllowed() {
		// The default `service` has affinity OFF: switching to a brand-new channel is fine (single instance).
		FakeClientSession alice = join("alice", "team1", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.Join("team9", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		assertEquals("team9", alice.channelName());
		assertFalse(received(alice, ErrorCode.CHANNEL_ROUTING_MISMATCH));
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
	void renamingBroadcastsMemberRenamedToEveryoneIncludingSelf() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);

		service.onMessage(alice, new ClientMessage.Rename("alice2"));

		ServerMessage.MemberRenamed toBob = firstOf(bob, ServerMessage.MemberRenamed.class);
		assertEquals("alice", toBob.memberId(), "the session id is unchanged — only the label moves");
		assertEquals("alice2", toBob.displayName());

		ServerMessage.MemberRenamed toSelf = firstOf(alice, ServerMessage.MemberRenamed.class);
		assertEquals("alice", toSelf.memberId(), "the renamer is notified too, as confirmation");
		assertEquals("alice2", toSelf.displayName());

		assertEquals("alice2", alice.displayName(), "the server-side session label is updated");
	}

	@Test
	void aRenameIsReflectedInALaterJoinersRoster() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.Rename("alice2"));

		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		ServerMessage.Joined joined = firstOf(bob, ServerMessage.Joined.class);
		assertTrue(joined.members().stream()
						.anyMatch(member -> "alice".equals(member.id()) && "alice2".equals(member.displayName())),
				"a new joiner's roster snapshot carries the renamed member's current name");
	}

	@Test
	void anInvalidRenameIsRejectedAndNotBroadcast() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);

		service.onMessage(alice, new ClientMessage.Rename("bad/name"));   // a slash is not in the allowed charset

		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("alice", alice.displayName(), "the label is unchanged on rejection");
		assertFalse(bob.sent.stream().anyMatch(ServerMessage.MemberRenamed.class::isInstance),
				"no rename is broadcast for an invalid name");
	}

	@Test
	void aDisplayNameMayHoldLettersFromAnyScriptAndSpaces() {
		// The rule is deliberately not ASCII-only: a name is what other people see you as, so Hebrew, Han and
		// accented Latin belong. Multi-word names are allowed too, and several spaces in a row are kept AS TYPED —
		// nothing collapses them, because both clients always print the session id beside a name, so two names that
		// look alike are still told apart.
		assertEquals("יוסי כהן", joinWithName("hebrew", "יוסי כהן").displayName());
		assertEquals("יוֹסֵי", joinWithName("niqqud", "יוֹסֵי").displayName(), "combining marks (niqqud) survive");
		assertEquals("李雷", joinWithName("han", "李雷").displayName());
		assertEquals("José", joinWithName("latin", "José").displayName());
		assertEquals("Roy  Ash", joinWithName("runs", "Roy  Ash").displayName(), "internal spacing is left alone");
	}

	@Test
	void aDisplayNameIsStrippedAndNfcNormalisedBeforeItIsStored() {
		// The server owns the canonical form, so what it stores and broadcasts is what every client compares against.
		// Padding is invisible in the browser roster (HTML drops the edges), so it must not be able to mint a second
		// name; NFC because the same name can arrive composed or decomposed and a rename between the two would look
		// like it did nothing.
		assertEquals("Roy Ash", joinWithName("padded", "  Roy Ash  ").displayName(), "the edges are stripped");
		assertEquals(1, joinWithName("nfc", "e\u0301").displayName().length(),
				"e + combining acute is composed to one code point");
		assertEquals("\u00e9", joinWithName("nfc2", "e\u0301").displayName());
	}

	@Test
	void aDisplayNameOfNothingButSpacesIsRejected() {
		// The pattern alone accepts a lone space (it is in the class, and {1,32} is satisfied) — only stripping BEFORE
		// the match rejects it, by leaving an empty string. Reversing that order looks harmless, hence this test.
		FakeClientSession session = session("blank");
		service.onMessage(session, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "   ", TestKeyChecks.ENCRYPTED));

		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(session, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("team"), "the channel is not created for a blank name");
	}

	@Test
	void aDisplayNameCarryingInvisibleCharactersIsRejected() {
		// Not an impersonation defence — the id tag always accompanies a name — but a control character can split a
		// log record in two (names reach the log through the MDC) and a bidi override reorders the text AROUND it, so
		// a roster row or log line could be made to read differently than it is. One case per family.
		String[] invisible = {
				"Alice\u200BBob",   // ZWSP: a format character, NOT whitespace, so "no whitespace" would miss it
				"Alice\u202EBob",   // RIGHT-TO-LEFT OVERRIDE: reorders the surrounding text
				"Roy\u00A0Ash",     // NBSP: renders as a space but is a different character
				"Roy\u3000Ash",     // ideographic space
				"Alice\u0007",      // BEL, a C0 control
				"Alice\tBob",
				"Alice\nBob"        // a newline would forge a second log line
		};
		for (String name : invisible) {
			FakeClientSession session = session("inv-" + name.hashCode());
			service.onMessage(session, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, name, TestKeyChecks.ENCRYPTED));
			assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(session, ServerMessage.ErrorMessage.class).code(),
					"rejected: " + name.replaceAll("\\p{C}", "?"));
		}
	}

	@Test
	void theDisplayNameLimitCountsCodePointsNotUtf16Units() {
		// A supplementary letter is two UTF-16 units but one code point, and Java's \p{...} classes match it as one
		// unit — so 32 astral letters must pass where a units-based count would see 64 and refuse.
		assertEquals("\uD835\uDD04".repeat(32), joinWithName("astral", "\uD835\uDD04".repeat(32)).displayName());

		FakeClientSession tooLong = session("too-long");
		service.onMessage(tooLong, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "a".repeat(33), TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(tooLong, ServerMessage.ErrorMessage.class).code());
	}

	/// Joins `name` into its own channel and returns the session, so a test can assert on the name the SERVER settled
	/// on (its canonical form) rather than on what was sent.
	private FakeClientSession joinWithName(String channel, String name) {
		FakeClientSession session = session("s-" + channel);
		service.onMessage(session, new ClientMessage.Join(channel, ChannelMode.MULTI_CHANNEL_PTT, name, TestKeyChecks.ENCRYPTED));
		return session;
	}

	@Test
	void togglingTheFloorQueueBroadcastsNoSnapshotWhenTheFloorDidNotMove() {
		// The queue flag is not the floor: enabling changes who MAY wait, not who IS waiting, and disabling an empty
		// queue drops nobody. A snapshot then repeats the floor verbatim and every client narrates it — which is how
		// a plain toggle came to log "Floor is free" into a floor that was already free, twice per round trip.
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		bob.sent.clear();

		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.SetFloorQueue(false));

		assertEquals(2, bob.sent.stream().filter(ServerMessage.FloorQueueChanged.class::isInstance).count(),
				"both toggles are still announced");
		assertEquals(0, bob.sent.stream().filter(ServerMessage.FloorStatus.class::isInstance).count(),
				"but neither moved the floor, so no snapshot is fanned out");
	}

	@Test
	void disablingTheFloorQueueWithSomeoneWaitingStillBroadcastsTheSnapshot() {
		// The other side of the guard: disabling DOES clear the queue, and the members it drops have to see that —
		// suppressing the snapshot here would leave them showing a queue position they no longer hold.
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds the floor
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob is queued behind her
		// lastOf, not firstOf: bob's FIRST FloorStatus is the snapshot that follows his own Joined, long before he
		// queued. What matters here is the state he currently believes.
		assertEquals(List.of(bob.id()), lastOf(bob, ServerMessage.FloorStatus.class).waiting(),
				"precondition: bob really is waiting");
		bob.sent.clear();

		service.onMessage(alice, new ClientMessage.SetFloorQueue(false));

		ServerMessage.FloorStatus snapshot = firstOf(bob, ServerMessage.FloorStatus.class);
		assertTrue(snapshot.waiting().isEmpty(), "the queue was cleared, and the snapshot says so");
	}

	@Test
	void aNoOpRenameToTheSameNameIsIgnoredWithoutChurnOrError() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);

		service.onMessage(alice, new ClientMessage.Rename("alice"));   // already the current name — a no-op

		assertEquals("alice", alice.displayName(), "the label is unchanged");
		assertFalse(bob.sent.stream().anyMatch(ServerMessage.MemberRenamed.class::isInstance),
				"a same-name rename broadcasts no MemberRenamed to other members (no churn)");
		assertFalse(alice.sent.stream().anyMatch(ServerMessage.MemberRenamed.class::isInstance),
				"and none back to the requester either");
		assertFalse(alice.sent.stream().anyMatch(ServerMessage.ErrorMessage.class::isInstance),
				"a no-op is handled gracefully, not as an error");
	}

	@Test
	void aDuplicateJoinToTheSameChannelIsIdempotentAndDoesNotChurnMembership() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();
		bob.sent.clear();

		// Alice re-sends Join for the channel she is already in (a duplicate / retry).
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));

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

		service.onMessage(alice, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));

		assertEquals("other", firstOf(alice, ServerMessage.Joined.class).channel(), "joining a different channel switches");
		assertFalse(channelExists("team"), "the previous channel is left (and dropped once empty)");
		assertEquals(1, channel("other").size());
	}

	@Test
	void aSwitchToAnInvalidTargetKeepsTheClientInItsCurrentChannel() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		// Bad target channel name: validated BEFORE leaving, so the switch is refused without dropping alice.
		service.onMessage(alice, new ClientMessage.Join("bad name!", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));

		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("team", alice.channelName(), "an invalid switch target must not drop the client from its channel");
		assertEquals(1, channel("team").size(), "alice is still a member of her channel");
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

		assertEquals(ErrorCode.NOT_OWNER, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, channel("team").mode(), "the mode is unchanged");
	}

	@Test
	void ownershipTransfersWhenTheOwnerLeaves() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		bob.sent.clear();

		service.onClose(alice, "test close");

		assertEquals("bob", firstOf(bob, ServerMessage.OwnerChanged.class).ownerId());
		assertEquals("bob", channel("team").ownerId());
	}

	@Test
	void aJoinWithAnInvalidDisplayNameIsRejected() {
		FakeClientSession session = session("sess-1");
		service.onMessage(session, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "has/slash", TestKeyChecks.ENCRYPTED));

		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(session, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("team"), "the channel is not created when the join is rejected");
	}

	@Test
	void aChannelNameInAnyScriptIsAccepted() {
		// Channel names used to be ASCII-only. They are now the same allow-list as display names minus whitespace
		// and dots, so a Hebrew or Han room name is a room name.
		List.of("\u05E9\u05DC\u05D5\u05DD", "\u674E\u96F7", "\u0395\u03BB\u03AD\u03BD\u03B7", "\u05E6\u05D5\u05D5\u05EA-1")
				.forEach(name -> {
					FakeClientSession session = session("s-" + name);
					service.onMessage(session, new ClientMessage.Join(name, ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
					assertEquals(name, firstOf(session, ServerMessage.Joined.class).channel(), name);
				});
	}

	@Test
	void aChannelNameMayContainSpacesAndEveryVisibleSpellingIsONEChannel() {
		// Spaces are allowed now. What matters more is that they CONVERGE: the name is the registry key and each
		// client's PBKDF2 salt, so two members typing what looks like the same room must land in one room with one
		// key. NBSP, the ideographic space, a tab and a double space all collapse to a single plain space.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("my room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		assertEquals("my room", firstOf(alice, ServerMessage.Joined.class).channel());

		List.of("my\u00A0room", "my\u3000room", "my\troom", "my   room", "  my room  ")
				.forEach(spelling -> {
					FakeClientSession peer = session("peer-" + spelling.hashCode());
					service.onMessage(peer, new ClientMessage.Join(spelling, ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
					assertEquals("my room", firstOf(peer, ServerMessage.Joined.class).channel(),
							() -> "should be the same room: " + spelling);
				});
		assertEquals(1, channelRegistry.channelCount(), "every spelling joined ONE channel");
	}

	@Test
	void aChannelNameOfNothingButWhitespaceIsRejected() {
		// Collapse-then-strip reduces it to the empty string, which the pattern refuses — the same discipline
		// canonicalDisplayName follows, so a name cannot be minted out of invisible padding.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join(" \u00A0\t ", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(alice, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aChannelNameWithADotOrAnInvisibleCharacterIsStillRejected() {
		// Spaces are now allowed (and collapsed), so what is left to reject is a dot, and anything invisible: the
		// rule is an allow-list, and ZWSP / the bidi overrides are not in the collapsed-whitespace set either, so
		// they survive canonicalisation and are then refused.
		List.of("team.one", "bad\u200bname", "team\u202e", "a/b")
				.forEach(name -> {
					FakeClientSession session = session("bad-" + name.hashCode());
					service.onMessage(session, new ClientMessage.Join(name, ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
					assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(session, ServerMessage.ErrorMessage.class).code(),
							() -> "should reject " + name);
				});
	}

	@Test
	void twoSpellingsOfOneChannelNameLandInTheSAMEChannel() {
		// The whole reason the server canonicalises. Hebrew SHIN WITH SHIN DOT is a composition EXCLUSION, so
		// U+FB2A and the U+05E9 U+05C1 sequence render identically while being different strings. Both clients
		// derive their key from this name (it is the PBKDF2 salt), so if the server kept them as two registry keys
		// these two users would be in separate rooms; and if it keyed them together WITHOUT normalising, their
		// key-checks would disagree and each would be told PASSPHRASE_MISMATCH for a passphrase that is identical.
		// Normalising on both sides is what makes "same visible name" mean "same room AND same key".
		String precomposed = "\uFB2A\u05DC\u05D5\u05DD";
		String decomposed = "\u05E9\u05C1\u05DC\u05D5\u05DD";
		assertNotEquals(precomposed, decomposed, "the two spellings really are different strings");

		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join(precomposed, ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join(decomposed, ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));

		String joined = firstOf(alice, ServerMessage.Joined.class).channel();
		assertEquals(decomposed, joined, "the canonical (NFC) form is what the server stores and echoes");
		assertEquals(joined, firstOf(bob, ServerMessage.Joined.class).channel());
		assertEquals(2, channel(joined).size(), "both spellings put their author in ONE channel");
		assertEquals(1, channelRegistry.channelCount(), "and only one channel was created");
	}

	@Test
	void aChannelNameIsStrippedSoACopyPastedSpaceIsTheSameRoom() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("  team-1  ", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		assertEquals("team-1", firstOf(alice, ServerMessage.Joined.class).channel());
	}

	@Test
	void aJoinWithNoKeyCheckIsRefusedOutsideTheGlobalRoom() {
		// The new rule: an ordinary channel cannot be plaintext, so a join has to bring a key-check. Distinct from
		// PASSPHRASE_MISMATCH, which is about DISAGREEING with a channel's key — this fires before any channel
		// exists, on a creation attempt, which is the case that used to quietly produce a plaintext channel.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));

		assertEquals(ErrorCode.PASSPHRASE_REQUIRED, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("team"), "a refused join must not have created the channel");
	}

	@Test
	void everyModeButGlobalRequiresAKeyCheck() {
		// Full-duplex is not a special case: the rule is about the MODE being global, not about push-to-talk.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("duplex", ChannelMode.FULL_DUPLEX, "alice", null));
		assertEquals(ErrorCode.PASSPHRASE_REQUIRED, firstOf(alice, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aSwitchWithNoKeyCheckIsRefusedAndKeepsTheCurrentChannel() {
		// A switch is a fresh Join on a live socket, and the all-or-nothing guarantee has to hold for this refusal
		// too: the refused switcher keeps the channel, floor and roster entry it already had.
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.Join("elsewhere", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));

		assertEquals(ErrorCode.PASSPHRASE_REQUIRED, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("team", alice.channelName(), "the refused switch left us where we were");
		assertFalse(channelExists("elsewhere"), "and did not create the target");
	}

	@Test
	void anInvalidNameIsReportedBeforeTheMissingPassphrase() {
		// Ordering, deliberately: a join that is wrong in more than one way names the thing the user can SEE. The
		// passphrase check sits after the name checks for exactly this reason, and putting it earlier was measured
		// to break four name-validation tests.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("bad.name", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(alice, ServerMessage.ErrorMessage.class).code());

		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "x".repeat(33), null));
		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(bob, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAMismatchedKeyCheckIsRejected() {
		// The creator establishes the channel's key-check (TestKeyChecks.ENCRYPTED); this is encrypted-vs-WRONG-KEY,
		// not the plaintext-vs-encrypted case it used to be — there are no plaintext channels to contrast with.
		join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-X"));

		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertEquals(1, channel("team").size(), "the mismatched joiner is not added");
	}

	@Test
	void changingToGlobalPttIsRejectedOutsideTheGlobalChannel() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangeMode(ChannelMode.GLOBAL_PTT));

		assertEquals(ErrorCode.INVALID_MODE, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals(ChannelMode.MULTI_CHANNEL_PTT, channel("team").mode(), "the mode is unchanged");
	}

	@Test
	void theOwnerCanChangeThePassphraseAndEveryoneIsNotified() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		alice.sent.clear();
		bob.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));

		assertEquals("kcv-B", firstOf(alice, ServerMessage.PassphraseChanged.class).keyCheck(), "the owner is notified too");
		assertEquals("kcv-B", firstOf(bob, ServerMessage.PassphraseChanged.class).keyCheck());
		assertEquals("kcv-B", channel("team").keyCheck(), "the channel's recorded key-check is rotated");
	}

	@Test
	void aNonOwnerCannotChangeThePassphrase() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		bob.sent.clear();

		service.onMessage(bob, new ClientMessage.ChangePassphrase("kcv-B", null));

		assertEquals(ErrorCode.NOT_OWNER, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertEquals("kcv-A", channel("team").keyCheck(), "a non-owner cannot rotate the key");
	}

	@Test
	void changingThePassphraseBeforeJoiningIsRejected() {
		FakeClientSession session = session("sess-1");
		service.onMessage(session, new ClientMessage.ChangePassphrase("kcv-B", null));
		assertEquals(ErrorCode.NOT_IN_CHANNEL, firstOf(session, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void afterARekeyANewJoinerMustPresentTheNewKeyCheck() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));

		// The old passphrase no longer works...
		FakeClientSession stale = session("stale");
		service.onMessage(stale, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "stale", "kcv-A"));
		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(stale, ServerMessage.ErrorMessage.class).code());

		// ...but the new one does.
		FakeClientSession fresh = session("fresh");
		service.onMessage(fresh, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "fresh", "kcv-B"));
		assertEquals("team", firstOf(fresh, ServerMessage.Joined.class).channel());
		assertEquals(2, channel("team").size(), "alice + the joiner using the new key");
	}

	@Test
	void clearingThePassphraseIsRefusedAndChangesNothing() {
		// The inverse of what this used to assert: encryption can no longer be turned off. Checked for all three of
		// its effects, because a refusal that half-applied would be worse than either outcome — the channel would be
		// plaintext while its members still held keys.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangePassphrase(null, null));   // "make this channel plaintext"

		assertEquals(ErrorCode.PASSPHRASE_REQUIRED, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("kcv-A", channel("team").keyCheck(), "the channel keeps the passphrase it had");
		assertTrue(alice.sent.stream().noneMatch(ServerMessage.PassphraseChanged.class::isInstance),
				"a refused rotation must not announce one");
		// And the key the channel KEPT is the one that still admits its holders — probing with a null key-check
		// would only re-test the join guard, which cannot distinguish "the clear was refused" from "the clear
		// worked and this channel is now plaintext" (both refuse a null). So present kcv-A and expect to get in.
		FakeClientSession holder = session("holder");
		service.onMessage(holder, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "holder", "kcv-A"));
		assertEquals("team", firstOf(holder, ServerMessage.Joined.class).channel(),
				"a holder of the retained passphrase is still admitted");
	}

	@Test
	void rotatingThePassphraseOnTheGlobalRoomIsRefused() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("global", ChannelMode.GLOBAL_PTT, "alice", null));
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));

		// The global room is server-owned (sentinel owner), so no participant can rotate it — it stays unencrypted.
		assertEquals(ErrorCode.NOT_OWNER, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertNull(channel("global").keyCheck());
	}

	@Test
	void aRefusedClearLeavesALaterRotationWorking() {
		// This test used to be "the owner can re-enable encryption after clearing it". That sequence is gone, but
		// the sequencing is still worth pinning, and for a sharp reason: while the clear was being refused, THIS
		// TEST STILL PASSED in its old form — the rotation to kcv-B that followed made every assertion true
		// regardless of whether the clear had worked. A refusal has to be inert, not merely overwritten, so assert
		// the state between the two calls rather than only at the end.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		service.onMessage(alice, new ClientMessage.ChangePassphrase(null, null));      // refused
		assertEquals("kcv-A", channel("team").keyCheck(), "the refused clear left the key-check alone");
		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));   // a real rotation still works
		assertEquals("kcv-B", channel("team").keyCheck());
		FakeClientSession stale = session("stale");
		service.onMessage(stale, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "stale", "kcv-A"));
		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(stale, ServerMessage.ErrorMessage.class).code());
		FakeClientSession fresh = session("fresh");
		service.onMessage(fresh, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "fresh", "kcv-B"));
		assertEquals("team", firstOf(fresh, ServerMessage.Joined.class).channel());
	}

	@Test
	void aSecondRotationReplacesTheKeyCheckAgain() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));
		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-C", null));
		assertEquals("kcv-C", channel("team").keyCheck(), "the latest rotation wins");
		List<ServerMessage.PassphraseChanged> announced = alice.sent.stream()
				.filter(ServerMessage.PassphraseChanged.class::isInstance)
				.map(ServerMessage.PassphraseChanged.class::cast)
				.toList();
		assertEquals("kcv-C", announced.getLast().keyCheck(), "the final announcement carries the final key-check");
	}

	@Test
	void theOwnerCanTransferOwnershipAndEveryoneIsNotified() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();
		bob.sent.clear();

		service.onMessage(alice, new ClientMessage.TransferOwnership("bob"));

		assertEquals("bob", firstOf(alice, ServerMessage.OwnerChanged.class).ownerId(), "the old owner is notified too");
		assertEquals("bob", firstOf(bob, ServerMessage.OwnerChanged.class).ownerId());
		assertEquals("bob", channel("team").ownerId());
	}

	@Test
	void aNonOwnerCannotTransferOwnership() {
		join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		bob.sent.clear();

		service.onMessage(bob, new ClientMessage.TransferOwnership("bob"));

		assertEquals(ErrorCode.NOT_OWNER, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertEquals("alice", channel("team").ownerId(), "ownership is unchanged");
	}

	@Test
	void transferringOwnershipToANonMemberIsRejected() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.TransferOwnership("ghost"));

		assertEquals(ErrorCode.UNKNOWN_TARGET, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("alice", channel("team").ownerId(), "ownership is unchanged");
	}

	@Test
	void transferringOwnershipBeforeJoiningIsRejected() {
		FakeClientSession session = session("sess-1");
		service.onMessage(session, new ClientMessage.TransferOwnership("whoever"));
		assertEquals(ErrorCode.NOT_IN_CHANNEL, firstOf(session, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void theNewOwnerCanRotateAndTheOldOwnerCannot() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		service.onMessage(alice, new ClientMessage.TransferOwnership("bob"));
		alice.sent.clear();
		bob.sent.clear();

		// The NEW owner (bob) can rotate; everyone — including the old owner — is notified.
		service.onMessage(bob, new ClientMessage.ChangePassphrase("kcv-B", null));
		assertEquals("kcv-B", channel("team").keyCheck());
		assertEquals("kcv-B", firstOf(bob, ServerMessage.PassphraseChanged.class).keyCheck());
		assertEquals("kcv-B", firstOf(alice, ServerMessage.PassphraseChanged.class).keyCheck());

		// The OLD owner (alice) no longer can — authority moved with ownership.
		alice.sent.clear();
		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-C", null));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("kcv-B", channel("team").keyCheck(), "the rejected rotation leaves the key unchanged");
	}

	@Test
	void aRotationFollowedByATransferKeepsBothMutations() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));

		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));
		service.onMessage(alice, new ClientMessage.TransferOwnership("bob"));

		// Both writes go through the same channel-name bin lock; the composed result keeps both.
		assertEquals("kcv-B", channel("team").keyCheck(), "the rotation survives the transfer");
		assertEquals("bob", channel("team").ownerId(), "ownership moved");
	}

	@Test
	void afterTransferAndTheNewOwnerLeavingSurvivorsConvergeOnACurrentMember() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession carol = join("carol", "team", ChannelMode.MULTI_CHANNEL_PTT);
		carol.sent.clear();

		service.onMessage(alice, new ClientMessage.TransferOwnership("bob"));   // alice -> bob
		service.onClose(bob, "bob leaves");                                    // new owner departs -> re-election

		// The LAST OwnerChanged a bystander saw must name the channel's CURRENT owner (a present member) — never
		// the departed bob. This is the OwnerChanged-names-a-current-member convergence invariant.
		String announced = lastOf(carol, ServerMessage.OwnerChanged.class).ownerId();
		assertEquals(channel("team").ownerId(), announced, "survivors converge on the live owner");
		assertNotEquals("bob", announced, "never left believing the departed member still owns the channel");
		assertTrue(channel("team").member(announced) instanceof Some<ClientSession>, "the announced owner is a current member");
	}

	@Test
	void aSwitchToAChannelWithAWrongPassphraseLeavesTheSwitcherWhereItWas() {
		// alice owns encrypted "team"; "other" already exists with a DIFFERENT key-check.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bootstrap = session("bootstrap");
		service.onMessage(bootstrap, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "bootstrap", "kcv-OTHER"));
		alice.sent.clear();

		// In-place switch to "other" with the WRONG key-check. The mismatch is only knowable inside the atomic join,
		// so this used to drop the switcher from BOTH channels; the join now departs the old channel only on success.
		service.onMessage(alice, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-WRONG"));

		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("team", alice.channelName(), "a failed switch no longer costs the switcher its channel");
		assertEquals(1, channel("team").size(), "it is still a member there, with its floor and roster entry intact");
		assertEquals(1, channel("other").size(), "the mismatched switcher was not added to the target");
	}

	@Test
	void aFailedSwitchAlsoUndoesTheDisplayNameItCarried() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bootstrap = session("bootstrap");
		service.onMessage(bootstrap, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "bootstrap", "kcv-OTHER"));
		FakeClientSession bob = session("bob");
		service.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		bob.sent.clear();

		// Join carries a display name as well as a channel, so a rejected switch must not apply half of it: staying in
		// "team" under a new name nobody there was told about would leave that roster wrong forever.
		service.onMessage(bob, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "bob-renamed", "kcv-WRONG"));

		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertEquals("bob", bob.displayName(), "the name the failed Join carried is rolled back");
		assertEquals("team", bob.channelName());
		assertTrue(alice.sent.stream().noneMatch(ServerMessage.MemberRenamed.class::isInstance),
				"and the channel it stayed in was never told about a rename that did not happen");
	}

	@Test
	void aSuccessfulSwitchDoesApplyTheDisplayNameItCarried() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));

		service.onMessage(alice, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "alice-renamed", TestKeyChecks.ENCRYPTED));

		assertEquals("alice-renamed", alice.displayName(), "on success the Join's name takes effect");
		assertEquals("other", alice.channelName());
	}

	@Test
	void anUninvolvedMemberAlsoHearsTheOwnerAndPassphraseChanges() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		service.onMessage(session("bob"), new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		FakeClientSession carol = session("carol");   // neither owner nor the transfer target — a pure bystander
		service.onMessage(carol, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "carol", "kcv-A"));
		carol.sent.clear();

		// Both broadcasts fan out to the WHOLE channel (forEach), so the bystander must receive them.
		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));
		assertEquals("kcv-B", firstOf(carol, ServerMessage.PassphraseChanged.class).keyCheck(), "bystander hears the rotation");
		service.onMessage(alice, new ClientMessage.TransferOwnership("bob"));
		assertEquals("bob", firstOf(carol, ServerMessage.OwnerChanged.class).ownerId(), "bystander hears the transfer");
	}

	// --- additional branch coverage: validation edges and audio-relay rules that are awkward to reach over
	// --- a real socket (the WebSocket container caps the wire frame, so onAudio's own size guard, the
	// --- signaling-transport skip, and the per-recipient failure isolation are exercised directly here).

	private static FakeClientSession signaling(String id) {
		return new FakeClientSession(id, Transport.SIGNALING, id);
	}

	/// Builds the mixed-transport channel the join path now REFUSES, by adding the second member to the channel
	/// directly instead of through `Join`.
	///
	/// That is deliberate, and it is the right shape for a defence-in-depth gate: the signaling gate below is the
	/// server-side BELT, so its test must construct the state the invariant forbids and assert the backstop still
	/// holds. Routing through `Join` would make these tests pass vacuously — the join is refused, so the member is
	/// never there to be signalled at — which is exactly what happened to an earlier version of them.
	private FakeClientSession forceIntoChannel(String channelName, FakeClientSession session) {
		channel(channelName).add(session);
		session.joinedChannel(channelName);
		return session;
	}

	@Test
	void webRtcSignalingIsNotRelayedToAMemberOnTheAudioTransport() {
		// The audio path is transport-gated in both directions; signaling was gated in neither, so a relay member
		// could be HANDED an offer — and the browser answered it, attaching its microphone to a peer connection
		// whose media takes neither the floor/owner-mute enforcement nor the passphrase E2EE. Dropped silently,
		// like a signaling session's audio frames: a client sending these on the wrong transport is confused, not
		// owed a reply, and an error per ICE candidate would be a flood.
		FakeClientSession webrtc = signaling("webrtc");
		service.onMessage(webrtc, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession relay = forceIntoChannel("room", session("relay"));
		relay.sent.clear();
		webrtc.sent.clear();

		service.onMessage(webrtc, new ClientMessage.Offer("relay", "sdp-offer"));

		assertTrue(relay.sent.stream().noneMatch(ServerMessage.SignalOffer.class::isInstance),
				"a relay member must not be handed an offer it would answer with its microphone");
		assertTrue(webrtc.sent.stream().noneMatch(ServerMessage.ErrorMessage.class::isInstance),
				"...and the sender gets no error: dropped silently, like audio on the wrong transport");
	}

	@Test
	void signalingFromAMemberOnTheAudioTransportIsAlsoDropped() {
		// The other direction, for symmetry: a relay member that sends signaling (an older client, or one confused
		// about its own transport) must not reach a WebRTC member either.
		FakeClientSession webrtc = signaling("webrtc");
		service.onMessage(webrtc, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession relay = forceIntoChannel("room", session("relay"));
		webrtc.sent.clear();

		service.onMessage(relay, new ClientMessage.Answer("webrtc", "sdp-answer"));
		service.onMessage(relay, new ClientMessage.IceCandidate("webrtc", "cand", "0", 0));

		assertTrue(webrtc.sent.stream().noneMatch(ServerMessage.SignalAnswer.class::isInstance));
		assertTrue(webrtc.sent.stream().noneMatch(ServerMessage.SignalIce.class::isInstance));
	}

	@Test
	void aJoinerOnTheOtherTransportIsRefusedAndTheChannelIsUndisturbed() {
		// The structural fix: one transport per channel, decided by whoever joined first. A mixed channel is a full
		// roster with working floor control and NO audio path in either direction — it looks like it works, which is
		// why it is refused rather than merely warned about.
		FakeClientSession relay = session("relay");
		service.onMessage(relay, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		relay.sent.clear();
		FakeClientSession webrtc = signaling("webrtc");

		service.onMessage(webrtc, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));

		assertEquals(ErrorCode.TRANSPORT_MISMATCH, firstOf(webrtc, ServerMessage.ErrorMessage.class).code());
		assertEquals(1, channel("room").size(), "the refused joiner is not added");
		assertTrue(relay.sent.stream().noneMatch(ServerMessage.MemberJoined.class::isInstance),
				"and the incumbent is not told about a member that never joined");
	}

	@Test
	void theMirrorImageIsRefusedToo() {
		FakeClientSession webrtc = signaling("webrtc");
		service.onMessage(webrtc, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession relay = session("relay");
		service.onMessage(relay, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		assertEquals(ErrorCode.TRANSPORT_MISMATCH, firstOf(relay, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void theFirstMemberDecidesTheTransportAndAnEmptiedChannelDecidesAfresh() {
		// The answer to "what about a channel that was left": there is no such thing to inherit from. A channel is
		// dropped the instant its last member leaves, so the next creator decides. Deriving the transport from the
		// roster (rather than storing it) is what makes that structural instead of something to remember.
		FakeClientSession relay = session("relay");
		service.onMessage(relay, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		service.onClose(relay, "bye");
		assertFalse(channelExists("room"), "the channel is dropped once empty");

		FakeClientSession webrtc = signaling("webrtc");
		service.onMessage(webrtc, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		assertEquals("room", firstOf(webrtc, ServerMessage.Joined.class).channel(),
				"the new first member sets the transport, whatever the old one used");

		FakeClientSession late = session("late");
		service.onMessage(late, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "carol", "kcv-A"));
		assertEquals(ErrorCode.TRANSPORT_MISMATCH, firstOf(late, ServerMessage.ErrorMessage.class).code(),
				"...and now it is the relay transport that is refused");
	}

	@Test
	void aLockedChannelRefusesAWrongTransportKnockerWithoutTroublingTheOwner() {
		// The gate exists in BOTH branches of joinOrCreate, and a mutation run showed the locked/parking one was
		// uncovered. It sits BEFORE knock() on purpose — the same argument the LOCKED and key-check gates above it
		// make: never ask an owner to approve someone who could not be admitted anyway.
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		alice.sent.clear();
		FakeClientSession webrtc = signaling("webrtc");

		svc.onMessage(webrtc, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals(ErrorCode.TRANSPORT_MISMATCH, firstOf(webrtc, ServerMessage.ErrorMessage.class).code());
		assertTrue(webrtc.sent.stream().noneMatch(ServerMessage.JoinPending.class::isInstance),
				"refused outright, not parked");
		assertNull(webrtc.pendingChannel(), "and not left marked as waiting");
		assertTrue(alice.sent.stream().noneMatch(ServerMessage.JoinRequests.class::isInstance),
				"the owner is never shown a request it could not usefully approve");
	}

	@Test
	void aWrongPassphraseIsReportedAheadOfAWrongTransport() {
		// Ordering: the passphrase is the membership credential, so it stays the FIRST gate. Someone who cannot
		// present it must learn nothing about how the channel is configured.
		FakeClientSession relay = session("relay");
		service.onMessage(relay, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession wrong = signaling("wrong");

		service.onMessage(wrong, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-DIFFERENT"));

		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(wrong, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aRefusedTransportSwitchKeepsTheChannelAndRollsBackTheName() {
		// A switch is a fresh Join, so the all-or-nothing guarantee has to hold for this refusal too: the refused
		// switcher keeps its channel AND the display name it had, since handleJoin applies the new name before the
		// atomic join and undoes it on refusal.
		FakeClientSession relay = session("relay");
		service.onMessage(relay, new ClientMessage.Join("home", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession webrtc = signaling("webrtc");
		service.onMessage(webrtc, new ClientMessage.Join("webrtc-room", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		relay.sent.clear();

		service.onMessage(relay, new ClientMessage.Join("webrtc-room", ChannelMode.MULTI_CHANNEL_PTT, "renamed", "kcv-A"));

		assertEquals(ErrorCode.TRANSPORT_MISMATCH, firstOf(relay, ServerMessage.ErrorMessage.class).code());
		assertEquals("home", relay.channelName(), "the refused switch left us where we were");
		assertEquals("alice", relay.displayName(), "and rolled the display name back");
	}

	@Test
	void signalingStillFlowsBetweenTwoWebRtcMembers() {
		// The gate must not break the transport it exists for: both ends signaling, so the offer is relayed.
		FakeClientSession alice = signaling("alice");
		service.onMessage(alice, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bob = signaling("bob");
		service.onMessage(bob, new ClientMessage.Join("room", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-A"));
		bob.sent.clear();

		service.onMessage(alice, new ClientMessage.Offer("bob", "sdp-offer"));

		assertEquals("sdp-offer", firstOf(bob, ServerMessage.SignalOffer.class).sdp());
	}

	@Test
	void aJoinWithANullChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join(null, ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnEmptyChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnOverlongChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("x".repeat(65), ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void channelNameValidationHappensBeforeDisplayNameValidation() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("bad.name", ChannelMode.MULTI_CHANNEL_PTT, "also bad!!", TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code(),
				"the channel name is validated before the display name");
	}

	@Test
	void aJoinWithANullDisplayNameIsRejectedAsInvalidDisplayName() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, null, TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnOverlongDisplayNameIsRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "x".repeat(33), TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(s, ServerMessage.ErrorMessage.class).code());
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
		service.onMessage(s, new ClientMessage.Join("global", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.RESERVED_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("global"), "the global channel is not created by a reserved-name rejection");
	}

	@Test
	void joiningTheGlobalNameInFullDuplexIsReservedRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("global", ChannelMode.FULL_DUPLEX, "alice", TestKeyChecks.ENCRYPTED));
		assertEquals(ErrorCode.RESERVED_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("global"));
	}

	@Test
	void anEncryptedGlobalPttJoinIsRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join(null, ChannelMode.GLOBAL_PTT, "alice", "kcv-X"));
		assertEquals(ErrorCode.ENCRYPTION_NOT_ALLOWED, firstOf(s, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("global"), "an encrypted join never creates the global channel");
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
		assertEquals(ErrorCode.NOT_OWNER, firstOf(alice, ServerMessage.ErrorMessage.class).code(),
				"no participant owns the server-managed global channel");
		assertEquals(ChannelMode.GLOBAL_PTT, channel("global").mode(), "the global mode is fixed");
	}

	@Test
	void globalOwnershipDoesNotTransferWhenAMemberLeaves() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		FakeClientSession bob = join("bob", null, ChannelMode.GLOBAL_PTT);
		bob.sent.clear();

		service.onClose(alice, "test close");   // a member leaving must not re-elect a user as owner of the global room

		assertTrue(bob.sent.stream().noneMatch(ServerMessage.OwnerChanged.class::isInstance),
				"the global channel stays server-owned; no ownership is re-elected on a leave");
		assertEquals("server", channel("global").ownerId());
	}

	@Test
	void theGlobalChannelIsRecreatedServerOwnedAfterEmptying() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		service.onClose(alice, "test close");
		assertFalse(channelExists("global"), "the global channel is dropped once empty");
		FakeClientSession bob = join("bob", null, ChannelMode.GLOBAL_PTT);
		assertEquals("server", firstOf(bob, ServerMessage.Joined.class).ownerId(),
				"the recreated global channel is server-owned again");
	}

	@Test
	void emptyAndOversizedAudioFramesAreDroppedAndAFrameAtTheLimitIsRelayed() {
		FakeClientSession alice = join("alice", "fd", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "fd", ChannelMode.FULL_DUPLEX);

		service.onAudio(alice, ByteBuffer.wrap(new byte[0]));          // empty -> dropped
		service.onAudio(alice, ByteBuffer.wrap(new byte[8193]));       // over the 8192 limit -> dropped
		assertEquals(0, bob.audio.size(), "empty and oversized frames are dropped");

		service.onAudio(alice, ByteBuffer.wrap(new byte[8192]));       // exactly at the limit -> relayed
		assertEquals(1, bob.audio.size());
		assertEquals(8192 + 1, bob.audio.getFirst().length, "the relayed frame is the body plus the 1-byte stream-index prefix");
	}

	@Test
	void audioFromASignalingSenderIsDroppedAndSignalingMembersAreSkipped() {
		FakeClientSession alice = join("alice", "room", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "room", ChannelMode.FULL_DUPLEX);
		FakeClientSession carol = signaling("carol");
		service.onMessage(carol, new ClientMessage.Join("room", ChannelMode.FULL_DUPLEX, "carol", TestKeyChecks.ENCRYPTED));

		byte[] frame = {1, 2, 3};
		service.onAudio(alice, ByteBuffer.wrap(frame));
		assertEquals(1, bob.audio.size(), "an audio-relay member receives the frame");
		assertEquals(0, carol.audio.size(), "a signaling member is skipped");

		service.onAudio(carol, ByteBuffer.wrap(frame));   // a signaling sender cannot relay audio
		assertEquals(1, bob.audio.size(), "audio from a signaling sender is dropped");
	}

	@Test
	void audioWithNoChannelIsDroppedWithoutException() {
		FakeClientSession s = session("never-joined");
		assertDoesNotThrow(() -> service.onAudio(s, ByteBuffer.wrap(new byte[]{1, 2, 3})));
	}

	@Test
	void aRelayFailureToOneRecipientDoesNotBlockOthers() {
		FakeClientSession alice = join("alice", "relayfail", ChannelMode.FULL_DUPLEX);
		FakeClientSession good = join("good", "relayfail", ChannelMode.FULL_DUPLEX);
		ClientSession bad = new ThrowingSession("bad");
		service.onMessage(bad, new ClientMessage.Join("relayfail", ChannelMode.FULL_DUPLEX, "bad", TestKeyChecks.ENCRYPTED));

		byte[] frame = {4, 5, 6};
		assertDoesNotThrow(() -> service.onAudio(alice, ByteBuffer.wrap(frame)));
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
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorStatus.class::isInstance),
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
		assertDoesNotThrow(() -> service.onAudio(s, ByteBuffer.wrap(new byte[]{1, 2, 3})));
	}

	@Test
	void leavingAChannelThatNoLongerExistsIsASilentNoOp() {
		FakeClientSession s = session("orphan");
		s.joinedChannel("ghost");
		assertDoesNotThrow(() -> service.onMessage(s, new ClientMessage.Leave()));
		assertFalse(channelExists("ghost"), "no channel is resurrected");
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
		service.onAudio(alice, ByteBuffer.wrap(frame));

		byte[] received = bob.audio.getFirst();
		int aliceSid = channel("fd-sid").requireStreamIndex("alice");
		assertEquals(aliceSid, received[0] & 0xFF, "the frame is prefixed with the sender's stream index");
		assertArrayEquals(frame, Arrays.copyOfRange(received, 1, received.length), "the body is the original frame");
	}

	@Test
	void membersGetDistinctStreamIndicesAnnouncedInJoinedAndMemberJoined() {
		FakeClientSession alice = join("alice", "sid-roster", ChannelMode.MULTI_CHANNEL_PTT);
		int aliceSid = channel("sid-roster").requireStreamIndex("alice");
		assertEquals(aliceSid, firstOf(alice, ServerMessage.Joined.class).members().getFirst().streamId());

		join("bob", "sid-roster", ChannelMode.MULTI_CHANNEL_PTT);
		int bobSid = channel("sid-roster").requireStreamIndex("bob");
		assertNotEquals(aliceSid, bobSid, "members get distinct stream indices");
		assertEquals(bobSid, firstOf(alice, ServerMessage.MemberJoined.class).member().streamId(),
				"existing members learn the newcomer's index via MemberJoined");
	}

	@Test
	void aFreedStreamIndexIsNotImmediatelyReused() {
		FakeClientSession alice = join("alice", "sid-reuse", ChannelMode.MULTI_CHANNEL_PTT);
		join("bob", "sid-reuse", ChannelMode.MULTI_CHANNEL_PTT);   // keeps the channel alive when Alice leaves
		int aliceSid = channel("sid-reuse").requireStreamIndex("alice");

		service.onClose(alice, "test close");
		join("carol", "sid-reuse", ChannelMode.MULTI_CHANNEL_PTT);

		assertNotEquals(aliceSid, channel("sid-reuse").requireStreamIndex("carol"),
				"a freed index is quarantined by the rotating allocator, not immediately reused");
	}

	@Test
	void aFullChannelRefusesFurtherNewcomersWithChannelFull() {
		// One stream index per member over the 0..254 space, so a channel holds at most 255. Fill it, then the next
		// join is refused with CHANNEL_FULL rather than assigning a colliding index.
		for (int i = 0; i < 255; i++) {
			join("m" + i, "packed", ChannelMode.FULL_DUPLEX);
		}
		assertTrue(channel("packed").isFull());
		assertEquals(255, channel("packed").size());

		FakeClientSession overflow = join("m255", "packed", ChannelMode.FULL_DUPLEX);
		assertEquals(ErrorCode.CHANNEL_FULL, firstOf(overflow, ServerMessage.ErrorMessage.class).code());
		assertFalse(overflow.sent.stream().anyMatch(ServerMessage.Joined.class::isInstance),
				"the overflow joiner never joined");
		assertEquals(255, channel("packed").size(), "the overflow joiner was not added");
	}

	// --- push-to-talk floor anti-hogging (idle auto-release + max-hold), driven with a fake clock ----------

	@Test
	void idleAutoReleaseReassignsTheFloorFromASilentHolder() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 5, 0);   // idle-release 5 s, max-hold off
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("ptt", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("ptt", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice takes the floor at t=0
		assertTrue(channel("ptt").holdsFloor("alice"));

		bob.sent.clear();
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // immediate retry: alice isn't idle yet
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"with the queue off, a busy non-idle floor yields no grant (FloorDenied is retired — nothing is sent)");
		assertTrue(channel("ptt").holdsFloor("alice"), "the floor is still alice's");

		clock.advance(Duration.ofSeconds(6));                    // 6 s of silence from alice
		bob.sent.clear();
		alice.sent.clear();
		svc.onMessage(bob, new ClientMessage.RequestFloor());

		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorGranted.class::isInstance),
				"bob preempts the idle holder and is granted the floor");
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& "bob".equals(holderId)),
				"the ex-holder learns via the FloorStatus snapshot that bob now holds the floor, so its client stops");
		assertTrue(channel("ptt").holdsFloor("bob"));
	}

	@Test
	void aFreshlyPreemptedHolderIsNotImmediatelyDoublePreempted() {
		// Regression: the idle-preempt must stamp the new holder's activity ATOMICALLY with the swap. Otherwise
		// bob (who just took the floor from idle alice) still carries alice's stale mark, and carol could steal
		// it the same instant despite bob never being idle.
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 5, 0);   // idle-release 5 s, max-hold off
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		FakeClientSession carol = session("carol");
		svc.onMessage(alice, new ClientMessage.Join("ptt3", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("ptt3", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(carol, new ClientMessage.Join("ptt3", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds at t=0, then goes silent

		clock.advance(Duration.ofSeconds(6));
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob preempts idle alice -> holds, stamped active at t=6
		assertTrue(channel("ptt3").holdsFloor("bob"));

		carol.sent.clear();
		svc.onMessage(carol, new ClientMessage.RequestFloor());   // same instant: bob is freshly active, not idle
		assertTrue(carol.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"carol cannot steal the floor from a holder that was just granted it");
		assertTrue(channel("ptt3").holdsFloor("bob"), "bob keeps the floor he just acquired");
	}

	@Test
	void aSilentHolderIsSweptOffTheFloorAfterMaxHoldWithoutContentionOrIdleRelease() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 0, 10);   // idle-release OFF, max-hold 10 s
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("swept", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("swept", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds at t=0, then goes silent (no frames, no release)
		assertTrue(channel("swept").holdsFloor("alice"));

		alice.sent.clear();
		bob.sent.clear();
		svc.releaseExpiredFloors();                               // within the cap -> no-op
		assertTrue(channel("swept").holdsFloor("alice"), "the sweep leaves a holder alone before the cap");

		clock.advance(Duration.ofSeconds(11));                    // past the cap, with no audio frame and no other requester
		svc.releaseExpiredFloors();

		assertFalse(channel("swept").holdsFloor("alice"), "the silent over-cap holder is reclaimed by the sweep");
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& holderId == null),
				"the (ex-)holder is notified via FloorStatus (no holder) so its client stops transmitting");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& holderId == null),
				"other members are notified the floor is free");
	}

	@Test
	void maxHoldReleasesTheFloorAfterContinuousHolding() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 0, 10);   // idle-release off, max-hold 10 s
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("ptt2", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("ptt2", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds at t=0
		bob.audio.clear();
		alice.sent.clear();
		bob.sent.clear();

		svc.onAudio(alice, ByteBuffer.wrap(new byte[]{1, 2, 3}));                  // within the cap -> relayed
		assertEquals(1, bob.audio.size(), "a frame within the hold cap is relayed");

		clock.advance(Duration.ofSeconds(11));                   // past the 10 s cap
		svc.onAudio(alice, ByteBuffer.wrap(new byte[]{4, 5, 6}));

		assertEquals(1, bob.audio.size(), "the over-cap frame is dropped, not relayed");
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& holderId == null),
				"the speaker is told (FloorStatus shows no holder) its talk time was up so its client stops");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& holderId == null),
				"the other members are told the floor was released");
		assertFalse(channel("ptt2").holdsFloor("alice"), "the floor is freed for the next requester");
	}

	@Test
	void anActiveSpeakerRefreshingTheFloorIsNotPreempted() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 5, 0);   // idle-release 5 s, max-hold off
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("ptt-active", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("ptt-active", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice acquires at t=0
		assertTrue(channel("ptt-active").holdsFloor("alice"));

		clock.advance(Duration.ofSeconds(4));
		svc.onAudio(alice, ByteBuffer.wrap(new byte[]{1, 2, 3}));                  // active speaker -> refreshes the activity mark to t=4 s

		clock.advance(Duration.ofSeconds(2));                    // t=6 s: only 2 s since the last frame (< 5 s idle window)
		bob.sent.clear();
		svc.onMessage(bob, new ClientMessage.RequestFloor());

		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"an actively-talking holder is not preempted, so bob gets no grant (a refused request sends nothing)");
		assertTrue(channel("ptt-active").holdsFloor("alice"), "the floor is still alice's");
	}

	@Test
	void aSenderOverItsAudioRateHasFramesDroppedBeforeFanOut() {
		// audio 2 fps -> burst 2
		ConnectionService svc = new ConnectionService(
				channelRegistry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						2,
						1_000_000,
						Duration.ZERO,
						Duration.ZERO,
						Duration.ofSeconds(10),
						false, 0,
						null, false, Duration.ZERO),
				BROADCASTER
		);
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("flood", ChannelMode.FULL_DUPLEX, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("flood", ChannelMode.FULL_DUPLEX, "bob", TestKeyChecks.ENCRYPTED));

		byte[] frame = {1, 2, 3};
		svc.onAudio(alice, ByteBuffer.wrap(frame));
		svc.onAudio(alice, ByteBuffer.wrap(frame));
		assertEquals(2, bob.audio.size(), "the burst-capacity frames are relayed");
		svc.onAudio(alice, ByteBuffer.wrap(frame));   // over the per-sender rate -> dropped before fan-out
		assertEquals(2, bob.audio.size(), "a frame past the rate cap is dropped before fan-out");
	}

	@Test
	void onCloseForgetsTheSendersRateBucketSoAReconnectStartsFull() {
		// audio 1 fps -> burst 1
		ConnectionService svc = new ConnectionService(
				channelRegistry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						1,
						1_000_000,
						Duration.ZERO,
						Duration.ZERO,
						Duration.ofSeconds(10),
						false, 0,
						null, false, Duration.ZERO),
				BROADCASTER
		);
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "bob", TestKeyChecks.ENCRYPTED));

		byte[] frame = {1, 2, 3};
		svc.onAudio(alice, ByteBuffer.wrap(frame));
		svc.onAudio(alice, ByteBuffer.wrap(frame));   // exhausts the 1-token bucket
		assertEquals(1, bob.audio.size(), "the second frame is over the cap and dropped");

		svc.onClose(alice, "test close");          // must evict alice's bucket
		svc.onMessage(alice, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "alice", TestKeyChecks.ENCRYPTED));   // same id reconnects
		bob.audio.clear();
		svc.onAudio(alice, ByteBuffer.wrap(frame));
		assertEquals(1, bob.audio.size(), "after onClose evicts the bucket, the reconnecting id starts from a full bucket");
	}

	@Test
	void controlMessagesOverTheRateAreDroppedBeforeDispatch() {
		// fixed -> no token refill, so burst == the rate
		// control rate 2 -> burst 2: the Join spends the first token and the first Rename the second; the second
		// Rename is over the cap and dropped before dispatch, so the applied name stays at the first rename.
		ConnectionService svc = new ConnectionService(
				channelRegistry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						1000,
						2,
						Duration.ZERO,
						Duration.ZERO,
						Duration.ofSeconds(10),
						false, 0,
						null, false, Duration.ZERO),
				BROADCASTER,
				new MutableClock(Instant.EPOCH)
		);
		FakeClientSession alice = session("alice");

		svc.onMessage(alice, new ClientMessage.Join("flood-ctl", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));   // token 1
		svc.onMessage(alice, new ClientMessage.Rename("renamed-once"));    // token 2 -> applied
		svc.onMessage(alice, new ClientMessage.Rename("renamed-twice"));   // over the rate -> dropped

		assertEquals("renamed-once", alice.displayName(),
				"the control message past the per-session rate cap is dropped before dispatch");
	}

	// --- push-to-talk floor QUEUE ("raise hand", owner-toggleable) — see docs/CLIENT_PROTOCOL.md §3b -------

	@Test
	void enqueuingForABusyFloorPlacesTheMemberInTheFloorStatusQueue() {
		FakeClientSession alice = join("alice", "q1", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "q1", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice grabs the free floor
		assertTrue(channel("q1").holdsFloor("alice"));

		bob.sent.clear();
		service.onMessage(bob, new ClientMessage.RequestFloor());     // busy floor + queue on -> bob is queued
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"a queued member is not granted the floor");
		ServerMessage.FloorStatus status = lastOf(bob, ServerMessage.FloorStatus.class);
		assertEquals("alice", status.holderId(), "alice still holds the floor");
		assertEquals(List.of("bob"), status.waiting(), "bob sees itself in the waiting queue");
	}

	/// The reserved head must receive the SNAPSHOT that makes it `waiting[0]` of a free floor BEFORE the imperative
	/// FloorReserved trigger. Clients derive reservedness from the snapshot — there is no `reserved` field on the wire
	/// (docs/CLIENT_PROTOCOL.md §3b) — so a trigger that overtakes it lands while the member still looks merely queued
	/// behind the ex-holder, and the client contradicts the alert it has just raised: the browser rendered "In line #1
	/// of N — tap to leave", and the Java client's `t` sent ReleaseFloor and DECLINED the turn.
	///
	/// Asserted by INDEX, not by membership: every other FloorReserved assertion in this class is an `anyMatch` over
	/// the recipient's messages, which passes either way round. The mailbox is strictly FIFO per recipient, so the
	/// recorded order is the delivery order.
	@Test
	void theReservedHeadSeesTheFreedFloorSnapshotBeforeItIsToldItsTurn() {
		FakeClientSession alice = join("alice", "order", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "order", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues behind her, becoming the head

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.ReleaseFloor());   // the floor frees and is reserved for bob

		List<ServerMessage> delivered = List.copyOf(bob.sent);
		int snapshot = indexOf(delivered, ServerMessage.FloorStatus.class);
		int trigger = indexOf(delivered, ServerMessage.FloorReserved.class);
		assertTrue(snapshot >= 0, () -> "bob was never sent the freed-floor snapshot: " + delivered);
		assertTrue(trigger >= 0, () -> "bob was never told its turn: " + delivered);
		assertTrue(snapshot < trigger,
				() -> "the FloorStatus making bob the head of a free floor must precede the FloorReserved trigger, got " + delivered);
		// And the snapshot bob sees first is genuinely the one its own derivation reads as "my turn".
		ServerMessage.FloorStatus reserved = (ServerMessage.FloorStatus) delivered.get(snapshot);
		assertNull(reserved.holderId(), "the floor is free while reserved");
		assertEquals(List.of("bob"), reserved.waiting(), "bob is the head being offered the floor");
	}

	/// The same ordering, on the paths where the snapshot RIDES a batched fan-out (a departure, a mute) instead of
	/// being broadcast on its own. Those are the easy ones to get wrong: there the FloorStatus is only APPENDED to an
	/// events list and sent later, so moving the reserve call above the append changes nothing on the wire — the
	/// to-one trigger still has to be held back until after the fan-out itself.
	@Test
	void aFloorFreedByADepartureOrAMuteAlsoSnapshotsBeforeItTellsTheNewHead() {
		FakeClientSession alice = join("alice", "batched", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "batched", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession carol = join("carol", "batched", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));

		// A MUTE frees the floor: bob holds, carol is the queue head, the owner mutes bob.
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob holds
		service.onMessage(carol, new ClientMessage.RequestFloor());   // carol queues, becoming the head
		carol.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));
		assertSnapshotPrecedesTrigger(carol, "a floor freed by muting its holder");

		// A DEPARTURE frees it: carol now holds, bob (unmuted) is the head, carol's socket closes.
		service.onMessage(alice, new ClientMessage.MuteMember("bob", false));
		service.onMessage(carol, new ClientMessage.RequestFloor());   // carol claims its reserved turn -> holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues behind carol, becoming the head
		bob.sent.clear();
		service.onClose(carol, "the holder leaves");
		assertSnapshotPrecedesTrigger(bob, "a floor freed by its holder leaving");
	}

	/// Asserts a newly reserved head received the freed-floor FloorStatus BEFORE the FloorReserved trigger, and that
	/// the snapshot is the one its own derivation reads as "my turn" (free floor, itself at the head).
	private static void assertSnapshotPrecedesTrigger(FakeClientSession head, String path) {
		List<ServerMessage> delivered = List.copyOf(head.sent);
		int snapshot = indexOf(delivered, ServerMessage.FloorStatus.class);
		int trigger = indexOf(delivered, ServerMessage.FloorReserved.class);
		assertTrue(snapshot >= 0, () -> path + ": no freed-floor snapshot reached the new head: " + delivered);
		assertTrue(trigger >= 0, () -> path + ": the new head was never told its turn: " + delivered);
		assertTrue(snapshot < trigger,
				() -> path + ": the FloorStatus must precede the FloorReserved trigger, got " + delivered);
		ServerMessage.FloorStatus reserved = (ServerMessage.FloorStatus) delivered.get(snapshot);
		assertNull(reserved.holderId(), () -> path + ": the floor is free while reserved");
		assertEquals(List.of(head.id()), reserved.waiting(), () -> path + ": the recipient is the head being offered the floor");
	}

	/// The position of the first message of `type` in a recipient's delivered order, or -1 if it never arrived.
	private static int indexOf(List<ServerMessage> delivered, Class<? extends ServerMessage> type) {
		for (int i = 0; i < delivered.size(); i++) {
			if (type.isInstance(delivered.get(i))) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void theReservedHeadClaimsItsTurnWhileANonHeadCannotGrabTheFreedFloor() {
		FakeClientSession alice = join("alice", "q2", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "q2", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession carol = join("carol", "q2", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));

		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (head)
		service.onMessage(carol, new ClientMessage.RequestFloor());   // carol queues behind bob

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.ReleaseFloor());   // alice releases -> bob reserved
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"the head is told it is its turn (FloorReserved)");

		carol.sent.clear();
		service.onMessage(carol, new ClientMessage.RequestFloor());   // carol is NOT the head -> can't grab
		assertTrue(carol.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"a non-head cannot grab a reserved floor");
		assertFalse(channel("q2").holdsFloor("carol"));

		bob.sent.clear();
		service.onMessage(bob, new ClientMessage.RequestFloor());     // the reserved head claims its turn
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorGranted.class::isInstance),
				"the reserved head claims and goes live");
		assertTrue(channel("q2").holdsFloor("bob"));
		ServerMessage.FloorStatus status = lastOf(bob, ServerMessage.FloorStatus.class);
		assertEquals("bob", status.holderId(), "the snapshot shows bob holding the floor");
		assertEquals(List.of("carol"), status.waiting(), "carol remains queued behind bob");
	}

	@Test
	void aMidQueueWaiterLeavingDoesNotResetTheReservedHeadsClaimWindow() {
		MutableClock clock = new MutableClock(Instant.EPOCH);
		ConnectionService svc = serviceWithClock(clock, 0, 0);   // timers off; reservation clock driven by hand
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		FakeClientSession carol = session("carol");
		svc.onMessage(alice, new ClientMessage.Join("q3", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("q3", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(carol, new ClientMessage.Join("q3", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));

		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (head)
		svc.onMessage(carol, new ClientMessage.RequestFloor());   // carol queues behind bob

		clock.advance(Duration.ofSeconds(3));
		svc.onMessage(alice, new ClientMessage.ReleaseFloor());   // t=3 s: bob reserved
		assertEquals(Instant.EPOCH.plusSeconds(3), channel("q3").floorReservedAt(), "bob's reservation is stamped at t=3 s");

		clock.advance(Duration.ofSeconds(4));                     // t=7 s
		svc.onMessage(carol, new ClientMessage.ReleaseFloor());   // a MID-QUEUE waiter leaves the line
		assertEquals(Instant.EPOCH.plusSeconds(3), channel("q3").floorReservedAt(),
				"a mid-queue waiter leaving must NOT reset the reserved head's running claim window");
		assertEquals(List.of("bob"), channel("q3").floorQueue(), "carol left; bob is still the reserved head");
	}

	@Test
	void theReservedHeadDecliningOffersTheFloorToTheNextInLine() {
		FakeClientSession alice = join("alice", "q4", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "q4", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession carol = join("carol", "q4", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));

		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (head)
		service.onMessage(carol, new ClientMessage.RequestFloor());   // carol queues
		service.onMessage(alice, new ClientMessage.ReleaseFloor());   // bob reserved

		carol.sent.clear();
		service.onMessage(bob, new ClientMessage.ReleaseFloor());     // the reserved head declines its turn
		assertTrue(carol.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"declining the turn offers the floor to the next in line (carol reserved)");
		assertEquals(List.of("carol"), channel("q4").floorQueue(), "bob left; carol is now the head");
	}

	@Test
	void anUnclaimedReservationExpiresAndTheFloorPassesToTheNextInLine() {
		// Base the clock at a NON-EPOCH instant: a reservation stamped at exactly Instant.EPOCH would collide with
		// the "no reservation running" sentinel (floorReservedAt == EPOCH), so real reservations must be post-EPOCH.
		MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(1_000));
		ConnectionService svc = serviceWithClock(clock, 0, 0);   // idle/max-hold off; reservation window = 10 s
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		FakeClientSession carol = session("carol");
		svc.onMessage(alice, new ClientMessage.Join("q5", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("q5", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(carol, new ClientMessage.Join("q5", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));

		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (head)
		svc.onMessage(carol, new ClientMessage.RequestFloor());   // carol queues
		svc.onMessage(alice, new ClientMessage.ReleaseFloor());   // t=0: bob reserved

		svc.releaseExpiredFloors();                               // within the 10 s window -> no change
		assertEquals(List.of("bob", "carol"), channel("q5").floorQueue(), "the reservation stands within the window");

		clock.advance(Duration.ofSeconds(11));                    // past the claim window
		carol.sent.clear();
		svc.releaseExpiredFloors();

		assertEquals(List.of("carol"), channel("q5").floorQueue(), "bob missed its turn and was dropped from the queue");
		assertTrue(carol.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"the floor is offered to the next in line (carol reserved)");
	}

	@Test
	void anUnclaimedReservationWithNoOneBehindItJustFreesTheFloor() {
		// The lone reserved head misses its window with an empty queue behind it: it is dropped and the floor is
		// simply freed (the "no one else was waiting" path) — no successor to reserve, so no FloorReserved goes out.
		MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(1_000));
		ConnectionService svc = serviceWithClock(clock, 0, 0);   // idle/max-hold off; reservation window = 10 s
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("q5b", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("q5b", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));

		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (the sole head)
		svc.onMessage(alice, new ClientMessage.ReleaseFloor());   // t=0: bob reserved, nobody behind it

		clock.advance(Duration.ofSeconds(11));                    // past the claim window
		bob.sent.clear();
		svc.releaseExpiredFloors();

		assertTrue(channel("q5b").floorQueue().isEmpty(), "bob missed its turn and was dropped, leaving the queue empty");
		assertFalse(channel("q5b").floorHolder() instanceof Some<String>, "the floor is free — no successor took it");
		assertFalse(bob.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"a dropped lone head is not itself re-reserved");
	}

	@Test
	void onlyTheOwnerTogglesTheFloorQueueAndDisablingClearsIt() {
		FakeClientSession alice = join("alice", "q6", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "q6", ChannelMode.MULTI_CHANNEL_PTT);

		bob.sent.clear();
		service.onMessage(bob, new ClientMessage.SetFloorQueue(true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(bob, ServerMessage.ErrorMessage.class).code(),
				"a non-owner cannot toggle the floor queue");
		assertFalse(channel("q6").isFloorQueueEnabled(), "a non-owner's toggle has no effect");

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		assertTrue(channel("q6").isFloorQueueEnabled());
		assertTrue(firstOf(bob, ServerMessage.FloorQueueChanged.class).enabled(), "the enable is broadcast to the channel");
		assertTrue(firstOf(alice, ServerMessage.FloorQueueChanged.class).enabled(), "the owner is notified too");

		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues
		assertEquals(List.of("bob"), channel("q6").floorQueue());

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.SetFloorQueue(false));
		assertFalse(channel("q6").isFloorQueueEnabled());
		assertTrue(channel("q6").floorQueue().isEmpty(), "disabling the queue clears the waiting line");
		assertFalse(firstOf(bob, ServerMessage.FloorQueueChanged.class).enabled(), "the disable is broadcast");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String _, List<String> waiting)
						&& waiting.isEmpty()),
				"the following FloorStatus shows the cleared (empty) queue");
	}

	@Test
	void theGlobalRoomsFloorQueueCannotBeToggled() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(alice, ServerMessage.ErrorMessage.class).code(),
				"the sentinel-owned global room refuses a floor-queue toggle");
		assertFalse(channel("global").isFloorQueueEnabled());
	}

	@Test
	void settingTheFloorQueueBeforeJoiningIsNotInChannel() {
		FakeClientSession stray = session("stray");
		service.onMessage(stray, new ClientMessage.SetFloorQueue(true));
		assertEquals(ErrorCode.NOT_IN_CHANNEL, firstOf(stray, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aMutedMemberCannotQueueForTheFloor() {
		FakeClientSession alice = join("alice", "q7", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "q7", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds a busy floor
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		bob.sent.clear();
		service.onMessage(bob, new ClientMessage.RequestFloor());     // muted -> refused, never queued
		assertTrue(channel("q7").floorQueue().isEmpty(), "a muted member is not added to the queue");
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance));
	}

	@Test
	void withTheQueueOffABusyFloorRequestIsRefusedWithoutGrantOrQueueOrCrash() {
		FakeClientSession alice = join("alice", "q8", ChannelMode.MULTI_CHANNEL_PTT);   // queue OFF by default
		FakeClientSession bob = join("bob", "q8", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		assertTrue(channel("q8").holdsFloor("alice"));

		bob.sent.clear();
		assertDoesNotThrow(() -> service.onMessage(bob, new ClientMessage.RequestFloor()));
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"with the queue off, a busy-floor request yields no grant (unchanged pre-queue behaviour)");
		assertTrue(channel("q8").floorQueue().isEmpty(), "and forms no queue");
		assertTrue(channel("q8").holdsFloor("alice"), "alice keeps the floor");
	}

	@Test
	void aHolderLeavingWithAQueueReservesTheHeadAndBroadcastsIt() {
		FakeClientSession alice = join("alice", "qA", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "qA", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (head)

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.Leave());          // the holder leaves with bob waiting
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"the queue head is offered the floor (FloorReserved) when the holder leaves");
		ServerMessage.FloorStatus status = lastOf(bob, ServerMessage.FloorStatus.class);
		assertNull(status.holderId(), "the floor is free (bob is reserved, not yet holding)");
		assertEquals(List.of("bob"), status.waiting(), "bob is the head being offered the floor");
	}

	@Test
	void aReservedHeadDisconnectingOffersTheFloorToTheNext() {
		FakeClientSession alice = join("alice", "qB", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "qB", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession carol = join("carol", "qB", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (head)
		service.onMessage(carol, new ClientMessage.RequestFloor());   // carol queues
		service.onMessage(alice, new ClientMessage.ReleaseFloor());   // bob reserved

		carol.sent.clear();
		service.onClose(bob, "bob disconnects");   // the reserved head drops
		assertTrue(carol.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"the next member is reserved when the reserved head disconnects");
		assertEquals(List.of("carol"), channel("qB").floorQueue(), "bob is gone; carol is the new head");
	}

	@Test
	void theSweepIdleReleasesARelayHolderAndReservesTheQueueHead() {
		// NON-EPOCH clock base (a reservation stamped at EPOCH would collide with the no-reservation sentinel).
		MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(1_000));
		ConnectionService svc = serviceWithClock(clock, 5, 0);   // idle 5 s, max-hold off
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("qC", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("qC", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice (relay) holds, then goes silent
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues

		svc.releaseExpiredFloors();   // within the idle window -> no change
		assertTrue(channel("qC").holdsFloor("alice"), "a holder within the idle window is kept");

		clock.advance(Duration.ofSeconds(6));   // alice idle past 5 s
		bob.sent.clear();
		svc.releaseExpiredFloors();

		assertFalse(channel("qC").holdsFloor("alice"), "the idle relay holder is released for the queue");
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"the freed floor is offered to the queue head");
	}

	@Test
	void aWebRtcHolderIsNotIdleReleasedEvenWithAQueueBehindIt() {
		MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(1_000));
		ConnectionService svc = serviceWithClock(clock, 5, 0);   // idle 5 s
		FakeClientSession alice = signaling("alice");   // WebRTC (non-relay) holder — no server-side activity signal
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("qD", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("qD", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // WebRTC alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues

		clock.advance(Duration.ofSeconds(60));   // long idle by wall time
		bob.sent.clear();
		svc.releaseExpiredFloors();

		assertTrue(channel("qD").holdsFloor("alice"),
				"a WebRTC (non-relay) holder is never idle-released — the server has no activity signal for it");
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorReserved.class::isInstance),
				"so the queue head is not reserved");
	}

	@Test
	void theSweepMaxHoldReleasesTheHolderAndOffersTheFloorToTheQueueHead() {
		MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(1_000));
		ConnectionService svc = serviceWithClock(clock, 0, 10);   // idle off, max-hold 10 s
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("qE", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("qE", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues

		clock.advance(Duration.ofSeconds(11));   // past the max-hold cap
		bob.sent.clear();
		svc.releaseExpiredFloors();

		assertFalse(channel("qE").holdsFloor("alice"), "the over-cap holder is swept off the floor");
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"the freed floor is offered to the queue head");
	}

	@Test
	void maxHoldViaOnAudioReleasesTheHolderAndOffersTheFloorToTheQueueHead() {
		MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(1_000));
		ConnectionService svc = serviceWithClock(clock, 0, 10);   // idle off, max-hold 10 s
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("qF", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("qF", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues

		clock.advance(Duration.ofSeconds(11));   // past the cap
		bob.sent.clear();
		svc.onAudio(alice, ByteBuffer.wrap(new byte[]{1, 2, 3}));   // the holder's next frame trips the cap and releases the floor

		assertFalse(channel("qF").holdsFloor("alice"), "the over-cap frame releases the holder");
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"onAudio's max-hold release offers the floor to the queue head");
	}

	@Test
	void mutingAQueuedOrReservedMemberDequeuesThem() {
		FakeClientSession alice = join("alice", "qG", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "qG", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession carol = join("carol", "qG", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession dave = join("dave", "qG", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // queue -> [bob]
		service.onMessage(carol, new ClientMessage.RequestFloor());   // -> [bob, carol]
		service.onMessage(dave, new ClientMessage.RequestFloor());    // -> [bob, carol, dave]
		assertEquals(List.of("bob", "carol", "dave"), channel("qG").floorQueue());

		// Mute a MID-QUEUE member (carol) while alice holds -> dequeued; the rest of the order is preserved.
		service.onMessage(alice, new ClientMessage.MuteMember("carol", true));
		assertEquals(List.of("bob", "dave"), channel("qG").floorQueue(), "a muted queued member is dequeued");

		// Alice releases -> bob reserved. Then mute the RESERVED HEAD (bob) -> dequeued and dave advances.
		service.onMessage(alice, new ClientMessage.ReleaseFloor());
		dave.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));
		assertEquals(List.of("dave"), channel("qG").floorQueue(), "muting the reserved head dequeues it, leaving dave");
		assertTrue(dave.sent.stream().anyMatch(ServerMessage.FloorReserved.class::isInstance),
				"the floor advances to the next in line (dave reserved)");
	}

	@Test
	void aMidQueueMemberDisconnectingKeepsTheReservedHeadsWindow() {
		FakeClientSession alice = join("alice", "qH", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "qH", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession carol = join("carol", "qH", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		service.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (head)
		service.onMessage(carol, new ClientMessage.RequestFloor());   // carol queues
		service.onMessage(alice, new ClientMessage.ReleaseFloor());   // bob reserved

		Instant reservedAt = channel("qH").floorReservedAt();
		bob.sent.clear();
		service.onClose(carol, "carol disconnects");   // a MID-QUEUE member drops
		assertEquals(reservedAt, channel("qH").floorReservedAt(),
				"a mid-queue member disconnecting must NOT reset the reserved head's claim window");
		assertEquals(List.of("bob"), channel("qH").floorQueue(), "carol is gone; bob is still the reserved head");
		ServerMessage.FloorStatus status = lastOf(bob, ServerMessage.FloorStatus.class);
		assertNull(status.holderId(), "the floor is still free (bob reserved)");
		assertEquals(List.of("bob"), status.waiting(), "the re-broadcast snapshot shows the shifted queue");
	}

	// --- owner-enforced mute -----------------------------------------------------------------------

	@Test
	void theOwnerMutesAMemberAndTheServerDropsThatMembersAudio() {
		FakeClientSession alice = join("alice", "mute", ChannelMode.FULL_DUPLEX);   // alice is the owner
		FakeClientSession bob = join("bob", "mute", ChannelMode.FULL_DUPLEX);

		byte[] frame = {1, 2, 3};
		service.onAudio(bob, ByteBuffer.wrap(frame));
		assertEquals(1, alice.audio.size(), "before muting, bob's audio is relayed");

		alice.sent.clear();
		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		assertTrue(channel("mute").isMuted("bob"), "the server records bob as muted");
		// The snapshot is broadcast to the whole channel, including the muted member (so its client can stop).
		assertEquals(Set.of("bob"), lastOf(bob, ServerMessage.MuteStatus.class).muted());
		assertEquals(Set.of("bob"), lastOf(alice, ServerMessage.MuteStatus.class).muted(), "the owner is notified too");

		alice.audio.clear();
		service.onAudio(bob, ByteBuffer.wrap(frame));
		assertEquals(0, alice.audio.size(), "a muted member's audio is dropped server-side, not relayed");
	}

	@Test
	void unmutingReenablesTheMembersAudio() {
		FakeClientSession alice = join("alice", "unmute", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "unmute", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		byte[] frame = {1, 2, 3};
		service.onAudio(bob, ByteBuffer.wrap(frame));
		assertEquals(0, alice.audio.size(), "while muted, bob's audio is dropped");

		service.onMessage(alice, new ClientMessage.MuteMember("bob", false));
		assertFalse(channel("unmute").isMuted("bob"));
		service.onAudio(bob, ByteBuffer.wrap(frame));
		assertEquals(1, alice.audio.size(), "after unmuting, bob's audio is relayed again");
	}

	@Test
	void aNonOwnerCannotMuteAnotherMember() {
		join("alice", "nomute", ChannelMode.FULL_DUPLEX);   // alice owns it
		FakeClientSession bob = join("bob", "nomute", ChannelMode.FULL_DUPLEX);

		service.onMessage(bob, new ClientMessage.MuteMember("alice", true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertFalse(channel("nomute").isMuted("alice"), "a non-owner's mute request has no effect");
	}

	@Test
	void mutingAnUnknownTargetOrTheOwnerItselfIsRejected() {
		FakeClientSession alice = join("alice", "badtarget", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "badtarget", ChannelMode.FULL_DUPLEX);

		service.onMessage(alice, new ClientMessage.MuteMember("ghost", true));
		assertEquals(ErrorCode.UNKNOWN_TARGET, firstOf(alice, ServerMessage.ErrorMessage.class).code());

		alice.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("alice", true));   // the owner can't mute itself
		assertEquals(ErrorCode.UNKNOWN_TARGET, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertFalse(channel("badtarget").isMuted("alice"), "the owner is never muted");
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.MuteStatus.class::isInstance),
				"a rejected mute (unknown target or the owner itself) broadcasts no snapshot to the channel");
	}

	@Test
	void mutingTheFloorHolderFreesTheFloorAndTellsEveryone() {
		FakeClientSession alice = join("alice", "mute-floor", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "mute-floor", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(bob, new ClientMessage.RequestFloor());
		assertTrue(channel("mute-floor").holdsFloor("bob"), "bob is talking");

		alice.sent.clear();
		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		assertFalse(channel("mute-floor").holdsFloor("bob"), "muting the floor holder frees the floor");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& holderId == null),
				"the muted (ex-)holder is told the floor is free (FloorStatus) so its client stops transmitting");
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& holderId == null),
				"the other members learn the floor reopened");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.MuteStatus(Set<String> muted)
						&& muted.contains("bob")),
				"bob is also told it was muted");
	}

	@Test
	void aMutedMemberIsRefusedTheFloor() {
		FakeClientSession alice = join("alice", "muted-floor-req", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "muted-floor-req", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		bob.sent.clear();
		service.onMessage(bob, new ClientMessage.RequestFloor());
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"a muted member is refused the floor, so it can't seize and hold it");
		assertFalse(channel("muted-floor-req").holdsFloor("bob"), "the muted member never acquires the floor");

		// Positive control: once unmuted, the SAME request in the SAME channel succeeds — proving the refusal above
		// was the mute (not an unrelated floor bug), and that unmuting restores floor eligibility.
		service.onMessage(alice, new ClientMessage.MuteMember("bob", false));
		bob.sent.clear();
		service.onMessage(bob, new ClientMessage.RequestFloor());
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.FloorGranted.class::isInstance),
				"an unmuted member is granted the floor");
		assertTrue(channel("muted-floor-req").holdsFloor("bob"));
	}

	@Test
	void aMemberMutedBetweenTheFloorEntryGateAndTheMonitorIsStillRefused() throws InterruptedException {
		// The floor-request mute check has TWO layers: a lock-free entry gate and an authoritative re-check inside
		// the synchronized acquire. A single-threaded mute-before-request only exercises the entry gate; this test
		// drives the RE-CHECK by muting bob in the window AFTER it passed the entry gate but BEFORE it holds the
		// monitor — the concurrent race the re-check exists to close.
		join("alice", "floor-race", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "floor-race", ChannelMode.MULTI_CHANNEL_PTT);
		Channel channel = channel("floor-race");

		Thread requester = new Thread(() -> service.onMessage(bob, new ClientMessage.RequestFloor()), "floor-race-requester");
		synchronized (channel) {
			// Hold the channel monitor, then start bob's request. bob passes the entry-gate mute check (it is NOT
			// muted yet) and then blocks entering handleRequestFloor's synchronized acquire. A BLOCKED thread state
			// requires contention, and this monitor is the ONLY one anyone contends (bob's rate-limit bucket is
			// per-session and untouched here), so BLOCKED unambiguously means "parked here, past the entry gate" —
			// no arbitrary sleep needed.
			requester.start();
			long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (requester.getState() != Thread.State.BLOCKED && System.nanoTime() < deadlineNanos) {
				Thread.onSpinWait();
			}
			assertEquals(Thread.State.BLOCKED, requester.getState(),
					"bob's request must reach the synchronized floor acquire (past the lock-free entry gate)");
			// Mute bob NOW, while it waits: the entry gate already saw it unmuted, so only the under-monitor
			// re-check can catch it. setMuted is called under the monitor, honoring its contract.
			channel.setMuted("bob", true);
		}   // releasing the monitor lets bob proceed into the synchronized block and hit the re-check
		requester.join(Duration.ofSeconds(5));

		assertFalse(channel.holdsFloor("bob"),
				"a member muted after passing the entry gate must STILL be refused by the under-monitor re-check");
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.FloorGranted.class::isInstance),
				"no FloorGranted reaches the member muted mid-request");
	}

	@Test
	void muteAllMutesEveryoneExceptTheOwner() {
		FakeClientSession alice = join("alice", "mute-all", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "mute-all", ChannelMode.FULL_DUPLEX);
		FakeClientSession carol = join("carol", "mute-all", ChannelMode.FULL_DUPLEX);

		service.onMessage(alice, new ClientMessage.MuteAll(true));

		Channel channel = channel("mute-all");
		assertFalse(channel.isMuted("alice"), "the owner is never muted by mute-all");
		assertTrue(channel.isMuted("bob"));
		assertTrue(channel.isMuted("carol"));

		byte[] frame = {1, 2, 3};
		alice.audio.clear();
		service.onAudio(bob, ByteBuffer.wrap(frame));
		service.onAudio(carol, ByteBuffer.wrap(frame));
		assertEquals(0, alice.audio.size(), "both muted members' audio is dropped");

		bob.audio.clear();
		carol.audio.clear();
		service.onAudio(alice, ByteBuffer.wrap(frame));   // the owner is not muted and can still be heard
		assertEquals(1, bob.audio.size(), "the owner can still talk");
		assertEquals(1, carol.audio.size());
	}

	@Test
	void muteIsIdempotentAndDoesNotReBroadcastAnUnchangedState() {
		FakeClientSession alice = join("alice", "mute-idem", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "mute-idem", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));   // already muted -> no-op
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.MuteStatus.class::isInstance),
				"re-muting an already-muted member broadcasts nothing");
	}

	@Test
	void theRosterSnapshotReportsEachMembersMuteState() {
		FakeClientSession alice = join("alice", "mute-roster", ChannelMode.FULL_DUPLEX);
		join("bob", "mute-roster", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		// A later joiner's Joined snapshot must carry bob's muted state so its client renders it correctly.
		FakeClientSession carol = join("carol", "mute-roster", ChannelMode.FULL_DUPLEX);
		ServerMessage.Joined joined = firstOf(carol, ServerMessage.Joined.class);
		assertTrue(joined.members().stream().anyMatch(m -> m.id().equals("bob") && m.muted()),
				"the roster marks bob muted");
		assertTrue(joined.members().stream().anyMatch(m -> m.id().equals("carol") && !m.muted()),
				"a fresh joiner is not muted");
	}

	@Test
	void leavingClearsTheMuteStateSoARejoinStartsUnmuted() {
		FakeClientSession alice = join("alice", "mute-leave", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "mute-leave", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));
		assertTrue(channel("mute-leave").isMuted("bob"));

		service.onMessage(bob, new ClientMessage.Leave());
		assertFalse(channel("mute-leave").isMuted("bob"), "leaving scrubs the mute state");

		FakeClientSession bobAgain = join("bob", "mute-leave", ChannelMode.FULL_DUPLEX);   // same id reconnects
		assertFalse(channel("mute-leave").isMuted("bob"), "the rejoining id is not muted");
		byte[] frame = {1, 2, 3};
		alice.audio.clear();
		service.onAudio(bobAgain, ByteBuffer.wrap(frame));
		assertEquals(1, alice.audio.size(), "the rejoined member is heard again");
	}

	@Test
	void muteAllFreesTheFloorOfAMutedHolderInPtt() {
		FakeClientSession alice = join("alice", "mute-all-floor", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		FakeClientSession bob = join("bob", "mute-all-floor", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(bob, new ClientMessage.RequestFloor());
		assertTrue(channel("mute-all-floor").holdsFloor("bob"), "bob is talking");

		alice.sent.clear();
		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteAll(true));

		assertFalse(channel("mute-all-floor").holdsFloor("bob"),
				"mute-all frees the muted holder's floor too (the same floor teardown as single-member mute)");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, _)
						&& holderId == null),
				"the muted ex-holder is told the floor is free (FloorStatus)");
	}

	@Test
	void unmutingAnAlreadyUnmutedMemberBroadcastsNothing() {
		FakeClientSession alice = join("alice", "unmute-idem", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "unmute-idem", ChannelMode.FULL_DUPLEX);   // never muted

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", false));   // no-op unmute
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.MuteStatus.class::isInstance),
				"unmuting an already-unmuted member is a no-op that broadcasts nothing");
	}

	@Test
	void transferringOwnershipToAMutedMemberUnmutesTheNewOwner() {
		FakeClientSession alice = join("alice", "mute-xfer", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "mute-xfer", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));
		assertTrue(channel("mute-xfer").isMuted("bob"));

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.TransferOwnership("bob"));

		assertEquals("bob", channel("mute-xfer").ownerId(), "bob is now the owner");
		assertFalse(channel("mute-xfer").isMuted("bob"),
				"the new owner is never muted — otherwise it could never talk and could not unmute itself");
		assertTrue(bob.sent.stream().anyMatch(m ->
						m instanceof ServerMessage.MuteStatus(Set<String> muted) && !muted.contains("bob")),
				"the channel is told the new owner was unmuted");
		byte[] frame = {1, 2, 3};
		alice.audio.clear();
		service.onAudio(bob, ByteBuffer.wrap(frame));
		assertEquals(1, alice.audio.size(), "the new (unmuted) owner can be heard");
	}

	@Test
	void autoElectingAMutedMemberAsOwnerUnmutesIt() {
		FakeClientSession alice = join("alice", "mute-elect", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "mute-elect", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		bob.sent.clear();
		service.onClose(alice, "owner disconnects");   // bob is the only member left -> auto-elected owner

		assertEquals("bob", channel("mute-elect").ownerId(), "bob is auto-elected owner");
		assertFalse(channel("mute-elect").isMuted("bob"),
				"a departure-triggered auto-election of a muted member unmutes it (no muted-owner deadlock)");
		assertTrue(bob.sent.stream().anyMatch(m ->
						m instanceof ServerMessage.MuteStatus(Set<String> muted) && !muted.contains("bob")),
				"bob is told it was unmuted on promotion");
	}

	/// The reason mute is a snapshot: a channel-wide mute must cost each recipient a CONSTANT number of frames, not
	/// one per member. Asserted by comparing two channel sizes rather than by a magic number — under the retired
	/// per-member scheme the bigger channel delivered strictly more frames, which is the O(N^2) total this replaced.
	/// It matters because the per-recipient control queue is bounded and its overflow DISCONNECTS the client, so a
	/// burst that scales with channel size turns a moderation click into a way to drop everyone.
	@Test
	void muteAllCostsEachMemberTheSameFramesWhateverTheChannelSize() {
		assertEquals(
				muteAllFramesPerMember("small", 3),
				muteAllFramesPerMember("large", 8),
				"a channel-wide mute must fan out one snapshot, so a member's frame count cannot grow with the roster"
		);
	}

	/// Mutes everyone in a fresh `channelName` of `members` (owner included) and returns how many messages the LAST
	/// member received for that single click.
	private int muteAllFramesPerMember(String channelName, int members) {
		FakeClientSession owner = join("owner-" + channelName, channelName, ChannelMode.FULL_DUPLEX);
		FakeClientSession last = null;
		for (int i = 1; i < members; i++) {
			last = join("m" + i + "-" + channelName, channelName, ChannelMode.FULL_DUPLEX);
		}
		last.sent.clear();
		service.onMessage(owner, new ClientMessage.MuteAll(true));
		assertEquals(1, last.sent.stream().filter(ServerMessage.MuteStatus.class::isInstance).count(),
				"exactly one mute snapshot, however many members flipped");
		return last.sent.size();
	}

	/// An in-place switch adds the session to its TARGET and departs the old channel only afterwards — deliberately,
	/// so a refused switch cannot drop it from both — and no lock on the old channel is held across that gap. So a
	/// concurrent change there can fan out to a member that has already moved on, and NO channel-scoped message
	/// carries a channel name for the client to filter on.
	///
	/// It would not be a passing glitch either: most of these are CHANGE events with no periodic re-sync, so a stray
	/// one stays wrong. Staged here by putting a session in a channel's roster while its own channel pointer names
	/// another — exactly the state that gap produces.
	@Test
	void aChannelBroadcastSkipsAMemberThatHasAlreadyMovedToAnotherChannel() {
		FakeClientSession alice = join("alice", "old", ChannelMode.MULTI_CHANNEL_PTT);   // owner of the old channel
		FakeClientSession switcher = join("switcher", "old", ChannelMode.MULTI_CHANNEL_PTT);

		// Mid-switch: the target already has it (its pointer says so), the old channel has not let go yet.
		switcher.joinedChannel("target");
		switcher.sent.clear();
		alice.sent.clear();

		// Every kind of channel-scoped change, so the guard is shown to cover the class and not one message.
		service.onMessage(alice, new ClientMessage.SetLocked(true));
		service.onMessage(alice, new ClientMessage.SetMuteNewMembers(true));
		service.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		service.onMessage(alice, new ClientMessage.MuteAll(true));
		service.onMessage(alice, new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));

		assertTrue(switcher.sent.isEmpty(),
				() -> "a member that has moved on must receive none of its old channel's broadcasts, got " + switcher.sent);
		// The channel it really is in still works — the guard skips a stray recipient, not the fan-out.
		assertFalse(alice.sent.isEmpty(), "the members actually in the channel still receive everything");
		assertTrue(alice.sent.stream().anyMatch(ServerMessage.ChannelLocked.class::isInstance));
		assertTrue(alice.sent.stream().anyMatch(ServerMessage.MuteNewMembersChanged.class::isInstance));
		assertTrue(alice.sent.stream().anyMatch(ServerMessage.ModeChanged.class::isInstance));
	}

	// --- the owner's standing "mute new members on entry" rule -------------------------------------

	@Test
	void withMuteOnEntryArmedAJoinerArrivesMutedAndEveryoneIsToldWithoutAMuteSnapshot() {
		FakeClientSession alice = join("alice", "entry", ChannelMode.FULL_DUPLEX);   // owner
		service.onMessage(alice, new ClientMessage.SetMuteNewMembers(true));
		assertTrue(lastOf(alice, ServerMessage.MuteNewMembersChanged.class).enabled(), "the channel is told the rule is armed");

		alice.sent.clear();
		FakeClientSession bob = join("bob", "entry", ChannelMode.FULL_DUPLEX);

		assertTrue(channel("entry").isMuted("bob"), "the server muted the joiner as it was added");
		// The joiner learns of its OWN mute from its own roster, which is the bit each client's full-duplex mic
		// auto-open reads inside its Joined handler — so it must be true there, not one message later.
		assertTrue(firstOf(bob, ServerMessage.Joined.class).members().stream()
						.anyMatch(member -> member.id().equals("bob") && member.muted()),
				"the joiner's own Joined roster shows it muted");
		assertTrue(firstOf(bob, ServerMessage.Joined.class).muteNewMembers(), "and carries the rule itself");
		// The others learn from MemberJoined alone. A MuteStatus here would be wrong twice over: it is documented as
		// sent only for a CHANGE, and it would name an id its recipients have not been introduced to yet.
		assertTrue(firstOf(alice, ServerMessage.MemberJoined.class).member().muted(),
				"the other members are told the joiner is muted, on the message that introduces it");
		assertTrue(alice.sent.stream().noneMatch(ServerMessage.MuteStatus.class::isInstance),
				"a join emits no mute snapshot — the rule changes nobody already present");
		// Enforcement, not just bookkeeping.
		alice.audio.clear();
		service.onAudio(bob, ByteBuffer.wrap(new byte[]{1, 2, 3}));
		assertEquals(0, alice.audio.size(), "an entry-muted member's audio is dropped server-side");
	}

	@Test
	void armingMuteOnEntryLeavesTheMembersAlreadyPresentAlone() {
		// The whole point of a separate flag: "Mute everyone now" is the one-shot over the present, this is the
		// standing rule for arrivals. Arming it must not cut off whoever is mid-sentence.
		FakeClientSession alice = join("alice", "entry-now", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "entry-now", ChannelMode.FULL_DUPLEX);

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.SetMuteNewMembers(true));

		assertFalse(channel("entry-now").isMuted("bob"), "a member already present is untouched");
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.MuteStatus.class::isInstance),
				"and no mute snapshot is sent, because no mute changed");
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.MuteNewMembersChanged.class::isInstance),
				"only the rule itself is broadcast");
	}

	@Test
	void anEntryMutedMemberTheOwnerUnmutesStaysUnmutedAcrossAReJoin() {
		// THE regression this guards: the browser's Apply flow re-sends Join for the CURRENT channel routinely. If the
		// entry-mute were applied per Join rather than per add, every Apply would silently undo the owner's unmute.
		FakeClientSession alice = join("alice", "entry-rejoin", ChannelMode.FULL_DUPLEX);   // owner
		service.onMessage(alice, new ClientMessage.SetMuteNewMembers(true));
		FakeClientSession bob = join("bob", "entry-rejoin", ChannelMode.FULL_DUPLEX);
		assertTrue(channel("entry-rejoin").isMuted("bob"), "bob arrived muted");

		service.onMessage(alice, new ClientMessage.MuteMember("bob", false));   // the owner lets bob speak
		assertFalse(channel("entry-rejoin").isMuted("bob"));

		bob.sent.clear();
		service.onMessage(bob, new ClientMessage.Join("entry-rejoin", ChannelMode.FULL_DUPLEX, "bob", TestKeyChecks.ENCRYPTED));

		assertFalse(channel("entry-rejoin").isMuted("bob"), "an idempotent re-join must not re-apply the entry mute");
		assertFalse(firstOf(bob, ServerMessage.Joined.class).members().stream()
						.anyMatch(member -> member.id().equals("bob") && member.muted()),
				"and the re-snapshot reports it unmuted");
	}

	@Test
	void aNewcomerAdmittedFromTheWaitingListAlsoArrivesMuted() {
		// An approved knocker completes its join by re-sending Join, so it goes through the same add — no separate
		// handling, which is exactly why the rule belongs at the add.
		// The shared `service` has parking disabled (max-join-requests 0), so a locked channel would refuse outright.
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("entry-knock", ChannelMode.FULL_DUPLEX, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetMuteNewMembers(true));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));

		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("entry-knock", ChannelMode.FULL_DUPLEX, "bob", TestKeyChecks.ENCRYPTED));
		assertTrue(bob.sent.stream().anyMatch(ServerMessage.JoinPending.class::isInstance), "bob is parked");
		svc.onMessage(alice, new ClientMessage.ResolveJoinRequest("bob", true));
		svc.onMessage(bob, new ClientMessage.Join("entry-knock", ChannelMode.FULL_DUPLEX, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals("entry-knock", bob.channelName(), "its own re-Join completed the join");
		assertTrue(channel("entry-knock").isMuted("bob"), "an admitted newcomer is a newcomer");
	}

	@Test
	void promotingAnEntryMutedMemberToOwnerUnmutesIt() {
		// The rule can only ever mute a NON-owner, but ownership moves: an entry-muted member that inherits the
		// channel would otherwise be a muted owner, and an owner cannot unmute itself.
		FakeClientSession alice = join("alice", "entry-owner", ChannelMode.FULL_DUPLEX);   // owner
		service.onMessage(alice, new ClientMessage.SetMuteNewMembers(true));
		FakeClientSession bob = join("bob", "entry-owner", ChannelMode.FULL_DUPLEX);
		assertTrue(channel("entry-owner").isMuted("bob"));

		bob.sent.clear();
		service.onClose(alice, "the owner leaves");   // auto-election promotes bob

		assertEquals("bob", channel("entry-owner").ownerId());
		assertFalse(channel("entry-owner").isMuted("bob"), "the new owner is never muted — it could not unmute itself");
		assertTrue(bob.sent.stream().anyMatch(m ->
						m instanceof ServerMessage.MuteStatus(Set<String> muted) && !muted.contains("bob")),
				"and the channel is told, via the snapshot");
	}

	@Test
	void onlyTheOwnerCanArmMuteOnEntryAndTheGlobalRoomNever() {
		FakeClientSession alice = join("alice", "entry-owner-only", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "entry-owner-only", ChannelMode.FULL_DUPLEX);

		service.onMessage(bob, new ClientMessage.SetMuteNewMembers(true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertFalse(channel("entry-owner-only").mutesNewMembers(), "a non-owner cannot arm it");
		assertTrue(alice.sent.stream().noneMatch(ServerMessage.MuteNewMembersChanged.class::isInstance),
				"and nothing is broadcast");

		// The global room's sentinel owner is nobody's session id, so even its first member is not its owner.
		FakeClientSession global = join("g", null, ChannelMode.GLOBAL_PTT);
		service.onMessage(global, new ClientMessage.SetMuteNewMembers(true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(global, ServerMessage.ErrorMessage.class).code());
		assertFalse(channel("global").mutesNewMembers());
	}

	@Test
	void theGlobalRoomCannotBeMuted() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		join("bob", null, ChannelMode.GLOBAL_PTT);

		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(alice, ServerMessage.ErrorMessage.class).code(),
				"no participant owns the server-managed global room, so no one can mute in it");
		assertFalse(channel("global").isMuted("bob"));

		alice.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteAll(true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(alice, ServerMessage.ErrorMessage.class).code());
	}

	// --- owner-locked channel ----------------------------------------------------------------------

	@Test
	void theOwnerLocksTheChannelAndANewcomerIsRefused() {
		FakeClientSession alice = join("alice", "lockable", ChannelMode.MULTI_CHANNEL_PTT);   // owner
		alice.sent.clear();
		service.onMessage(alice, new ClientMessage.SetLocked(true));
		assertTrue(channel("lockable").isLocked());
		assertTrue(firstOf(alice, ServerMessage.ChannelLocked.class).locked(),
				"the lock is broadcast to the channel (the owner included)");

		FakeClientSession bob = join("bob", "lockable", ChannelMode.MULTI_CHANNEL_PTT);   // a newcomer
		assertEquals(ErrorCode.CHANNEL_LOCKED, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertFalse(bob.sent.stream().anyMatch(ServerMessage.Joined.class::isInstance), "the newcomer never joined");
		assertNull(bob.channelName(), "the refused joiner is not in the channel");
		assertEquals(1, channel("lockable").size(), "bob was not added");
	}

	@Test
	void unlockingLetsNewMembersJoinAgain() {
		FakeClientSession alice = join("alice", "relock", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = join("bob", "relock", ChannelMode.FULL_DUPLEX);
		assertEquals(ErrorCode.CHANNEL_LOCKED, firstOf(bob, ServerMessage.ErrorMessage.class).code(), "refused while locked");

		service.onMessage(alice, new ClientMessage.SetLocked(false));
		assertFalse(channel("relock").isLocked());
		FakeClientSession carol = join("carol", "relock", ChannelMode.FULL_DUPLEX);
		assertTrue(carol.sent.stream().anyMatch(ServerMessage.Joined.class::isInstance), "a newcomer joins once unlocked");
		assertEquals(2, channel("relock").size(), "alice + carol (bob never joined)");
	}

	@Test
	void aNonOwnerCannotLockTheChannel() {
		join("alice", "noown-lock", ChannelMode.FULL_DUPLEX);   // alice owns it
		FakeClientSession bob = join("bob", "noown-lock", ChannelMode.FULL_DUPLEX);
		service.onMessage(bob, new ClientMessage.SetLocked(true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertFalse(channel("noown-lock").isLocked(), "a non-owner's lock request has no effect");
	}

	@Test
	void anExistingMemberCanReSnapshotALockedChannelAndSeesItLocked() {
		FakeClientSession alice = join("alice", "relock-snap", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "relock-snap", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.SetLocked(true));

		bob.sent.clear();
		// bob re-sends Join for its CURRENT channel — an idempotent re-snapshot, allowed despite the lock.
		service.onMessage(bob, new ClientMessage.Join("relock-snap", ChannelMode.FULL_DUPLEX, "bob", TestKeyChecks.ENCRYPTED));
		ServerMessage.Joined snap = firstOf(bob, ServerMessage.Joined.class);
		assertTrue(snap.locked(), "the re-snapshot carries the locked state");
		assertFalse(bob.sent.stream().anyMatch(ServerMessage.ErrorMessage.class::isInstance),
				"an existing member is never locked out of its own channel");
		assertEquals(2, channel("relock-snap").size(), "membership is unchanged");
	}

	@Test
	void aLockedChannelStaysLockedWhenTheOwnerLeavesAndTheNewOwnerCanUnlock() {
		FakeClientSession alice = join("alice", "lock-persist", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "lock-persist", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.SetLocked(true));

		service.onClose(alice, "owner leaves");   // bob is auto-elected owner
		assertEquals("bob", channel("lock-persist").ownerId());
		assertTrue(channel("lock-persist").isLocked(), "the lock survives the ownership change");

		FakeClientSession carol = join("carol", "lock-persist", ChannelMode.FULL_DUPLEX);
		assertEquals(ErrorCode.CHANNEL_LOCKED, firstOf(carol, ServerMessage.ErrorMessage.class).code(),
				"the inherited lock still refuses newcomers");

		service.onMessage(bob, new ClientMessage.SetLocked(false));   // the new owner can unlock
		assertFalse(channel("lock-persist").isLocked());
		FakeClientSession dave = join("dave", "lock-persist", ChannelMode.FULL_DUPLEX);
		assertTrue(dave.sent.stream().anyMatch(ServerMessage.Joined.class::isInstance), "the new owner unlocked it");
	}

	@Test
	void theGlobalRoomCannotBeLocked() {
		FakeClientSession alice = join("alice", null, ChannelMode.GLOBAL_PTT);
		service.onMessage(alice, new ClientMessage.SetLocked(true));
		assertEquals(ErrorCode.NOT_OWNER, firstOf(alice, ServerMessage.ErrorMessage.class).code(),
				"no participant owns the server-managed global room, so no one can lock it");
		assertFalse(channel("global").isLocked());
	}

	@Test
	void lockingBroadcastsToExistingMembersAndDoesNotAffectThem() {
		FakeClientSession alice = join("alice", "lock-bcast", ChannelMode.FULL_DUPLEX);   // owner
		FakeClientSession bob = join("bob", "lock-bcast", ChannelMode.FULL_DUPLEX);
		assertFalse(firstOf(bob, ServerMessage.Joined.class).locked(),
				"a normal join into an unlocked channel reports locked=false");

		bob.sent.clear();
		alice.audio.clear();
		service.onMessage(alice, new ClientMessage.SetLocked(true));

		// The lock reaches an EXISTING member (not just the owner), removes nobody, and doesn't gate their audio.
		assertTrue(firstOf(bob, ServerMessage.ChannelLocked.class).locked(),
				"an existing member is told the channel locked");
		assertEquals(2, channel("lock-bcast").size(), "locking removes no existing members");
		service.onAudio(bob, ByteBuffer.wrap(new byte[]{1, 2, 3}));
		assertEquals(1, alice.audio.size(), "an existing member's audio still relays while the channel is locked");

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.SetLocked(false));
		assertFalse(firstOf(bob, ServerMessage.ChannelLocked.class).locked(),
				"the unlock is broadcast to the channel too");
	}

	@Test
	void settingLockBeforeJoiningAChannelIsRejected() {
		FakeClientSession stray = session("stray");   // never joined a channel
		service.onMessage(stray, new ClientMessage.SetLocked(true));
		assertEquals(ErrorCode.NOT_IN_CHANNEL, firstOf(stray, ServerMessage.ErrorMessage.class).code());
	}

	// --- owner-approved join requests (a locked channel parks newcomers) ----------------------------

	/// A service whose channels park up to `cap` newcomers (`walkie.max-join-requests`), over the shared registry.
	private ConnectionService serviceParking(int cap) {
		return new ConnectionService(
				channelRegistry,
				new WalkieProperties(
						new String[]{"*"}, 8192, 65536, 100, 1_000_000, Duration.ofSeconds(5), Duration.ofSeconds(300), Duration.ofSeconds(10), false, cap, null, false, Duration.ZERO),
				BROADCASTER
		);
	}

	@Test
	void aNewcomerAtALockedChannelIsParkedAndTheOwnerIsTold() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals("team", firstOf(bob, ServerMessage.JoinPending.class).channel(),
				"the newcomer is told it is waiting, NOT refused");
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.Joined.class::isInstance), "and it did not join");
		assertNull(bob.channelName(), "a parked newcomer is not a member of anything");
		assertEquals("team", bob.pendingChannel(), "it is marked as waiting, so a disconnect can scrub it");
		assertEquals(List.of("bob"),
				lastOf(alice, ServerMessage.JoinRequests.class).requests().stream().map(JoinRequestInfo::id).toList(),
				"the owner is sent the waiting list");
		assertEquals(1, channel("team").size(), "the channel still has only its owner");
	}

	/// A fresh connection's Join is the only place its display name comes from, so parking must not roll it back —
	/// the owner would be looking at an anonymous entry and could not tell who it is deciding about.
	@Test
	void aParkedNewcomerKeepsTheNameItsJoinCarried() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = new FakeClientSession("bob-id", Transport.AUDIO_RELAY, "");   // no name until it joins

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals("bob", bob.displayName(), "the name its Join carried survives being parked");
		assertEquals(
				"bob",
				lastOf(alice, ServerMessage.JoinRequests.class).requests().getFirst().displayName(),
				"so the owner sees who is asking, not a blank entry"
		);
	}

	/// The waiting list renders the newcomer's display name, but a rename changes no channel's MEMBERSHIP — so
	/// nothing else would ever refresh it, and the owner would go on deciding about a name that newcomer no longer
	/// uses.
	@Test
	void renamingWhileWaitingRefreshesTheOwnersList() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(bob, new ClientMessage.Rename("bob-renamed"));

		assertEquals("bob-renamed", bob.displayName());
		assertEquals(
				"bob-renamed",
				lastOf(alice, ServerMessage.JoinRequests.class).requests().getFirst().displayName(),
				"the owner is shown the name the newcomer now uses"
		);
	}

	/// The same, but reached by re-sending Join with a different name — which is what the browser's Apply does when
	/// the user edits the display-name field while waiting.
	@Test
	void reJoiningWithANewNameWhileWaitingRefreshesTheOwnersList() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob2", TestKeyChecks.ENCRYPTED));

		assertEquals("bob2", bob.displayName(), "a parked newcomer keeps the name its latest Join carried");
		assertEquals(
				List.of("bob2"),
				lastOf(alice, ServerMessage.JoinRequests.class).requests().stream()
						.map(JoinRequestInfo::displayName).toList()
		);
		assertEquals(1, channel("team").joinRequestInfos().size(), "and it is still ONE request, not two");
	}

	/// The anti-spam guard has to survive the fix above: a plain retry loop must not hand the owner a snapshot per
	/// attempt, or a client could fill their control mailbox and get them disconnected for backlog.
	@Test
	void reJoiningWithTheSameNameDoesNotReNotifyTheOwner() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		long afterFirstKnock = alice.sent.stream().filter(ServerMessage.JoinRequests.class::isInstance).count();

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(bob, new ClientMessage.Rename("bob"));   // a no-op rename is also not a change

		assertEquals(
				afterFirstKnock,
				alice.sent.stream().filter(ServerMessage.JoinRequests.class::isInstance).count(),
				"nothing the owner can see changed, so they are sent nothing"
		);
	}

	/// A SWITCHER that gets parked has its Join-carried name rolled back (its old channel was never told of a
	/// rename), so the waiting list must keep showing the name that channel still knows it by.
	@Test
	void aParkedSwitcherKeepsTheNameItsOwnChannelKnowsItBy() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob-renamed", TestKeyChecks.ENCRYPTED));

		assertEquals("bob", bob.displayName(), "the rename is rolled back with the rest of the refused switch");
		assertEquals(
				"bob",
				lastOf(alice, ServerMessage.JoinRequests.class).requests().getFirst().displayName(),
				"so the waiting list agrees with the roster of the channel it is still in"
		);
	}

	@Test
	void aParkedNewcomerCannotLetItselfIn() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		// Re-sending Join is exactly what an APPROVED newcomer does to claim its place, so an unapproved one
		// re-sending it must NOT get in — otherwise the lock means nothing.
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertTrue(bob.sent.stream().noneMatch(ServerMessage.Joined.class::isInstance),
				"a re-Join without an approval does not admit the newcomer");
		assertEquals(1, channel("team").size());
		assertEquals(1, alice.sent.stream().filter(ServerMessage.JoinRequests.class::isInstance).count(),
				"and the idempotent re-knock does not re-notify the owner (a looping client can't flood them)");
	}

	/// Being parked costs the switcher nothing: the join departs the old channel only once the target has taken it,
	/// so waiting for an owner's approval leaves the existing membership, floor and roster entry untouched.
	@Test
	void aMemberSwitchingIntoALockedChannelIsParkedAndKeepsItsOldChannel() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals("other", bob.channelName(), "waiting does not cost the switcher the channel it already had");
		assertEquals("team", bob.pendingChannel(), "and it is now waiting at the locked channel's door");
		assertEquals("team", firstOf(bob, ServerMessage.JoinPending.class).channel());
		assertEquals(1, channel("other").size(), "it is still a member of its own channel");
	}

	@Test
	void aWaitingNewcomerThatDisconnectsIsScrubbedFromTheOwnersList() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onClose(bob, "normal close");

		assertTrue(lastOf(alice, ServerMessage.JoinRequests.class).requests().isEmpty(),
				"the request dies with the socket — otherwise it would hold a slot on the owner's list forever");
		assertNull(bob.pendingChannel());
	}

	@Test
	void knockingAtASecondDoorWithdrawsTheFirstRequest() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession dave = session("dave");
		svc.onMessage(dave, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "dave", TestKeyChecks.ENCRYPTED));
		svc.onMessage(dave, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(bob, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals("other", bob.pendingChannel(), "a session waits at exactly one door");
		assertTrue(lastOf(alice, ServerMessage.JoinRequests.class).requests().isEmpty(),
				"the abandoned request is withdrawn and its owner's list refreshed");
		assertEquals(List.of("bob"),
				lastOf(dave, ServerMessage.JoinRequests.class).requests().stream().map(JoinRequestInfo::id).toList());
	}

	@Test
	void withTheCapAtZeroALockedChannelStillRefusesOutright() {
		ConnectionService svc = serviceParking(0);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals(ErrorCode.CHANNEL_LOCKED, firstOf(bob, ServerMessage.ErrorMessage.class).code(),
				"cap 0 keeps the pre-feature behaviour, so CHANNEL_LOCKED stays reachable");
		assertNull(bob.pendingChannel(), "and nothing is parked");
	}

	@Test
	void aWrongPassphraseIsRefusedRatherThanParked() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", "kcv-B"));

		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(bob, ServerMessage.ErrorMessage.class).code(),
				"the key-check is validated BEFORE parking, so the owner is never asked to approve someone who "
						+ "could not have got in anyway");
		assertNull(bob.pendingChannel());
	}

	@Test
	void theWaitingListIsRefusedOnceFull() {
		ConnectionService svc = serviceParking(1);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		FakeClientSession carol = session("carol");

		svc.onMessage(carol, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));

		assertEquals(ErrorCode.TOO_MANY_JOIN_REQUESTS, firstOf(carol, ServerMessage.ErrorMessage.class).code());
		assertNull(carol.pendingChannel());
		assertEquals(List.of("bob"),
				lastOf(alice, ServerMessage.JoinRequests.class).requests().stream().map(JoinRequestInfo::id).toList(),
				"the full list is unchanged");
	}

	/// Admitting is grant-then-claim: the owner's approval does not add the member, it authorises the newcomer's own
	/// re-sent `Join` to pass the lock. The server cannot add it directly — a newcomer may be in another channel, and
	/// leaving that one from inside the atomic join is forbidden — so the round trip IS the design.
	@Test
	void anAdmittedNewcomerJoinsByReSendingItsJoin() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(alice, new ClientMessage.ResolveJoinRequest("bob", true));

		assertEquals("team", firstOf(bob, ServerMessage.JoinApproved.class).channel(), "the newcomer is told to claim");
		assertNull(bob.channelName(), "an approval alone does not make it a member");

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals("team", bob.channelName(), "its own re-Join completes the join");
		assertEquals(2, channel("team").size());
		assertNull(bob.pendingChannel(), "and it is no longer waiting");
		assertTrue(lastOf(alice, ServerMessage.JoinRequests.class).requests().isEmpty(),
				"the request left the owner's list when it was consumed");
	}

	@Test
	void aGrantIsOneShotSoAReJoinCannotBeReplayed() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.ResolveJoinRequest("bob", true));
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		// bob is in. It leaves, then tries to walk back in on the strength of the approval it already spent.
		svc.onMessage(bob, new ClientMessage.Leave());
		bob.sent.clear();

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertNull(bob.channelName(), "the spent grant does not let it back in");
		assertEquals("team", firstOf(bob, ServerMessage.JoinPending.class).channel(), "it has to ask again");
	}

	@Test
	void aDeniedNewcomerIsToldAndDroppedFromTheList() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(alice, new ClientMessage.ResolveJoinRequest("bob", false));

		assertEquals(ErrorCode.JOIN_REQUEST_DENIED, firstOf(bob, ServerMessage.ErrorMessage.class).code());
		assertNull(bob.pendingChannel());
		assertTrue(lastOf(alice, ServerMessage.JoinRequests.class).requests().isEmpty());
		assertEquals(1, channel("team").size());
	}

	@Test
	void anUnclaimedApprovalCanStillBeRevoked() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.ResolveJoinRequest("bob", true));
		// bob's client never comes back to claim it — which is why a granted request stays on the owner's list.
		assertEquals(List.of("bob"),
				lastOf(alice, ServerMessage.JoinRequests.class).requests().stream().map(JoinRequestInfo::id).toList());

		svc.onMessage(alice, new ClientMessage.ResolveJoinRequest("bob", false));
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertNull(bob.channelName(), "the revoked approval no longer admits it");
		// Its claim arrives to find no grant, so it counts as asking again — which is what bob is in fact doing.
		assertEquals("team", bob.pendingChannel());
		assertEquals(List.of("bob"),
				lastOf(alice, ServerMessage.JoinRequests.class).requests().stream().map(JoinRequestInfo::id).toList(),
				"a revoked-then-retried newcomer is back on the list as a fresh request");
	}

	@Test
	void aWithdrawnRequestLeavesTheOwnersList() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(bob, new ClientMessage.WithdrawJoinRequest());

		assertNull(bob.pendingChannel());
		assertTrue(lastOf(alice, ServerMessage.JoinRequests.class).requests().isEmpty());
	}

	@Test
	void unlockingAdmitsEveryoneWhoWasWaiting() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		FakeClientSession carol = session("carol");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(carol, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));

		svc.onMessage(alice, new ClientMessage.SetLocked(false));

		// An unlocked channel admits anyone, so leaving people parked at an open door would be incoherent.
		assertEquals("team", firstOf(bob, ServerMessage.JoinApproved.class).channel());
		assertEquals("team", firstOf(carol, ServerMessage.JoinApproved.class).channel());
		assertNull(bob.pendingChannel());
		assertTrue(lastOf(alice, ServerMessage.JoinRequests.class).requests().isEmpty(),
				"the list is drained, not left full of approvals no unlocked channel would ever consume");

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		assertEquals("team", bob.channelName(), "and they join normally, the lock being gone");
	}

	/// The case that makes the sealed LeaveOutcome necessary: the owner of a locked channel is its ONLY member, so
	/// its departure drops the channel — taking the waiting list with it. Those newcomers would wait forever for an
	/// owner who no longer exists, so they are released instead.
	@Test
	void theLastMemberLeavingALockedChannelReleasesEveryoneWaiting() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onClose(alice, "normal close");

		assertFalse(channelExists("team"), "the channel emptied and was dropped");
		assertEquals("team", firstOf(bob, ServerMessage.JoinApproved.class).channel(),
				"the lock died with the channel, so the waiting newcomer is cleared to join");
		assertNull(bob.pendingChannel());

		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		assertEquals("team", bob.channelName(), "its re-Join RECREATES the channel");
		assertEquals("bob", channel("team").ownerId(), "and whoever was waiting at an abandoned door now owns it");
		assertFalse(channel("team").isLocked(), "a freshly created channel is never locked");
	}

	@Test
	void aNewlyElectedOwnerInheritsTheWaitingList() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		FakeClientSession carol = session("carol");
		svc.onMessage(carol, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		carol.sent.clear();

		svc.onClose(alice, "normal close");   // ownership auto-elects to carol

		assertEquals("carol", channel("team").ownerId());
		assertEquals(List.of("bob"),
				lastOf(carol, ServerMessage.JoinRequests.class).requests().stream().map(JoinRequestInfo::id).toList(),
				"the waiting list is owner-only knowledge, so the new owner must be handed it");
		svc.onMessage(carol, new ClientMessage.ResolveJoinRequest("bob", true));
		assertEquals("team", firstOf(bob, ServerMessage.JoinApproved.class).channel(), "and can act on it");
	}

	@Test
	void onlyTheOwnerCanResolveAJoinRequest() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		FakeClientSession carol = session("carol");
		svc.onMessage(carol, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));

		svc.onMessage(carol, new ClientMessage.ResolveJoinRequest("bob", true));

		assertEquals(ErrorCode.NOT_OWNER, firstOf(carol, ServerMessage.ErrorMessage.class).code());
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.JoinApproved.class::isInstance),
				"a non-owner's approval admits nobody");
	}

	@Test
	void resolvingAnUnknownRequestIsReportedRatherThanIgnored() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));

		svc.onMessage(alice, new ClientMessage.ResolveJoinRequest("ghost", true));

		assertEquals(ErrorCode.UNKNOWN_TARGET, firstOf(alice, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void admitAllAdmitsEveryWaitingNewcomerAndDenyAllTurnsThemAway() {
		ConnectionService svc = serviceParking(16);
		FakeClientSession alice = session("alice");
		svc.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.SetLocked(true));
		FakeClientSession bob = session("bob");
		FakeClientSession carol = session("carol");
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(carol, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));

		svc.onMessage(alice, new ClientMessage.ResolveAllJoinRequests(true));

		// Admit-all keeps the channel LOCKED, so each newcomer still needs its grant to pass the lock.
		svc.onMessage(bob, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "bob", TestKeyChecks.ENCRYPTED));
		svc.onMessage(carol, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "carol", TestKeyChecks.ENCRYPTED));
		assertEquals(3, channel("team").size());
		assertTrue(channel("team").isLocked(), "admit-all is not an unlock");

		FakeClientSession dave = session("dave");
		svc.onMessage(dave, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "dave", TestKeyChecks.ENCRYPTED));
		svc.onMessage(alice, new ClientMessage.ResolveAllJoinRequests(false));

		assertEquals(ErrorCode.JOIN_REQUEST_DENIED, firstOf(dave, ServerMessage.ErrorMessage.class).code());
		assertNull(dave.pendingChannel());
	}

	// --- session lifecycle: a closed session must not resurrect per-session state --------------------

	/// A late control frame from a session whose socket has already gone is dropped BEFORE anything acts on it.
	/// The motivating case is the per-session rate-limiter bucket: `onClose` forgets it, and a straggler frame
	/// reaching `tryAcquire` would re-create it — a permanent one-entry leak, because nothing forgets it a second
	/// time. `afterConnectionClosed` closes the session before `onClose` runs, so the guard sees `isClosed() == true`
	/// for the whole teardown. Only expressible now that [FakeClientSession] can actually be closed.
	@Test
	void aControlMessageFromAClosedSessionIsDropped() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);

		alice.close();   // its socket went away
		service.onMessage(alice, new ClientMessage.Rename("alice-renamed"));

		assertEquals("alice", alice.displayName(), "a closed session's control message is never dispatched");
		assertTrue(
				bob.sent.stream().noneMatch(ServerMessage.MemberRenamed.class::isInstance),
				"and nothing is broadcast to the channel on its behalf"
		);
	}

	/// The same frame from a session that is still open IS dispatched — so the guard above is doing the work, and
	/// the assertion isn't passing for some unrelated reason (e.g. a rejected display name).
	@Test
	void theSameControlMessageFromAnOpenSessionIsDispatched() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		FakeClientSession bob = join("bob", "team", ChannelMode.MULTI_CHANNEL_PTT);

		service.onMessage(alice, new ClientMessage.Rename("alice-renamed"));

		assertEquals("alice-renamed", alice.displayName());
		assertEquals("alice-renamed", firstOf(bob, ServerMessage.MemberRenamed.class).displayName());
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
		public String handshakeChannel() {
			return null;
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
		public String pendingChannel() {
			return null;   // this fake never waits at a door; it exists only to fail an audio send
		}

		@Override
		public void pendingIn(String channel) {
		}

		@Override
		public void pendingCleared() {
		}

		@Override
		public boolean supportsAudioRelay() {
			return true;
		}

		@Override
		public void sendEncoded(String encoded) {
			// control frames are irrelevant to this fake
		}

		@Override
		public void sendAudio(byte[] audio) {
			throw new RuntimeException("simulated send failure");
		}
	}
}
