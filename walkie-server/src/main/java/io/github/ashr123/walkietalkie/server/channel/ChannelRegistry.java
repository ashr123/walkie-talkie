package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.option.Option;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Owns the set of live channels and handles atomic create/join/leave with empty-channel cleanup.
@Component
public class ChannelRegistry {

	private final Map<String, Channel> channels = new ConcurrentHashMap<>();

	/// Returns the named channel, creating it with `mode` when absent, then adds `session`.
	///
	/// @throws ChannelModeConflictException if the channel already exists with a different mode
	public Channel joinOrCreate(String name, ChannelMode mode, ClientSession session) {
		Channel channel = channels.compute(name, (key, existing) -> {
			if (existing == null) {
				return new Channel(key, mode);
			}
			if (existing.mode() != mode) {
				throw new ChannelModeConflictException(key, existing.mode(), mode);
			}
			return existing;
		});
		channel.add(session);
		return channel;
	}

	public Option<Channel> find(String name) {
		return Option.of(channels.get(name));
	}

	/// Removes a member and drops the channel entirely once it becomes empty.
	public void leave(String name, String sessionId) {
		channels.computeIfPresent(name, (_, channel) -> {
			channel.remove(sessionId);
			return channel.isEmpty() ? null : channel;
		});
	}

	public int channelCount() {
		return channels.size();
	}
}
