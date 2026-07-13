package io.github.ashr123.walkietalkie.server.session;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/// The session's outbound path is asynchronous and non-blocking, with control and audio handled separately:
/// audio is droppable on overflow, control is delivered reliably (with priority) or — if it cannot even be
/// queued — the hopelessly-behind session is closed. These tests pin isolation, failure-swallowing, the
/// audio drop policy, control reliability under audio congestion, the control-overflow close, and teardown.
// Mockito mocks of Closeable types (WebSocketSession) trip IntelliJ's "AutoCloseableResource" inspection,
// but a mock is not a real resource — there is nothing to close. Suppress that false positive class-wide.
@SuppressWarnings("resource")
class WebSocketClientSessionTest {

	/// A doAnswer body that blocks until `release`, surviving an interrupt (so close() teardown is clean).
	private static org.mockito.stubbing.Answer<Object> blockUntil(CountDownLatch entered, CountDownLatch release) {
		return _ -> {
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		};
	}

	@Test
	void aFailedControlSendIsSwallowedNotThrownToTheCaller() throws Exception {
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("sess-1");
		doThrow(new IOException("socket down")).when(ws).sendMessage(any());
		MessageCodec codec = mock(MessageCodec.class);
		when(codec.encode(any())).thenReturn("{}");

		WebSocketClientSession session = new WebSocketClientSession(ws, codec, Transport.AUDIO_RELAY, null);
		session.start();
		try {
			assertDoesNotThrow(() -> session.send(new ServerMessage.FloorIdle()));
			verify(ws, timeout(1000)).sendMessage(any());   // the drainer attempted the send and swallowed the failure
		} finally {
			session.close();
		}
	}

	@Test
	void aFailedAudioSendIsSwallowedNotThrownToTheCaller() throws Exception {
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("sess-2");
		doThrow(new IOException("socket down")).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, mock(MessageCodec.class), Transport.AUDIO_RELAY, null);
		session.start();
		try {
			assertDoesNotThrow(() -> session.sendAudio(new byte[]{1, 2, 3}));
			verify(ws, timeout(1000)).sendMessage(any());
		} finally {
			session.close();
		}
	}

	@Test
	void aSendThatThrowsARuntimeExceptionIsAlsoSwallowedAndTheDrainerSurvives() throws Exception {
		// Stand-in for the decorator's SessionLimitExceededException (a RuntimeException, not IOException).
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("sess-3");
		doThrow(new IllegalStateException("session limit exceeded")).when(ws).sendMessage(any());
		MessageCodec codec = mock(MessageCodec.class);
		when(codec.encode(any())).thenReturn("{}");

		WebSocketClientSession session = new WebSocketClientSession(ws, codec, Transport.AUDIO_RELAY, null);
		session.start();
		try {
			assertDoesNotThrow(() -> session.send(new ServerMessage.FloorIdle()));
			assertDoesNotThrow(() -> session.send(new ServerMessage.FloorIdle()));
			verify(ws, timeout(1000).times(2)).sendMessage(any());   // the drainer survived the first RuntimeException
		} finally {
			session.close();
		}
	}

	@Test
	void aSlowRecipientNeitherBlocksTheCallerNorStarvesOtherRecipients() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch slowEntered = new CountDownLatch(1);

		WebSocketSession slowWs = mock(WebSocketSession.class);
		when(slowWs.getId()).thenReturn("slow");
		doAnswer(blockUntil(slowEntered, release)).when(slowWs).sendMessage(any());

		WebSocketSession fastWs = mock(WebSocketSession.class);
		when(fastWs.getId()).thenReturn("fast");

		WebSocketClientSession slow = new WebSocketClientSession(slowWs, mock(MessageCodec.class), Transport.AUDIO_RELAY, null);
		WebSocketClientSession fast = new WebSocketClientSession(fastWs, mock(MessageCodec.class), Transport.AUDIO_RELAY, null);
		slow.start();
		fast.start();
		try {
			long startNanos = System.nanoTime();
			slow.sendAudio(new byte[]{1});
			fast.sendAudio(new byte[]{2});
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

			assertTrue(slowEntered.await(1, TimeUnit.SECONDS), "the slow recipient's drainer should be wedged mid-send");
			assertTrue(elapsedMs < 500, "the caller must not block on the wedged recipient; took " + elapsedMs + "ms");
			verify(fastWs, timeout(1000)).sendMessage(any());   // the fast recipient is delivered despite the slow one
		} finally {
			release.countDown();
			slow.close();
			fast.close();
		}
	}

	@Test
	void audioFramesAreDroppedWhenTheAudioMailboxOverflows() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch wedged = new CountDownLatch(1);
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("flooded");
		doAnswer(blockUntil(wedged, release)).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, mock(MessageCodec.class), Transport.AUDIO_RELAY, null);
		session.start();
		try {
			session.sendAudio(new byte[]{0});                 // the drainer takes this and wedges
			assertTrue(wedged.await(1, TimeUnit.SECONDS));
			// Flood far past capacity; overflow is dropped, never blocking the caller or throwing.
			assertDoesNotThrow(() -> {
				for (int i = 0; i < 1_000; i++) {
					session.sendAudio(new byte[]{(byte) i});
				}
			});
		} finally {
			release.countDown();
			session.close();
		}
	}

	@Test
	void controlIsDeliveredEvenWhileAudioIsCongested() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch wedged = new CountDownLatch(1);
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("congested");
		MessageCodec codec = mock(MessageCodec.class);
		when(codec.encode(any())).thenReturn("{}");
		doAnswer(blockUntil(wedged, release)).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, codec, Transport.AUDIO_RELAY, null);
		session.start();
		try {
			session.sendAudio(new byte[]{0});                 // wedge the drainer
			assertTrue(wedged.await(1, TimeUnit.SECONDS));
			for (int i = 0; i < 1_000; i++) {
				session.sendAudio(new byte[]{1});             // audio overflows and is dropped
			}
			session.send(new ServerMessage.FloorIdle());      // ...but a control message must NOT be dropped
			release.countDown();
			// The control TextMessage is delivered (drained with priority), proving it survived the audio flood.
			verify(ws, timeout(2000)).sendMessage(argThat(TextMessage.class::isInstance));
		} finally {
			release.countDown();
			session.close();
		}
	}

	@Test
	void aControlBacklogOverflowClosesTheHopelesslyBehindSession() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch wedged = new CountDownLatch(1);
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("dead");
		MessageCodec codec = mock(MessageCodec.class);
		when(codec.encode(any())).thenReturn("{}");
		doAnswer(blockUntil(wedged, release)).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, codec, Transport.AUDIO_RELAY, null);
		session.start();
		try {
			session.send(new ServerMessage.FloorIdle());      // the drainer takes this and wedges
			assertTrue(wedged.await(1, TimeUnit.SECONDS));
			for (int i = 0; i < 2_000; i++) {
				session.send(new ServerMessage.FloorIdle());  // control overflows its generous headroom
			}
			verify(ws, timeout(2000)).close(any(CloseStatus.class));   // hopelessly behind -> disconnected
		} finally {
			release.countDown();
			session.close();
		}
	}

	@Test
	void aClosedSessionDropsFurtherSends() throws Exception {
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("closing");

		WebSocketClientSession session = new WebSocketClientSession(ws, mock(MessageCodec.class), Transport.AUDIO_RELAY, null);
		session.start();
		session.close();

		session.sendAudio(new byte[]{1});
		verify(ws, after(300).never()).sendMessage(any());   // nothing is sent after close
	}

	@Test
	void closingBeforeStartingIsASafeNoOp() {
		WebSocketSession ws = mock(WebSocketSession.class);
		assertDoesNotThrow(() -> new WebSocketClientSession(ws, mock(MessageCodec.class), Transport.AUDIO_RELAY, null).close());
	}

	@Test
	void supportsAudioRelayReflectsTheTransport() {
		WebSocketSession ws = mock(WebSocketSession.class);
		MessageCodec codec = mock(MessageCodec.class);
		assertTrue(new WebSocketClientSession(ws, codec, Transport.AUDIO_RELAY, null).supportsAudioRelay());
		assertFalse(new WebSocketClientSession(ws, codec, Transport.SIGNALING, null).supportsAudioRelay(),
				"a signaling session does not relay audio");
	}
}
