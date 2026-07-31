package io.github.ashr123.walkietalkie.server.session;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
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

	/// Keepalive off for every test that is not about it: these pin the outbound path, and a Ping arriving mid-test
	/// would be an unrelated sendMessage call for their verifications to trip over.
	private static final Duration NO_KEEPALIVE = Duration.ZERO;

	/// Far shorter than any real interval (the default is 30 s) so a keepalive test finishes in milliseconds.
	private static final Duration BRISK_KEEPALIVE = Duration.ofMillis(40);

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

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
		session.start();
		try {
			assertDoesNotThrow(() -> session.sendEncoded("{}"));
			verify(ws, timeout(1000)).sendMessage(any());   // the drainer attempted the send and swallowed the failure
		} finally {
			session.close();
		}
	}

	@Test
	void anIdleSessionSendsKeepalivePingsSoAProxyDoesNotReapIt() throws Exception {
		// The drainer's park is the idleness detector: waiting the whole interval with nothing to send IS "idle", so
		// no timer, registry or scheduler is involved. What this pins is that the timeout emits a Ping — the frame
		// that keeps a Cloudflare tunnel (~100 s) or an nginx proxy_read_timeout (60 s by default) from closing a
		// quiet channel, and that both browsers and the JDK client answer with a Pong for free.
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("idle");

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, BRISK_KEEPALIVE);
		session.start();
		try {
			// Two, not one: the first proves a Ping is sent at all, the second that the keepalive REPEATS rather than
			// firing once and leaving the next idle window unprotected.
			verify(ws, timeout(2000).atLeast(2)).sendMessage(isA(PingMessage.class));
		} finally {
			session.close();
		}
	}

	@Test
	void keepaliveOffParksIndefinitelyAndSendsNothing() throws Exception {
		// 0 is a real deployment setting ("the path has no idle reaper" — loopback, or a proxy configured yourself),
		// and it must restore the original indefinite park rather than pinging on some default interval.
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("quiet");

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
		session.start();
		try {
			// Comfortably longer than the brisk interval above, so a keepalive that ignored ZERO would be caught.
			Thread.sleep(300);
			verify(ws, never()).sendMessage(any());
		} finally {
			session.close();
		}
	}

	@Test
	void aKeepaliveSessionStillDeliversRealFramesAndClosesCleanly() throws Exception {
		// The keepalive changes how the drainer WAITS, so the ordinary path has to be re-pinned under it: a queued
		// frame must still go out promptly (not wait for the interval to elapse) and teardown must still terminate.
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("mixed");

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, BRISK_KEEPALIVE);
		session.start();
		try {
			session.sendEncoded("{\"type\":\"floorStatus\"}");
			verify(ws, timeout(1000)).sendMessage(isA(TextMessage.class));
		} finally {
			assertDoesNotThrow(session::close);
		}
	}

	@Test
	void aFailedAudioSendIsSwallowedNotThrownToTheCaller() throws Exception {
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("sess-2");
		doThrow(new IOException("socket down")).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
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

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
		session.start();
		try {
			assertDoesNotThrow(() -> session.sendEncoded("{}"));
			assertDoesNotThrow(() -> session.sendEncoded("{}"));
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

		WebSocketClientSession slow = new WebSocketClientSession(slowWs, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
		WebSocketClientSession fast = new WebSocketClientSession(fastWs, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
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

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
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
		doAnswer(blockUntil(wedged, release)).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
		session.start();
		try {
			session.sendAudio(new byte[]{0});                 // wedge the drainer
			assertTrue(wedged.await(1, TimeUnit.SECONDS));
			for (int i = 0; i < 1_000; i++) {
				session.sendAudio(new byte[]{1});             // audio overflows and is dropped
			}
			session.sendEncoded("{}");      // ...but a control message must NOT be dropped
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
		doAnswer(blockUntil(wedged, release)).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
		session.start();
		try {
			session.sendEncoded("{}");      // the drainer takes this and wedges
			assertTrue(wedged.await(1, TimeUnit.SECONDS));
			for (int i = 0; i < 2_000; i++) {
				session.sendEncoded("{}");  // control overflows its generous headroom
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

		WebSocketClientSession session = new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE);
		session.start();
		session.close();

		session.sendAudio(new byte[]{1});
		verify(ws, after(300).never()).sendMessage(any());   // nothing is sent after close
	}

	@Test
	void closingBeforeStartingIsASafeNoOp() {
		WebSocketSession ws = mock(WebSocketSession.class);
		assertDoesNotThrow(() -> new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE).close());
	}

	@Test
	void supportsAudioRelayReflectsTheTransport() {
		WebSocketSession ws = mock(WebSocketSession.class);
		assertTrue(new WebSocketClientSession(ws, Transport.AUDIO_RELAY, null, NO_KEEPALIVE).supportsAudioRelay());
		assertFalse(new WebSocketClientSession(ws, Transport.SIGNALING, null, NO_KEEPALIVE).supportsAudioRelay(),
				"a signaling session does not relay audio");
	}
}
