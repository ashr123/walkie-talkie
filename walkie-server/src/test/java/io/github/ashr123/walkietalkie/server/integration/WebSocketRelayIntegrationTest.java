package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// End-to-end smoke test of the relay transport: two real WebSocket clients, a push-to-talk floor grant,
/// an audio frame fanned out byte-for-byte, and a busy-floor denial — plus the malformed-token boundary.
class WebSocketRelayIntegrationTest extends WebSocketIntegrationTestSupport {

	@Test
	void pushToTalkFloorGrantAndAudioFanOut() throws Exception {
		String tokenA = login();
		String tokenB = login();

		CollectingHandler handlerA = new CollectingHandler();
		CollectingHandler handlerB = new CollectingHandler();
		WebSocketSession sessionA = connect(AUDIO, handlerA, tokenA);
		WebSocketSession sessionB = connect(AUDIO, handlerB, tokenB);

		try {
			send(sessionA, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			ServerMessage.Joined joinedA = awaitType(handlerA.messages, ServerMessage.Joined.class);
			assertEquals("team", joinedA.channel());

			send(sessionB, new ClientMessage.Join("team", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			awaitType(handlerB.messages, ServerMessage.Joined.class);

			// Alice's queue should see Bob arrive.
			assertNotNull(awaitType(handlerA.messages, ServerMessage.MemberJoined.class));

			// Alice grabs the floor; she is granted and Bob is told the floor is taken.
			send(sessionA, new ClientMessage.RequestFloor());
			assertNotNull(awaitType(handlerA.messages, ServerMessage.FloorGranted.class));
			ServerMessage.FloorTaken taken = awaitType(handlerB.messages, ServerMessage.FloorTaken.class);
			assertEquals(joinedA.selfId(), taken.holderId());

			// Alice transmits an audio frame; Bob receives the exact bytes.
			byte[] frame = "pcm-audio-frame".getBytes(StandardCharsets.UTF_8);
			sendBinary(sessionA, frame);
			byte[] received = handlerB.audio.poll(5, TimeUnit.SECONDS);
			assertArrayEquals(frame, received);

			// While Alice holds the floor, Bob is denied.
			send(sessionB, new ClientMessage.RequestFloor());
			assertNotNull(awaitType(handlerB.messages, ServerMessage.FloorDenied.class));
		} finally {
			sessionA.close(CloseStatus.NORMAL);
			sessionB.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void aMalformedTokenIsRejectedAtTheBoundaryNotAsAServerError() throws Exception {
		// A token that is not a valid signed token (here: garbage) must be treated as unauthenticated at the
		// inbound boundary (TokenAuthenticationFilter -> AuthService.verify), never surface as a 500. Drive a
		// protected endpoint with a garbage bearer token and require it to behave exactly like no token.
		int missing = httpStatus(AUDIO, null);
		int malformed = httpStatus(AUDIO, "Bearer not-a-token");
		assertNotEquals(500, malformed, "a malformed token must not surface as a server error");
		assertEquals(missing, malformed, "a malformed token must be rejected exactly like a missing one");
	}
}
