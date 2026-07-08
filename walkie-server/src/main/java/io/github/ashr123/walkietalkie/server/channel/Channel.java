package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.NoneInt;
import io.github.ashr123.option.Option;
import io.github.ashr123.option.OptionInt;
import io.github.ashr123.option.SomeInt;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/// A single conversation room. Membership, the talk floor, the mode and the owner are tracked with
/// concurrent primitives so that connections handled on different (virtual) threads can join, leave,
/// transmit and re-configure safely. The `mode`, `ownerId` and `keyCheck` are mutable: the owner may change
/// the mode or rotate the encryption passphrase, and ownership transfers to another member when the owner
/// leaves.
public final class Channel {

	/// Usable stream indices are 0..254; 255 (0xFF) is reserved as a future "extended id" escape.
	private static final int STREAM_INDEX_RANGE = 255;
	private final String name;
	/// The roster: session id -> [Member]. Membership and the member's stream index live in ONE map value, so
	/// they are published ([#add]) and retired ([#remove]) atomically — a lock-free reader (the audio fan-out, the
	/// in-place re-join re-snapshot) sees a member either complete with its index or not at all, never a member
	/// whose identifier is missing. (They used to live in two maps mutated in separate steps, which let such a
	/// reader catch a member mid-join/mid-leave without an index.)
	private final Map<String, Member> members = new ConcurrentHashMap<>();
	/// Stream-index pool for [#allocateStreamIndex]/[#freeStreamIndex] (both synchronized): a monotonic rotating
	/// cursor that skips live indices avoids reusing a just-freed index until it has cycled the whole range —
	/// quarantining recycled indices so a new talker can't inherit a departed member's still-in-flight frames.
	private final boolean[] indexInUse = new boolean[STREAM_INDEX_RANGE];
	/// Session ids the owner has muted. Their relayed audio is dropped in `ConnectionService.onAudio` before
	/// fan-out, so a mute is enforced by the server rather than trusted to the client. A concurrent set so the
	/// per-frame [#isMuted] read is lock-free; the mute/unmute mutations run under this channel's monitor when they
	/// must be atomic with the `MemberMuted` broadcast and with freeing the floor of a member being muted —
	/// mirroring the floor discipline. Entries are dropped on [#remove], so a member's mute never outlives it.
	private final Set<String> mutedMembers = ConcurrentHashMap.newKeySet();
	/// The session id currently holding the floor, or `null` when the floor is free. Written only under this
	/// channel's monitor, so the check-then-set in the acquire/release/preempt paths is atomic; `volatile` so
	/// the lock-free reads on the hot audio path ([#holdsFloor]) and the join hint ([#floorHolder]) see the
	/// latest value. (A lock-free read may be momentarily stale — that is why the audio path re-validates under
	/// the monitor before fanning a frame out.)
	private volatile String floorHolder;
	private volatile ChannelMode mode;
	private volatile String ownerId;
	/// The key-check value every member must present to join (a short value derived from the E2EE
	/// passphrase, or `null` for an unencrypted channel), set by the creator and changed by the owner on a
	/// passphrase rotation. The server compares it to reject a mismatched passphrase; it is not the key and
	/// reveals nothing usable about it.
	///
	/// Concurrency: the **write** ([#setKeyCheck], from [ChannelRegistry#changePassphrase]) and the
	/// join-validation **read** ([ChannelRegistry#joinOrCreate]) both happen inside the registry's
	/// `channels.computeIfPresent(name, …)` / `compute(name, …)` remapping, so the `ConcurrentHashMap` bin lock
	/// for the channel name serializes a rotation with every join's key-check check. It is also read **live** when
	/// `ConnectionService.handleChangePassphrase` announces a rotation (under the channel monitor, a *different*
	/// lock), so the field is `volatile` for that cross-lock visibility — the monitor + live read make
	/// back-to-back rotations converge on the channel's current value (mirroring the `OwnerChanged` discipline).
	/// Still mutate it only via [#setKeyCheck] from the registry's bin-locked remapping.
	private volatile String keyCheck;
	/// Whether the owner has LOCKED the channel to new members. While true, [ChannelRegistry#joinOrCreate] refuses a
	/// join from any session not already a member; existing members are unaffected (locking blocks only new joins).
	/// Concurrency mirrors [#keyCheck]: the **write** ([#setLocked], from [ChannelRegistry#setLocked]) and the
	/// enforcement **read** ([ChannelRegistry#joinOrCreate]) both run inside the registry's `channels.compute*(name,
    /// …)` remapping, so the `ConcurrentHashMap` bin lock for the channel name serializes a lock toggle with every
	/// join — a joiner sees either the locked or the unlocked state, never a torn one. It is also read **live**
	/// under the channel monitor when `ConnectionService.handleSetLocked` announces the change (a *different* lock),
	/// and lock-free by the in-place re-join re-snapshot, so it is `volatile` for that cross-lock visibility;
	/// back-to-back toggles converge on the current value like the passphrase/owner broadcasts. Mutate it only via
	/// [#setLocked] from the registry's bin-locked remapping.
	private volatile boolean locked;
	private int rotation;
	// --- floor hold timing (push-to-talk anti-hogging) --------------------------------------------
	// Two marks let the server bound how long one member keeps the floor WITHOUT ever inspecting audio content
	// — so the limits hold on end-to-end-encrypted channels too. `floorAcquiredAt` backs a max-hold cap
	// (continuous talk time); `floorActivityAt` (the holder's most recent frame) backs idle auto-release. Both
	// are stamped under the monitor by the acquire / preempt / activity methods and read lock-free via the
	// getters (volatile); they start at EPOCH and are only read after a real acquire stamps them.
	//
	// Serialization contract: the floor-mutating methods here (tryAcquireFloor / releaseFloor / preemptFloorIfIdle /
	// markFloorActivity / releaseIfExpired) synchronize on THIS Channel instance, so each does its check-then-set
	// on the plain `floorHolder` field atomically. A caller that must make a floor transition atomic with the
	// message it broadcasts about it (so a concurrently-acquiring member can't be told the floor is free, or
	// vice-versa) wraps the whole mutate-and-notify in `synchronized (channel)` — reentrant with these methods.
	// holdsFloor / floorHolder() stay lock-free (volatile) reads for the per-frame path.
	// NOTE for such callers: never invoke a ChannelRegistry mutate (joinOrCreate/leave) while holding this
	// monitor — the registry takes its bin lock then this monitor (via add/remove), so the reverse order deadlocks.
	private volatile Instant floorAcquiredAt = Instant.EPOCH;
	private volatile Instant floorActivityAt = Instant.EPOCH;
	public Channel(String name, ChannelMode mode, String ownerId, String keyCheck) {
		this.name = name;
		this.mode = mode;
		this.ownerId = ownerId;
		this.keyCheck = keyCheck;
	}

	public String name() {
		return name;
	}

	public String keyCheck() {
		return keyCheck;
	}

	/// Replaces the key-check on a passphrase rotation. Call **only** from [ChannelRegistry#changePassphrase],
	/// i.e. inside the channel-name `channels.compute(…)` span, so it serializes with join validation (see the
	/// `keyCheck` field's concurrency note).
	public void setKeyCheck(String keyCheck) {
		this.keyCheck = keyCheck;
	}

	public ChannelMode mode() {
		return mode;
	}

	public void setMode(ChannelMode mode) {
		this.mode = mode;
	}

	public boolean isLocked() {
		return locked;
	}

	/// Locks or unlocks the channel to new members. Call **only** from [ChannelRegistry#setLocked], i.e. inside the
	/// channel-name `channels.computeIfPresent(…)` span, so it serializes with join enforcement (see the `locked`
	/// field's concurrency note).
	public void setLocked(boolean locked) {
		this.locked = locked;
	}

	public String ownerId() {
		return ownerId;
	}

	public void setOwner(String ownerId) {
		this.ownerId = ownerId;
	}

	/// Adds a member, allocating its stream index and publishing session + index as ONE map entry — so no reader
	/// can ever observe a member without its identifier. Call ONLY from [ChannelRegistry#joinOrCreate]'s bin-locked
	/// remapping (which also holds this channel's monitor): same-channel add/remove are serialized by that bin
	/// lock, which makes the idempotency check-then-put race-free (a re-add keeps the existing index).
	public void add(ClientSession session) {
		if (members.containsKey(session.id())) {
			return;   // idempotent — the member keeps the index it already has
		}
		members.put(session.id(), new Member(session, allocateStreamIndex()));
	}

	public void remove(String sessionId) {
		Member removed = members.remove(sessionId);   // retires the member AND its index in one atomic step
		synchronized (this) {
			// Clear the floor under the SAME monitor as tryAcquireFloor/preemptFloorIfIdle/releaseIfExpired, so a
			// leaver's floor release is serialized with a concurrent grant's holder-swap-and-stamp and can't leave
			// the holder reference and the acquire/activity marks disagreeing.
			if (sessionId.equals(floorHolder)) {
				floorHolder = null;
			}
			// Scrub the mute UNDER the monitor too, so a leave can't race a concurrent owner mute
			// (setMuted / setMutedForAllExcept run under this same monitor): whichever runs second wins, and since
			// members.remove above precedes this block, a mute handler that ran first sees the member gone and skips
			// it while one that runs after us re-checks membership under the monitor — so no muted-id ever outlives
			// its member (a leak that would otherwise linger for the channel's lifetime, session ids being unique).
			mutedMembers.remove(sessionId);
			if (removed != null) {
				freeStreamIndex(removed.streamIndex());   // return the slot to the pool (reentrant — also synchronized)
			}
		}
	}

	public boolean isEmpty() {
		return members.isEmpty();
	}

	public int size() {
		return members.size();
	}

	public Option<ClientSession> member(String sessionId) {
		return Option.of(members.get(sessionId)).map(Member::session);
	}

	/// Any current member, used to elect a new owner after the previous one leaves;
	/// [io.github.ashr123.option.None] when the channel has no members.
	public Option<String> anyMember() {
		return Option.of(members.keySet().stream().findAny());
	}

	/// The stream index of member `sessionId`, or [NoneInt] for a non-member. `0` is a VALID index (the range is
	/// 0..254), so a missing session must NOT be treated as index 0 — that would alias its frames onto whoever holds
	/// index 0 and misroute audio. Because the index lives INSIDE the member entry, this is one atomic read: present
	/// member -> its index, absent -> [NoneInt] — no in-between. A caller acting on a known-present member uses
	/// [#requireStreamIndex]; the audio fan-out matches [NoneInt] to DROP a frame whose sender just left (a leave
	/// racing an in-flight frame), rather than stamp a bogus index.
	public OptionInt streamIndexOf(String sessionId) {
		return members.get(sessionId) instanceof Member(ClientSession _, int index) ?
				new SomeInt(index) :
				NoneInt.INSTANCE;
	}

	/// The stream index of a member that MUST have one: every current member is assigned one in [#add], so a
	/// missing index is an invariant breach, not an expected outcome. Fails fast rather than aliasing onto a valid
	/// index. Used to build the roster / `MemberJoined`, where the member is known-present.
	public int requireStreamIndex(String sessionId) {
		return switch (streamIndexOf(sessionId)) {
			case SomeInt(int index) -> index;
			case NoneInt _ -> throw new IllegalStateException("no stream index for active member '" + sessionId + "'");
		};
	}

	/// Whether the channel is at capacity — one stream index per member, and the space is 0..254, so a channel
	/// holds at most [#STREAM_INDEX_RANGE] members. [ChannelRegistry#joinOrCreate] refuses a newcomer here rather
	/// than letting [#assignStreamIndex] run out of indices.
	public boolean isFull() {
		return members.size() >= STREAM_INDEX_RANGE;
	}

	private synchronized int allocateStreamIndex() {
		for (int probe = 0; probe < STREAM_INDEX_RANGE; probe++) {
			int candidate = rotation;
			rotation = (rotation + 1) % STREAM_INDEX_RANGE;
			if (!indexInUse[candidate]) {
				indexInUse[candidate] = true;
				return candidate;
			}
		}
		// Unreachable: ChannelRegistry.joinOrCreate refuses a join once the channel isFull(), so add() is never
		// called without a free index. Fail loud rather than silently reuse index 0 (which would alias this
		// member's frames onto index 0's owner) if that invariant is ever broken.
		throw new IllegalStateException("stream-index space exhausted for channel '" + name + "' despite the membership cap");
	}

	private synchronized void freeStreamIndex(int index) {
		indexInUse[index] = false;
	}

	public List<MemberInfo> memberInfos() {
		// Complete by construction: every Member carries its own index, so even a lock-free point-in-time snapshot
		// (the in-place re-join re-snapshot) never meets a member without one — nothing to skip, nothing to invent.
		return members.values().stream()
				.map(member -> new MemberInfo(
						member.session().id(),
						member.session().displayName(),
						member.streamIndex(),
						isMuted(member.session().id())
				))
				.toList();
	}

	/// Whether `sessionId` is currently owner-muted (lock-free — read on the per-frame audio fan-out path).
	public boolean isMuted(String sessionId) {
		return mutedMembers.contains(sessionId);
	}

	/// Sets one member's mute state; returns whether it actually changed (so the caller only broadcasts a real
	/// transition). Call under this channel's monitor when the change must be atomic with the `MemberMuted`
	/// broadcast and any floor release.
	public boolean setMuted(String sessionId, boolean muted) {
		return muted ? mutedMembers.add(sessionId) : mutedMembers.remove(sessionId);
	}

	/// Mutes or unmutes every current member EXCEPT `exceptId` (the owner, who is never muted), returning the ids
	/// whose state actually changed so the caller broadcasts one `MemberMuted` per real transition. Call under the
	/// monitor.
	public List<String> setMutedForAllExcept(String exceptId, boolean muted) {
		return members.keySet().stream()
				.filter(id -> !id.equals(exceptId) && setMuted(id, muted))
				.toList();
	}

	/// Applies an action to every member except the one with `excludeSessionId`.
	public void forEachOther(String excludeSessionId, Consumer<? super ClientSession> action) {
		members.values().stream()
				.map(Member::session)
				.filter(session -> !session.id().equals(excludeSessionId))
				.forEach(action);
	}

	/// Applies an action to **every** member (including any current floor holder) — used to broadcast a floor
	/// release/reset to the whole channel.
	public void forEach(Consumer<? super ClientSession> action) {
		members.values().stream()
				.map(Member::session)
				.forEach(action);
	}

	/// Attempts to acquire the talk floor, stamping the acquire + activity marks **atomically** with the holder
	/// swap (under the monitor) so a concurrent idle-preempt can never observe a stale, idle mark for a holder
	/// that has just acquired. Full-duplex channels have no floor and always grant (no holder, no marks).
	public synchronized boolean tryAcquireFloor(String sessionId, Instant now) {
		if (mode == ChannelMode.FULL_DUPLEX) {
			return true;
		}
		if (floorHolder == null) {   // free? take it — atomic since the whole method holds the monitor
			floorHolder = sessionId;
			floorAcquiredAt = now;
			floorActivityAt = now;
			return true;
		}
		return false;
	}

	/// Releases the floor if held by `sessionId`; returns whether a release actually happened. Synchronized so
	/// the holder check and the clear are one atomic step (callers also already hold the monitor — it is reentrant).
	public synchronized boolean releaseFloor(String sessionId) {
		if (mode == ChannelMode.FULL_DUPLEX || !sessionId.equals(floorHolder)) {
			return false;
		}
		floorHolder = null;
		return true;
	}

	/// Unconditionally frees the floor (used when the mode changes). The single volatile write is itself atomic;
	/// callers make it ordered with the other floor transitions by holding the monitor across mutate-and-notify.
	public void clearFloor() {
		floorHolder = null;
	}

	/// Whether `sessionId` may currently transmit (always true in full-duplex mode).
	public boolean holdsFloor(String sessionId) {
		return mode == ChannelMode.FULL_DUPLEX || sessionId.equals(floorHolder);
	}

	public Option<String> floorHolder() {
		return Option.of(floorHolder);
	}

	/// Refreshes the activity mark — call when the current holder transmits a frame, so idle auto-release
	/// measures silence from the last frame, not from acquisition. Synchronized so it can't interleave between
	/// a preempt's idle check and its swap.
	public synchronized void markFloorActivity(Instant now) {
		floorActivityAt = now;
	}

	/// When the floor was acquired — the basis for the max-hold cap.
	public Instant floorAcquiredAt() {
		return floorAcquiredAt;
	}

	/// When the holder most recently transmitted — the basis for idle auto-release.
	public Instant floorActivityAt() {
		return floorActivityAt;
	}

	/// Atomically reassigns the floor from `expectedHolder` to `newHolder` for an idle auto-release, stamping
	/// the new holder's acquire + activity marks **in the same step** as the swap. It succeeds only if
	/// `expectedHolder` still holds the floor AND its last activity is at or before `idleBefore` (so a holder
	/// that just refreshed — sent a frame, or was just granted — is not preempted). Doing the idle check, the
	/// swap, and the re-stamp under one monitor closes the window where a freshly-granted holder still shows the
	/// previous holder's stale (idle) mark and could be double-preempted.
	public synchronized boolean preemptFloorIfIdle(String expectedHolder, String newHolder, Instant now, Instant idleBefore) {
		// expectedHolder is the non-null current-holder id the caller observed; only swap if it still holds.
		if (floorActivityAt.isAfter(idleBefore) || !expectedHolder.equals(floorHolder)) {
			return false;
		}
		floorHolder = newHolder;
		floorAcquiredAt = now;
		floorActivityAt = now;
		return true;
	}

	/// Force-releases the floor if a holder has held it since at or before `acquiredAtOrBefore` (the max-hold
	/// cutoff, i.e. `now − cap`), and returns the released holder's id; returns `null` if there is no holder, the
	/// channel is full-duplex, or the hold has not yet reached the cap.
	/// Synchronized so the acquire-time read and the clear can't interleave with a concurrent acquire's stamp.
	/// Unlike idle auto-release this is a pure hold-time cap, so it bounds **any** holder — including a WebRTC
	/// member whose media never reaches the server.
	public synchronized String releaseIfExpired(Instant acquiredAtOrBefore) {
		String holder = floorHolder;
		if (mode == ChannelMode.FULL_DUPLEX || holder == null || floorAcquiredAt.isAfter(acquiredAtOrBefore)) {
			return null;
		}
		floorHolder = null;
		return holder;
	}

	/// A channel member: the session plus its per-channel stream index — the compact `uint8` identity the server
	/// stamps on the member's relayed audio frames so receivers can demultiplex and decode per talker (a session
	/// id is a ~36-char UUID, far too big to carry on every 20 ms frame). Held as one immutable record so the
	/// member and its identifier are inseparable: there is no state in which a member exists without an index.
	private record Member(ClientSession session, int streamIndex) {
	}
}
