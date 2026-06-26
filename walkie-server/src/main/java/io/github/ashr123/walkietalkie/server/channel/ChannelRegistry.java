package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/// Owns the set of live channels and handles atomic create/join/leave with empty-channel cleanup.
@Component
public class ChannelRegistry {

	private final Map<String, Channel> channels = new ConcurrentHashMap<>();

	/// Returns the named channel after adding `session`, creating it (owned by `session`, with `mode` and the
	/// joiner's `keyCheck`) when absent. An existing channel keeps its own mode and owner — the joiner adopts
	/// them — but the joiner's `keyCheck` must **match** the channel's, or the join is refused: the member is
	/// not added and this returns `null` (the caller reports a passphrase mismatch). The whole check-and-add
	/// happens inside the atomic map update, so it cannot race with a concurrent create or [#leave].
	public Channel joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session) {
		return joinOrCreate(name, mode, keyCheck, session, session.id());
	}

	/// As [#joinOrCreate(String, ChannelMode, String, ClientSession)], but stamps a newly-created channel with
	/// an explicit `ownerId` instead of the joiner's session id — used to give the server-managed "global"
	/// channel a sentinel owner that no participant can match. An existing channel keeps its own owner.
	public Channel joinOrCreate(String name, ChannelMode mode, String keyCheck, ClientSession session, String ownerId) {
		AtomicReference<Channel> joined = new AtomicReference<>();
		channels.compute(name, (key, existing) -> {
			Channel channel = existing == null ? new Channel(key, mode, ownerId, keyCheck) : existing;
			if (Objects.equals(channel.keyCheck(), keyCheck)) {
				channel.add(session);
				joined.set(channel);
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
}
