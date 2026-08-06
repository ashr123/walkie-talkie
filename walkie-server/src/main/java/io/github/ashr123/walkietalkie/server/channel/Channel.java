package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.NoneInt;
import io.github.ashr123.option.Option;
import io.github.ashr123.option.OptionInt;
import io.github.ashr123.option.SomeInt;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.server.transport.ConnectionService;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.JoinRequestInfo;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;

import java.time.Instant;
import java.util.*;
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
	/// Stream-index pool for [#allocateStreamIndex]/[#freeStreamIndex] (both under the channel monitor): a monotonic rotating
	/// cursor that skips live indices avoids reusing a just-freed index until it has cycled the whole range —
	/// quarantining recycled indices so a new talker can't inherit a departed member's still-in-flight frames.
	private final boolean[] indexInUse = new boolean[STREAM_INDEX_RANGE];
	/// Session ids the owner has muted. Their relayed audio is dropped in `ConnectionService.onAudio` before
	/// fan-out, so a mute is enforced by the server rather than trusted to the client. A concurrent set so the
	/// per-frame [#isMuted] read is lock-free; the mute/unmute mutations run under this channel's monitor when they
	/// must be atomic with the `MuteStatus` snapshot broadcast and with freeing the floor of a member being muted —
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
	// --- floor queue (push-to-talk "raise hand", owner-toggleable) --------------------------------
	// See docs/CLIENT_PROTOCOL.md §3b. When enabled, a member that presses talk while the floor is busy is ENQUEUED
	// (FIFO) instead of denied; when the floor frees, it is RESERVED to the head for a bounded claim window
	// (the head must claim by acquiring, else the reservation-expiry sweep drops it and offers the floor to the
	// next head). Default off — behaviour then matches the pre-queue model (busy floor => acquire fails).
	//
	// Serialization: `floorQueue` is guarded by THIS monitor — every read/mutation runs under `synchronized
	// (channel)`, exactly like the floorHolder transitions it interleaves with (so an enqueue can't race a
	// grant/release into an inconsistent queue+holder pair). The reserved member is the head WHENEVER the floor
	// is free (`floorHolder == null`): the server reserves the head the instant the floor frees, so there is no
	// "free, queue non-empty, nobody reserved" state — hence no stored reserved-id.
	private volatile boolean floorQueueEnabled;
	/// Whether every member that JOINS from now on is muted as it is added ([#add]) — the owner's standing
	/// counterpart to a one-shot [#setMutedForAllExcept]. Written under this channel's monitor by the toggle
	/// handler and read under it at the add; `volatile` so the two lock-free [ServerMessage.Joined] builders see the
	/// latest value. Toggling it NEVER changes an existing member's mute, in either direction: arming the rule must
	/// not cut off whoever is mid-sentence, and disarming it must not undo the owner's deliberate mutes.
	private volatile boolean muteNewMembers;
	private final SequencedSet<String> floorQueue = new LinkedHashSet<>();
	// When the current head's reservation (claim window) started — the basis for the reservation-expiry sweep;
	// EPOCH when nobody is reserved. Stamped under the monitor by the reserve/acquire paths, read (under the
	// monitor) by the sweep; volatile for cross-thread visibility of the last write.
	private volatile Instant floorReservedAt = Instant.EPOCH;
	// --- owner-approved join requests (a LOCKED channel PARKS newcomers instead of refusing them) --------
	// A newcomer that hits a locked channel is parked here for the owner to admit or deny, rather than refused
	// outright (see ChannelRegistry#joinOrCreate). The owner's decision is a one-shot GRANT which the knocker's
	// OWN re-`Join` consumes ([#consumeGrant]) — the server never moves a session into the channel itself,
	// because admitting a knocker that is currently in another channel would mean calling a ChannelRegistry
	// mutate (leave) from inside another one's remapping function, which `ConcurrentHashMap` forbids.
	//
	// Serialization: `joinRequests` is guarded by THIS monitor — every read/mutation runs under
	// `synchronized (channel)`, exactly like `floorQueue`.
	//
	// INVARIANT: a request exists only while the channel is LOCKED. Knocks happen only in the locked branch, and
	// unlocking grants every parked request inside the same bin-locked step, so an unlocked channel is always
	// request-free. Nothing here is time-driven, so the floor sweep is not involved at all: a request lives until
	// the owner decides, the owner unlocks, the knocker withdraws or disconnects, or the channel is dropped.
	private final SequencedMap<String, JoinRequest> joinRequests = new LinkedHashMap<>();
	/// How many newcomers may be parked at once (`walkie.max-join-requests`), captured at creation exactly like
	/// [#floorQueueEnabled]. A DoS bound rather than a tuning knob: every change re-sends the owner a full
	/// snapshot, so an unbounded list would turn knock/withdraw churn into unbounded owner traffic. `0` disables
	/// parking, so a locked channel refuses newcomers outright — the behaviour before this feature existed.
	private final int maxJoinRequests;

	/// The server-wide settings a **newly created** channel adopts; an existing channel keeps its own state (like
	/// its mode and owner). Grouped into one value so [ChannelRegistry#joinOrCreate] carries a single "how to seed
	/// a fresh channel" argument rather than a growing tail of unrelated-looking flags.
	public record Defaults(boolean floorQueueEnabled, int maxJoinRequests) {

		/// Both features off: no push-to-talk floor queue, and a locked channel refuses newcomers outright instead
		/// of parking them for approval — i.e. the behaviour before either feature existed.
		public static final Defaults NONE = new Defaults(false, 0);
	}

	public Channel(String name, ChannelMode mode, String ownerId, String keyCheck, Defaults defaults) {
		this.name = name;
		this.mode = mode;
		this.ownerId = ownerId;
		this.keyCheck = keyCheck;
		// Seed the queue on/off state from the server-wide default a new channel adopts (walkie.floor-queue-default);
		// the owner can toggle it per channel afterwards. Set only at creation — an existing channel keeps its own.
		this.floorQueueEnabled = defaults.floorQueueEnabled();
		// Server-wide and fixed for the process (walkie.* properties are not refresh-scoped), so capturing it at
		// creation can't leave a channel enforcing a stale cap.
		this.maxJoinRequests = defaults.maxJoinRequests();
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
		// Atomic put-if-absent on the members map's own bin lock (one lookup, not containsKey + put). The value
		// factory runs ONLY on the absent path, so an idempotent re-add keeps the existing Member — and its stream
		// index — and never allocates a fresh index. allocateStreamIndex mutates the index pool under the caller's
		// channel monitor (ChannelRegistry.joinOrCreate holds it), so it is safe inside the compute lambda.
		boolean[] fresh = {false};
		members.computeIfAbsent(session.id(), _ -> {
			fresh[0] = true;
			return new Member(session, allocateStreamIndex());
		});
		// The owner's standing "mute new members" rule, applied HERE so it is inseparable from the add — exactly as
		// the stream index is. That placement is what lets the joiner learn of its own mute with no extra message:
		// [#memberInfos] derives each entry's `muted` bit, and the caller reads the roster for the joiner's `Joined`
		// later in this same monitor span.
		//
		// Only on the FRESH path, and never the owner. Re-muting an existing member would undo a deliberate unmute
		// on every idempotent re-add, and muting the owner would lock the channel's only moderator out for good —
		// an owner cannot unmute itself ([#setMuted] rejects the owner as a target) and nothing else would.
		if (fresh[0] && muteNewMembers && !session.id().equals(ownerId)) {
			mutedMembers.add(session.id());
		}
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
			// Drop a leaver from the floor queue too (under the same monitor), so a disconnect/switch can't leave a
			// ghost id waiting for a turn it can never take. If the leaver was the RESERVED HEAD (floor free and it
			// is first in line — computed AFTER the floorHolder-clear above, so a departing HOLDER, now
			// floorHolder==null but not in the queue, is never misjudged, and BEFORE the removal so the head is
			// still identifiable), end its claim window so the next head gets a fresh one from the caller's
			// reserveHead. A mid-queue leaver does NOT reset (it keeps the running head's window). The caller
			// (ConnectionService.handleLeave) then re-reserves + re-broadcasts FloorStatus after this removal.
			boolean wasReservedHead = isHeadOfferedFloor(sessionId);
			floorQueue.remove(sessionId);
			if (wasReservedHead) {
				floorReservedAt = Instant.EPOCH;
			}
			// Scrub the mute UNDER the monitor too, so a leave can't race a concurrent owner mute
			// (setMuted / setMutedForAllExcept run under this same monitor): whichever runs second wins, and since
			// members.remove above precedes this block, a mute handler that ran first sees the member gone and skips
			// it while one that runs after us re-checks membership under the monitor — so no muted-id ever outlives
			// its member (a leak that would otherwise linger for the channel's lifetime, session ids being unique).
			mutedMembers.remove(sessionId);
			if (removed != null) {
				freeStreamIndex(removed.streamIndex());   // return the slot to the pool (under this monitor)
			}
		}
	}

	public boolean isEmpty() {
		return members.isEmpty();
	}

	public int size() {
		return members.size();
	}

	/// The transport every member of this channel uses — absent only for a memberless channel, which the registry
	/// never publishes (a channel is dropped the instant its last member leaves).
	///
	/// DERIVED from a member rather than stored, so it cannot drift from who is actually here. The invariant is
	/// "every member of a channel shares one transport" (enforced in [ChannelRegistry#joinOrCreate]); a field would
	/// be a second answer to a question the roster already answers, and would have to be maintained on every add
	/// and remove. Any member will do, precisely because they agree.
	///
	/// Why the invariant exists: relay audio and WebRTC media never meet. The fan-out skips a signaling member and
	/// a signaling sender's frames are dropped on arrival, so a mixed channel is a full roster with working floor
	/// control and NO audio path in either direction — it looks like it works.
	///
	/// Concurrency: read inside the registry's `channels.compute(name, …)` span, which is where every add and
	/// remove for this name runs, so it cannot change under the join being validated against it.
	public Option<Transport> firstMemberTransport() {
		// Option.of(Optional) + map — the same idiom as member() and anyMember() just below.
		return Option.of(members.values().stream().findAny()).map(member -> member.session().transport());
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
		return members.get(sessionId) instanceof Member(_, int index) ?
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
	/// than letting [#allocateStreamIndex] run out of indices.
	public boolean isFull() {
		return members.size() >= STREAM_INDEX_RANGE;
	}

	/// Takes a fresh stream index from the pool. Call ONLY under the channel monitor: `indexInUse`/`rotation` are
	/// guarded by the monitor (the sole path joinOrCreate -> add holds it), not by this method itself.
	private int allocateStreamIndex() {
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

	/// Returns a stream index to the pool. Call ONLY under the channel monitor (see [#allocateStreamIndex]).
	private void freeStreamIndex(int index) {
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

	/// A point-in-time copy of the owner-muted ids — the `muted` set carried by `ServerMessage.MuteStatus`.
	///
	/// Deliberately unordered, and typed as a `Set` to say so: unlike [#floorQueue], whose order IS its meaning
	/// (FIFO position), nothing about a mute has an order. Imposing one here would be the server doing a consumer's
	/// job — a client that DISPLAYS several ids is the thing that knows how it wants them arranged (both reference
	/// clients sort by display name, to match their rosters), and no client may depend on what arrives.
	public synchronized Set<String> mutedMembers() {
		return Set.copyOf(mutedMembers);
	}

	/// Sets one member's mute state; returns whether it actually changed (so the caller only broadcasts a real
	/// transition). Call under this channel's monitor when the change must be atomic with the `MuteStatus` snapshot
	/// broadcast and any floor release.
	public boolean setMuted(String sessionId, boolean muted) {
		return muted ? mutedMembers.add(sessionId) : mutedMembers.remove(sessionId);
	}

	/// Mutes or unmutes every current member EXCEPT `exceptId` (the owner, who is never muted), returning the ids
	/// whose state actually changed. The caller needs them to detach each newly muted member from the floor, and to
	/// tell a no-op apart from a real change — the broadcast itself is ONE [#mutedMembers] snapshot either way, not
	/// one message per id. Call under the monitor.
	public List<String> setMutedForAllExcept(String exceptId, boolean muted) {
		return members.keySet().stream()
				.filter(id -> !id.equals(exceptId) && setMuted(id, muted))
				.toList();
	}

	/// Applies an action to every member except the one with `excludeSessionId`.
	public void forEachOther(String excludeSessionId, Consumer<? super ClientSession> action) {
		// Plain loop over the members view (weakly-consistent, same as a stream) — no per-frame Stream pipeline,
		// map/filter stage objects, spliterator, or capturing filter lambda on the audio fan-out hot path.
		for (Member member : members.values()) {
			ClientSession session = member.session();
			if (!session.id().equals(excludeSessionId)) {
				action.accept(session);
			}
		}
	}

	/// Applies an action to **every** member (including any current floor holder) — used to broadcast a floor
	/// release/reset to the whole channel.
	public void forEach(Consumer<? super ClientSession> action) {
		for (Member member : members.values()) {
			action.accept(member.session());
		}
	}

	/// Attempts to acquire the talk floor, stamping the acquire + activity marks **atomically** with the holder
	/// swap (under the monitor) so a concurrent idle-preempt can never observe a stale, idle mark for a holder
	/// that has just acquired. Full-duplex channels have no floor and always grant (no holder, no marks).
	public synchronized boolean tryAcquireFloor(String sessionId, Instant now) {
		if (mode == ChannelMode.FULL_DUPLEX) {
			return true;
		}
		// Grant only if the floor is free AND either the queue is empty (a plain grab) or this caller is the
		// reserved head claiming its turn. A non-head can NOT jump a reserved floor — it must enqueue instead —
		// so the FIFO order the queue promises is honoured. Whole method holds the monitor, so the check-and-set
		// (and the queue read) are atomic w.r.t. concurrent enqueue/release/reserve.
		if (canAcquireFreeFloor(sessionId)) {
			floorHolder = sessionId;
			floorQueue.remove(sessionId);       // the claimant leaves the queue (no-op for a plain grab)
			floorAcquiredAt = now;
			floorActivityAt = now;
			floorReservedAt = Instant.EPOCH;    // acquiring ends any reservation
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

	/// Unconditionally resets ALL floor state — the holder, the waiting queue AND any running reservation — used
	/// when the mode changes (a mode switch supersedes every talk indicator and starts a fresh floor). Synchronized
	/// like the other queue mutators so the `floorQueue` clear runs under this monitor; callers make it ordered with
	/// the other floor transitions and its broadcast by holding the monitor across mutate-and-notify.
	public synchronized void clearFloor() {
		floorHolder = null;
		floorQueue.clear();
		floorReservedAt = Instant.EPOCH;
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

	// --- floor queue -----------------------------------------------------------------------------------

	/// The head of the waiting queue; call only under the monitor with a non-empty queue.
	private String headOfQueue() {
		return floorQueue.getFirst();
	}

	/// Whether the floor is free and the queue head is therefore the member currently offered the next turn.
	/// Call only under the monitor.
	private boolean hasHeadOfferedFloor() {
		return floorHolder == null && !isFloorQueueEmpty();
	}

	/// Whether `sessionId` is that currently-offered queue head; call only under the monitor.
	private boolean isHeadOfferedFloor(String sessionId) {
		return hasHeadOfferedFloor() && sessionId.equals(headOfQueue());
	}

	/// Whether `sessionId` may take the floor right now: a plain grab on a free floor with no queue, or the
	/// currently-offered head claiming its reserved turn. Call only under the monitor.
	private boolean canAcquireFreeFloor(String sessionId) {
		return floorHolder == null && (isFloorQueueEmpty() || sessionId.equals(headOfQueue()));
	}

	public boolean isFloorQueueEnabled() {
		return floorQueueEnabled;
	}

	public boolean mutesNewMembers() {
		return muteNewMembers;
	}

	/// Arms or disarms the standing "mute every arrival" rule. Deliberately has NO side effect on the current
	/// members — unlike [#setFloorQueueEnabled], which clears the queue when disabled — because this rule is about
	/// arrivals only: see the field's note. Under the monitor, so it serializes with the [#add] that reads it.
	public synchronized void setMuteNewMembers(boolean enabled) {
		muteNewMembers = enabled;
	}

	/// Turns the owner-controlled floor queue on or off. Disabling also **clears** the queue and any reservation
	/// (there is nowhere to wait), so the caller should snapshot [#floorQueue] first if it needs to notify the
	/// dropped members. Call under the monitor so the flip, the clear and the broadcast are one atomic transition
	/// (mirrors the mode/lock/passphrase discipline).
	///
	/// Returns whether the FLOOR state changed — that is, whether this call actually dropped waiters or ended a
	/// running claim window — so the caller can re-broadcast `FloorStatus` only when there is something new to say.
	/// The flag itself is not the floor: enabling a queue changes who MAY wait, not who is waiting, and disabling an
	/// empty one changes nothing at all. Both used to fan out a snapshot that repeated the floor verbatim, which
	/// every client dutifully narrated ("Floor is free") on a toggle that had not touched the floor.
	public synchronized boolean setFloorQueueEnabled(boolean enabled) {
		floorQueueEnabled = enabled;
		if (enabled) {
			return false;
		}
		boolean floorChanged = !floorQueue.isEmpty() || !floorReservedAt.equals(Instant.EPOCH);
		floorQueue.clear();
		floorReservedAt = Instant.EPOCH;
		return floorChanged;
	}

	/// Appends `sessionId` to the tail of the floor queue — a no-op (returns `false`) if it already holds the
	/// floor or is already waiting. Returns whether it was newly enqueued. The caller enforces the preconditions
	/// (queue enabled, not full-duplex, not muted, floor busy) and re-broadcasts `FloorStatus`. Under the monitor.
	public synchronized boolean enqueueFloor(String sessionId) {
		return !sessionId.equals(floorHolder) && floorQueue.add(sessionId);
	}

	/// Removes `sessionId` from the floor queue (a member leaving the line, declining its turn, or disconnecting).
	/// Returns whether it was queued. If the removed member was the RESERVED HEAD (floor free and it was first in
	/// line), its running claim window is ended — `floorReservedAt` is reset to EPOCH — so the NEXT head gets a
	/// fresh window from the caller's [#reserveHead]. A MID-QUEUE removal must NOT reset the clock (that would
	/// unfairly restart the running head's window). Computed BEFORE the removal so the head is still identifiable.
	/// Under the monitor.
	public synchronized boolean dequeueFloor(String sessionId) {
		boolean wasReservedHead = isHeadOfferedFloor(sessionId);
		boolean removed = floorQueue.remove(sessionId);
		if (removed && wasReservedHead) {
			floorReservedAt = Instant.EPOCH;
		}
		return removed;
	}

	/// Starts the claim window for the head of the queue — the FREE -> RESERVED transition. Returns the reserved
	/// head's id, or `null` if the floor is held, the queue is empty, OR a reservation is already running.
	///
	/// IDEMPOTENT by design: the guard enforces the invariant `floorReservedAt != EPOCH` iff the current head has a
	/// running claim window, so a redundant call while a reservation runs is a no-op (returns `null`, no re-stamp).
	/// That is what lets callers invoke it UNCONDITIONALLY on any floor-freeing event — even across the monitor gap
	/// around a [ChannelRegistry] mutate — without ever moving a running reservation's clock backward or resetting
	/// it: a fresh window is stamped only when the head genuinely changed (the previous head was claimed/removed,
	/// which reset the clock to EPOCH — see [#tryAcquireFloor], [#dequeueFloor], [#remove]). Under the monitor.
	public synchronized String reserveHead(Instant now) {
		if (!hasHeadOfferedFloor() || !floorReservedAt.equals(Instant.EPOCH)) {
			return null;
		}
		floorReservedAt = now;
		return headOfQueue();
	}

	/// Idle auto-release for the queue path: frees the floor if the current holder has been silent since at or
	/// before `idleBefore`, so the floor can be offered to the queue head (the caller then calls [#reserveHead]).
	/// Returns the released holder id, or `null` if there is no holder, the hold is not yet idle, or the channel
	/// is full-duplex. The caller restricts this to a **relay** holder (WebRTC has no activity signal) before
	/// invoking — all under this monitor. (Distinct from [#preemptFloorIfIdle], which reassigns straight to a
	/// named requester in the queue-DISABLED path; here the freed floor goes to whoever is next in line.)
	public synchronized String releaseIfIdle(Instant idleBefore) {
		String holder = floorHolder;
		if (mode == ChannelMode.FULL_DUPLEX || holder == null || floorActivityAt.isAfter(idleBefore)) {
			return null;
		}
		floorHolder = null;
		return holder;
	}

	/// The reserved head whose claim window has expired (reserved since at or before `reservedAtOrBefore`), or
	/// `null` if nobody is reserved or the window has not elapsed. The caller drops it ([#dequeueFloor]) and
	/// offers the floor to the next head ([#reserveHead]). Under the monitor.
	///
	/// The `floorReservedAt == EPOCH` guard is critical: it means "no reservation is running", so this NEVER
	/// expires an UNstamped head. That makes the sweep a no-op during the brief "floor free but head not yet
	/// reserved" transient (the monitor gap in a leave/mute, before the caller's `reserveHead` runs), so the sweep
	/// cannot drop the rightful head out from under a concurrent re-reservation.
	public synchronized String expiredReservationHead(Instant reservedAtOrBefore) {
		return !hasHeadOfferedFloor()
				|| floorReservedAt.equals(Instant.EPOCH)
				|| floorReservedAt.isAfter(reservedAtOrBefore)
				? null
				: headOfQueue();
	}

	/// A point-in-time copy of the waiting queue, in FIFO order (the reserved head is first when the floor is
	/// free). This is the `waiting` list carried by `FloorStatus`.
	public synchronized List<String> floorQueue() {
		return List.copyOf(floorQueue);
	}

	public synchronized boolean isFloorQueueEmpty() {
		return floorQueue.isEmpty();
	}

	/// Cheap lock-free check for the 1 Hz [ConnectionService] floor sweep: true when this channel can have NO floor
	/// work — no holder (nothing to max-hold or idle-release) and no running reservation (nothing to expire), so the
	/// sweep can skip it without opening a logging scope or taking the monitor. Reads the two volatiles directly; a
	/// racy false result (the channel turns active right after the read) is harmless — the next 1 s tick catches it.
	public boolean hasNoSweepWork() {
		return floorHolder == null && floorReservedAt.equals(Instant.EPOCH);
	}

	/// The member currently offered the floor (the head, while the floor is free), or [io.github.ashr123.option.None].
	/// Derived, not stored: reserved is exactly the head whenever `floorHolder == null` (the server reserves the
	/// head the instant the floor frees). Under the monitor.
	public synchronized Option<String> reservedHolder() {
		return Option.of(hasHeadOfferedFloor() ? headOfQueue() : null);
	}

	// --- owner-approved join requests -------------------------------------------------------------------

	/// One newcomer parked at a locked channel's door. `granted` is the owner's one-shot admission, consumed by the
	/// knocker's own re-`Join` ([#consumeGrant]) — and it is the security boundary of the whole feature: without
	/// it, any parked session could let itself into a locked channel simply by re-sending `Join`.
	///
	/// The session's display name is deliberately NOT copied here but read live in [#joinRequestInfos], so a rename
	/// while waiting can't leave the owner looking at a stale label. Its key-check isn't stored either: the re-`Join`
	/// carries a fresh one that the ordinary join path validates, so a passphrase rotation while someone waits needs
	/// no invalidation logic — it simply becomes a normal mismatch.
	private record JoinRequest(ClientSession session, boolean granted) {
	}

	/// The outcome of a [#knock]: newly parked, already waiting (an idempotent re-knock), or the list is at its cap.
	public enum KnockOutcome {
		REGISTERED,
		ALREADY_WAITING,
		LIST_FULL
	}

	/// Whether this channel parks newcomers at all (`walkie.max-join-requests > 0`). When false, a locked channel
	/// refuses them outright — the behaviour before this feature existed, kept as an operator escape hatch for
	/// "closed, don't even ask". Lock-free: the cap is final.
	public boolean acceptsJoinRequests() {
		return maxJoinRequests > 0;
	}

	/// Parks `session` for the owner's approval, at the tail of the arrival order.
	///
	/// Idempotent by design: a re-knock while already waiting reports [KnockOutcome#ALREADY_WAITING] and changes
	/// nothing — so a client that retries cannot spam the owner with snapshots, and cannot clear a grant it has
	/// already been given. Under the monitor.
	public synchronized KnockOutcome knock(ClientSession session) {
		if (joinRequests.containsKey(session.id())) {
			return KnockOutcome.ALREADY_WAITING;
		}
		if (joinRequests.size() >= maxJoinRequests) {
			return KnockOutcome.LIST_FULL;
		}
		joinRequests.put(session.id(), new JoinRequest(session, false));
		return KnockOutcome.REGISTERED;
	}

	/// Consumes the one-shot admission grant for `sessionId`: `true` — and the request is REMOVED — iff the owner
	/// had granted it. An ungranted request is left untouched and returns `false`, which is what stops a parked
	/// newcomer admitting itself by re-sending `Join`. Under the monitor.
	public synchronized boolean consumeGrant(String sessionId) {
		JoinRequest request = joinRequests.get(sessionId);
		if (request == null || !request.granted()) {
			return false;
		}
		joinRequests.remove(sessionId);
		return true;
	}

	/// Grants `sessionId`'s parked request — the owner admitting one newcomer. Returns that knocker's session so the
	/// caller can tell it to claim (re-send `Join`), or [io.github.ashr123.option.None] if it is not waiting here.
	/// Idempotent: granting an already-granted request just returns it again, and its position is kept (a
	/// `LinkedHashMap` re-`put` on an existing key does not reorder). Under the monitor.
	public synchronized Option<ClientSession> grant(String sessionId) {
		JoinRequest request = joinRequests.get(sessionId);
		if (request != null) {
			joinRequests.put(sessionId, new JoinRequest(request.session(), true));
		}
		return Option.of(request).map(JoinRequest::session);
	}

	/// Grants EVERY parked request, in arrival order, returning their sessions. Serves both "admit all" and the
	/// UNLOCK path: an unlocked channel admits anyone, so leaving people parked at an open door would be incoherent
	/// — which is what keeps the "requests exist only while locked" invariant true. Under the monitor.
	public synchronized List<ClientSession> grantAll() {
		List<ClientSession> sessions = new ArrayList<>(joinRequests.size());
		for (Map.Entry<String, JoinRequest> entry : joinRequests.entrySet()) {
			ClientSession session = entry.getValue().session();
			sessions.add(session);
			entry.setValue(new JoinRequest(session, true));   // in-place value update: no reordering, no re-put
		}
		return sessions;
	}

	/// Removes `sessionId`'s parked request — a deny, the knocker's own cancel, or the disconnect scrub — and
	/// returns the removed knocker's session so the caller can notify it (or `None` if it wasn't waiting). Works on
	/// a GRANTED request too, so the owner can still revoke an approval whose client never came back to claim it.
	/// Under the monitor.
	public synchronized Option<ClientSession> withdraw(String sessionId) {
        return Option.of(joinRequests.remove(sessionId)).map(JoinRequest::session);
	}

	/// Removes and returns every parked request — used when this channel is about to be DROPPED from the registry
	/// because its last member left. Those knockers are then cleared to join rather than left waiting forever: the
	/// lock dies with the channel, so their re-`Join` recreates it (and the first to arrive owns it). Under the
	/// monitor.
	public synchronized List<ClientSession> drainJoinRequests() {
		List<ClientSession> sessions = joinRequests.values().stream().map(JoinRequest::session).toList();
		joinRequests.clear();
		return sessions;
	}

	/// The owner's view of who is waiting, in arrival order — the payload of the owner-only `JoinRequests` snapshot.
	/// GRANTED-but-unclaimed requests are included on purpose: they are the ones whose client never came back, and
	/// the owner needs to see them to revoke them ([#withdraw]). Under the monitor.
	public synchronized List<JoinRequestInfo> joinRequestInfos() {
		return joinRequests.values().stream()
				.map(request -> new JoinRequestInfo(request.session().id(), request.session().displayName()))
				.toList();
	}

	/// When the current head's reservation started (basis for the claim-window expiry); EPOCH if nobody reserved.
	public Instant floorReservedAt() {
		return floorReservedAt;
	}

	/// A channel member: the session plus its per-channel stream index — the compact `uint8` identity the server
	/// stamps on the member's relayed audio frames so receivers can demultiplex and decode per talker (a session
	/// id is a ~36-char UUID, far too big to carry on every 20 ms frame). Held as one immutable record so the
	/// member and its identifier are inseparable: there is no state in which a member exists without an index.
	private record Member(ClientSession session, int streamIndex) {
	}
}
