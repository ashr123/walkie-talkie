package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Owns the set of live channels and handles atomic create/join/leave with empty-channel cleanup.
@Component
public class ChannelRegistry {

	private static final Consumer<JoinResult> NO_OP = _ -> {
	};

	private final Map<String, Channel> channels = new ConcurrentHashMap<>();

	/// The outcome of a successful join: the joined `channel`, whether this join `created` it (vs joining one
	/// that already existed), plus the joiner's view of the channel captured **atomically with its add** — the
	/// member `roster` (including the joiner) and the current `floorHolder` (the talk-floor hint). The roster and
	/// hint are snapshotted inside the map update under the channel monitor, so the `Joined` roster and floor
	/// hint the caller sends can't be torn by a concurrent floor grant or leave.
	public record JoinResult(Channel channel, boolean created, List<MemberInfo> roster, Option<String> floorHolder) {
	}

	/// Adds `session` to the named channel — creating it (owned by `session`, with `mode` and the joiner's
	/// `keyCheck`) when absent — and returns a [JoinResult] with the joiner's atomically-captured roster + floor
	/// hint. An existing channel keeps its own mode and owner (the joiner adopts them), but the joiner's
	/// `keyCheck` must **match** the channel's, or the join is refused: the member is not added and this returns
	/// `null` (the caller reports a passphrase mismatch). The whole check-add-and-snapshot happens inside the
	/// atomic map update, so it cannot race with a concurrent create or [#leave].
	public JoinResult joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session) {
		return joinOrCreate(name, mode, keyCheck, session, session.id(), NO_OP);
	}

	/// As [#joinOrCreate(String, ChannelMode, String, ClientSession)], but stamps a newly-created channel with
	/// an explicit `ownerId` instead of the joiner's session id — used to give the server-managed "global"
	/// channel a sentinel owner that no participant can match. An existing channel keeps its own owner.
	public JoinResult joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, String ownerId) {
		return joinOrCreate(name, mode, keyCheck, session, ownerId, NO_OP);
	}

	/// As [#joinOrCreate(String, ChannelMode, String, ClientSession)], plus a hook run on a successful add. See
	/// the six-argument form for what `onJoinUnderLock` guarantees.
	public JoinResult joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, Consumer<JoinResult> onJoinUnderLock) {
		return joinOrCreate(name, mode, keyCheck, session, session.id(), onJoinUnderLock);
	}

	/// Full form. On a successful add, `onJoinUnderLock` is invoked exactly once with the captured [JoinResult]
	/// **while the channel monitor is still held** (and before any concurrent floor transition can run) — the
	/// caller uses it to emit the joiner's initial state (its `Joined` snapshot and floor hint) so that emission
	/// is serialized with floor grants/releases: a release can't slip a `FloorIdle` in before the hint (orphaning
	/// it) and a grant/preempt can't leave the hint naming a stale holder. The hook MUST be short and
	/// non-blocking — it runs under the registry bin lock and the channel monitor, and must NOT call back into
	/// the registry (that would invert the bin→monitor order). It is skipped entirely on a key-check mismatch.
	public JoinResult joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, String ownerId, Consumer<JoinResult> onJoinUnderLock) {
		AtomicReference<JoinResult> joined = new AtomicReference<>();
		channels.compute(name, (key, existing) -> {
			Channel channel = existing == null ? new Channel(key, mode, ownerId, keyCheck) : existing;
			if (Objects.equals(channel.keyCheck(), keyCheck)) {
				// Add the joiner, snapshot its view, AND let the caller emit that view to it — all ATOMICALLY under
				// the channel monitor (bin→monitor, the established lock order — never the reverse). Doing the
				// add (which makes the joiner broadcast-eligible), the snapshot, and the joiner's initial-state
				// emission in one monitor span means no floor transition can interleave between the joiner becoming
				// eligible and being told the floor state: a concurrent grant can't both fan a FloorTaken to the
				// fresh member AND have the hint resend it, a concurrent release can't slip a FloorIdle in before
				// the hint, and a concurrent leave (also bin-serialized on this key) can't leave the captured
				// roster disagreeing with the leaver's MemberLeft.
				synchronized (channel) {
					channel.add(session);
					JoinResult result = new JoinResult(channel, existing == null, channel.memberInfos(), channel.floorHolder());
					onJoinUnderLock.accept(result);
					joined.set(result);
				}
			}
			return channel;   // keep the channel even on a key-check mismatch (don't drop it)
		});
		return joined.get();   // null when the joiner's key-check didn't match the channel's
	}

	public Option<Channel> find(String name) {
		return Option.of(channels.get(name));
	}

	/// Atomically removes a member, dropping the channel once empty. If the leaver owned the channel and
	/// others remain, ownership is reassigned and the new owner id is returned (so the caller can
	/// announce it); otherwise [io.github.ashr123.option.None].
	public Option<String> leave(String name, String sessionId) {
		AtomicReference<String> newOwner = new AtomicReference<>();
		channels.computeIfPresent(name, (_, channel) -> {
			boolean wasOwner = sessionId.equals(channel.ownerId());
			channel.remove(sessionId);
			if (channel.isEmpty()) {
				return null;
			}
			if (wasOwner && channel.anyMember() instanceof Some(String elected)) {
				channel.setOwner(elected);
				newOwner.set(elected);
			}
			return channel;
		});
		return Option.of(newOwner.get());
	}

	public int channelCount() {
		return channels.size();
	}

	/// A weakly-consistent live view of the channels, for periodic maintenance sweeps (e.g. the push-to-talk
	/// max-hold reclaim). Safe to iterate concurrently with create/join/leave — it is a `ConcurrentHashMap`
	/// value view, not a snapshot copy.
	public Iterable<Channel> channels() {
		return channels.values();
	}
}
