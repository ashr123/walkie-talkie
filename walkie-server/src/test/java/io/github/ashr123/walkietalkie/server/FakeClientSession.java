package io.github.ashr123.walkietalkie.server;

import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.server.session.Transport;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// In-memory [ClientSession] for unit tests; records everything sent to it.
public final class FakeClientSession implements ClientSession {

	/// Decodes the pre-serialized form a broadcast delivers back into a typed message — so this test double, not
	/// production, is where a sent [ServerMessage] is reconstructed. Round-trips the same simple protocol records
	/// the real Jackson bean does, so no configuration is needed.
	private static final JsonMapper JSON = JsonMapper.shared();

	public final Collection<ServerMessage> sent = new CopyOnWriteArrayList<>();
	public final List<byte[]> audio = new CopyOnWriteArrayList<>();
	private final String id;
	private final Transport transport;
	private String displayName;
	private String channelName;
	private String handshakeChannel;   // the routing channel a test pins via setHandshakeChannel (null by default)

	public FakeClientSession(String id, Transport transport, String displayName) {
		this.id = id;
		this.transport = transport;
		this.displayName = displayName;
	}

	/// Test hook: set the channel this fake was "routed to" at the handshake (see [ClientSession#handshakeChannel]),
	/// so channel-affinity behaviour can be exercised without a real WebSocket handshake.
	public void setHandshakeChannel(String handshakeChannel) {
		this.handshakeChannel = handshakeChannel;
	}

	@Override
	public String handshakeChannel() {
		return handshakeChannel;
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
	public void sendEncoded(String encoded) {
		// All control now arrives pre-serialized (via MessageBroadcaster's toOne/toAll/toOthers); decode it back to
		// a typed message so tests assert on received ServerMessages. This test double, not production, is the only
		// place a sent message is reconstructed.
		sent.add(JSON.readValue(encoded, ServerMessage.class));
	}

	@Override
	public void sendAudio(byte[] audioFrame) {
		audio.add(audioFrame);
	}
}
