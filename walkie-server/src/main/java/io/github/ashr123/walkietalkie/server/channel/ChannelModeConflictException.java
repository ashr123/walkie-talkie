package io.github.ashr123.walkietalkie.server.channel;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;

import java.io.Serial;

/// Thrown when a client tries to join an existing channel using a different [ChannelMode].
public class ChannelModeConflictException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public ChannelModeConflictException(String channel, ChannelMode existing, ChannelMode requested) {
		super("Channel '" + channel + "' already exists with mode " + existing
				+ "; cannot join it as " + requested);
	}
}
