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
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

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
					5,
					300,
					10,
					false,
					null, false
			),
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
						idleSeconds,
						maxHoldSeconds,
						10,
						false,
						null, false
				),
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

	/// Whether a channel with `name` currently exists — the absence counterpart to [#channel], for asserting a
	/// channel was never created or was dropped once empty (where [#channel] would instead fail the test).
	private boolean channelExists(String name) {
		return channelRegistry.find(name) instanceof Some(Channel _);
	}

	private FakeClientSession join(String id, String channelName, ChannelMode mode) {
		FakeClientSession session = session(id);
		service.onMessage(session, new ClientMessage.Join(channelName, mode, id, null));
		return session;
	}

	// --- channel affinity (multi-instance routing) -------------------------------------------------

	/// A service with `channelAffinity` ON, over the shared registry — so the routing invariant is exercised.
	private ConnectionService affinityService() {
		return new ConnectionService(
				channelRegistry,
				new WalkieProperties(new String[]{"*"}, 8192, 65536, 100, 1_000_000, 5, 300, 10, false, null, true), BROADCASTER);
	}

	private static boolean received(FakeClientSession session, ErrorCode code) {
		return session.sent.stream().anyMatch(m -> m instanceof ServerMessage.ErrorMessage(ErrorCode c, String _) && c == code);
	}

	@Test
	void channelAffinityAllowsJoiningTheHandshakeChannel() {
		ConnectionService svc = affinityService();
		FakeClientSession alice = session("alice");
		alice.setHandshakeChannel("team1");   // the router pinned this socket to team1
		svc.onMessage(alice, new ClientMessage.Join("team1", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals("team1", alice.channelName());
		assertFalse(received(alice, ErrorCode.CHANNEL_ROUTING_MISMATCH));
	}

	@Test
	void channelAffinityAllowsSwitchingToAChannelThisInstanceAlreadyHosts() {
		ConnectionService svc = affinityService();
		FakeClientSession bob = session("bob");
		bob.setHandshakeChannel("team2");
		svc.onMessage(bob, new ClientMessage.Join("team2", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));   // team2 now hosted here
		FakeClientSession alice = session("alice");
		alice.setHandshakeChannel("team1");
		svc.onMessage(alice, new ClientMessage.Join("team1", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(alice, new ClientMessage.Join("team2", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));   // co-located switch
		assertEquals("team2", alice.channelName());
		assertFalse(received(alice, ErrorCode.CHANNEL_ROUTING_MISMATCH));
	}

	@Test
	void channelAffinityRefusesSwitchingToAChannelOwnedByAnotherInstance() {
		ConnectionService svc = affinityService();
		FakeClientSession alice = session("alice");
		alice.setHandshakeChannel("team1");
		svc.onMessage(alice, new ClientMessage.Join("team1", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		// team9 is neither the handshake channel nor hosted here → this socket can't serve it.
		svc.onMessage(alice, new ClientMessage.Join("team9", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertTrue(received(alice, ErrorCode.CHANNEL_ROUTING_MISMATCH));
		assertEquals("team1", alice.channelName(), "the rejected switch must not drop the client from its channel");
		assertFalse(channelExists("team9"), "the wrong-instance channel must not be created here");
	}

	@Test
	void withoutChannelAffinityASwitchToAnyChannelIsAllowed() {
		// The default `service` has affinity OFF: switching to a brand-new channel is fine (single instance).
		FakeClientSession alice = join("alice", "team1", ChannelMode.MULTI_CHANNEL_PTT);
		service.onMessage(alice, new ClientMessage.Join("team9", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
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

		service.onMessage(alice, new ClientMessage.Rename("bad name"));   // a space is not in the allowed charset

		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertEquals("alice", alice.displayName(), "the label is unchanged on rejection");
		assertFalse(bob.sent.stream().anyMatch(ServerMessage.MemberRenamed.class::isInstance),
				"no rename is broadcast for an invalid name");
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
		assertFalse(channelExists("team"), "the previous channel is left (and dropped once empty)");
		assertEquals(1, channel("other").size());
	}

	@Test
	void aSwitchToAnInvalidTargetKeepsTheClientInItsCurrentChannel() {
		FakeClientSession alice = join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);
		alice.sent.clear();

		// Bad target channel name: validated BEFORE leaving, so the switch is refused without dropping alice.
		service.onMessage(alice, new ClientMessage.Join("bad name!", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));

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
		service.onMessage(session, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "has spaces", null));

		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(session, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("team"), "the channel is not created when the join is rejected");
	}

	@Test
	void aJoinWithAMismatchedKeyCheckIsRejected() {
		join("alice", "team", ChannelMode.MULTI_CHANNEL_PTT);   // creator establishes keyCheck = null (unencrypted)
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
	void theOwnerCanDisableEncryptionByClearingThePassphrase() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		alice.sent.clear();

		service.onMessage(alice, new ClientMessage.ChangePassphrase(null, null));   // null key-check = make it plaintext

		assertNull(firstOf(alice, ServerMessage.PassphraseChanged.class).keyCheck(), "null key-check announces 'unencrypted'");
		assertNull(channel("team").keyCheck(), "the channel is now unencrypted");
		// A plaintext joiner can now join, and an encrypted one is rejected.
		FakeClientSession plain = session("plain");
		service.onMessage(plain, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "plain", null));
		assertEquals("team", firstOf(plain, ServerMessage.Joined.class).channel());
		FakeClientSession enc = session("enc");
		service.onMessage(enc, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "enc", "kcv-A"));
		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(enc, ServerMessage.ErrorMessage.class).code());
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
	void theOwnerCanReEnableEncryptionAfterClearingIt() {
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		service.onMessage(alice, new ClientMessage.ChangePassphrase(null, null));      // disable encryption
		service.onMessage(alice, new ClientMessage.ChangePassphrase("kcv-B", null));   // re-enable with a new key
		assertEquals("kcv-B", channel("team").keyCheck());
		// A plaintext joiner is now rejected again; one with the new key-check is accepted.
		FakeClientSession plain = session("plain");
		service.onMessage(plain, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "plain", null));
		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(plain, ServerMessage.ErrorMessage.class).code());
		FakeClientSession enc = session("enc");
		service.onMessage(enc, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "enc", "kcv-B"));
		assertEquals("team", firstOf(enc, ServerMessage.Joined.class).channel());
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
		assertTrue(channel("team").member(announced) instanceof Some(ClientSession _), "the announced owner is a current member");
	}

	@Test
	void aSwitchToAChannelWithAWrongPassphraseDropsTheClientFromBoth() {
		// alice owns encrypted "team"; "other" already exists with a DIFFERENT key-check.
		FakeClientSession alice = session("alice");
		service.onMessage(alice, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-A"));
		FakeClientSession bootstrap = session("bootstrap");
		service.onMessage(bootstrap, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "bootstrap", "kcv-OTHER"));
		alice.sent.clear();

		// In-place switch to "other" with the WRONG key-check: handleJoin leaves "team" BEFORE joinOrCreate
		// validates the target's key-check, so this is the one switch failure that genuinely drops you.
		service.onMessage(alice, new ClientMessage.Join("other", ChannelMode.MULTI_CHANNEL_PTT, "alice", "kcv-WRONG"));

		assertEquals(ErrorCode.PASSPHRASE_MISMATCH, firstOf(alice, ServerMessage.ErrorMessage.class).code());
		assertNull(alice.channelName(), "a wrong-passphrase switch drops the client from BOTH channels");
		assertFalse(channelExists("team"), "the old channel was left (and dropped once empty)");
		assertEquals(1, channel("other").size(), "the mismatched switcher was not added to the target");
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

	@Test
	void aJoinWithANullChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join(null, ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnEmptyChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnOverlongChannelNameIsRejectedAsInvalidChannel() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("x".repeat(65), ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void channelNameValidationHappensBeforeDisplayNameValidation() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("bad name", ChannelMode.MULTI_CHANNEL_PTT, "also bad!!", null));
		assertEquals(ErrorCode.INVALID_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code(),
				"the channel name is validated before the display name");
	}

	@Test
	void aJoinWithANullDisplayNameIsRejectedAsInvalidDisplayName() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, null, null));
		assertEquals(ErrorCode.INVALID_DISPLAY_NAME, firstOf(s, ServerMessage.ErrorMessage.class).code());
	}

	@Test
	void aJoinWithAnOverlongDisplayNameIsRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "x".repeat(33), null));
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
		service.onMessage(s, new ClientMessage.Join("global", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		assertEquals(ErrorCode.RESERVED_CHANNEL, firstOf(s, ServerMessage.ErrorMessage.class).code());
		assertFalse(channelExists("global"), "the global channel is not created by a reserved-name rejection");
	}

	@Test
	void joiningTheGlobalNameInFullDuplexIsReservedRejected() {
		FakeClientSession s = session("s1");
		service.onMessage(s, new ClientMessage.Join("global", ChannelMode.FULL_DUPLEX, "alice", null));
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
		assertDoesNotThrow(() -> service.onAudio(s, new byte[]{1, 2, 3}));
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
		service.onAudio(alice, frame);

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
		svc.onMessage(alice, new ClientMessage.Join("ptt", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("ptt", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));

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
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
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
		svc.onMessage(alice, new ClientMessage.Join("ptt3", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("ptt3", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(carol, new ClientMessage.Join("ptt3", ChannelMode.MULTI_CHANNEL_PTT, "carol", null));
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
		svc.onMessage(alice, new ClientMessage.Join("swept", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("swept", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds at t=0, then goes silent (no frames, no release)
		assertTrue(channel("swept").holdsFloor("alice"));

		alice.sent.clear();
		bob.sent.clear();
		svc.releaseExpiredFloors();                               // within the cap -> no-op
		assertTrue(channel("swept").holdsFloor("alice"), "the sweep leaves a holder alone before the cap");

		clock.advance(Duration.ofSeconds(11));                    // past the cap, with no audio frame and no other requester
		svc.releaseExpiredFloors();

		assertFalse(channel("swept").holdsFloor("alice"), "the silent over-cap holder is reclaimed by the sweep");
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
						&& holderId == null),
				"the (ex-)holder is notified via FloorStatus (no holder) so its client stops transmitting");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
						&& holderId == null),
				"other members are notified the floor is free");
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
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
						&& holderId == null),
				"the speaker is told (FloorStatus shows no holder) its talk time was up so its client stops");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
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
		svc.onMessage(alice, new ClientMessage.Join("ptt-active", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("ptt-active", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice acquires at t=0
		assertTrue(channel("ptt-active").holdsFloor("alice"));

		clock.advance(Duration.ofSeconds(4));
		svc.onAudio(alice, new byte[]{1, 2, 3});                  // active speaker -> refreshes the activity mark to t=4 s

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
						0,
						0,
						10,
						false,
						null, false
				),
				BROADCASTER
		);
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
		// audio 1 fps -> burst 1
		ConnectionService svc = new ConnectionService(
				channelRegistry,
				new WalkieProperties(
						new String[]{"*"},
						8192,
						65536,
						1,
						1_000_000,
						0,
						0,
						10,
						false,
						null, false
				),
				BROADCASTER
		);
		FakeClientSession alice = session("alice");
		FakeClientSession bob = session("bob");
		svc.onMessage(alice, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "bob", null));

		byte[] frame = {1, 2, 3};
		svc.onAudio(alice, frame);
		svc.onAudio(alice, frame);   // exhausts the 1-token bucket
		assertEquals(1, bob.audio.size(), "the second frame is over the cap and dropped");

		svc.onClose(alice, "test close");          // must evict alice's bucket
		svc.onMessage(alice, new ClientMessage.Join("recon", ChannelMode.FULL_DUPLEX, "alice", null));   // same id reconnects
		bob.audio.clear();
		svc.onAudio(alice, frame);
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
						0,
						0,
						10,
						false,
						null, false
				),
				BROADCASTER,
				new MutableClock(Instant.EPOCH)
		);
		FakeClientSession alice = session("alice");

		svc.onMessage(alice, new ClientMessage.Join("flood-ctl", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));   // token 1
		svc.onMessage(alice, new ClientMessage.Rename("renamed-once"));    // token 2 -> applied
		svc.onMessage(alice, new ClientMessage.Rename("renamed-twice"));   // over the rate -> dropped

		assertEquals("renamed-once", alice.displayName(),
				"the control message past the per-session rate cap is dropped before dispatch");
	}

	// --- push-to-talk floor QUEUE ("raise hand", owner-toggleable) — see docs/FLOOR_QUEUE.md -------

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
		svc.onMessage(alice, new ClientMessage.Join("q3", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("q3", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(carol, new ClientMessage.Join("q3", ChannelMode.MULTI_CHANNEL_PTT, "carol", null));
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
		svc.onMessage(alice, new ClientMessage.Join("q5", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("q5", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(carol, new ClientMessage.Join("q5", ChannelMode.MULTI_CHANNEL_PTT, "carol", null));
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
		svc.onMessage(alice, new ClientMessage.Join("q5b", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("q5b", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));

		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues (the sole head)
		svc.onMessage(alice, new ClientMessage.ReleaseFloor());   // t=0: bob reserved, nobody behind it

		clock.advance(Duration.ofSeconds(11));                    // past the claim window
		bob.sent.clear();
		svc.releaseExpiredFloors();

		assertTrue(channel("q5b").floorQueue().isEmpty(), "bob missed its turn and was dropped, leaving the queue empty");
		assertFalse(channel("q5b").floorHolder() instanceof Some(String _), "the floor is free — no successor took it");
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
		svc.onMessage(alice, new ClientMessage.Join("qC", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("qC", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
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
		svc.onMessage(alice, new ClientMessage.Join("qD", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("qD", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
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
		svc.onMessage(alice, new ClientMessage.Join("qE", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("qE", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
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
		svc.onMessage(alice, new ClientMessage.Join("qF", ChannelMode.MULTI_CHANNEL_PTT, "alice", null));
		svc.onMessage(bob, new ClientMessage.Join("qF", ChannelMode.MULTI_CHANNEL_PTT, "bob", null));
		svc.onMessage(alice, new ClientMessage.SetFloorQueue(true));
		svc.onMessage(alice, new ClientMessage.RequestFloor());   // alice holds
		svc.onMessage(bob, new ClientMessage.RequestFloor());     // bob queues

		clock.advance(Duration.ofSeconds(11));   // past the cap
		bob.sent.clear();
		svc.onAudio(alice, new byte[]{1, 2, 3});   // the holder's next frame trips the cap and releases the floor

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
		service.onAudio(bob, frame);
		assertEquals(1, alice.audio.size(), "before muting, bob's audio is relayed");

		alice.sent.clear();
		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		assertTrue(channel("mute").isMuted("bob"), "the server records bob as muted");
		// MemberMuted is broadcast to the whole channel, including the muted member (so its client can stop).
		assertEquals("bob", firstOf(bob, ServerMessage.MemberMuted.class).memberId());
		assertTrue(firstOf(bob, ServerMessage.MemberMuted.class).muted());
		assertTrue(firstOf(alice, ServerMessage.MemberMuted.class).muted(), "the owner is notified too");

		alice.audio.clear();
		service.onAudio(bob, frame);
		assertEquals(0, alice.audio.size(), "a muted member's audio is dropped server-side, not relayed");
	}

	@Test
	void unmutingReenablesTheMembersAudio() {
		FakeClientSession alice = join("alice", "unmute", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "unmute", ChannelMode.FULL_DUPLEX);
		service.onMessage(alice, new ClientMessage.MuteMember("bob", true));

		byte[] frame = {1, 2, 3};
		service.onAudio(bob, frame);
		assertEquals(0, alice.audio.size(), "while muted, bob's audio is dropped");

		service.onMessage(alice, new ClientMessage.MuteMember("bob", false));
		assertFalse(channel("unmute").isMuted("bob"));
		service.onAudio(bob, frame);
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
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.MemberMuted.class::isInstance),
				"a rejected mute (unknown target or the owner itself) broadcasts no MemberMuted to the channel");
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
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
						&& holderId == null),
				"the muted (ex-)holder is told the floor is free (FloorStatus) so its client stops transmitting");
		assertTrue(alice.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
						&& holderId == null),
				"the other members learn the floor reopened");
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.MemberMuted mm && mm.muted()),
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
		service.onAudio(bob, frame);
		service.onAudio(carol, frame);
		assertEquals(0, alice.audio.size(), "both muted members' audio is dropped");

		bob.audio.clear();
		carol.audio.clear();
		service.onAudio(alice, frame);   // the owner is not muted and can still be heard
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
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.MemberMuted.class::isInstance),
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
		service.onAudio(bobAgain, frame);
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
		assertTrue(bob.sent.stream().anyMatch(m -> m instanceof ServerMessage.FloorStatus(String holderId, java.util.List<String> _)
						&& holderId == null),
				"the muted ex-holder is told the floor is free (FloorStatus)");
	}

	@Test
	void unmutingAnAlreadyUnmutedMemberBroadcastsNothing() {
		FakeClientSession alice = join("alice", "unmute-idem", ChannelMode.FULL_DUPLEX);
		FakeClientSession bob = join("bob", "unmute-idem", ChannelMode.FULL_DUPLEX);   // never muted

		bob.sent.clear();
		service.onMessage(alice, new ClientMessage.MuteMember("bob", false));   // no-op unmute
		assertTrue(bob.sent.stream().noneMatch(ServerMessage.MemberMuted.class::isInstance),
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
						m instanceof ServerMessage.MemberMuted(
								String memberId, boolean muted
						) && memberId.equals("bob") && !muted),
				"the channel is told the new owner was unmuted");
		byte[] frame = {1, 2, 3};
		alice.audio.clear();
		service.onAudio(bob, frame);
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
						m instanceof ServerMessage.MemberMuted(
								String memberId, boolean muted
						) && memberId.equals("bob") && !muted),
				"bob is told it was unmuted on promotion");
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
		service.onMessage(bob, new ClientMessage.Join("relock-snap", ChannelMode.FULL_DUPLEX, "bob", null));
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
		service.onAudio(bob, new byte[]{1, 2, 3});
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
