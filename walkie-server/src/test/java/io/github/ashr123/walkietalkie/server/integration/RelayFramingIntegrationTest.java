package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// End-to-end relay framing over real sockets: a receiver gets each frame prefixed with the sender's
/// per-channel stream index. (The receiver-side per-sender decode/mix is verified manually — only the
/// server framing is asserted here.)
class RelayFramingIntegrationTest extends WebSocketIntegrationTestSupport {

	@Test
	void aRecipientReceivesAudioPrefixedWithTheSendersStreamIndex() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login());
		     WebSocketSession sb = connect(AUDIO, b, login())) {
			send(sa, new ClientMessage.Join("fd-sid", ChannelMode.FULL_DUPLEX, "Alice", null));
			ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("fd-sid", ChannelMode.FULL_DUPLEX, "Bob", null));
			ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);

			// Bob learns Alice's stream index from his snapshot.
			int aliceSid = joinedB.members().stream()
					.filter(member -> member.id().equals(joinedA.selfId()))
					.findFirst().orElseThrow().streamId();

			byte[] frame = "fd-frame".getBytes(StandardCharsets.UTF_8);
			sendBinary(sa, frame);
			byte[] received = b.audio.poll(5, TimeUnit.SECONDS);
			assertNotNull(received, "Bob should receive Alice's audio");
			assertEquals(aliceSid, received[0] & 0xFF, "the frame is prefixed with Alice's stream index");
			assertArrayEquals(frame, Arrays.copyOfRange(received, 1, received.length), "the body is the original frame");
		}
	}

	@Test
	void aJoinWithTheCanonicalJsonShapeIsAccepted() throws Exception {
		// Pin the exact wire JSON a client sends: it must deserialize cleanly and produce a Joined snapshot,
		// not fail with BAD_MESSAGE.
		CollectingHandler a = new CollectingHandler();
		try (WebSocketSession sa = connect(AUDIO, a, login())) {
			//language=JSON
			sendRaw(sa, """
					{"type":"join","channel":"lobby","mode":"FULL_DUPLEX","displayName":"Alice","keyCheck":null}""");
			assertEquals("lobby", awaitType(a.messages, ServerMessage.Joined.class).channel());
		}
	}
}
