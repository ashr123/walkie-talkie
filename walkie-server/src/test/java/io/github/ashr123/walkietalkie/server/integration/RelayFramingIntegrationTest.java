package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// End-to-end relay framing over real sockets: a `relayFraming = 1` receiver gets each frame prefixed with
/// the sender's per-channel stream index; a legacy receiver gets the un-prefixed frame. (The receiver-side
/// per-sender decode/mix is verified manually — only the server framing is asserted here.)
class RelayFramingIntegrationTest extends WebSocketIntegrationTestSupport {

	@Test
	void aV1RecipientReceivesAudioPrefixedWithTheSendersStreamIndex() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			send(sa, new ClientMessage.Join("fd-v1", ChannelMode.FULL_DUPLEX, "Alice", null, 1));
			ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("fd-v1", ChannelMode.FULL_DUPLEX, "Bob", null, 1));
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
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void aLegacyRecipientReceivesUnprefixedAudio() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(AUDIO, a, login());
		WebSocketSession sb = connect(AUDIO, b, login());
		try {
			// Both legacy (4-arg Join -> relayFraming 0).
			send(sa, new ClientMessage.Join("fd-legacy", ChannelMode.FULL_DUPLEX, "Alice", null));
			awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("fd-legacy", ChannelMode.FULL_DUPLEX, "Bob", null));
			awaitType(b.messages, ServerMessage.Joined.class);

			byte[] frame = "legacy-frame".getBytes(StandardCharsets.UTF_8);
			sendBinary(sa, frame);
			byte[] received = b.audio.poll(5, TimeUnit.SECONDS);
			assertArrayEquals(frame, received, "a legacy recipient gets the un-prefixed frame");
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}
}
