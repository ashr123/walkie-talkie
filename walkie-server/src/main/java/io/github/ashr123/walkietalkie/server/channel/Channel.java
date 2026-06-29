package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
	private final Map<String, ClientSession> members = new ConcurrentHashMap<>();

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

	/// Per-channel stream-index allocator: maps each member's session id to a uint8 routing index (0..254).
	/// The server prefixes this index onto a member's relayed audio so multi-stream receivers can demultiplex
	/// talkers. A monotonic rotating cursor that skips live indices avoids reusing a just-freed index until it
	/// has cycled the whole range — quarantining recycled indices so a new talker can't inherit a departed
	/// member's still-in-flight frames. Allocation/free are synchronized; reads go through the concurrent map.
	private final Map<String, Integer> streamIndices = new ConcurrentHashMap<>();
	private final boolean[] indexInUse = new boolean[STREAM_INDEX_RANGE];
	private int rotation;

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

	public String ownerId() {
		return ownerId;
	}

	public void setOwner(String ownerId) {
		this.ownerId = ownerId;
	}

	public void add(ClientSession session) {
		members.put(session.id(), session);
		assignStreamIndex(session.id());
	}

	public void remove(String sessionId) {
		members.remove(sessionId);
		synchronized (this) {
			// Clear the floor under the SAME monitor as tryAcquireFloor/preemptFloorIfIdle/releaseIfExpired, so a
			// leaver's floor release is serialized with a concurrent grant's holder-swap-and-stamp and can't leave
			// the holder reference and the acquire/activity marks disagreeing.
			if (sessionId.equals(floorHolder)) {
				floorHolder = null;
			}
			freeStreamIndex(sessionId);   // reentrant — also synchronized(this)
		}
	}

	public boolean isEmpty() {
		return members.isEmpty();
	}

	public int size() {
		return members.size();
	}

	public Option<ClientSession> member(String sessionId) {
		return Option.of(members.get(sessionId));
	}

	/// Any current member, used to elect a new owner after the previous one leaves;
	/// [io.github.ashr123.option.None] when the channel has no members.
	public Option<String> anyMember() {
		return Option.of(members.keySet().stream().findAny());
	}

	/// The stream index assigned to `sessionId`, or 0 if unknown (defensive; an active member always has one).
	public int streamIndexOf(String sessionId) {
		Integer index = streamIndices.get(sessionId);
		return index == null ? 0 : index;
	}

	private synchronized void assignStreamIndex(String sessionId) {
		if (streamIndices.containsKey(sessionId)) {
			return;
		}
		for (int probe = 0; probe < STREAM_INDEX_RANGE; probe++) {
			int candidate = rotation;
			rotation = (rotation + 1) % STREAM_INDEX_RANGE;
			if (!indexInUse[candidate]) {
				indexInUse[candidate] = true;
				streamIndices.put(sessionId, candidate);
				return;
			}
		}
		streamIndices.put(sessionId, 0);   // >254 concurrent members (far above any real channel) — tolerate a collision
	}

	private synchronized void freeStreamIndex(String sessionId) {
		Integer index = streamIndices.remove(sessionId);
		if (index != null) {
			indexInUse[index] = false;
		}
	}

	public List<MemberInfo> memberInfos() {
		return members.values().stream()
				.map(session -> new MemberInfo(session.id(), session.displayName(), streamIndexOf(session.id())))
				.toList();
	}

	/// Applies an action to every member except the one with `excludeSessionId`.
	public void forEachOther(String excludeSessionId, Consumer<? super ClientSession> action) {
		for (ClientSession session : members.values()) {
			if (!session.id().equals(excludeSessionId)) {
				action.accept(session);
			}
		}
	}

	/// Applies an action to **every** member (including any current floor holder) — used to broadcast a floor
	/// release/reset to the whole channel.
	public void forEach(Consumer<? super ClientSession> action) {
		members.values().forEach(action);
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
}
