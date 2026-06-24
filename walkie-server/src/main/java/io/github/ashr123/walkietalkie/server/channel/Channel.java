package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// A single conversation room. Membership and the talk floor are tracked with concurrent primitives
/// so that connections handled on different (virtual) threads can join, leave and transmit safely.
public final class Channel {

	private final String name;
	private final ChannelMode mode;
	private final Map<String, ClientSession> members = new ConcurrentHashMap<>();

	/// The session id currently holding the floor, or `null` when the floor is free.
	private final AtomicReference<String> floorHolder = new AtomicReference<>();

	public Channel(String name, ChannelMode mode) {
		this.name = name;
		this.mode = mode;
	}

	public String name() {
		return name;
	}

	public ChannelMode mode() {
		return mode;
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

	public Optional<ClientSession> member(String sessionId) {
		return Optional.ofNullable(members.get(sessionId));
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

	/// Whether `sessionId` may currently transmit (always true in full-duplex mode).
	public boolean holdsFloor(String sessionId) {
		return mode == ChannelMode.FULL_DUPLEX || sessionId.equals(floorHolder.get());
	}

	public Optional<String> floorHolder() {
		return Optional.ofNullable(floorHolder.get());
	}
}
