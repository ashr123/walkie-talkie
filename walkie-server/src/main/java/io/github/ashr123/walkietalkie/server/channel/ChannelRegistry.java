package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.Transport;
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

	private static final Consumer<JoinOutcome.Admitted> NO_OP = _ -> {
	};

	private final Map<String, Channel> channels = new ConcurrentHashMap<>();

	/// The outcome of a join attempt, decided **inside** the atomic map update so it can't race a concurrent
	/// create/join/leave/`setLocked`/rekey. Sealed, so a caller's `switch` is exhaustive and the reason for a
	/// refusal is carried explicitly rather than re-derived: this replaces an earlier `null` return that meant
	/// *any* of three different refusals, which forced the caller to re-read the channel afterwards and GUESS
	/// which one applied — an approximation that could name a reason that only became true after the fact.
	///
	/// The `Channel` appears only on the two outcomes that leave the joiner attached to one — [Admitted] (it is a
	/// member) and [Pending] (it is parked at the door, and the caller needs the channel to notify its owner).
	/// [Refused] carries none, mirroring [RekeyResult]/[TransferResult]/[LockResult]: nothing was joined, so there
	/// is nothing for a caller to null-check.
	public sealed interface JoinOutcome {

		/// The joiner was added: `created` says whether this join brought the channel into being, and the
		/// `roster` (including the joiner) + `floorHolder` hint are the joiner's view of the channel captured
		/// **atomically with its add**, under the channel monitor — so the `Joined` snapshot the caller sends
		/// can't be torn by a concurrent floor grant or leave.
		record Admitted(Channel channel, boolean created, List<MemberInfo> roster, Option<String> floorHolder)
				implements JoinOutcome {
		}

		/// The channel is LOCKED and parks newcomers, so the joiner was placed on its waiting list instead of being
		/// admitted or refused — the owner now decides (see [Channel#knock]). The joiner is NOT a member: it holds
		/// no stream index, receives no broadcasts, and its `channelName` is untouched, so it keeps whatever channel
		/// it was already in while it waits. `alreadyWaiting` marks an idempotent re-knock, which the caller uses to
		/// avoid re-notifying the owner about a request already on their list.
		record Pending(Channel channel, boolean alreadyWaiting) implements JoinOutcome {
		}

		/// The joiner was NOT added, for exactly this `reason`. The channel is left unchanged and still registered.
		record Refused(Reason reason) implements JoinOutcome {
		}

		/// Why a join was refused, in the order [ChannelRegistry#joinOrCreate] evaluates them: the channel is locked
		/// and does NOT park newcomers (`walkie.max-join-requests` is 0); it is at its member cap (one stream index
		/// per member); the joiner's key-check differs from the channel's (wrong or missing end-to-end-encryption
		/// passphrase); or the channel parks newcomers but its waiting list is already at the cap.
		enum Reason {
			LOCKED,
			FULL,
			PASSPHRASE_MISMATCH,
			WAITING_LIST_FULL
		}
	}

	/// Adds `session` to the named channel — creating it (owned by `session`, with `mode` and the joiner's
	/// `keyCheck`) when absent — and returns the [JoinOutcome]: [JoinOutcome.Admitted] with the joiner's
	/// atomically-captured roster + floor hint, or [JoinOutcome.Refused] carrying exactly why. An existing channel
	/// keeps its own mode and owner (the joiner adopts them), but the joiner's `keyCheck` must **match** the
	/// channel's. The whole check-add-and-snapshot happens inside the atomic map update, so it cannot race with a
	/// concurrent create or [#leave].
	public JoinOutcome joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session) {
		return joinOrCreate(name, mode, keyCheck, session, session.id(), session.transport(), Channel.Defaults.NONE, NO_OP);
	}

	/// As [#joinOrCreate(String, ChannelMode, String, ClientSession)], but stamps a newly-created channel with
	/// an explicit `ownerId` instead of the joiner's session id — used to give the server-managed "global"
	/// channel a sentinel owner that no participant can match. An existing channel keeps its own owner.
	public JoinOutcome joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, String ownerId) {
		return joinOrCreate(name, mode, keyCheck, session, ownerId, session.transport(), Channel.Defaults.NONE, NO_OP);
	}

	/// As [#joinOrCreate(String, ChannelMode, String, ClientSession)], plus the [Channel.Defaults] a newly-created
	/// channel adopts and a hook run on a successful add. See the full form for what `onJoinUnderLock` guarantees.
	public JoinOutcome joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, Channel.Defaults defaults, Consumer<? super JoinOutcome.Admitted> onJoinUnderLock) {
		return joinOrCreate(name, mode, keyCheck, session, session.id(), session.transport(), defaults, onJoinUnderLock);
	}

	/// As the full form, but seeding a newly-created channel with `transport` — the media plane the joiner ASKED
	/// for ([ClientMessage.Join#transport()]) — instead of the
	/// endpoint it happens to be dialled on. The shorter overloads pass the dialled endpoint, which is the right
	/// answer for a client that never offers the choice.
	public JoinOutcome joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, Transport transport, Channel.Defaults defaults, Consumer<? super JoinOutcome.Admitted> onJoinUnderLock) {
		return joinOrCreate(name, mode, keyCheck, session, session.id(), transport, defaults, onJoinUnderLock);
	}

	/// Full form. `defaults` seeds a **newly created** channel (an existing channel keeps its own state, like its
	/// mode/owner) — see [Channel.Defaults]. On a successful add,
	/// `onJoinUnderLock` is invoked exactly once with the captured [JoinOutcome.Admitted] **while the channel
	/// monitor is still held** (and before any concurrent floor transition can run) — the caller uses it to emit the
	/// joiner's initial state (its `Joined` snapshot and floor snapshot) so that emission is serialized with floor
	/// grants/releases: a release can't slip a stale floor snapshot in before the hint and a grant/preempt can't
	/// leave the hint naming a stale holder. The hook MUST be short and non-blocking — it runs under the registry
	/// bin lock and the channel monitor, and must NOT call back into the registry (that would invert the
	/// bin→monitor order). It is skipped entirely on a refusal.
	public JoinOutcome joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, String ownerId, Transport transport, Channel.Defaults defaults, Consumer<? super JoinOutcome.Admitted> onJoinUnderLock) {
		AtomicReference<JoinOutcome> outcome = new AtomicReference<>();
		channels.compute(name, (key, existing) -> {
			// A new channel takes the media plane its CREATOR asked for; an existing one keeps its own and the
			// joiner adopts it, exactly as with the mode. Nothing below re-checks it — there is no mismatch to
			// refuse any more, because a joiner cannot disagree with a channel it does not get a vote on.
			Channel channel = existing == null
					? new Channel(key, mode, ownerId, keyCheck, transport, defaults)
					: existing;
			// Each refusal below records its OWN reason while still under the bin lock, so the caller never has to
			// re-read the channel to work out which rule rejected the joiner.
			//
			// A channel its owner LOCKED does not admit newcomers off the street. Only reachable for a NEWCOMER: a
			// re-join to the member's CURRENT channel short-circuits in ConnectionService.handleJoin and never gets
			// here. This read is under the bin lock, so it is atomic with a concurrent setLocked/leave/join (a
			// freshly created channel is never locked, so this only affects joins).
			//
			// Three ways through a locked door, in this order:
			//   1. the owner already ADMITTED this session — spend its one-shot grant and fall through to the
			//      ordinary add below. The grant bypasses the LOCK only; capacity and the key-check still apply.
			//   2. the channel parks newcomers — validate the key-check FIRST, so the owner is never asked to
			//      approve someone who could not have got in anyway — then knock.
			//   3. it doesn't park them (`walkie.max-join-requests` = 0) — refuse outright, as before this feature.
			if (existing != null && channel.isLocked()) {
				// One atomic step: the ticket is TORN HERE, before the capacity and key-check gates below. If one of
				// those then refuses this join, the grant is spent and the newcomer must knock again — deliberate,
				// because peeking first and spending later would open a window for a concurrent deny (which takes
				// only the channel monitor, not this bin lock) to withdraw the request in between.
				boolean admittedByOwner = channel.consumeGrant(session.id());
				if (!admittedByOwner) {
					if (!channel.acceptsJoinRequests()) {
						outcome.set(new JoinOutcome.Refused(JoinOutcome.Reason.LOCKED));
						return channel;   // keep the channel; do not add the joiner
					}
					if (!Objects.equals(channel.keyCheck(), keyCheck)) {
						outcome.set(new JoinOutcome.Refused(JoinOutcome.Reason.PASSPHRASE_MISMATCH));
						return channel;
					}
					outcome.set(switch (channel.knock(session)) {
						case REGISTERED -> new JoinOutcome.Pending(channel, false);
						case ALREADY_WAITING -> new JoinOutcome.Pending(channel, true);
						case LIST_FULL -> new JoinOutcome.Refused(JoinOutcome.Reason.WAITING_LIST_FULL);
					});
					return channel;
				}
			}
			// Refuse a newcomer once the channel is at capacity (one stream index per member, range 0..254) rather
			// than assign a colliding index. Under the bin lock, so the capacity check + the add are atomic w.r.t.
			// concurrent joins/leaves. Only newcomers reach here (a current member's re-join short-circuits before
			// joinOrCreate).
			if (existing != null && channel.isFull()) {
				outcome.set(new JoinOutcome.Refused(JoinOutcome.Reason.FULL));
				return channel;
			}
			if (Objects.equals(channel.keyCheck(), keyCheck)) {
				// Add the joiner, snapshot its view, AND let the caller emit that view to it — all ATOMICALLY under
				// the channel monitor (bin→monitor, the established lock order — never the reverse). Doing the
				// add (which makes the joiner broadcast-eligible), the snapshot, and the joiner's initial-state
				// emission in one monitor span means no floor transition can interleave between the joiner becoming
				// eligible and being told the floor state: a concurrent grant/release/reserve can't land a floor
				// broadcast that races the joiner's own initial FloorStatus hint (leaving it naming a stale holder
				// or an out-of-date queue), and a concurrent leave (also bin-serialized on this key) can't leave the
				// captured roster disagreeing with the leaver's MemberLeft.
				synchronized (channel) {
					channel.add(session);
					JoinOutcome.Admitted admitted =
							new JoinOutcome.Admitted(channel, existing == null, channel.memberInfos(), channel.floorHolder());
					onJoinUnderLock.accept(admitted);
					outcome.set(admitted);
				}
			} else {
				outcome.set(new JoinOutcome.Refused(JoinOutcome.Reason.PASSPHRASE_MISMATCH));
			}
			return channel;   // keep the channel even on a key-check mismatch (don't drop it)
		});
		return outcome.get();
	}

	public Option<Channel> find(String name) {
		return Option.of(channels.get(name));
	}

	/// What a departure changed. Sealed and exhaustive because these outcomes are mutually exclusive — a channel that
	/// emptied cannot also have elected an owner — and because the caller MUST NOT overlook [ChannelDropped]: a
	/// dropped channel takes its waiting list with it, and those newcomers would otherwise wait forever for an owner
	/// who no longer exists. An `Option<String>` return (all this used to be) could not express that at all.
	public sealed interface LeaveOutcome {

		/// The member was removed and others remain; the owner is unchanged.
		record Removed() implements LeaveOutcome {
		}

		/// The member was removed, others remain, and it OWNED the channel — so ownership was auto-elected to
		/// `newOwnerId`. The new owner inherits any waiting list and must be sent the current snapshot.
		record OwnerElected(String newOwnerId) implements LeaveOutcome {
		}

		/// The LAST member left, so the channel was dropped from the registry. `clearedRequests` are the newcomers
		/// who were waiting at its door: the lock died with the channel, so they are cleared to join, and whichever
		/// of them re-sends `Join` first RECREATES the channel and owns it.
		record ChannelDropped(List<ClientSession> clearedRequests) implements LeaveOutcome {
		}

		/// No such channel (already dropped), or the session was not a member of it — nothing changed.
		record NotFound() implements LeaveOutcome {
		}
	}

	/// Atomically removes a member, dropping the channel once empty and auto-electing a new owner when the leaver
	/// owned it. See [LeaveOutcome] for what the caller must then announce.
	public LeaveOutcome leave(String name, String sessionId) {
		AtomicReference<LeaveOutcome> outcome = new AtomicReference<>(new LeaveOutcome.NotFound());
		channels.computeIfPresent(name, (_, channel) -> {
			boolean wasOwner = sessionId.equals(channel.ownerId());
			channel.remove(sessionId);
			if (channel.isEmpty()) {
				// Nobody is left to admit anyone, so hand the waiting newcomers back for the caller to release
				// rather than letting them vanish with the channel object.
				outcome.set(new LeaveOutcome.ChannelDropped(channel.drainJoinRequests()));
				return null;
			}
			if (wasOwner && channel.anyMember() instanceof Some(String elected)) {
				channel.setOwner(elected);
				outcome.set(new LeaveOutcome.OwnerElected(elected));
			} else {
				outcome.set(new LeaveOutcome.Removed());
			}
			return channel;
		});
		return outcome.get();
	}

	/// The result of a [#changePassphrase] attempt. `Ok` carries the exact `Channel` whose key-check was rotated —
	/// the caller broadcasts over **that** object, never a fresh `find()`-by-name, which could resolve a
	/// dropped-and-recreated same-named channel and misroute the notice (the same-object discipline [#leave] uses).
	/// `NotOwner` = the requester doesn't own the channel; `NotFound` = no such channel (e.g. it emptied and was
	/// dropped); `EncryptionRequired` = the rotation asked to CLEAR the passphrase, which no channel permits any
	/// more. A sealed hierarchy so the caller's `switch` is exhaustive and the `Channel` is present only on the
	/// success variant (never a null field).
	public sealed interface RekeyResult {
		record Ok(Channel channel) implements RekeyResult {}

		record NotOwner() implements RekeyResult {}

		record NotFound() implements RekeyResult {}

		/// The owner asked to turn encryption off. Refused, so the channel keeps the passphrase it had — a
		/// distinct variant rather than a caller-side pre-check because the rule has to be evaluated under the
		/// same bin lock as the write it is refusing (see [#changePassphrase]).
		record EncryptionRequired() implements RekeyResult {}
	}

	/// Rotates a channel's key-check on the owner's request. It can only ever be rotated to another key, never
	/// cleared: there are no plaintext channels (`global` is server-owned, so it never reaches here at all). The owner check and the key-check
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
			if (!requesterId.equals(channel.ownerId())) {
				result.set(new RekeyResult.NotOwner());
			} else if (newKeyCheck == null) {
				// Clearing the passphrase would make this channel plaintext, which no channel may be. Refused HERE,
				// inside the lock and before the write, so the channel is never even momentarily unencrypted for a
				// concurrent joiner to slip into — a caller-side pre-check could not promise that. Ordered after the
				// owner check so a non-owner still hears NOT_OWNER, which is the truer answer for them.
				result.set(new RekeyResult.EncryptionRequired());
			} else {
				channel.setKeyCheck(newKeyCheck);
				result.set(new RekeyResult.Ok(channel));
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
			} else if (channel.member(newOwnerId) instanceof Some<ClientSession>) {
				channel.setOwner(newOwnerId);
				result.set(new TransferResult.Ok(channel));
			} else {
				result.set(new TransferResult.NotAMember());
			}
			return channel;
		});
		return result.get();
	}

	/// The result of a [#changeTransport] attempt. `Ok` carries the `Channel` that moved — the caller broadcasts
	/// over that exact instance (see [RekeyResult] for why a fresh `find()` would be unsafe) — and `changed` says
	/// whether the transport was actually different, so a request naming the plane the channel is already on is a
	/// success that broadcasts nothing rather than an error. `NotOwner` = the requester doesn't own the channel;
	/// `NotFound` = no such channel. Sealed, so the caller's `switch` is exhaustive.
	public sealed interface TransportResult {
		record Ok(Channel channel, boolean changed) implements TransportResult {}

		record NotOwner() implements TransportResult {}

		record NotFound() implements TransportResult {}
	}

	/// Moves a channel and every one of its members to the other media plane, on the owner's request. The owner
	/// check and both writes happen **inside** `channels.computeIfPresent(name, …)` — the same bin lock
	/// [#joinOrCreate] compares a joiner's transport under — so the move is atomic with respect to every concurrent
	/// join: a joiner is measured against either the old transport or the new one, never against a channel that has
	/// moved but whose members have not. Without that, a `SIGNALING` joiner could be admitted to a channel mid-move
	/// and find half the roster unable to hear it.
	///
	/// Nobody is disconnected and nobody is removed: see
	/// [io.github.ashr123.walkietalkie.shared.protocol.ClientMessage.ChangeTransport] for why the control plane
	/// survives a media-plane change, and why it has to.
	public TransportResult changeTransport(String name, String requesterId, Transport transport) {
		AtomicReference<TransportResult> result = new AtomicReference<>(new TransportResult.NotFound());
		channels.computeIfPresent(name, (_, channel) -> {
			result.set(requesterId.equals(channel.ownerId())
					? new TransportResult.Ok(channel, channel.setTransport(transport))
					: new TransportResult.NotOwner());
			return channel;
		});
		return result.get();
	}

	/// The result of a [#setLocked] attempt. `Ok` carries the `Channel` whose lock state changed — the caller
	/// broadcasts over that exact instance (see [RekeyResult] for why a fresh `find()` would be unsafe). `NotOwner`
	/// = the requester doesn't own the channel; `NotFound` = no such channel. Sealed, so the caller's `switch` is
	/// exhaustive and the `Channel` is present only on success.
	public sealed interface LockResult {
		/// The flag was changed. `clearedRequests` are the newcomers UNLOCKING released — an unlocked channel admits
		/// anyone, so leaving people parked at an open door would be incoherent, and it is what keeps the invariant
		/// "a request exists only while the channel is locked" true. Empty when locking, or when nobody was waiting.
		record Ok(Channel channel, List<ClientSession> clearedRequests) implements LockResult {}

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
				// Unlocking releases everyone waiting, in the SAME bin-locked step as the flag: a concurrent join
				// therefore either sees the channel still locked (and is parked) or sees it open (and walks in) —
				// never "open but you are still on a list nobody will ever read".
				//
				// It DRAINS rather than grants. A grant exists only to bypass the lock, and there is no longer a lock
				// to bypass: joinOrCreate's locked branch — the only place a grant is consumed — is skipped once the
				// channel is open, so granting here would leave every entry in the list forever.
				result.set(new LockResult.Ok(channel, locked ? List.of() : channel.drainJoinRequests()));
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
