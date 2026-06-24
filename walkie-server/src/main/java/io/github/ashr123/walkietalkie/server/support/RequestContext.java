package io.github.ashr123.walkietalkie.server.support;

import org.slf4j.MDC;

/// Carries the authenticated user for the dynamic scope of a single inbound control message.
///
/// The user is held in a Java 25 [ScopedValue] ([#CURRENT_USER]) as the source of truth and mirrored
/// into the SLF4J MDC by [#runAs], so the log lines emitted while handling that message are tagged
/// with the caller via the `%X{user}` pattern. A scoped value is used in preference to a
/// `ThreadLocal` because it is immutable for its scope and needs no manual cleanup. Message dispatch
/// is currently synchronous, so the binding behaves as a simple per-message value; no code forks to
/// child threads today, so the scoped value's cross-thread propagation is not relied upon.
public final class RequestContext {

	/// MDC key under which the current user id is exposed to the logging pattern (`%X{user}`).
	public static final String MDC_USER_KEY = "user";

	public static final ScopedValue<AuthenticatedUser> CURRENT_USER = ScopedValue.newInstance();

	private RequestContext() {
	}

	/// Runs {@code action} with {@code userId} bound as the current user: as a [ScopedValue] (the
	/// source of truth) and mirrored into the MDC, read back from the scoped value. On exit the MDC
	/// key is restored to its previous value (not merely removed), so the MDC stays consistent with
	/// the scoped value even if {@code runAs} is nested.
	public static void runAs(String userId, Runnable action) {
		ScopedValue.where(CURRENT_USER, new AuthenticatedUser(userId)).run(() -> {
			String previous = MDC.get(MDC_USER_KEY);
			MDC.put(MDC_USER_KEY, currentUserIdOrSystem());
			try {
				action.run();
			} finally {
				if (previous == null) {
					MDC.remove(MDC_USER_KEY);
				} else {
					MDC.put(MDC_USER_KEY, previous);
				}
			}
		});
	}

	/// Returns the bound user id, or {@code "system"} when called outside a bound scope (for example
	/// connection-lifecycle or other server-initiated logging that is not handling a client message).
	public static String currentUserIdOrSystem() {
		return CURRENT_USER.isBound() ? CURRENT_USER.get().userId() : "system";
	}
}
