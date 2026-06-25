package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.LoginResponse;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketRelayIntegrationTest {

	private final HttpClient httpClient = HttpClient.newHttpClient();
	@LocalServerPort
	private int port;
	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void pushToTalkFloorGrantAndAudioFanOut() throws Exception {
		String tokenA = login();
		String tokenB = login();

		StandardWebSocketClient client = new StandardWebSocketClient();
		CollectingHandler handlerA = new CollectingHandler();
		CollectingHandler handlerB = new CollectingHandler();

		WebSocketSession sessionA = connect(client, handlerA, tokenA);
		WebSocketSession sessionB = connect(client, handlerB, tokenB);

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
			sessionA.sendMessage(new BinaryMessage(ByteBuffer.wrap(frame)));
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
		int missing = protectedEndpointStatus(null);
		int malformed = protectedEndpointStatus("Bearer not-a-token");
		assertNotEquals(500, malformed, "a malformed token must not surface as a server error");
		assertEquals(missing, malformed, "a malformed token must be rejected exactly like a missing one");
	}

	private int protectedEndpointStatus(String authorization) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws/audio")).GET();
		if (authorization != null) {
			builder.header("Authorization", authorization);
		}
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private String login() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		return jsonMapper.readValue(response.body(), LoginResponse.class).token();
	}

	private WebSocketSession connect(StandardWebSocketClient client, CollectingHandler handler, String token)
			throws Exception {
		// Encode the token exactly as the real clients do (WalkieClient uses URLEncoder, the browser uses
		// encodeURIComponent), so the test exercises the same opaque-token boundary and survives a future
		// token format that is not URL-safe.
		URI uri = URI.create("ws://localhost:" + port + "/ws/audio?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
		return client.execute(handler, null, uri).get(5, TimeUnit.SECONDS);
	}

	private void send(WebSocketSession session, ClientMessage message) throws Exception {
		session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
	}

	private <T extends ServerMessage> T awaitType(BlockingQueue<ServerMessage> queue, Class<T> type)
			throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadlineNanos) {
			ServerMessage message = queue.poll(200, TimeUnit.MILLISECONDS);
			if (type.isInstance(message)) {
				return type.cast(message);
			}
		}
		throw new AssertionError("Timed out waiting for " + type.getSimpleName());
	}

	/// Collects inbound control messages and audio frames into queues for the test to assert on.
	private final class CollectingHandler extends AbstractWebSocketHandler {

		private final BlockingQueue<ServerMessage> messages = new LinkedBlockingQueue<>();
		private final BlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();

		@Override
		protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
			messages.add(jsonMapper.readValue(message.getPayload(), ServerMessage.class));
		}

		@Override
		protected void handleBinaryMessage(@NonNull WebSocketSession session, BinaryMessage message) {
			ByteBuffer payload = message.getPayload();
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			audio.add(bytes);
		}
	}
}
