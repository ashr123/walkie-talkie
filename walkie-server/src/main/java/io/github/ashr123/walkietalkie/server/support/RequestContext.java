package io.github.ashr123.walkietalkie.server.support;

import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import org.slf4j.MDC;

/// Tags the log lines emitted while handling one unit of work — a client's control message, a connection
/// lifecycle event, or a server-initiated per-channel task — with WHO and WHERE, by mirroring the identity and
/// channel into the SLF4J [MDC] for the duration of that work. The console pattern reads them back via
/// `%X{session}` / `%X{name}` / `%X{channel}` (see `application.yml`), so a handler just calls `log.…` and every
/// line it emits carries the context, instead of each message spelling out `session=…` / `channel=…`.
///
/// Usage: open a [Scope] at the boundary of a unit of work with try-with-resources — [#scope] for a client,
/// [#channelScope] for server-initiated per-channel work — then just call `log` inside the block; the scope
/// restores the previous MDC values when the block exits. [#updateDisplayName] / [#updateChannel] advance the open
/// scope when the name/channel only become known (or change) mid-block, e.g. across a join.
///
/// A try-with-resources [AutoCloseable] scope is used rather than a `run(Runnable)` wrapper so the scoped work
/// stays a plain block — normal control flow, no effectively-final captures — matching the codebase's lifecycle
/// convention (AutoCloseable + try-with-resources). The MDC is the single source of truth on purpose: it is the
/// ONLY thing the logging pattern can read (there is no `%scopedValue`), and Logback snapshots it into each event
/// at creation time, so it stays correct even under an async appender — a [java.lang.ScopedValue] would need a
/// custom converter, could not be read async-safely, and nothing outside logging consumes this context.
public final class RequestContext {

	/// MDC key exposing the current session id to the logging pattern (`%X{session}`). Absent ⇒ the pattern's
	/// `system` default — server-initiated logging that is not acting for a specific client (e.g. the floor sweep).
	public static final String MDC_SESSION_KEY = "session";
	/// MDC key exposing the current display name (`%X{name}`). Set only for a non-blank name (it is blank before a
	/// client has joined), so the pattern's default shows otherwise.
	public static final String MDC_NAME_KEY = "name";
	/// MDC key exposing the current channel (`%X{channel}`). Set only while in a channel, so the pattern's default
	/// shows before a join / after a leave.
	public static final String MDC_CHANNEL_KEY = "channel";

	private RequestContext() {
	}

	/// Opens a scope tagged with a client's identity + current channel — for handling one of its control messages
	/// or a connection lifecycle event (connect / disconnect). Close it with try-with-resources. The name may be
	/// blank before the client has joined, and the channel absent before/after membership; [#updateDisplayName] /
	/// [#updateChannel] advance them mid-scope as those become known.
	public static Scope scope(ClientSession session) {
		return open(session.id(), session.displayName(), session.channelName());
	}

	/// Opens a scope tagged with only the channel — for server-initiated per-channel work that has no acting client
	/// (the scheduled floor sweep; the audio path's max-hold release). No session is bound, so the pattern shows
	/// `session=system`; the affected member, if any, is named in the message itself.
	public static Scope channelScope(Channel channel) {
		return open(null, null, channel.name());
	}

	/// Shared core: the returned [Scope] snapshots the current MDC, then mirrors the given identity/channel into it,
	/// and restores the snapshot on close. A null/blank component leaves its key unset, so the pattern falls back to
	/// its default. Package-private: callers use the typed [#scope] / [#channelScope].
	static Scope open(String sessionId, String displayName, String channelName) {
		return new Scope(sessionId, displayName, channelName);
	}

	/// Advances the display name mirrored into the MDC for the REMAINDER of the current scope — used when the name
	/// only becomes known mid-scope (validated while handling a `Join`, after the scope snapshotted the still-blank
	/// name) or changes (a `Rename`). A no-op outside a per-client scope and for a blank name; the enclosing scope's
	/// restore-on-close still puts back the pre-scope value, so it can't leak.
	public static void updateDisplayName(String displayName) {
		if (inClientScope() && displayName != null && !displayName.isBlank()) {
			MDC.put(MDC_NAME_KEY, displayName);
		}
	}

	/// Advances (or clears) the channel mirrored into the MDC for the REMAINDER of the current scope — used when the
	/// session joins/switches a channel (`channelName` = the new channel) or leaves one (`channelName` = null, which
	/// removes the key so the pattern's default shows again). A no-op outside a per-client scope; the enclosing
	/// scope's restore-on-close still puts back the pre-scope value, so it can't leak.
	public static void updateChannel(String channelName) {
		if (!inClientScope()) {
			return;
		}
		if (channelName == null || channelName.isBlank()) {
			MDC.remove(MDC_CHANNEL_KEY);
		} else {
			MDC.put(MDC_CHANNEL_KEY, channelName);
		}
	}

	/// True when a per-client scope ([#scope]) is active on this thread, detected by its session key being set. The
	/// mid-scope updaters gate on this so a stray call on an unscoped thread can't write a key that would then never
	/// be restored. (A [#channelScope] binds no session and never calls the updaters.)
	private static boolean inClientScope() {
		return MDC.get(MDC_SESSION_KEY) != null;
	}

	private static void putIfPresent(String key, String value) {
		if (value != null && !value.isBlank()) {
			MDC.put(key, value);
		}
	}

	private static void restore(String key, String previous) {
		if (previous == null) {
			MDC.remove(key);
		} else {
			MDC.put(key, previous);
		}
	}

	/// The active logging scope: mirrors an identity/channel into the MDC for the duration of a try-with-resources
	/// block and restores the previous values on [#close]. Obtain one from [#scope] / [#channelScope]. [#close] is
	/// idempotent, so an accidental double-close can't clobber an enclosing scope's restored values.
	public static final class Scope implements AutoCloseable {

		private final String previousSession;
		private final String previousName;
		private final String previousChannel;
		private boolean closed;

		private Scope(String sessionId, String displayName, String channelName) {
			this.previousSession = MDC.get(MDC_SESSION_KEY);
			this.previousName = MDC.get(MDC_NAME_KEY);
			this.previousChannel = MDC.get(MDC_CHANNEL_KEY);
			putIfPresent(MDC_SESSION_KEY, sessionId);
			putIfPresent(MDC_NAME_KEY, displayName);
			putIfPresent(MDC_CHANNEL_KEY, channelName);
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			restore(MDC_SESSION_KEY, previousSession);
			restore(MDC_NAME_KEY, previousName);
			restore(MDC_CHANNEL_KEY, previousChannel);
		}
	}
}
