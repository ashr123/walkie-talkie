package io.github.ashr123.walkietalkie.server.session;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/// [ClientSession] backed by a Spring [WebSocketSession]. The wrapped session is expected
/// to be a [ConcurrentWebSocketSessionDecorator] so that fan-out sends from multiple threads are
/// serialized safely.
public final class WebSocketClientSession implements ClientSession {

	private final WebSocketSession session;
	private final MessageCodec codec;
	private final Transport transport;

	// Set when the client joins a channel (from the validated Join.displayName); "" until then.
	private volatile String displayName = "";
	private volatile String channelName;
	private volatile ChannelMode channelMode;

	public WebSocketClientSession(WebSocketSession session,
	                              MessageCodec codec,
	                              Transport transport) {
		this.session = session;
		this.codec = codec;
		this.transport = transport;
	}

	private static String truncateReason(String reason) {
		// Close-frame reason phrases are limited to 123 UTF-8 bytes by the protocol.
		return reason.length() > 100 ? reason.substring(0, 100) : reason;
	}

	@Override
	public String id() {
		return session.getId();
	}

	@Override
	public Transport transport() {
		return transport;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	@Override
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	@Override
	public String channelName() {
		return channelName;
	}

	@Override
	public ChannelMode channelMode() {
		return channelMode;
	}

	@Override
	public void joinedChannel(String channel, ChannelMode mode) {
		this.channelName = channel;
		this.channelMode = mode;
	}

	@Override
	public void leftChannel() {
		this.channelName = null;
		this.channelMode = null;
	}

	@Override
	public boolean supportsAudioRelay() {
		return transport == Transport.AUDIO_RELAY;
	}

	@Override
	public void send(ServerMessage message) {
		try {
			session.sendMessage(new TextMessage(codec.encode(message)));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to send control message to " + id(), e);
		}
	}

	@Override
	public void sendAudio(byte[] audio) {
		try {
			session.sendMessage(new BinaryMessage(ByteBuffer.wrap(audio)));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to send audio to " + id(), e);
		}
	}

	@Override
	public void close(String reason) {
		try {
			session.close(CloseStatus.NORMAL.withReason(truncateReason(reason)));
		} catch (IOException _) {
			// best-effort close
		}
	}
}
