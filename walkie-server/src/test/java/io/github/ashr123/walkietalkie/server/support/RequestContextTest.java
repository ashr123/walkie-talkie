package io.github.ashr123.walkietalkie.server.support;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestContextTest {

	@Test
	void runAsBindsTheSessionNameAndChannelForTheScopeAndClearsThemAfterwards() {
		assertEquals("system", RequestContext.currentSessionIdOrSystem(), "no session is bound initially");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC has no session initially");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC has no name initially");
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "MDC has no channel initially");

		AtomicReference<String> scopedInside = new AtomicReference<>();
		AtomicReference<String> mdcSessionInside = new AtomicReference<>();
		AtomicReference<String> mdcNameInside = new AtomicReference<>();
		AtomicReference<String> mdcChannelInside = new AtomicReference<>();
		RequestContext.runAs("session-a", "Alice", "room-1", () -> {
			scopedInside.set(RequestContext.currentSessionIdOrSystem());
			mdcSessionInside.set(MDC.get(RequestContext.MDC_SESSION_KEY));
			mdcNameInside.set(MDC.get(RequestContext.MDC_NAME_KEY));
			mdcChannelInside.set(MDC.get(RequestContext.MDC_CHANNEL_KEY));
		});

		assertEquals("session-a", scopedInside.get(), "scoped value is readable inside the scope");
		assertEquals("session-a", mdcSessionInside.get(), "MDC carries the session inside the scope");
		assertEquals("Alice", mdcNameInside.get(), "MDC carries the name inside the scope");
		assertEquals("room-1", mdcChannelInside.get(), "MDC carries the channel inside the scope");
		assertEquals("system", RequestContext.currentSessionIdOrSystem(), "scope is unbound afterwards");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC session is cleared afterwards");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC name is cleared afterwards");
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "MDC channel is cleared afterwards");
	}

	@Test
	void aBlankNameOrChannelLeavesThoseKeysUnset() {
		AtomicReference<String> mdcNameInside = new AtomicReference<>("sentinel");
		AtomicReference<String> mdcChannelInside = new AtomicReference<>("sentinel");
		// Pre-join: the name is still blank and the session is in no channel.
		RequestContext.runAs("session-a", "", null, () -> {
			mdcNameInside.set(MDC.get(RequestContext.MDC_NAME_KEY));
			mdcChannelInside.set(MDC.get(RequestContext.MDC_CHANNEL_KEY));
		});

		assertNull(mdcNameInside.get(), "a blank display name (pre-join) does not set the MDC name key");
		assertNull(mdcChannelInside.get(), "a null channel (not in a channel) does not set the MDC channel key");
	}

	@Test
	void updateDisplayNameAdvancesTheMdcNameMidScopeAndIsStillCleanedUp() {
		AtomicReference<String> atEntry = new AtomicReference<>("sentinel");
		AtomicReference<String> afterUpdate = new AtomicReference<>();
		// Mirrors a join: the scope begins with a blank name, then the name becomes known partway through.
		RequestContext.runAs("session-a", "", null, () -> {
			atEntry.set(MDC.get(RequestContext.MDC_NAME_KEY));
			RequestContext.updateDisplayName("Alice");
			afterUpdate.set(MDC.get(RequestContext.MDC_NAME_KEY));
		});

		assertNull(atEntry.get(), "the name is unset at entry when the scope began blank");
		assertEquals("Alice", afterUpdate.get(), "updateDisplayName advances the MDC name within the scope");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "the mid-scope name is still cleaned up on scope exit");
	}

	@Test
	void updateChannelAdvancesOnJoinThenClearsOnLeaveMidScopeAndIsStillCleanedUp() {
		AtomicReference<String> atEntry = new AtomicReference<>("sentinel");
		AtomicReference<String> afterJoin = new AtomicReference<>();
		AtomicReference<String> afterLeave = new AtomicReference<>("sentinel");
		// Mirrors a session that joins a channel partway through the scope, then leaves it.
		RequestContext.runAs("session-a", "Alice", null, () -> {
			atEntry.set(MDC.get(RequestContext.MDC_CHANNEL_KEY));
			RequestContext.updateChannel("room-1");
			afterJoin.set(MDC.get(RequestContext.MDC_CHANNEL_KEY));
			RequestContext.updateChannel(null);
			afterLeave.set(MDC.get(RequestContext.MDC_CHANNEL_KEY));
		});

		assertNull(atEntry.get(), "no channel at entry when the scope began outside a channel");
		assertEquals("room-1", afterJoin.get(), "updateChannel advances the MDC channel on a join");
		assertNull(afterLeave.get(), "updateChannel(null) clears the MDC channel on a leave");
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
	void nestedRunAsRestoresTheOuterSessionNameAndChannelOnExit() {
		AtomicReference<String> innerSession = new AtomicReference<>();
		AtomicReference<String> innerName = new AtomicReference<>();
		AtomicReference<String> innerChannel = new AtomicReference<>();
		AtomicReference<String> afterInnerSession = new AtomicReference<>();
		AtomicReference<String> afterInnerName = new AtomicReference<>();
		AtomicReference<String> afterInnerChannel = new AtomicReference<>();
		AtomicReference<String> afterInnerScoped = new AtomicReference<>();

		RequestContext.runAs("outer", "Olivia", "room-out", () -> {
			RequestContext.runAs("inner", "Ivan", "room-in", () -> {
				innerSession.set(MDC.get(RequestContext.MDC_SESSION_KEY));
				innerName.set(MDC.get(RequestContext.MDC_NAME_KEY));
				innerChannel.set(MDC.get(RequestContext.MDC_CHANNEL_KEY));
			});
			afterInnerSession.set(MDC.get(RequestContext.MDC_SESSION_KEY));
			afterInnerName.set(MDC.get(RequestContext.MDC_NAME_KEY));
			afterInnerChannel.set(MDC.get(RequestContext.MDC_CHANNEL_KEY));
			afterInnerScoped.set(RequestContext.currentSessionIdOrSystem());
		});

		assertEquals("inner", innerSession.get(), "inner scope tags the inner session");
		assertEquals("Ivan", innerName.get(), "inner scope tags the inner name");
		assertEquals("room-in", innerChannel.get(), "inner scope tags the inner channel");
		assertEquals("outer", afterInnerSession.get(), "MDC session is restored to the outer scope, not wiped");
		assertEquals("Olivia", afterInnerName.get(), "MDC name is restored to the outer scope, not wiped");
		assertEquals("room-out", afterInnerChannel.get(), "MDC channel is restored to the outer scope, not wiped");
		assertEquals("outer", afterInnerScoped.get(), "scoped value still resolves to the outer session");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC session is cleared after the outer scope");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC name is cleared after the outer scope");
		assertNull(MDC.get(RequestContext.MDC_CHANNEL_KEY), "MDC channel is cleared after the outer scope");
	}
}
