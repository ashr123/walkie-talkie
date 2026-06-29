package io.github.ashr123.walkietalkie.server.support;

import org.slf4j.MDC;

/// Carries the authenticated connection's identity — the per-connection WebSocket session id and the
/// current display name — plus the channel it is currently in, for the dynamic scope of a single inbound
/// control message (or a connection lifecycle event).
///
/// The identity is held in a Java 25 [ScopedValue] ([#CURRENT_SESSION]) as the source of truth and
/// mirrored into the SLF4J MDC by [#runAs], so the log lines emitted while handling that message are
/// tagged with the originating session and name via the `%X{session}` / `%X{name}` patterns. The current
/// channel is mirrored into the MDC too (`%X{channel}`) so every handler line is tagged with it instead of
/// repeating `channel=…` in each message — the channel is logging context, not part of the identity, so it
/// lives only in the MDC (not in [AuthenticatedUser]). A scoped value is used in preference to a
/// `ThreadLocal` because it is immutable for its scope and needs no manual cleanup. Message dispatch is
/// currently synchronous, so the binding behaves as a simple per-message value.
public final class RequestContext {

	/// MDC key under which the current session id is exposed to the logging pattern (`%X{session}`).
	public static final String MDC_SESSION_KEY = "session";
	/// MDC key under which the current display name is exposed to the logging pattern (`%X{name}`). Only set
	/// when a non-blank name is bound (it is blank before the client joins), so the pattern's default shows.
	public static final String MDC_NAME_KEY = "name";
	/// MDC key under which the session's current channel is exposed to the logging pattern (`%X{channel}`).
	/// Only set while the session is in a channel (so the pattern's default shows before a join / after a leave).
	public static final String MDC_CHANNEL_KEY = "channel";

	public static final ScopedValue<AuthenticatedUser> CURRENT_SESSION = ScopedValue.newInstance();

	private RequestContext() {
	}

	/// Runs `action` with `sessionId` + `displayName` bound as the current identity: as a [ScopedValue] (the
	/// source of truth) and mirrored into the MDC, plus `channelName` mirrored into the MDC. On exit each MDC key
	/// is restored to its previous value (not merely removed), so the MDC stays consistent even if `runAs` is
	/// nested. A blank `displayName` (e.g. before the client has joined) leaves the name MDC key unset; a blank
	/// `channelName` (not in a channel) likewise leaves the channel key unset.
	public static void runAs(String sessionId, String displayName, String channelName, Runnable action) {
		ScopedValue.where(CURRENT_SESSION, new AuthenticatedUser(sessionId, displayName)).run(() -> {
			String previousSession = MDC.get(MDC_SESSION_KEY);
			String previousName = MDC.get(MDC_NAME_KEY);
			String previousChannel = MDC.get(MDC_CHANNEL_KEY);
			MDC.put(MDC_SESSION_KEY, sessionId);
			if (displayName != null && !displayName.isBlank()) {
				MDC.put(MDC_NAME_KEY, displayName);
			}
			if (channelName != null && !channelName.isBlank()) {
				MDC.put(MDC_CHANNEL_KEY, channelName);
			}
			try {
				action.run();
			} finally {
				restore(MDC_SESSION_KEY, previousSession);
				restore(MDC_NAME_KEY, previousName);
				restore(MDC_CHANNEL_KEY, previousChannel);
			}
		});
	}

	private static void restore(String key, String previous) {
		if (previous == null) {
			MDC.remove(key);
		} else {
			MDC.put(key, previous);
		}
	}

	/// Advances the display name mirrored into the MDC for the REMAINDER of the current scope — used when the
	/// name only becomes known mid-scope (a client's name is validated and set while handling its `Join`, after
	/// [#runAs] snapshotted the still-blank name at entry). A no-op outside a bound scope or for a blank name.
	/// The enclosing scope's restore-on-exit still puts back the pre-scope value, so this can never leak past
	/// the message being handled.
	public static void updateDisplayName(String displayName) {
		if (CURRENT_SESSION.isBound() && displayName != null && !displayName.isBlank()) {
			MDC.put(MDC_NAME_KEY, displayName);
		}
	}

	/// Advances the channel mirrored into the MDC for the REMAINDER of the current scope — used when the session
	/// joins/switches a channel (`channelName` = the new channel) or leaves one (`channelName` = null, which
	/// removes the key so the pattern's default shows again) mid-scope, after [#runAs] snapshotted the channel at
	/// entry. A no-op outside a bound scope. The enclosing scope's restore-on-exit still puts back the pre-scope
	/// value, so this can never leak past the message being handled.
	public static void updateChannel(String channelName) {
		if (!CURRENT_SESSION.isBound()) {
			return;
		}
		if (channelName == null || channelName.isBlank()) {
			MDC.remove(MDC_CHANNEL_KEY);
		} else {
			MDC.put(MDC_CHANNEL_KEY, channelName);
		}
	}

	/// Returns the bound session id, or `"system"` when called outside a bound scope (for example
	/// connection-lifecycle or other server-initiated logging that is not handling a client message).
	public static String currentSessionIdOrSystem() {
		return CURRENT_SESSION.isBound() ? CURRENT_SESSION.get().sessionId() : "system";
	}
}
