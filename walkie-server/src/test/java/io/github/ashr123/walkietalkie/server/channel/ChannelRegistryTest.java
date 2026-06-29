package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.None;
import io.github.ashr123.option.Option;
import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
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

	@Test
	void reusesChannelForSameMode() {
		Channel first = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")).channel();
		Channel second = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b")).channel();
		assertSame(first, second);
		assertEquals(2, second.size());
	}

	@Test
	void adoptsExistingModeAndOwnerOnJoin() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")).channel();
		Channel joined = registry.joinOrCreate("team", ChannelMode.FULL_DUPLEX, null, session("b")).channel();
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
		Channel global = registry.joinOrCreate("global", ChannelMode.GLOBAL_PTT, null, session("a"), "server").channel();
		assertEquals("server", global.ownerId(), "the 5-arg form uses the explicit owner, not the joiner's id");
		assertEquals(1, global.size());
		assertNull(global.keyCheck());
	}

	@Test
	void refusesAJoinerWhoseKeyCheckDoesNotMatch() {
		ChannelRegistry.JoinResult created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a"));
		assertNotNull(created, "the creator establishes the channel's key-check");

		assertNull(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-B", session("b")),
				"a different key-check (wrong passphrase) is refused");
		assertNull(registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("c")),
				"an unencrypted joiner is refused from an encrypted channel");
		assertEquals(1, created.channel().size(), "refused joiners are not added");

		assertSame(created.channel(), registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("d")).channel(),
				"a matching key-check joins normally");
		assertEquals(2, created.channel().size());
	}

	@Test
	void joinResultSnapshotsTheRosterIncludingTheJoiner() {
		ChannelRegistry.JoinResult first = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"));
		assertEquals(Set.of("a"), ids(first), "the creator's roster snapshot contains just itself");
		assertInstanceOf(None.class, first.floorHolder(), "no floor is held yet, so no hint");

		ChannelRegistry.JoinResult second = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		assertEquals(Set.of("a", "b"), ids(second),
				"the second joiner's roster snapshot includes itself and the existing member");
	}

	@Test
	void joinResultFlagsWhetherThisJoinCreatedTheChannel() {
		ChannelRegistry.JoinResult creator = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a"));
		assertTrue(creator.created(), "the first joiner brought the channel into being");

		ChannelRegistry.JoinResult later = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		assertFalse(later.created(), "a joiner of an already-existing channel did not create it");
	}

	@Test
	void joinResultCapturesTheCurrentFloorHolderAsTheHint() {
		Channel channel = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")).channel();
		channel.tryAcquireFloor("a", Instant.EPOCH);

		ChannelRegistry.JoinResult joiner = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		assertEquals(Option.of("a"), joiner.floorHolder(), "the joiner's floor hint reflects the active holder");
	}

	private static Set<String> ids(ChannelRegistry.JoinResult result) {
		return result.roster().stream().map(MemberInfo::id).collect(Collectors.toSet());
	}

	// --- changePassphrase outcome matrix (the OK / NOT_OWNER / NOT_FOUND contract + same-object channel()) ---

	@Test
	void changePassphraseRotatesForTheOwnerAndReturnsTheSameChannel() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a")).channel();
		ChannelRegistry.RekeyResult result = registry.changePassphrase("team", "a", "kcv-B");
		assertEquals(ChannelRegistry.RekeyOutcome.OK, result.outcome());
		assertSame(created, result.channel(), "OK returns the exact mutated channel instance (not a fresh find())");
		assertEquals("kcv-B", created.keyCheck(), "the key-check is rotated in place");
	}

	@Test
	void changePassphraseRefusesANonOwnerAndLeavesTheKeyCheck() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("a")).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, "kcv-A", session("b"));
		ChannelRegistry.RekeyResult result = registry.changePassphrase("team", "b", "kcv-B");
		assertEquals(ChannelRegistry.RekeyOutcome.NOT_OWNER, result.outcome());
		assertNull(result.channel(), "a non-OK result carries no channel");
		assertEquals("kcv-A", created.keyCheck(), "a refused rotation leaves the key-check unchanged");
	}

	@Test
	void changePassphraseOnAMissingChannelIsNotFound() {
		ChannelRegistry.RekeyResult result = registry.changePassphrase("ghost", "a", "kcv-B");
		assertEquals(ChannelRegistry.RekeyOutcome.NOT_FOUND, result.outcome());
		assertNull(result.channel());
	}

	// --- transferOwnership outcome matrix (OK / NOT_OWNER / NOT_A_MEMBER / NOT_FOUND + same-object channel()) ---

	@Test
	void transferOwnershipMovesTheOwnerForTheOwnerAndReturnsTheSameChannel() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		ChannelRegistry.TransferResult result = registry.transferOwnership("team", "a", "b");
		assertEquals(ChannelRegistry.TransferOutcome.OK, result.outcome());
		assertSame(created, result.channel());
		assertEquals("b", created.ownerId(), "ownership moved to the named member");
	}

	@Test
	void transferOwnershipRefusesANonOwner() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")).channel();
		registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("b"));
		ChannelRegistry.TransferResult result = registry.transferOwnership("team", "b", "b");
		assertEquals(ChannelRegistry.TransferOutcome.NOT_OWNER, result.outcome());
		assertNull(result.channel());
		assertEquals("a", created.ownerId(), "ownership is unchanged");
	}

	@Test
	void transferOwnershipToANonMemberIsRejected() {
		Channel created = registry.joinOrCreate("team", ChannelMode.MULTI_CHANNEL_PTT, null, session("a")).channel();
		ChannelRegistry.TransferResult result = registry.transferOwnership("team", "a", "ghost");
		assertEquals(ChannelRegistry.TransferOutcome.NOT_A_MEMBER, result.outcome());
		assertNull(result.channel());
		assertEquals("a", created.ownerId(), "ownership is unchanged");
	}

	@Test
	void transferOwnershipOnAMissingChannelIsNotFound() {
		ChannelRegistry.TransferResult result = registry.transferOwnership("ghost", "a", "b");
		assertEquals(ChannelRegistry.TransferOutcome.NOT_FOUND, result.outcome());
		assertNull(result.channel());
	}
}
