package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// A single conversation room. Membership, the talk floor, the mode and the owner are tracked with
/// concurrent primitives so that connections handled on different (virtual) threads can join, leave,
/// transmit and re-configure safely. The `mode` and `ownerId` are mutable: the owner may change the
/// mode, and ownership transfers to another member when the owner leaves.
public final class Channel {

	/// Usable stream indices are 0..254; 255 (0xFF) is reserved as a future "extended id" escape.
	private static final int STREAM_INDEX_RANGE = 255;

	private final String name;
	private final Map<String, ClientSession> members = new ConcurrentHashMap<>();

	/// The session id currently holding the floor, or `null` when the floor is free.
	private final AtomicReference<String> floorHolder = new AtomicReference<>();

	private volatile ChannelMode mode;
	private volatile String ownerId;

	/// The key-check value every member must present to join (a short value derived from the E2EE
	/// passphrase, or `null` for an unencrypted channel), set by the creator. The server compares it to
	/// reject a mismatched passphrase; it is not the key and reveals nothing usable about it.
	private final String keyCheck;

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
		floorHolder.compareAndSet(sessionId, null);
		freeStreamIndex(sessionId);
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

	/// Attempts to acquire the talk floor. Full-duplex channels always grant.
	public boolean tryAcquireFloor(String sessionId) {
		return mode == ChannelMode.FULL_DUPLEX || floorHolder.compareAndSet(null, sessionId);
	}

	/// Releases the floor if held by `sessionId`; returns whether a release actually happened.
	public boolean releaseFloor(String sessionId) {
		return mode != ChannelMode.FULL_DUPLEX && floorHolder.compareAndSet(sessionId, null);
	}

	/// Unconditionally frees the floor (used when the mode changes).
	public void clearFloor() {
		floorHolder.set(null);
	}

	/// Whether `sessionId` may currently transmit (always true in full-duplex mode).
	public boolean holdsFloor(String sessionId) {
		return mode == ChannelMode.FULL_DUPLEX || sessionId.equals(floorHolder.get());
	}

	public Option<String> floorHolder() {
		return Option.of(floorHolder.get());
	}

	// --- floor hold timing (push-to-talk anti-hogging) --------------------------------------------
	// Two marks let the server bound how long one member keeps the floor WITHOUT ever inspecting audio content
	// — so the limits hold on end-to-end-encrypted channels too. `floorAcquiredAt` backs a max-hold cap
	// (continuous talk time); `floorActivityAt` (the holder's most recent relayed frame) backs idle
	// auto-release. Both are instants supplied by the caller, so Channel stays clock-free and unit-testable;
	// they start at EPOCH and are only read after a real acquire stamps them (see ConnectionService).
	private volatile Instant floorAcquiredAt = Instant.EPOCH;
	private volatile Instant floorActivityAt = Instant.EPOCH;

	/// Records that the floor was just acquired at `now`, resetting both the hold and the activity marks.
	public void markFloorAcquired(Instant now) {
		floorAcquiredAt = now;
		floorActivityAt = now;
	}

	/// Refreshes the activity mark — call when the current holder transmits a frame, so idle auto-release
	/// measures silence from the last frame, not from acquisition.
	public void markFloorActivity(Instant now) {
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

	/// Atomically reassigns the floor from `expectedHolder` to `newHolder` for an idle auto-release: it succeeds
	/// only if `expectedHolder` still holds the floor AND its last activity is at or before `idleBefore` (so a
	/// holder that just refreshed — sent a frame, or released and re-acquired — is not preempted). The idle
	/// check and the swap are one synchronized step; the activity *write* ([#markFloorActivity]) stays lock-free
	/// on the per-frame path and only moves the mark forward, so it can't manufacture a false idle.
	public synchronized boolean preemptFloorIfIdle(String expectedHolder, String newHolder, Instant idleBefore) {
		return !floorActivityAt.isAfter(idleBefore)    // the holder was active within the window — leave it alone
				&& floorHolder.compareAndSet(expectedHolder, newHolder);
	}
}
