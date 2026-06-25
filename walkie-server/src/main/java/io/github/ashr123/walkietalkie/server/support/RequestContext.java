package io.github.ashr123.walkietalkie.server.support;

import org.slf4j.MDC;

/// Carries the authenticated connection's identity — the per-connection WebSocket session id — for the
/// dynamic scope of a single inbound control message.
///
/// The identity is held in a Java 25 [ScopedValue] ([#CURRENT_SESSION]) as the source of truth and
/// mirrored into the SLF4J MDC by [#runAs], so the log lines emitted while handling that message are
/// tagged with the originating session via the `%X{session}` pattern. A scoped value is used in
/// preference to a `ThreadLocal` because it is immutable for its scope and needs no manual cleanup.
/// Message dispatch is currently synchronous, so the binding behaves as a simple per-message value.
public final class RequestContext {

	/// MDC key under which the current session id is exposed to the logging pattern (`%X{session}`).
	public static final String MDC_SESSION_KEY = "session";

	public static final ScopedValue<AuthenticatedUser> CURRENT_SESSION = ScopedValue.newInstance();

	private RequestContext() {
	}

	/// Runs `action` with `sessionId` bound as the current session: as a [ScopedValue] (the source of
	/// truth) and mirrored into the MDC, read back from the scoped value. On exit the MDC key is restored
	/// to its previous value (not merely removed), so the MDC stays consistent with the scoped value even
	/// if `runAs` is nested.
	public static void runAs(String sessionId, Runnable action) {
		ScopedValue.where(CURRENT_SESSION, new AuthenticatedUser(sessionId)).run(() -> {
			String previous = MDC.get(MDC_SESSION_KEY);
			MDC.put(MDC_SESSION_KEY, currentSessionIdOrSystem());
			try {
				action.run();
			} finally {
				if (previous == null) {
					MDC.remove(MDC_SESSION_KEY);
				} else {
					MDC.put(MDC_SESSION_KEY, previous);
				}
			}
		});
	}

	/// Returns the bound session id, or `"system"` when called outside a bound scope (for example
	/// connection-lifecycle or other server-initiated logging that is not handling a client message).
	public static String currentSessionIdOrSystem() {
		return CURRENT_SESSION.isBound() ? CURRENT_SESSION.get().sessionId() : "system";
	}
}
