package io.github.ashr123.walkietalkie.server.support;

/// Holds per-message ambient state using a Java 25 [ScopedValue] (JEP 506, finalized).
///
/// Unlike a `ThreadLocal`, a scoped value is immutable for the dynamic scope it is bound in
/// and is cheap to share with virtual threads. The connection layer binds [#CURRENT_USER]
/// around the handling of each inbound message so that any code reached during that handling can
/// read the caller's identity without it being threaded through every method signature.
public final class RequestContext {

	public static final ScopedValue<AuthenticatedUser> CURRENT_USER = ScopedValue.newInstance();

	private RequestContext() {
	}

	/// Returns the bound user id, or `"system"` when called outside a bound scope.
	public static String currentUserIdOrSystem() {
		return CURRENT_USER.isBound() ? CURRENT_USER.get().userId() : "system";
	}
}
