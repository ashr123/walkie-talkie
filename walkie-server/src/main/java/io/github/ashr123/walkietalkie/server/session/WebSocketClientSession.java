package io.github.ashr123.walkietalkie.server.session;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// [ClientSession] backed by a Spring [WebSocketSession] (expected to be a
/// [ConcurrentWebSocketSessionDecorator] so the socket layer bounds a wedged in-flight write).
///
/// Sends are **asynchronous and non-blocking**: each outbound frame is encoded on the caller thread and then
/// handed to a per-session mailbox drained by exactly **one** dedicated virtual thread. This gives:
///   - **Isolation** — a slow/backpressured recipient backs up only its own mailbox and its own drainer; it
///     never blocks the fan-out caller ([io.github.ashr123.walkietalkie.server.channel.Channel#forEachOther])
///     or any other recipient.
///   - **Ordering** — one consumer thread, so a recipient's frames are delivered in submission order (the
///     relay's Opus decode is per-stream stateful; reordering would corrupt audio).
///
/// Control and audio are split into two queues with different overflow policies, because they tolerate loss
/// very differently:
///   - **Audio** ([#audioOut]) is bounded and **lossy**: when it overflows (a recipient ~[#AUDIO_CAPACITY]
///     frames behind) the frame is dropped — a momentary click that the next frame heals.
///   - **Control** ([#controlOut]) is state-changing (floor/mode/owner/membership) and is **never silently
///     dropped**: dropping it would permanently desync a client that stays connected (full state re-syncs
///     only via the `Joined` snapshot on (re)join). It has generous headroom; if even that overflows the
///     client is hopelessly behind, so the session is closed ([#terminateForBacklog]) to force a clean
///     reconnect + re-sync. The drainer services control ahead of audio so state updates stay timely.
public final class WebSocketClientSession implements ClientSession {

	private static final Logger log = LoggerFactory.getLogger(WebSocketClientSession.class);

	/// Bounded audio mailbox depth. At 50 frames/s (20 ms Opus) this is ~5 seconds of buffered push-to-talk
	/// audio before a recipient is judged behind and its overflowing frames are dropped.
	private static final int AUDIO_CAPACITY = 256;
	/// Control mailbox depth. Control is sparse, so this is large headroom; overflowing it means the client
	/// cannot even drain state updates and is treated as hopelessly behind (the session is closed).
	private static final int CONTROL_CAPACITY = 1024;
	/// How long the drainer parks waiting for the next audio frame; bounds how quickly it notices [#close].
	private static final long POLL_TIMEOUT_MS = 200;

	private final WebSocketSession session;
	private final MessageCodec codec;
	private final Transport transport;

	private final BlockingQueue<Runnable> controlOut = new LinkedBlockingQueue<>(CONTROL_CAPACITY);
	private final BlockingQueue<Runnable> audioOut = new LinkedBlockingQueue<>(AUDIO_CAPACITY);
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private Thread drainer;   // the single consumer; started by start() after the session is registered

	// Set when the client joins a channel (from the validated Join.displayName); "" until then.
	private volatile String displayName = "";
	private volatile String channelName;

	public WebSocketClientSession(WebSocketSession session,
	                              MessageCodec codec,
	                              Transport transport) {
		this.session = session;
		this.codec = codec;
		this.transport = transport;
	}

	/// Starts the single outbound drainer virtual thread. Called once, AFTER the session has been registered
	/// for lookup, so a disconnect can always find and [#close] it (no construct-before-register leak window).
	public void start() {
		this.drainer = Thread.ofVirtual().name("ws-out-" + id()).start(this::drainLoop);
	}

	/// The sole consumer: drains control with priority (state updates stay timely even when audio is backed
	/// up), then audio, performing the (blocking) socket write in FIFO order. Drains anything already queued
	/// at [#close] before exiting, so a graceful close still flushes in-flight frames.
	private void drainLoop() {
		while (!closed.get() || !controlOut.isEmpty() || !audioOut.isEmpty()) {
			try {
				Runnable task = controlOut.poll();
				if (task == null) {
					task = audioOut.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
				if (task != null) {
					task.run();
				}
			} catch (InterruptedException _) {
				// close()/terminate interrupted the wait; the loop re-checks, drains the remainder, then exits.
			}
		}
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
		if (closed.get()) {
			return;
		}
		// Encode on the caller (cheap, CPU-only) so the single drainer stays lean and stays the sole writer.
		String encoded = codec.encode(message);
		if (!controlOut.offer(() -> sendQuietly(new TextMessage(encoded)))) {
			// A state-changing message is never silently dropped: if it can't even be queued, the client is
			// hopelessly behind, so disconnect it to force a clean reconnect + Joined re-sync.
			terminateForBacklog();
		}
	}

	@Override
	public void sendAudio(byte[] audio) {
		if (closed.get()) {
			return;
		}
		ByteBuffer payload = ByteBuffer.wrap(audio);
		if (!audioOut.offer(() -> sendQuietly(new BinaryMessage(payload)))) {
			log.debug("Audio backlog overflow for {}; dropping a frame", id());
		}
	}

	/// Performs the actual (blocking) write on the drainer thread, swallowing failures so one bad recipient
	/// never affects the others. `RuntimeException` is caught alongside `IOException` because the decorator
	/// throws `SessionLimitExceededException` (a `RuntimeException`) exactly when a recipient is too slow.
	private void sendQuietly(WebSocketMessage<?> message) {
		try {
			session.sendMessage(message);
		} catch (RuntimeException | IOException e) {
			log.debug("Dropping outbound frame to {}: {}", id(), e.getMessage());
		}
	}

	/// Disconnects a hopelessly-behind client (control could not be queued). The close handshake is itself a
	/// socket write that could block on the dead client, so it runs on a throwaway virtual thread — the
	/// fan-out caller never blocks. Membership cleanup then happens via the normal afterConnectionClosed path.
	private void terminateForBacklog() {
		if (stopPump()) {   // the first caller to close the session also disconnects the socket
			log.debug("Control backlog overflow for {}; closing the hopelessly-behind session", id());
			Thread.ofVirtual().name("ws-close-" + id()).start(() -> {
				try {
					session.close(CloseStatus.POLICY_VIOLATION.withReason("send backlog"));
				} catch (Exception _) {
					// best effort
				}
			});
		}
	}

	/// Stops the drainer, idempotently. Returns whether this call is the one that transitioned the session to
	/// closed, so a caller can run one-shot teardown (e.g. the socket close above) exactly once.
	private boolean stopPump() {
		boolean firstClose = closed.compareAndSet(false, true);
		if (firstClose && drainer != null) {
			drainer.interrupt();
		}
		return firstClose;
	}

	@Override
	public void close() {
		stopPump();
	}
}
