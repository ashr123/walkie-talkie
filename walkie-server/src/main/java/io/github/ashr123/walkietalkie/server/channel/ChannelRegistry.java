package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/// Owns the set of live channels and handles atomic create/join/leave with empty-channel cleanup.
@Component
public class ChannelRegistry {

	private final Map<String, Channel> channels = new ConcurrentHashMap<>();

	/// Returns the named channel, creating it (owned by `session`, with `mode`) when absent, then adds
	/// `session`. An existing channel keeps its own mode and owner — the joiner adopts them, so the
	/// requested `mode` only matters when this call creates the channel. The membership add happens
	/// inside the atomic map update, so it cannot race with a concurrent [#leave] dropping the channel.
	public Channel joinOrCreate(String name, ChannelMode mode, ClientSession session) {
		return channels.compute(name, (key, existing) -> {
			Channel channel = existing != null ? existing : new Channel(key, mode, session.id());
			channel.add(session);
			return channel;
		});
	}

	public Option<Channel> find(String name) {
		return Option.of(channels.get(name));
	}

	/// Atomically removes a member, dropping the channel once empty. If the leaver owned the channel and
	/// others remain, ownership is reassigned and the new owner id is returned (so the caller can
	/// announce it); otherwise [io.github.ashr123.option.None].
	public Option<String> leave(String name, String sessionId) {
		AtomicReference<String> newOwner = new AtomicReference<>();
		channels.computeIfPresent(name, (key, channel) -> {
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
