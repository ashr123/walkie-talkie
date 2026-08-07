package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Option;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRegistryTest {

	private final ChannelRegistry registry = new ChannelRegistry();

	private static FakeClientSession session(String id) {
		return new FakeClientSession(id, Transport.AUDIO_RELAY, id);
	}

	/// Narrows a join outcome to [ChannelRegistry.JoinOutcome.Admitted], failing the test if the join was refused —
	/// the sealed-outcome equivalent of the old `assertNotNull` on a nullable result.
	private static ChannelRegistry.JoinOutcome.Admitted admitted(ChannelRegistry.JoinOutcome outcome) {
		return assertInstanceOf(ChannelRegistry.JoinOutcome.Admitted.class, outcome);
	}

	/// Asserts the join was refused for EXACTLY `reason`. This is what the sealed outcome buys over the old `null`
	/// return, which could not distinguish a locked channel from a full one from a key-check mismatch.
	private static void assertRefused(ChannelRegistry.JoinOutcome.Reason reason,
	                                  ChannelRegistry.JoinOutcome outcome,
	                                  String message) {
		assertEquals(
				reason,
				assertInstanceOf(ChannelRegistry.JoinOutcome.Refused.class, outcome, message).reason(),
				message
		);
	}

	@Test
	void reusesChannelForSameMode() {
		Channel first = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		Channel second = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"))).channel();
		assertSame(first, second);
		assertEquals(2, second.size());
	}

	@Test
	void adoptsExistingModeAndOwnerOnJoin() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		Channel joined = admitted(registry.joinOrCreate("team", ChannelMode.FULL_DUPLEX, null, session("b"))).channel();
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
	void theExplicitOwnerOverloadStampsTheGivenOwnerNotTheSession() {
		Channel global = admitted(registry.joinOrCreate("global", ChannelMode.GLOBAL_PTT, null, session("a"), "server")).channel();
		assertEquals("server", global.ownerId(), "the 5-arg form uses the explicit owner, not the joiner's id");
		assertEquals(1, global.size());
		assertNull(global.keyCheck());
	}

	@Test
	void refusesAJoinerWhoseKeyCheckDoesNotMatch() {
		ChannelRegistry.JoinOutcome.Admitted created =
				admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a")));

		assertRefused(ChannelRegistry.JoinOutcome.Reason.PASSPHRASE_MISMATCH,
				registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-B", session("b")),
				"a different key-check (wrong passphrase) is refused");
		assertRefused(ChannelRegistry.JoinOutcome.Reason.PASSPHRASE_MISMATCH,
				registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("c")),
				"an unencrypted joiner is refused from an encrypted channel");
		assertEquals(1, created.channel().size(), "refused joiners are not added");

		assertSame(created.channel(), admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("d"))).channel(),
				"a matching key-check joins normally");
		assertEquals(2, created.channel().size());
	}

	@Test
	void admittedOutcomeSnapshotsTheRosterIncludingTheJoiner() {
		ChannelRegistry.JoinOutcome.Admitted first =
				admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")));
		assertEquals(Set.of("a"), ids(first), "the creator's roster snapshot contains just itself");
		assertInstanceOf(None.class, first.floorHolder(), "no floor is held yet, so no hint");

		ChannelRegistry.JoinOutcome.Admitted second =
				admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b")));
		assertEquals(Set.of("a", "b"), ids(second),
				"the second joiner's roster snapshot includes itself and the existing member");
	}

	@Test
	void admittedOutcomeFlagsWhetherThisJoinCreatedTheChannel() {
		ChannelRegistry.JoinOutcome.Admitted creator =
				admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")));
		assertTrue(creator.created(), "the first joiner brought the channel into being");

		ChannelRegistry.JoinOutcome.Admitted later =
				admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b")));
		assertFalse(later.created(), "a joiner of an already-existing channel did not create it");
	}

	@Test
	void admittedOutcomeCapturesTheCurrentFloorHolderAsTheHint() {
		Channel channel = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		channel.tryAcquireFloor("a", Instant.EPOCH);

		ChannelRegistry.JoinOutcome.Admitted joiner =
				admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b")));
		assertEquals(Option.of("a"), joiner.floorHolder(), "the joiner's floor hint reflects the active holder");
	}

	private static Set<String> ids(ChannelRegistry.JoinOutcome.Admitted result) {
		return result.roster().stream().map(MemberInfo::id).collect(Collectors.toSet());
	}

	// --- changePassphrase outcome matrix (Ok / NotOwner / NotFound / EncryptionRequired + same-object channel()) ---

	@Test
	void changePassphraseRotatesForTheOwnerAndReturnsTheSameChannel() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();
		ChannelRegistry.RekeyResult.Ok result = assertInstanceOf(ChannelRegistry.RekeyResult.Ok.class,
				registry.changePassphrase("team", "a", "kcv-B"));
		assertSame(created, result.channel(), "Ok carries the exact mutated channel instance (not a fresh find())");
		assertEquals("kcv-B", created.keyCheck(), "the key-check is rotated in place");
	}

	@Test
	void changePassphraseRefusesANonOwnerAndLeavesTheKeyCheck() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("b"));
		// NotOwner carries no channel by construction (sealed), so there's nothing to null-check.
		assertInstanceOf(ChannelRegistry.RekeyResult.NotOwner.class, registry.changePassphrase("team", "b", "kcv-B"));
		assertEquals("kcv-A", created.keyCheck(), "a refused rotation leaves the key-check unchanged");
	}

	@Test
	void changeTransportMovesTheChannelForTheOwnerAndReportsThatItMoved() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();
		assertEquals(Transport.AUDIO_RELAY, created.transport(), "created on the plane its creator dialled");

		ChannelRegistry.TransportResult.Ok result = assertInstanceOf(ChannelRegistry.TransportResult.Ok.class,
				registry.changeTransport("team", "a", Transport.SIGNALING));

		assertSame(created, result.channel(), "Ok carries the exact mutated channel instance (not a fresh find())");
		assertTrue(result.changed());
		assertEquals(Transport.SIGNALING, created.transport());
	}

	@Test
	void changeTransportToThePlaneItIsAlreadyOnIsOkButReportsNoChange() {
		// Not an error — a client re-sending its own state is not wrong — but the caller must be able to tell, so it
		// can skip a broadcast that would have every member rebuild a working audio pipeline for nothing.
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();
		ChannelRegistry.TransportResult.Ok result = assertInstanceOf(ChannelRegistry.TransportResult.Ok.class,
				registry.changeTransport("team", "a", Transport.AUDIO_RELAY));
		assertFalse(result.changed());
		assertEquals(Transport.AUDIO_RELAY, created.transport());
	}

	@Test
	void changeTransportRefusesANonOwnerAndLeavesThePlane() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("b"));
		assertInstanceOf(ChannelRegistry.TransportResult.NotOwner.class,
				registry.changeTransport("team", "b", Transport.SIGNALING));
		assertEquals(Transport.AUDIO_RELAY, created.transport(), "a refused move leaves the channel where it was");
	}

	@Test
	void changeTransportOnAMissingChannelIsNotFound() {
		assertInstanceOf(ChannelRegistry.TransportResult.NotFound.class,
				registry.changeTransport("ghost", "a", Transport.SIGNALING));
	}

	@Test
	void aJoinerOnTheOtherPlaneIsAdmittedAndTheChannelDoesNotMove() {
		// The rule that replaced TRANSPORT_MISMATCH: a joiner adopts, it does not negotiate. The second half
		// matters as much as the first — a joiner that could drag the channel onto its own plane would silently
		// reconfigure everyone already there.
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();

		Channel joined = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A",
				new FakeClientSession("b", Transport.SIGNALING, "b"))).channel();

		assertSame(created, joined);
		assertEquals(Transport.AUDIO_RELAY, created.transport());
		assertEquals(2, created.size(), "and it really was admitted, not quietly dropped");
	}

	@Test
	void aCreatorMayAskForAPlaneOtherThanTheOneItDialled() {
		// What ClientMessage.Join.transport buys: the browser's selector can create a WebRTC channel over the
		// /ws/audio socket it is already holding, instead of creating a relay channel and immediately moving it.
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A",
				session("a"), Transport.SIGNALING, Channel.Defaults.NONE, _ -> {})).channel();
		assertEquals(Transport.SIGNALING, created.transport());
	}

	@Test
	void changePassphraseOnAMissingChannelIsNotFound() {
		assertInstanceOf(ChannelRegistry.RekeyResult.NotFound.class, registry.changePassphrase("ghost", "a", "kcv-B"));
	}

	@Test
	void changePassphraseToNullIsRefusedAsEncryptionRequired() {
		// Clearing a passphrase would leave an ordinary channel plaintext, which nothing may do. Refused at the
		// registry, inside the same bin lock as the write, so the channel is never even momentarily unencrypted for
		// a concurrent joiner to slip into.
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();
		assertInstanceOf(ChannelRegistry.RekeyResult.EncryptionRequired.class,
				registry.changePassphrase("team", "a", null));
		assertEquals("kcv-A", created.keyCheck(), "the channel keeps the passphrase it had");
	}

	@Test
	void aNonOwnerClearingThePassphraseStillHearsNotOwner() {
		// Ordering: the owner check comes FIRST, so a non-owner is told the true thing about their request rather
		// than being lectured about encryption they were never allowed to change.
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"))).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("b"));
		assertInstanceOf(ChannelRegistry.RekeyResult.NotOwner.class, registry.changePassphrase("team", "b", null));
		assertEquals("kcv-A", created.keyCheck());
	}

	// --- transferOwnership outcome matrix (Ok / NotOwner / NotAMember / NotFound + same-object channel on Ok) ---

	@Test
	void transferOwnershipMovesTheOwnerForTheOwnerAndReturnsTheSameChannel() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		ChannelRegistry.TransferResult.Ok result = assertInstanceOf(ChannelRegistry.TransferResult.Ok.class,
				registry.transferOwnership("team", "a", "b"));
		assertSame(created, result.channel());
		assertEquals("b", created.ownerId(), "ownership moved to the named member");
	}

	@Test
	void transferOwnershipRefusesANonOwner() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		assertInstanceOf(ChannelRegistry.TransferResult.NotOwner.class, registry.transferOwnership("team", "b", "b"));
		assertEquals("a", created.ownerId(), "ownership is unchanged");
	}

	@Test
	void transferOwnershipToANonMemberIsRejected() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		assertInstanceOf(ChannelRegistry.TransferResult.NotAMember.class, registry.transferOwnership("team", "a", "ghost"));
		assertEquals("a", created.ownerId(), "ownership is unchanged");
	}

	@Test
	void transferOwnershipOnAMissingChannelIsNotFound() {
		assertInstanceOf(ChannelRegistry.TransferResult.NotFound.class, registry.transferOwnership("ghost", "a", "b"));
	}

	@Test
	void aLockedChannelRefusesANewcomerButNotTheCreate() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		assertInstanceOf(ChannelRegistry.LockResult.Ok.class, registry.setLocked("team", "a", true));
		assertTrue(created.isLocked());

		assertRefused(ChannelRegistry.JoinOutcome.Reason.LOCKED,
				registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b")),
				"a locked channel refuses a newcomer, and says so — LOCKED, not a key-check mismatch");
		assertEquals(1, created.size(), "the newcomer was not added");

		assertInstanceOf(ChannelRegistry.LockResult.Ok.class, registry.setLocked("team", "a", false));
		admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("c")));
		assertEquals(2, created.size());
	}

	@Test
	void setLockedIsRejectedForANonOwnerAndMissingChannel() {
		Channel created = admitted(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"))).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));

		assertInstanceOf(ChannelRegistry.LockResult.NotOwner.class, registry.setLocked("team", "b", true));
		assertFalse(created.isLocked(), "a non-owner's lock has no effect");

		assertInstanceOf(ChannelRegistry.LockResult.NotFound.class, registry.setLocked("ghost", "a", true));
	}
}
