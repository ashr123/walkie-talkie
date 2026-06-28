package io.github.ashr123.walkietalkie.server.support;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestContextTest {

	@Test
	void runAsBindsTheSessionAndNameForTheScopeAndClearsThemAfterwards() {
		assertEquals("system", RequestContext.currentSessionIdOrSystem(), "no session is bound initially");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC has no session initially");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC has no name initially");

		AtomicReference<String> scopedInside = new AtomicReference<>();
		AtomicReference<String> mdcSessionInside = new AtomicReference<>();
		AtomicReference<String> mdcNameInside = new AtomicReference<>();
		RequestContext.runAs("session-a", "Alice", () -> {
			scopedInside.set(RequestContext.currentSessionIdOrSystem());
			mdcSessionInside.set(MDC.get(RequestContext.MDC_SESSION_KEY));
			mdcNameInside.set(MDC.get(RequestContext.MDC_NAME_KEY));
		});

		assertEquals("session-a", scopedInside.get(), "scoped value is readable inside the scope");
		assertEquals("session-a", mdcSessionInside.get(), "MDC carries the session inside the scope");
		assertEquals("Alice", mdcNameInside.get(), "MDC carries the name inside the scope");
		assertEquals("system", RequestContext.currentSessionIdOrSystem(), "scope is unbound afterwards");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC session is cleared afterwards");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC name is cleared afterwards");
	}

	@Test
	void aBlankNameLeavesTheNameKeyUnset() {
		AtomicReference<String> mdcNameInside = new AtomicReference<>("sentinel");
		RequestContext.runAs("session-a", "", () -> mdcNameInside.set(MDC.get(RequestContext.MDC_NAME_KEY)));

		assertNull(mdcNameInside.get(), "a blank display name (pre-join) does not set the MDC name key");
	}

	@Test
	void updateDisplayNameAdvancesTheMdcNameMidScopeAndIsStillCleanedUp() {
		AtomicReference<String> atEntry = new AtomicReference<>("sentinel");
		AtomicReference<String> afterUpdate = new AtomicReference<>();
		// Mirrors a join: the scope begins with a blank name, then the name becomes known partway through.
		RequestContext.runAs("session-a", "", () -> {
			atEntry.set(MDC.get(RequestContext.MDC_NAME_KEY));
			RequestContext.updateDisplayName("Alice");
			afterUpdate.set(MDC.get(RequestContext.MDC_NAME_KEY));
		});

		assertNull(atEntry.get(), "the name is unset at entry when the scope began blank");
		assertEquals("Alice", afterUpdate.get(), "updateDisplayName advances the MDC name within the scope");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "the mid-scope name is still cleaned up on scope exit");
	}

	@Test
	void updateDisplayNameIsANoOpOutsideAScope() {
		RequestContext.updateDisplayName("Alice");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "with no scope bound, nothing is written to the MDC");
	}

	@Test
	void nestedRunAsRestoresTheOuterSessionAndNameOnExit() {
		AtomicReference<String> innerSession = new AtomicReference<>();
		AtomicReference<String> innerName = new AtomicReference<>();
		AtomicReference<String> afterInnerSession = new AtomicReference<>();
		AtomicReference<String> afterInnerName = new AtomicReference<>();
		AtomicReference<String> afterInnerScoped = new AtomicReference<>();

		RequestContext.runAs("outer", "Olivia", () -> {
			RequestContext.runAs("inner", "Ivan", () -> {
				innerSession.set(MDC.get(RequestContext.MDC_SESSION_KEY));
				innerName.set(MDC.get(RequestContext.MDC_NAME_KEY));
			});
			afterInnerSession.set(MDC.get(RequestContext.MDC_SESSION_KEY));
			afterInnerName.set(MDC.get(RequestContext.MDC_NAME_KEY));
			afterInnerScoped.set(RequestContext.currentSessionIdOrSystem());
		});

		assertEquals("inner", innerSession.get(), "inner scope tags the inner session");
		assertEquals("Ivan", innerName.get(), "inner scope tags the inner name");
		assertEquals("outer", afterInnerSession.get(), "MDC session is restored to the outer scope, not wiped");
		assertEquals("Olivia", afterInnerName.get(), "MDC name is restored to the outer scope, not wiped");
		assertEquals("outer", afterInnerScoped.get(), "scoped value still resolves to the outer session");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC session is cleared after the outer scope");
		assertNull(MDC.get(RequestContext.MDC_NAME_KEY), "MDC name is cleared after the outer scope");
	}
}
