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
	public JoinResult joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, Consumer<? super JoinResult> onJoinUnderLock) {
		return joinOrCreate(name, mode, keyCheck, session, session.id(), onJoinUnderLock);
	}

	/// Full form. On a successful add, `onJoinUnderLock` is invoked exactly once with the captured [JoinResult]
	/// **while the channel monitor is still held** (and before any concurrent floor transition can run) — the
	/// caller uses it to emit the joiner's initial state (its `Joined` snapshot and floor hint) so that emission
	/// is serialized with floor grants/releases: a release can't slip a `FloorIdle` in before the hint (orphaning
	/// it) and a grant/preempt can't leave the hint naming a stale holder. The hook MUST be short and
	/// non-blocking — it runs under the registry bin lock and the channel monitor, and must NOT call back into
	/// the registry (that would invert the bin→monitor order). It is skipped entirely on a key-check mismatch.
	public JoinResult joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, String ownerId, Consumer<? super JoinResult> onJoinUnderLock) {
		AtomicReference<JoinResult> joined = new AtomicReference<>();
		channels.compute(name, (key, existing) -> {
			Channel channel = existing == null ? new Channel(key, mode, ownerId, keyCheck) : existing;
			// A channel its owner LOCKED admits no new members — refuse before the key-check (a locked door doesn't
			// care about the key). Only reachable for a NEWCOMER: a re-join to the member's CURRENT channel
			// short-circuits in ConnectionService.handleJoin and never gets here. This read is under the bin lock,
			// so it is atomic with a concurrent setLocked/leave/join. `joined` stays null; the caller attributes the
			// rejection to CHANNEL_LOCKED (a freshly created channel is never locked, so this only affects joins).
			if (existing != null && channel.isLocked()) {
				return channel;   // keep the channel; do not add the joiner
			}
			// Refuse a newcomer once the channel is at capacity (one stream index per member, range 0..254) rather
			// than assign a colliding index. Under the bin lock, so the capacity check + the add are atomic w.r.t.
			// concurrent joins/leaves. Only newcomers reach here (a current member's re-join short-circuits before
			// joinOrCreate). `joined` stays null; the caller attributes the rejection to CHANNEL_FULL.
			if (existing != null && channel.isFull()) {
				return channel;
			}
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

	/// The result of a [#changePassphrase] attempt. `Ok` carries the exact `Channel` whose key-check was rotated —
	/// the caller broadcasts over **that** object, never a fresh `find()`-by-name, which could resolve a
	/// dropped-and-recreated same-named channel and misroute the notice (the same-object discipline [#leave] uses).
	/// `NotOwner` = the requester doesn't own the channel; `NotFound` = no such channel (e.g. it emptied and was
	/// dropped). A sealed hierarchy so the caller's `switch` is exhaustive and the `Channel` is present only on the
	/// success variant (never a null field).
	public sealed interface RekeyResult {
		record Ok(Channel channel) implements RekeyResult {}

		record NotOwner() implements RekeyResult {}

		record NotFound() implements RekeyResult {}
	}

	/// Rotates (sets/clears) a channel's key-check on the owner's request. The owner check and the key-check
	/// write happen **inside** `channels.computeIfPresent(name, …)`, i.e. under the same `ConcurrentHashMap` bin
	/// lock that [#joinOrCreate]'s `channels.compute` validates a joiner's key-check under — so a rotation is
	/// atomic with respect to every concurrent join (a joiner either validates against the old value and is then
	/// told of the change, or validates against the new value), and with respect to the ownership transfer a
	/// concurrent [#leave] performs (also under this bin lock). On `Ok` the result carries the mutated channel so
	/// the caller broadcasts [io.github.ashr123.walkietalkie.shared.protocol.ServerMessage.PassphraseChanged] over
	/// that exact instance; any member present at the rotation is still in its (concurrent) member view when the
	/// broadcast iterates, and any member that joins afterwards already used the new key-check.
	public RekeyResult changePassphrase(String name, String requesterId, String newKeyCheck) {
		AtomicReference<RekeyResult> result = new AtomicReference<>(new RekeyResult.NotFound());
		channels.computeIfPresent(name, (_, channel) -> {
			if (requesterId.equals(channel.ownerId())) {
				channel.setKeyCheck(newKeyCheck);
				result.set(new RekeyResult.Ok(channel));
			} else {
				result.set(new RekeyResult.NotOwner());
			}
			return channel;
		});
		return result.get();
	}

	/// The result of a [#transferOwnership] attempt. `Ok` carries the `Channel` whose owner changed — the caller
	/// broadcasts over that exact instance (see [RekeyResult] for why a fresh `find()` would be unsafe). `NotOwner`
	/// = the requester doesn't own the channel; `NotAMember` = the target id is not a member here; `NotFound` = no
	/// such channel. Sealed, so the caller's `switch` is exhaustive and the `Channel` is present only on success.
	public sealed interface TransferResult {
		record Ok(Channel channel) implements TransferResult {}

		record NotOwner() implements TransferResult {}

		record NotAMember() implements TransferResult {}

		record NotFound() implements TransferResult {}
	}

	/// Hands ownership to another current member on the owner's request. The owner check, the membership check
	/// and the owner write all happen **inside** `channels.computeIfPresent(name, …)` — the same bin lock under
	/// which [#leave] performs its departure-triggered auto-election — so an explicit transfer can't race that
	/// election (one wins the lock, then the other observes the result) and can't hand ownership to a member who
	/// is concurrently leaving (the membership check and the write are one atomic step). On `Ok` the result
	/// carries the channel so the caller broadcasts
	/// [io.github.ashr123.walkietalkie.shared.protocol.ServerMessage.OwnerChanged] over that exact instance.
	public TransferResult transferOwnership(String name, String requesterId, String newOwnerId) {
		AtomicReference<TransferResult> result = new AtomicReference<>(new TransferResult.NotFound());
		channels.computeIfPresent(name, (_, channel) -> {
			if (!requesterId.equals(channel.ownerId())) {
				result.set(new TransferResult.NotOwner());
			} else if (channel.member(newOwnerId) instanceof Some(ClientSession _)) {
				channel.setOwner(newOwnerId);
				result.set(new TransferResult.Ok(channel));
			} else {
				result.set(new TransferResult.NotAMember());
			}
			return channel;
		});
		return result.get();
	}

	/// The result of a [#setLocked] attempt. `Ok` carries the `Channel` whose lock state changed — the caller
	/// broadcasts over that exact instance (see [RekeyResult] for why a fresh `find()` would be unsafe). `NotOwner`
	/// = the requester doesn't own the channel; `NotFound` = no such channel. Sealed, so the caller's `switch` is
	/// exhaustive and the `Channel` is present only on success.
	public sealed interface LockResult {
		record Ok(Channel channel) implements LockResult {}

		record NotOwner() implements LockResult {}

		record NotFound() implements LockResult {}
	}

	/// Locks or unlocks a channel to new members on the owner's request. The owner check and the lock write happen
	/// **inside** `channels.computeIfPresent(name, …)` — the same bin lock [#joinOrCreate] reads the lock under — so
	/// a toggle is atomic w.r.t. every concurrent join (a joiner sees consistently either the locked or the
	/// unlocked state) and w.r.t. the ownership transfer a concurrent [#leave] performs. On `Ok` the result carries
	/// the mutated channel so the caller broadcasts
	/// [io.github.ashr123.walkietalkie.shared.protocol.ServerMessage.ChannelLocked] over that exact instance.
	public LockResult setLocked(String name, String requesterId, boolean locked) {
		AtomicReference<LockResult> result = new AtomicReference<>(new LockResult.NotFound());
		channels.computeIfPresent(name, (_, channel) -> {
			if (requesterId.equals(channel.ownerId())) {
				channel.setLocked(locked);
				result.set(new LockResult.Ok(channel));
			} else {
				result.set(new LockResult.NotOwner());
			}
			return channel;
		});
		return result.get();
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
