package io.github.ashr123.walkietalkie.server;

import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// In-memory [ClientSession] for unit tests; records everything sent to it.
public final class FakeClientSession implements ClientSession {

	public final List<ServerMessage> sent = new CopyOnWriteArrayList<>();
	public final List<byte[]> audio = new CopyOnWriteArrayList<>();
	private final String id;
	private final Transport transport;
	private String displayName;
	private String channelName;

	public FakeClientSession(String id, Transport transport, String displayName) {
		this.id = id;
		this.transport = transport;
		this.displayName = displayName;
	}

	@Override
	public String id() {
		return id;
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
	public void joinedChannel(String channel) {
		this.channelName = channel;
	}

	@Override
	public void leftChannel() {
		this.channelName = null;
	}

	@Override
	public boolean supportsAudioRelay() {
		return transport == Transport.AUDIO_RELAY;
	}

	@Override
	public void send(ServerMessage message) {
		sent.add(message);
	}

	@Override
	public void sendAudio(byte[] audioFrame) {
		audio.add(audioFrame);
	}
}
