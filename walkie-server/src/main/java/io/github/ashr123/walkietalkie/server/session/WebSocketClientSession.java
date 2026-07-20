package io.github.ashr123.walkietalkie.server.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/// [ClientSession] backed by a Spring [WebSocketSession] (expected to be a
/// [ConcurrentWebSocketSessionDecorator] so the socket layer bounds a wedged in-flight write).
///
/// Sends are **asynchronous and non-blocking**: each outbound frame is handed to a per-session mailbox drained
/// by exactly **one** dedicated virtual thread (a control frame arrives already encoded — see
/// [io.github.ashr123.walkietalkie.server.transport.MessageBroadcaster]; audio is a raw byte[]). This gives:
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
///     dropped for a live session**: dropping it would permanently desync a client that stays connected (full
///     state re-syncs only via the `Joined` snapshot on (re)join). It has generous headroom; if even that
///     overflows the client is hopelessly behind, so the session is closed ([#terminateForBacklog]) to force a
///     clean reconnect + re-sync. The drainer services control ahead of audio so state updates stay timely.
///     (The one window where a control frame is not delivered is a frame enqueued at the instant this session
///     is itself being torn down — `send` passed its `closed` check just as [#stopPump] flipped it and the
///     drainer exited; the lost frame is only ever to *this* closing recipient, which re-syncs via `Joined` on
///     reconnect, so no still-connected client is desynced.)
public final class WebSocketClientSession implements ClientSession {

	private static final Logger log = LoggerFactory.getLogger(WebSocketClientSession.class);

	/// Bounded audio mailbox depth. At 50 frames/s (20 ms Opus) this is ~5 seconds of buffered push-to-talk
	/// audio before a recipient is judged behind and its overflowing frames are dropped.
	private static final int AUDIO_CAPACITY = 256;
	/// Control mailbox depth. Control is sparse, so this is large headroom; overflowing it means the client
	/// cannot even drain state updates and is treated as hopelessly behind (the session is closed).
	private static final int CONTROL_CAPACITY = 1024;

	private final WebSocketSession session;
	private final Transport transport;
	/// The `channel` query param captured at the handshake (see [ClientSession#handshakeChannel]); may be null.
	private final String handshakeChannel;

	private final BlockingQueue<Runnable> controlOut = new LinkedBlockingQueue<>(CONTROL_CAPACITY);
	private final BlockingQueue<Runnable> audioOut = new LinkedBlockingQueue<>(AUDIO_CAPACITY);
	private final AtomicBoolean closed = new AtomicBoolean();
	/// Counts frames waiting across both queues: a permit is released for each accepted enqueue and consumed
	/// one-per-drain, so the single consumer parks on it (never busy-waits) and wakes only on real work. [#stopPump]
	/// releases one extra permit at close so a parked drainer unblocks, observes `closed`, and exits.
	private final Semaphore work = new Semaphore(0);

	// Set when the client joins a channel (from the validated Join.displayName); "" until then.
	private volatile String displayName = "";
	private volatile String channelName;

	public WebSocketClientSession(WebSocketSession session,
	                              Transport transport,
	                              String handshakeChannel) {
		this.session = session;
		this.transport = transport;
		this.handshakeChannel = handshakeChannel;
	}

	@Override
	public String handshakeChannel() {
		return handshakeChannel;
	}

	/// Starts the single outbound drainer virtual thread. Called once, AFTER the session has been registered for
	/// lookup, so a disconnect can always find the session and [#close] it — which flips `closed` and releases a
	/// wake permit, so a drainer parked (or not yet parked) always unblocks and exits. Because the wake is a
	/// permit and not an interrupt, it can't be lost to a start-vs-close race (the permit sticks until acquired).
	public void start() {
		Thread.ofVirtual().name("ws-out-" + id()).start(this::drainLoop);
	}

	/// The sole consumer: parks on [#work] until a frame is enqueued (or [#close] releases a wake permit), then
	/// drains exactly ONE task via [#drainOne] — control first, so state updates stay timely even when audio is
	/// backed up. Fully event-driven: one permit exists per queued frame and is consumed one-per-drain, so an
	/// idle session blocks indefinitely with no polling or busy-wait. On [#close] it stops taking new work and
	/// flushes whatever is still queued — best-effort: the flush reaches the client only while the socket is still
	/// writable (e.g. a server-initiated [#terminateForBacklog] close), whereas on a peer-initiated disconnect the
	/// socket is already gone and those sends fail fast and are swallowed.
	private void drainLoop() {
		while (!closed.get()) {
			work.acquireUninterruptibly();   // park until a frame is enqueued, or close() releases a wake permit
			drainOne();
		}
		// close() flips `closed` before releasing its wake permit, and `send*` refuse once `closed` is set, so no
		// new frame can arrive here; deliver whatever is already queued — control fully first, then audio — before
		// the thread exits.
		for (Runnable task; (task = controlOut.poll()) != null; ) {
			task.run();
		}
		for (Runnable task; (task = audioOut.poll()) != null; ) {
			task.run();
		}
	}

	/// Runs one queued task — control first (priority), else audio — performing the (blocking) socket write. A
	/// no-op when the acquired permit was the one [#stopPump] released to wake the drainer for shutdown and both
	/// queues are already empty.
	private void drainOne() {
		Runnable task = controlOut.poll();
		if (task == null) {
			task = audioOut.poll();
		}
		if (task != null) {
			task.run();
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
	public void sendEncoded(String encoded) {
		if (closed.get()) {
			return;
		}
		if (controlOut.offer(() -> sendQuietly(new TextMessage(encoded)))) {
			work.release();   // signal the drainer that one more frame is ready
		} else {
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
		// BinaryMessage(byte[]) wraps the array without copying (ByteBuffer.wrap under the hood) and the frame is
		// sent exactly once, so build it directly in the send task. The relay never mutates a payload it forwards,
		// so the shared no-copy wrap is safe even though this same array is fanned out to every recipient.
		if (audioOut.offer(() -> sendQuietly(new BinaryMessage(audio)))) {
			work.release();   // signal the drainer that one more frame is ready
		} else {
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

	/// Marks the session closed and wakes the drainer, idempotently. Flips `closed` (so `send*` refuse further
	/// frames and the drain loop will exit) then releases one wake permit so a parked drainer unblocks, observes
	/// `closed`, flushes what's left, and terminates. A permit (not an interrupt) so the wake can't be lost to a
	/// start-vs-close race. Returns whether THIS call is the one that closed it, so one-shot teardown (e.g. the
	/// socket close in [#terminateForBacklog]) runs exactly once.
	private boolean stopPump() {
		boolean firstClose = closed.compareAndSet(false, true);
		if (firstClose) {
			work.release();   // unblock the drainer's acquire; it re-checks `closed` and stops (no interrupt needed)
		}
		return firstClose;
	}

	@Override
	public void close() {
		stopPump();
	}
}
