package io.github.ashr123.walkietalkie.server.support;

import io.github.ashr123.walkietalkie.server.FakeClientSession;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestContextTest {

	@Test
	void aScopeBindsSessionNameAndChannelForItsBlockAndRestoresThemOnClose() {
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC has no session initially");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC has no name initially");
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "MDC has no channel initially");

		try (RequestContext.Scope _ = RequestContext.open("session-a", "Alice", "room-1")) {
			assertEquals("session-a", MDC.get(RequestContext.MDC_SESSION_KEY), "MDC carries the session inside the scope");
			assertEquals("Alice", MDC.get(RequestContext.MDC_NAME_KEY), "MDC carries the name inside the scope");
			assertEquals("room-1", MDC.get(RequestContext.MDC_CHANNEL_KEY), "MDC carries the channel inside the scope");
		}

		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC session is cleared after the scope");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC name is cleared after the scope");
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "MDC channel is cleared after the scope");
	}

	@Test
	void aNullOrBlankComponentLeavesThatKeyUnsetSoThePatternDefaultShows() {
		// What channelScope passes: server-initiated, channel-only work — no session, no name.
		try (RequestContext.Scope _ = RequestContext.open(null, null, "room-1")) {
			assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "a null session leaves the key unset — pattern shows 'system'");
			assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "a null name leaves the key unset");
			assertEquals("room-1", MDC.get(RequestContext.MDC_CHANNEL_KEY), "the channel is still set");
		}
	}

	@Test
	void updateDisplayNameAdvancesTheMdcNameMidScopeAndIsStillCleanedUp() {
		// Mirrors a join: the scope begins with a blank name, then the name becomes known partway through.
		try (RequestContext.Scope _ = RequestContext.open("session-a", "", null)) {
			assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "the name is unset at entry when the scope began blank");
			RequestContext.updateDisplayName("Alice");
			assertEquals("Alice", MDC.get(RequestContext.MDC_NAME_KEY), "updateDisplayName advances the MDC name within the scope");
		}
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "the mid-scope name is still cleaned up on scope exit");
	}

	@Test
	void updateChannelAdvancesOnJoinThenClearsOnLeaveMidScopeAndIsStillCleanedUp() {
		// Mirrors a session that joins a channel partway through the scope, then leaves it.
		try (RequestContext.Scope _ = RequestContext.open("session-a", "Alice", null)) {
			assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "no channel at entry when the scope began outside a channel");
			RequestContext.updateChannel("room-1");
			assertEquals("room-1", MDC.get(RequestContext.MDC_CHANNEL_KEY), "updateChannel advances the MDC channel on a join");
			RequestContext.updateChannel(null);
			assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "updateChannel(null) clears the MDC channel on a leave");
		}
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "the mid-scope channel is cleaned up on scope exit");
	}

	@Test
	void updateDisplayNameAndUpdateChannelAreNoOpsOutsideAScope() {
		RequestContext.updateDisplayName("Alice");
		RequestContext.updateChannel("room-1");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "with no scope bound, nothing is written to the MDC name");
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "with no scope bound, nothing is written to the MDC channel");
	}

	@Test
	void theUpdatersAreNoOpsInAChannelOnlyScopeWhichBindsNoSession() {
		// A channelScope (no session) is not a per-client scope: the mid-scope updaters must not fire there.
		try (RequestContext.Scope _ = RequestContext.open(null, null, "room-1")) {
			RequestContext.updateDisplayName("Alice");
			assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "updateDisplayName is a no-op in a channel-only scope (no session bound)");
		}
	}

	@Test
	void nestedScopesRestoreTheOuterValuesOnInnerClose() {
		try (RequestContext.Scope _ = RequestContext.open("outer", "Olivia", "room-out")) {
			try (RequestContext.Scope _inner = RequestContext.open("inner", "Ivan", "room-in")) {
				assertEquals("inner", MDC.get(RequestContext.MDC_SESSION_KEY), "inner scope tags the inner session");
				assertEquals("room-in", MDC.get(RequestContext.MDC_CHANNEL_KEY), "inner scope tags the inner channel");
			}
			assertEquals("outer", MDC.get(RequestContext.MDC_SESSION_KEY), "the outer session is restored on inner close, not wiped");
			assertEquals("room-out", MDC.get(RequestContext.MDC_CHANNEL_KEY), "the outer channel is restored on inner close, not wiped");
		}
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC session is cleared after the outer scope");
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "MDC channel is cleared after the outer scope");
	}

	@Test
	void closingAScopeTwiceIsIdempotentAndDoesNotClobberAnEnclosingScope() {
		try (RequestContext.Scope _ = RequestContext.open("outer", "Olivia", "room-out")) {
			RequestContext.Scope inner = RequestContext.open("inner", "Ivan", "room-in");
			inner.close();
			assertEquals("outer", MDC.get(RequestContext.MDC_SESSION_KEY), "the first close restored the outer session");
			inner.close();   // a second close must be a no-op
			assertEquals("outer", MDC.get(RequestContext.MDC_SESSION_KEY), "a double close does not re-restore and clobber the outer session");
			assertEquals("room-out", MDC.get(RequestContext.MDC_CHANNEL_KEY), "the outer channel is intact after the double close");
		}
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC session is cleared after the outer scope");
	}

	@Test
	void scopeFromAClientSessionPopulatesTheMdcFromItsAccessors() {
		// Guards the field mapping — id / displayName / channelName are all Strings, so a swap would still compile.
		FakeClientSession session = new FakeClientSession("sess-x", Transport.AUDIO_RELAY, "Alice");
		session.joinedChannel("room-9");
		try (RequestContext.Scope _ = RequestContext.scope(session)) {
			assertEquals("sess-x", MDC.get(RequestContext.MDC_SESSION_KEY), "scope maps ClientSession.id() to the session key");
			assertEquals("Alice", MDC.get(RequestContext.MDC_NAME_KEY), "scope maps ClientSession.displayName() to the name key");
			assertEquals("room-9", MDC.get(RequestContext.MDC_CHANNEL_KEY), "scope maps ClientSession.channelName() to the channel key");
		}
	}

	@Test
	void channelScopeTagsOnlyTheChannelAndLeavesTheSessionUnset() {
		Channel channel = new Channel("room-9", ChannelMode.MULTI_CHANNEL_PTT, "owner", null, false);
		try (RequestContext.Scope _ = RequestContext.channelScope(channel)) {
			assertEquals("room-9", MDC.get(RequestContext.MDC_CHANNEL_KEY), "channelScope tags the channel from Channel.name()");
			assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "channelScope binds no session — the pattern shows its 'system' default");
		}
	}
}
