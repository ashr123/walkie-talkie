package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;

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

	private final String name;
	private final Map<String, ClientSession> members = new ConcurrentHashMap<>();

	/// The session id currently holding the floor, or `null` when the floor is free.
	private final AtomicReference<String> floorHolder = new AtomicReference<>();

	private volatile ChannelMode mode;
	private volatile String ownerId;

	public Channel(String name, ChannelMode mode, String ownerId) {
		this.name = name;
		this.mode = mode;
		this.ownerId = ownerId;
	}

	public String name() {
		return name;
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
	}

	public void remove(String sessionId) {
		members.remove(sessionId);
		floorHolder.compareAndSet(sessionId, null);
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

	public List<MemberInfo> memberInfos() {
		return members.values().stream().map(ClientSession::toMemberInfo).toList();
	}

	/// Applies an action to every member except the one with `excludeSessionId`.
	public void forEachOther(String excludeSessionId, Consumer<ClientSession> action) {
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
}
