package io.github.ashr123.walkietalkie.server.support;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestContextTest {

	@Test
	void runAsBindsTheSessionForTheScopeAndClearsItAfterwards() {
		assertEquals("system", RequestContext.currentSessionIdOrSystem(), "no session is bound initially");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC has no session initially");

		AtomicReference<String> scopedInside = new AtomicReference<>();
		AtomicReference<String> mdcInside = new AtomicReference<>();
		RequestContext.runAs("session-a", () -> {
			scopedInside.set(RequestContext.currentSessionIdOrSystem());
			mdcInside.set(MDC.get(RequestContext.MDC_SESSION_KEY));
		});

		assertEquals("session-a", scopedInside.get(), "scoped value is readable inside the scope");
		assertEquals("session-a", mdcInside.get(), "MDC carries the session inside the scope");
		assertEquals("system", RequestContext.currentSessionIdOrSystem(), "scope is unbound afterwards");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC is cleared afterwards");
	}

	@Test
	void nestedRunAsRestoresTheOuterSessionOnExit() {
		AtomicReference<String> innerMdc = new AtomicReference<>();
		AtomicReference<String> afterInnerMdc = new AtomicReference<>();
		AtomicReference<String> afterInnerScoped = new AtomicReference<>();

		RequestContext.runAs("outer", () -> {
			RequestContext.runAs("inner", () -> innerMdc.set(MDC.get(RequestContext.MDC_SESSION_KEY)));
			afterInnerMdc.set(MDC.get(RequestContext.MDC_SESSION_KEY));
			afterInnerScoped.set(RequestContext.currentSessionIdOrSystem());
		});

		assertEquals("inner", innerMdc.get(), "inner scope tags the inner session");
		assertEquals("outer", afterInnerMdc.get(), "MDC is restored to the outer session, not wiped");
		assertEquals("outer", afterInnerScoped.get(), "scoped value still resolves to the outer session");
		assertNull(MDC.get(RequestContext.MDC_SESSION_KEY), "MDC is cleared after the outer scope");
	}
}
