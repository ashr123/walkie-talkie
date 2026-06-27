package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.*;
import io.github.jaredmdobson.concentus.OpusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sound.sampled.LineUnavailableException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/// Console walkie-talkie client over the WebSocket-relay transport (the only one available to a pure-Java
/// client; WebRTC is browser-to-browser). It orchestrates login, the relay WebSocket connection, the
/// interactive console and server-message handling, and — when a `--key` is given — per-frame AES-256-GCM
/// encryption.
///
/// Microphone capture, Opus (de)coding and speaker playback all live in [AudioEngine]; this class only
/// encrypts captured frames before sending them and decrypts received frames before handing them back to
/// the engine for playback. All loops run on Java 25 virtual threads.
///
/// It is [AutoCloseable]: [#WalkieClient] does the work and blocks on the console; the caller closes the client
/// (ideally via try-with-resources) to tear the session down.
public final class WalkieClient implements AutoCloseable {

	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
			"yyyy-MM-dd HH:mm:ss,SSS",
			Locale.getDefault(Locale.Category.FORMAT)
	);
	// Stateless, thread-safe infrastructure with no per-connection input — shared by every client instance.
	private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();
	/// Sentinel owner id the server stamps on the server-managed "global" room (mirrors
	/// `ConnectionService.GLOBAL_CHANNEL_OWNER`); that channel has no participant owner.
	private static final String SERVER_OWNER = "server";
	/// Upper bound on how long [#close] waits for the HttpClient — and the WebSocket close handshake riding on
	/// it — to drain gracefully before forcing termination, so quitting can never hang on a slow or vanished
	/// server. Two seconds is ample for a localhost/LAN close handshake while still feeling instant to a user.
	private static final Duration HTTP_SHUTDOWN_GRACE = Duration.ofSeconds(2);

	private final ClientOptions options;
	// Per-instance: its SSLContext trusts the system CAs plus (on localhost) the server's dev cert or a
	// --tls-truststore, so HTTPS + WSS verify against the target server — verification is never disabled.
	private final HttpClient httpClient;

	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean closed = new AtomicBoolean(false);   // guards close() so it is idempotent
	private final BlockingQueue<Outbound> sendQueue = new LinkedBlockingQueue<>();
	private final Map<String, String> memberNames = new ConcurrentHashMap<>(); // session id -> display name
	private final AudioEngine audio;
	private final WebSocket webSocket;
	private final FrameCrypto crypto;          // AES-256-GCM E2EE, or null when no passphrase; set before the loops start

	private volatile String selfId = "";
	private volatile String ownerId;
	private volatile ChannelMode currentMode;
	private volatile boolean warnedDecrypt; // listener thread only today, but volatile so the warn-once intent survives a threading refactor

	public WalkieClient(ClientOptions options) throws IOException, InterruptedException, GeneralSecurityException, LineUnavailableException, OpusException {
		this.options = options;
		this.httpClient = HttpClient.newBuilder()
				.sslContext(TlsTrust.forServer(options.server(), options.tlsTruststore()))
				.build();
		this.currentMode = options.mode();
		this.audio = new AudioEngine(options, this::sendAudioFrame);
		System.out.println("Connecting to " + options.server() + " as '" + options.display() + "' ...");
		String token = login();
		crypto = setupCrypto();
		audio.start();
		System.out.println("Audio: " + audio.description()
				+ (crypto == null ? "" : ", end-to-end encrypted (AES-256-GCM)"));

		webSocket = connect(token);
		// Start the sender only after webSocket is assigned, so this final field is safely published to the
		// sender thread (Thread.start() happens-after the write). The final-field freeze can't be relied on
		// here because `this` escapes during construction. A join queued by onOpen during connect is sent
		// once the sender drains the queue.
		Thread.ofVirtual().name("ptt-sender").start(this::senderLoop);

		// Blocks until the user quits or stdin closes; the caller then closes us (try-with-resources).
		consoleLoop();
	}

	/// Prints a status line prefixed with the local timestamp (`yyyy-MM-dd HH:mm:ss,SSS`).
	private static void log(String message) {
		System.out.println(LocalDateTime.now().format(DATE_TIME_FORMATTER) + " " + message);
	}

	private static String modeHint(ChannelMode mode, boolean micLive) {
		return mode == ChannelMode.FULL_DUPLEX
				? "Full-duplex: mic is " + (micLive ? "live" : "muted") + " — type 't' to mute/unmute."
				: "Push-to-talk: type 't' to grab/release the floor.";
	}

	// --- HTTP login + WebSocket -------------------------------------------------------------------

	private static void printHelp() {
		System.out.println("""
				--------------------------------------------------------------------------------------------------
				 Commands:  t = talk/stop   w = who's here   m <ptt|global|duplex> = mode
				            f = hi-fi on/off   q = quit   h = help
				--------------------------------------------------------------------------------------------------""");
	}

	private String login() throws IOException, InterruptedException {
		// Login takes no input: it just mints a signed, short-lived token. The token is an opaque string.
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(URI.create(options.server() + "/api/auth/login"))
						.POST(HttpRequest.BodyPublishers.noBody())
						.build(),
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			throw new IOException("Login failed: HTTP " + response.statusCode() + " " + response.body());
		}
		return JSON_MAPPER.readValue(response.body(), LoginResponse.class).token();
	}

	/// Builds the AES-256-GCM frame cipher from `--key` (or the WALKIE_KEY env var), or null to disable
	/// E2EE. Salted with the effective channel (the server forces "global" for global mode), so every
	/// client in the channel derives the same key.
	private FrameCrypto setupCrypto() throws GeneralSecurityException {
		String passphrase = options.key();
		if (passphrase == null || passphrase.isBlank()) {
			return null;
		}
		if (options.mode() == ChannelMode.GLOBAL_PTT) {
			// Global is the server-managed, always-unencrypted broadcast room — the server rejects an
			// encrypted global join, so drop the key here (and warn) rather than fail the join.
			log("[warn] global mode uses the server's unencrypted broadcast channel — ignoring --key");
			return null;
		}
		return FrameCrypto.fromPassphrase(passphrase, options.channel());
	}

	private void senderLoop() {
		try {
			while (running.get()) {
				(switch (sendQueue.take()) {
					case Outbound.Text(String json) -> webSocket.sendText(json, true);
					case Outbound.Binary(byte[] data) -> webSocket.sendBinary(ByteBuffer.wrap(data), true);
				})
						.join();
			}
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException _) {
			// connection closed; sender exits quietly
		}
	}

	/// Frame sink for [AudioEngine] (runs on its capture thread): encrypt the captured `[tag][opus]` frame
	/// when E2EE is on, then queue it for the sender loop.
	private void sendAudioFrame(byte[] frame) {
		try {
			sendQueue.offer(new Outbound.Binary(crypto == null ? frame : crypto.encrypt(frame)));
		} catch (GeneralSecurityException _) {
			// drop this frame; keep going
		}
	}

	// --- Server messages --------------------------------------------------------------------------

	private void handleServerMessage(String json) {
		switch (JSON_MAPPER.readValue(json, ServerMessage.class)) {
			case ServerMessage.Joined(
					String selfId, String channel, ChannelMode mode, String ownerId, List<MemberInfo> members
			) -> {
				this.selfId = selfId;
				this.ownerId = ownerId;
				this.currentMode = mode;
				// Full-duplex: the mic is live as soon as you join, unless --muted was passed; PTT/global start
				// muted and require 't' to grab the floor. (Full-duplex transmit needs no floor request.)
				audio.setTransmitting(mode == ChannelMode.FULL_DUPLEX && !options.startMuted());
				memberNames.clear();
				members.forEach(member -> memberNames.put(member.id(), member.displayName()));
				String ownerLine = SERVER_OWNER.equals(ownerId)
						? "server-managed room — no owner, unencrypted"
						: selfId.equals(ownerId)
						  ? "you own this channel — 'm <ptt|global|duplex>' to change the mode for everyone"
						  : "owner: " + name(ownerId);
				// If the channel already existed in another mode, its owner's mode wins and you adopt it.
				String modeNote = mode == options.mode()
						? ""
						: " (you requested " + options.mode() + ", adopted the channel's existing mode)";
				log("[joined] channel=" + channel + " mode=" + mode + modeNote + " members=" + members.size()
						+ System.lineSeparator() + "[owner] " + ownerLine
						+ System.lineSeparator() + modeHint(mode, audio.isTransmitting()));
			}
			case ServerMessage.MemberJoined(MemberInfo member) -> announceJoin(member);
			case ServerMessage.MemberLeft(String memberId) -> announceLeave(memberId);
			case ServerMessage.FloorGranted _ -> {
				audio.setTransmitting(true);
				log("[floor granted] talking — type 't' to stop");
			}
			case ServerMessage.FloorDenied(String currentHolderId) ->
					log("[floor busy] currently held by " + name(currentHolderId));
			case ServerMessage.FloorTaken(String holderId) -> log("[talking] " + name(holderId));
			case ServerMessage.FloorIdle _ -> log("[floor free]");
			case ServerMessage.ModeChanged(ChannelMode mode) -> {
				currentMode = mode;
				// Match the browser: switching to full-duplex opens the mic (unless --muted); else it mutes.
				audio.setTransmitting(mode == ChannelMode.FULL_DUPLEX && !options.startMuted());
				log("[mode changed] now " + mode + System.lineSeparator()
						+ modeHint(mode, audio.isTransmitting()));
			}
			case ServerMessage.OwnerChanged(String ownerId) -> {
				this.ownerId = ownerId;
				log(selfId.equals(ownerId)
						? "[owner] you are now the owner — 'm <ptt|global|duplex>' to change the mode"
						: "[owner] channel owner is now " + name(ownerId));
			}
			case ServerMessage.SignalOffer _, ServerMessage.SignalAnswer _,
			     ServerMessage.SignalIce _ -> { /* WebRTC: not used by the relay client */ }
			case ServerMessage.ErrorMessage(String code, String message) -> {
				log("[error] " + code + ": " + message);
				// A passphrase mismatch is fatal: the channel requires a different --key, so there's nothing
				// to do but disconnect. getAndSet(false) makes this fire once (mirrors onConnectionLost).
				if ("passphrase_mismatch".equals(code) && running.getAndSet(false)) {
					log("Disconnecting — this channel needs a different --key.");
					System.exit(0);
				}
			}
		}
	}

	/// Resolves a member's display name from its session id, always suffixed with a short session-id prefix
	/// (the session id is the real identity — display names aren't unique), or the raw id if the name is unknown.
	private String name(String id) {
		String display = memberNames.get(id);
		return display == null
				? id
				: display + " (#" + id.substring(0, Math.min(8, id.length())) + ")";
	}

	private void announceJoin(MemberInfo member) {
		memberNames.put(member.id(), member.displayName());
		log("[+] " + name(member.id()));
	}

	private void announceLeave(String memberId) {
		log("[-] " + name(memberId));
		memberNames.remove(memberId);
	}

	/// Prints the current roster on demand (the 'w' command), sorted lexicographically by display name (then by
	/// id), each member shown via [#name] (display name + `#id` prefix) with `(you)` / `(owner)` markers.
	private void listMembers() {
		if (memberNames.isEmpty()) {
			log("[members] (none yet — join a channel first)");
			return;
		}
		log(memberNames.entrySet().stream()
				.sorted(Map.Entry.<String, String>comparingByValue(String.CASE_INSENSITIVE_ORDER)
						.thenComparing(Map.Entry.comparingByKey()))   // lexicographic by name, then id
				.map(entry -> {
					String id = entry.getKey();
					return name(id) + (id.equals(selfId)
							? " (you)"
							: id.equals(ownerId)
							  ? " (owner)"
							  : "");
				})
				.collect(Collectors.joining(
						System.lineSeparator() + "  - ",
						"[members] " + memberNames.size() + " in this channel:" + System.lineSeparator() + "  - ",
						"")));
	}

	// --- Console control --------------------------------------------------------------------------

	private void consoleLoop() {
		printHelp();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while (running.get() && (line = reader.readLine()) != null) {
				String[] parts = line.strip().split("\\s+", 2);
				switch (parts[0].toLowerCase(Locale.ROOT)) {
					case "t", "talk" -> toggleTalk();
					case "m", "mode" -> changeMode(parts.length > 1 ? parts[1] : "");
					case "f", "fidelity" -> toggleFidelity();
					case "w", "who", "members" -> listMembers();
					case "q", "quit", "exit" -> running.set(false);
					case "h", "help" -> printHelp();
					case "" -> { /* ignore blank lines */ }
					default ->
							System.out.println("Commands: 't' talk/stop, 'w' who's here, 'm <ptt|global|duplex>' mode, 'f' hi-fi, 'q' quit, 'h' help.");
				}
			}
		} catch (IOException _) {
			// stdin closed; fall through to shutdown
		}
	}

	private void toggleTalk() {
		if (audio.isTransmitting()) {
			audio.setTransmitting(false);
			if (currentMode != ChannelMode.FULL_DUPLEX) {
				enqueue(new ClientMessage.ReleaseFloor());
			}
			log("[stopped]");
		} else if (currentMode == ChannelMode.FULL_DUPLEX) {
			audio.setTransmitting(true);
			log("[talking]");
		} else {
			enqueue(new ClientMessage.RequestFloor());
			log("[requesting floor...]");
		}
	}

	/// Flips the hi-fi (Opus music vs voice) profile live; [AudioEngine] rebuilds the encoder on its next
	/// transmitted frame, so the change applies without reconnecting.
	private void toggleFidelity() {
		boolean hifi = audio.toggleHiFi();
		log("[hi-fi " + (hifi ? "on — music profile" : "off — voice profile") + "] (applies on the next transmitted frame)");
	}

	/// Asks the server to change the channel mode. Gated locally to the owner (the server enforces it
	/// too); the resulting [ServerMessage.ModeChanged] is what actually updates everyone's controls.
	private void changeMode(String arg) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can change the mode");
			return;
		}
		switch (arg.toLowerCase(Locale.ROOT)) {
			case "ptt", "multi" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));
			case "global" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.GLOBAL_PTT));
			case "duplex", "full" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));
			default -> System.out.println("Usage: m <ptt|global|duplex>");
		}
	}

	private WebSocket connect(String token) {
		return httpClient.newWebSocketBuilder()
				.buildAsync(
						URI.create(options.server().replaceFirst("^http", "ws") + "/ws/audio?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)),
						new ClientListener()
				)
				.join();
	}

	// --- helpers ----------------------------------------------------------------------------------

	private void enqueue(ClientMessage message) {
		sendQueue.offer(new Outbound.Text(JSON_MAPPER.writeValueAsString(message)));
	}

	private void sendJoin() {
		enqueue(new ClientMessage.Join(
				options.channel(),
				options.mode(),
				options.display(),
				crypto == null ? null : crypto.keyCheck()
		));
	}

	/// Tears the session down: stops the loops, closes the WebSocket, closes the [AudioEngine], and shuts the
	/// HttpClient down (bounded, so it can't hang on a slow server). Idempotent, so it is safe in a
	/// try-with-resources block (the launcher's) — note some paths exit the process directly via
	/// [#onConnectionLost] and so never reach here.
	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		running.set(false);
		// Closing the WebSocket ends the session — the bearer token is stateless and self-expiring, so
		// there is nothing to revoke server-side.
		if (webSocket != null) {
			webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		}
		audio.close();
		// HttpClient has been AutoCloseable since Java 21; shutting it down frees its selector/executor
		// threads. Its close() blocks until in-flight operations (the WebSocket included) finish, so bound the
		// wait: shut down gracefully, then force it if the close handshake doesn't drain in time — teardown
		// must never hang on a slow or vanished server.
		httpClient.shutdown();
		try {
			if (!httpClient.awaitTermination(HTTP_SHUTDOWN_GRACE)) {
				httpClient.shutdownNow();
			}
		} catch (InterruptedException _) {
			httpClient.shutdownNow();
			Thread.currentThread().interrupt();
		}
		System.out.println("Goodbye.");
	}

	/// Reacts to the WebSocket dropping. A user-initiated quit has already flipped `running` to false and
	/// is tearing down on the main thread, so this is a no-op for that path. Any other close means the
	/// server went away while we were still live — and because the console loop is parked in a
	/// non-interruptible `System.in` read, it can never observe the flag, so we stop the process here
	/// instead of hanging until the next keypress. `getAndSet` makes this fire exactly once.
	private void onConnectionLost() {
		if (running.getAndSet(false)) {
			log("Server connection lost — exiting.");
			System.exit(0);
		}
	}

	private sealed interface Outbound {
		record Text(String json) implements Outbound {
		}

		record Binary(byte[] data) implements Outbound {
		}
	}

	private final class ClientListener implements WebSocket.Listener {

		@SuppressWarnings("StringBufferField")
		private final StringBuilder textBuffer = new StringBuilder();
		private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

		@Override
		public void onOpen(WebSocket webSocket) {
			log("[connected]");
			sendJoin();
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			textBuffer.append(data);
			if (last) {
				String json = textBuffer.toString();
				textBuffer.setLength(0);
				try {
					handleServerMessage(json);
				} catch (RuntimeException e) {
					log("[warn] could not handle message: " + e.getMessage());
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			byte[] chunk = new byte[data.remaining()];
			data.get(chunk);
			binaryBuffer.writeBytes(chunk);
			if (last) {
				byte[] frame = binaryBuffer.toByteArray();
				binaryBuffer.reset();
				// Demultiplex by the server-prepended stream index, then strip it (before the decrypt branch)
				// so the body handed to the engine is the same [tag][payload] / E2EE envelope a sender produced.
				if (frame.length >= 2) {
					int sid = frame[0] & 0xFF;
					byte[] body = Arrays.copyOfRange(frame, 1, frame.length);
					if (crypto == null) {
						audio.play(sid, body);
					} else {
						try {
							audio.play(sid, crypto.decrypt(body));
						} catch (GeneralSecurityException _) {
							if (!warnedDecrypt) {
								warnedDecrypt = true;
								log("[warn] could not decrypt audio — confirm everyone uses the same --key, --channel, and --mode");
							}
						}
					}
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			log("[closed " + statusCode + (reason.isBlank() ? "" : " " + reason) + "]");
			onConnectionLost();
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			log("[error] " + error.getMessage());
			onConnectionLost();
		}
	}
}
