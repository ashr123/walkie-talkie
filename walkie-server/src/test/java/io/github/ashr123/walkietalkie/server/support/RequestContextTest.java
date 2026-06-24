package io.github.ashr123.walkietalkie.server.support;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestContextTest {

	@Test
	void runAsBindsTheUserForTheScopeAndClearsItAfterwards() {
		assertEquals("system", RequestContext.currentUserIdOrSystem(), "no user is bound initially");
		assertNull(MDC.get(RequestContext.MDC_USER_KEY), "MDC has no user initially");

		AtomicReference<String> scopedInside = new AtomicReference<>();
		AtomicReference<String> mdcInside = new AtomicReference<>();
		RequestContext.runAs("alice", () -> {
			scopedInside.set(RequestContext.currentUserIdOrSystem());
			mdcInside.set(MDC.get(RequestContext.MDC_USER_KEY));
		});

		assertEquals("alice", scopedInside.get(), "scoped value is readable inside the scope");
		assertEquals("alice", mdcInside.get(), "MDC carries the user inside the scope");
		assertEquals("system", RequestContext.currentUserIdOrSystem(), "scope is unbound afterwards");
		assertNull(MDC.get(RequestContext.MDC_USER_KEY), "MDC is cleared afterwards");
	}

	@Test
	void nestedRunAsRestoresTheOuterUserOnExit() {
		AtomicReference<String> innerMdc = new AtomicReference<>();
		AtomicReference<String> afterInnerMdc = new AtomicReference<>();
		AtomicReference<String> afterInnerScoped = new AtomicReference<>();

		RequestContext.runAs("outer", () -> {
			RequestContext.runAs("inner", () -> innerMdc.set(MDC.get(RequestContext.MDC_USER_KEY)));
			afterInnerMdc.set(MDC.get(RequestContext.MDC_USER_KEY));
			afterInnerScoped.set(RequestContext.currentUserIdOrSystem());
		});

		assertEquals("inner", innerMdc.get(), "inner scope tags the inner user");
		assertEquals("outer", afterInnerMdc.get(), "MDC is restored to the outer user, not wiped");
		assertEquals("outer", afterInnerScoped.get(), "scoped value still resolves to the outer user");
		assertNull(MDC.get(RequestContext.MDC_USER_KEY), "MDC is cleared after the outer scope");
	}
}
