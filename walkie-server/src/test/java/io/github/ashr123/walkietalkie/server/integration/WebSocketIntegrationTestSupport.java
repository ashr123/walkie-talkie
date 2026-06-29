package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.LoginResponse;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
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
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Shared harness for the WebSocket integration tests. Boots the full application on a random port (the
/// Spring test context is cached and reused across every subclass with this same configuration) and offers
/// the plumbing each test needs: minting a login token, opening an authenticated socket to either transport,
/// sending control/audio frames, and awaiting (or asserting the absence of) server messages.
///
/// Each test logs in and connects fresh sessions and joins a uniquely-named channel, so the methods of a
/// class stay independent even though they share one embedded server.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class WebSocketIntegrationTestSupport {

	protected static final String AUDIO = "/ws/audio";
	protected static final String SIGNAL = "/ws/signal";
	protected final HttpClient httpClient = HttpClient.newHttpClient();
	private final WebSocketClient wsClient = new StandardWebSocketClient();
	@LocalServerPort
	protected int port;
	@Autowired
	protected JsonMapper jsonMapper;

	/// POST /api/auth/login and return the freshly minted, signed bearer token.
	protected String login() throws Exception {
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
						.POST(HttpRequest.BodyPublishers.noBody())
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), "login should succeed");
		return jsonMapper.readValue(response.body(), LoginResponse.class).token();
	}

	/// GETs `http://localhost:<port><path>` with an optional `Authorization` header and returns the HTTP
	/// status — used to assert which endpoints are public vs. authenticated, and that bad tokens are rejected
	/// at the boundary rather than surfacing as a 500.
	protected int httpStatus(String path, String authorizationHeader) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (authorizationHeader != null) {
			builder.header("Authorization", authorizationHeader);
		}
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	/// Sends a raw binary (audio) frame.
	protected static void sendBinary(WebSocketSession session, byte[] frame) throws Exception {
		session.sendMessage(new BinaryMessage(ByteBuffer.wrap(frame)));
	}

	/// Sends a control message as a JSON text frame.
	protected void send(WebSocketSession session, ClientMessage message) throws Exception {
		session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
	}

	/// Sends an arbitrary raw text frame — used to drive the malformed-control-message path that the typed
	/// [#send] cannot produce.
	protected static void sendRaw(WebSocketSession session, String text) throws Exception {
		session.sendMessage(new TextMessage(text));
	}

	/// Waits up to 5 seconds for a server message of `type`, skipping any other types that arrive first.
	protected static <T extends ServerMessage> T awaitType(BlockingQueue<? extends ServerMessage> queue, Class<T> type)
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

	/// Asserts that NO message of `type` arrives within a short window — for negative / no-op assertions
	/// (e.g. a non-owner's mode change is silently ignored). Other message types are tolerated and skipped.
	protected static void assertNotReceived(BlockingQueue<? extends ServerMessage> queue, Class<? extends ServerMessage> type)
			throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
		while (System.nanoTime() < deadlineNanos) {
			ServerMessage message = queue.poll(100, TimeUnit.MILLISECONDS);
			if (type.isInstance(message)) {
				throw new AssertionError("Unexpectedly received " + type.getSimpleName());
			}
		}
	}

	/// Opens an authenticated WebSocket to `path` ([#AUDIO] or [#SIGNAL]), passing the token as the `?token=`
	/// query parameter exactly as the real clients do (the browser can't set headers on a WS handshake).
	protected WebSocketSession connect(String path, WebSocketHandler handler, String token) throws Exception {
		return connect(path, handler, token, null);
	}

	/// As [#connect(String, WebSocketHandler, String)] but with extra handshake headers — used to drive the
	/// `Origin` check (CSWSH defense): a handshake with a disallowed `Origin` fails, surfacing as the returned
	/// future throwing rather than yielding a session.
	protected WebSocketSession connect(String path, WebSocketHandler handler, String token, WebSocketHttpHeaders headers) throws Exception {
		URI uri = URI.create("ws://localhost:" + port + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
		return wsClient.execute(handler, headers, uri).get(5, TimeUnit.SECONDS);
	}

	/// Waits up to 5 seconds for the server to close `session` (e.g. after it rejects an over-cap frame),
	/// returning whether it closed within that window.
	protected static boolean awaitClosed(WebSocketSession session) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadlineNanos) {
			if (!session.isOpen()) {
				return true;
			}
			Thread.sleep(50);
		}
		return false;
	}

	/// Asserts a relayed audio frame carries `expected` as its body after the mandatory 1-byte stream-index
	/// prefix the server prepends to every outbound relay frame. The prefix *value* (the sender's stream
	/// index) is asserted in RelayFramingIntegrationTest; here we only check the body survives the round trip.
	protected static void assertPrefixedBody(byte[] expected, byte[] received, String message) {
		assertNotNull(received, message);
		assertArrayEquals(expected, Arrays.copyOfRange(received, 1, received.length), message);
	}

	/// Collects inbound control messages and audio frames into queues for the test to assert on.
	protected final class CollectingHandler extends AbstractWebSocketHandler {

		final BlockingQueue<ServerMessage> messages = new LinkedBlockingQueue<>();
		final BlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();

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
