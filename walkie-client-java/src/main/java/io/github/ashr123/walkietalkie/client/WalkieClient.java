package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.LoginResponse;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import io.github.jaredmdobson.concentus.*;
import tools.jackson.databind.json.JsonMapper;

import javax.sound.sampled.*;
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
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// Console walkie-talkie client. Uses the WebSocket-relay transport (the only one available to a
/// pure-Java client; WebRTC is browser-to-browser). Captures the microphone with
/// [javax.sound.sampled], encodes 20 ms frames as Opus (Concentus) with in-band FEC, streams them
/// over the JDK's built-in WebSocket client, and decodes/plays incoming frames. All loops run on
/// Java 25 virtual threads.
///
/// Audio is 48 kHz fullband — **stereo when the capture/playback device supports it, otherwise mono**.
/// Each relay frame is prefixed with a 1-byte codec tag (1 = Opus, 2 = raw PCM S16LE) so it
/// interoperates with the browser client. Opus decoders emit their configured channel count and
/// down/upmix as needed, so a stereo client and a mono client still understand each other.
public final class WalkieClient {

	private static final float SAMPLE_RATE = 48_000f;
	private static final int SAMPLES_PER_CHANNEL = 960;    // 20 ms @ 48 kHz, per channel
	private static final int MAX_PACKET_BYTES = 4000;      // generous upper bound for one Opus packet
	private static final int MONO_BITRATE = 64_000;
	private static final int STEREO_BITRATE = 128_000;
	private static final byte CODEC_OPUS = 1;
	private static final byte CODEC_PCM = 2;

	private final ClientOptions options;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();
	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean transmitting = new AtomicBoolean(false);
	private final BlockingQueue<Outbound> sendQueue = new LinkedBlockingQueue<>();
	private final BlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>();

	private int channels;          // 1 or 2, negotiated against the audio device
	private int frameSamples;      // SAMPLES_PER_CHANNEL * channels (interleaved)
	private int pcmFrameBytes;     // frameSamples * 2
	private AudioFormat format;
	private OpusEncoder encoder;   // confined to the capture thread
	private OpusDecoder decoder;   // confined to the playback thread
	private TargetDataLine mic;
	private SourceDataLine speaker;
	private WebSocket webSocket;
	private volatile String token;
	private volatile String selfId = "";
	private volatile String ownerId;
	private volatile ChannelMode currentMode;

	public WalkieClient(ClientOptions options) {
		this.options = options;
		this.currentMode = options.mode();
	}

	public void run() throws Exception {
		System.out.println("Logging in as '" + options.user() + "' at " + options.server() + " ...");
		token = login();
		negotiateAudioFormat();
		setupCodec();
		openAudio();
		System.out.println("Audio: Opus 48 kHz + FEC, " + (channels == 2 ? "stereo" : "mono")
				+ " (" + (options.highFidelity() ? "music" : "voice") + " profile)");

		Thread.ofVirtual().name("ptt-sender").start(this::senderLoop);
		Thread.ofVirtual().name("ptt-playback").start(this::playbackLoop);
		Thread.ofVirtual().name("ptt-capture").start(this::captureLoop);

		connect(token);

		try {
			consoleLoop();
		} finally {
			shutdown();
		}
	}

	// --- HTTP login -------------------------------------------------------------------------------

	private String login() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(options.server() + "/api/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(Map.of("username", options.user()))))
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Login failed: HTTP " + response.statusCode() + " " + response.body());
		}
		// The token is an opaque string in headers/query params; the wire record types it as a UUID.
		return jsonMapper.readValue(response.body(), LoginResponse.class).token().toString();
	}

	/// Best-effort token revocation on a graceful quit. An abrupt disconnect is still covered by the
	/// server evicting the token when the WebSocket closes.
	private void logout() {
		if (token == null) {
			return;
		}
		try {
			httpClient.send(
					HttpRequest.newBuilder(URI.create(options.server() + "/api/auth/logout"))
							.header("Authorization", "Bearer " + token)
							.POST(HttpRequest.BodyPublishers.noBody())
							.build(),
					HttpResponse.BodyHandlers.discarding()
			);
		} catch (IOException _) {
			// best-effort
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}
	}

	// --- Audio format + codec ---------------------------------------------------------------------

	/// Picks stereo when both the capture and playback devices support a 48 kHz stereo line, else mono.
	private void negotiateAudioFormat() {
		AudioFormat stereo = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
		boolean stereoSupported =
				AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, stereo))
						&& AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, stereo));
		channels = stereoSupported ? 2 : 1;
		format = new AudioFormat(SAMPLE_RATE, 16, channels, true, false);
		frameSamples = SAMPLES_PER_CHANNEL * channels;
		pcmFrameBytes = frameSamples * 2;
	}

	private void setupCodec() throws OpusException {
		encoder = new OpusEncoder(
				(int) SAMPLE_RATE,
				channels,
				options.highFidelity()
						? OpusApplication.OPUS_APPLICATION_AUDIO
						: OpusApplication.OPUS_APPLICATION_VOIP
		);
		encoder.setBitrate(channels == 2 ? STEREO_BITRATE : MONO_BITRATE);
		encoder.setComplexity(10);
		encoder.setUseInbandFEC(true);
		encoder.setPacketLossPercent(10);
		encoder.setSignalType(options.highFidelity() ? OpusSignal.OPUS_SIGNAL_MUSIC : OpusSignal.OPUS_SIGNAL_VOICE);
		decoder = new OpusDecoder((int) SAMPLE_RATE, channels);
	}

	// --- WebSocket --------------------------------------------------------------------------------

	private void connect(String token) {
		webSocket = httpClient.newWebSocketBuilder()
				.buildAsync(
						URI.create(options.server().replaceFirst("^http", "ws") + "/ws/audio?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)),
						new ClientListener()
				)
				.join();
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

	// --- Audio ------------------------------------------------------------------------------------

	private void openAudio() throws LineUnavailableException {
		DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
		DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
		if (!AudioSystem.isLineSupported(micInfo) || !AudioSystem.isLineSupported(speakerInfo)) {
			throw new LineUnavailableException(
					"48 kHz " + channels + "-channel PCM is not supported by the default audio device");
		}
		mic = (TargetDataLine) AudioSystem.getLine(micInfo);
		mic.open(format);
		mic.start();
		speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
		speaker.open(format);
		speaker.start();
	}

	private void captureLoop() {
		byte[] buffer = new byte[pcmFrameBytes];
		short[] pcm = new short[frameSamples];
		byte[] packet = new byte[MAX_PACKET_BYTES];
		while (running.get()) {
			// line drained/closed; re-check running flag
			if (!readFully(buffer) ||
					// keep the mic flowing but don't transmit
					!transmitting.get()) {
				continue;
			}
			// keep the mic flowing but don't transmit
			ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
			try {
				// frame size is samples *per channel*; pcm holds frameSamples interleaved shorts.
				int len = encoder.encode(pcm, 0, SAMPLES_PER_CHANNEL, packet, 0, packet.length);
				byte[] frame = new byte[len + 1];
				frame[0] = CODEC_OPUS;
				System.arraycopy(packet, 0, frame, 1, len);
				sendQueue.offer(new Outbound.Binary(frame));
			} catch (OpusException _) {
				// drop this frame; keep going
			}
		}
	}

	private boolean readFully(byte[] buffer) {
		int read = 0;
		while (read < buffer.length && running.get()) {
			int n = mic.read(buffer, read, buffer.length - read);
			if (n <= 0) {
				return false;
			}
			read += n;
		}
		return read == buffer.length;
	}

	private void playbackLoop() {
		short[] pcm = new short[frameSamples];
		byte[] out = new byte[pcmFrameBytes];
		try {
			while (running.get()) {
				byte[] frame = playbackQueue.poll(200, TimeUnit.MILLISECONDS);
				if (frame == null || frame.length < 2) {
					continue;
				}
				switch (frame[0]) {
					case CODEC_PCM -> playPcmFallback(frame);
					case CODEC_OPUS -> {
						try {
							int perChannel = decoder.decode(frame, 1, frame.length - 1, pcm, 0, SAMPLES_PER_CHANNEL, false);
							int bytes = perChannel * channels * 2;
							ByteBuffer.wrap(out, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, perChannel * channels);
							speaker.write(out, 0, bytes);
						} catch (OpusException _) {
							// drop undecodable packet
						}
					}
					default -> {
						// unknown codec tag; ignore
					}
				}
			}
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}
	}

	/// Plays a raw-PCM fallback frame (sent only by a browser without WebCodecs, always mono 48 kHz).
	/// On a stereo line each mono sample is duplicated into the left/right pair.
	private void playPcmFallback(byte[] frame) {
		int payloadBytes = frame.length - 1;
		if (channels == 1) {
			speaker.write(frame, 1, payloadBytes);
			return;
		}
		int monoSamples = payloadBytes / 2;
		byte[] stereo = new byte[monoSamples * 4];
		for (int i = 0; i < monoSamples; i++) {
			int src = 1 + i * 2;
			int dst = i * 4;
			stereo[dst] = frame[src];
			stereo[dst + 1] = frame[src + 1];
			stereo[dst + 2] = frame[src];
			stereo[dst + 3] = frame[src + 1];
		}
		speaker.write(stereo, 0, stereo.length);
	}

	// --- Server messages --------------------------------------------------------------------------

	private void handleServerMessage(String json) {
		switch (jsonMapper.readValue(json, ServerMessage.class)) {
			case ServerMessage.Joined(String selfId, String channel, ChannelMode mode, String ownerId, List<MemberInfo> members) -> {
				this.selfId = selfId;
				this.ownerId = ownerId;
				this.currentMode = mode;
				System.out.println("[joined] channel=" + channel + " mode=" + mode + " members=" + members.size()
						+ (selfId.equals(ownerId) ? " (you own this channel)" : "") + System.lineSeparator()
						+ modeHint(mode));
			}
			case ServerMessage.MemberJoined(MemberInfo member) -> System.out.println("[+] " + describe(member));
			case ServerMessage.MemberLeft(String memberId) -> System.out.println("[-] member " + memberId);
			case ServerMessage.FloorGranted _ -> {
				transmitting.set(true);
				System.out.println("[floor granted] talking — type 't' to stop");
			}
			case ServerMessage.FloorDenied(String currentHolderId, _) ->
					System.out.println("[floor busy] currently held by " + currentHolderId);
			case ServerMessage.FloorTaken(String holderId) -> System.out.println("[talking] " + holderId);
			case ServerMessage.FloorIdle _ -> System.out.println("[floor free]");
			case ServerMessage.ModeChanged(ChannelMode mode) -> {
				currentMode = mode;
				transmitting.set(false);
				System.out.println("[mode changed] now " + mode + System.lineSeparator()
						+ modeHint(mode));
			}
			case ServerMessage.OwnerChanged(String ownerId) -> {
				this.ownerId = ownerId;
				System.out.println(selfId.equals(ownerId)
						? "[owner] you are now the owner — 'm <ptt|global|duplex>' to change the mode"
						: "[owner] channel owner is now " + ownerId);
			}
			case ServerMessage.SignalOffer _, ServerMessage.SignalAnswer _,
			     ServerMessage.SignalIce _ -> { /* WebRTC: not used by the relay client */ }
			case ServerMessage.ErrorMessage(String code, String message) ->
					System.out.println("[error] " + code + ": " + message);
		}
	}

	private String describe(MemberInfo member) {
		return member.displayName() + " (" + member.id() + ")";
	}

	private static String modeHint(ChannelMode mode) {
		return mode == ChannelMode.FULL_DUPLEX
				? "Full-duplex: type 't' to start/stop your mic."
				: "Push-to-talk: type 't' to grab/release the floor.";
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
					case "q", "quit", "exit" -> running.set(false);
					case "h", "help" -> printHelp();
					case "" -> { /* ignore blank lines */ }
					default -> System.out.println("Commands: 't' talk/stop, 'm <ptt|global|duplex>' mode, 'q' quit, 'h' help.");
				}
			}
		} catch (IOException _) {
			// stdin closed; fall through to shutdown
		}
	}

	private void toggleTalk() {
		if (transmitting.get()) {
			transmitting.set(false);
			if (currentMode != ChannelMode.FULL_DUPLEX) {
				enqueue(new ClientMessage.ReleaseFloor());
			}
			System.out.println("[stopped]");
		} else if (currentMode == ChannelMode.FULL_DUPLEX) {
			transmitting.set(true);
			System.out.println("[talking]");
		} else {
			enqueue(new ClientMessage.RequestFloor());
			System.out.println("[requesting floor...]");
		}
	}

	/// Asks the server to change the channel mode. Gated locally to the owner (the server enforces it
	/// too); the resulting [ServerMessage.ModeChanged] is what actually updates everyone's controls.
	private void changeMode(String arg) {
		if (!selfId.equals(ownerId)) {
			System.out.println("[denied] only the channel owner can change the mode");
			return;
		}
		switch (arg.toLowerCase(Locale.ROOT)) {
			case "ptt", "multi" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));
			case "global" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.GLOBAL_PTT));
			case "duplex", "full" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));
			default -> System.out.println("Usage: m <ptt|global|duplex>");
		}
	}

	private void printHelp() {
		System.out.println("""
				-------------------------------------------------------------------------------
				 Commands:  t = talk/stop   m <ptt|global|duplex> = mode   q = quit   h = help
				-------------------------------------------------------------------------------""");
	}

	// --- helpers ----------------------------------------------------------------------------------

	private void enqueue(ClientMessage message) {
		sendQueue.offer(new Outbound.Text(jsonMapper.writeValueAsString(message)));
	}

	private void sendJoin() {
		enqueue(new ClientMessage.Join(options.channel(), options.mode(), options.display()));
	}

	private void shutdown() {
		running.set(false);
		logout();
		if (webSocket != null) {
			webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		}
		if (mic != null) {
			mic.stop();
			mic.close();
		}
		if (speaker != null) {
			speaker.drain();
			speaker.stop();
			speaker.close();
		}
		System.out.println("Goodbye.");
	}

	private sealed interface Outbound {
		record Text(String json) implements Outbound {
		}

		record Binary(byte[] data) implements Outbound {
		}
	}

	private final class ClientListener implements WebSocket.Listener {

		private final StringBuilder textBuffer = new StringBuilder();
		private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

		@Override
		public void onOpen(WebSocket webSocket) {
			System.out.println("[connected]");
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
					System.out.println("[warn] could not handle message: " + e.getMessage());
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
				playbackQueue.offer(binaryBuffer.toByteArray());
				binaryBuffer.reset();
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			System.out.println("[closed " + statusCode + (reason.isBlank() ? "" : " " + reason) + "]");
			running.set(false);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			System.out.println("[error] " + error.getMessage());
			running.set(false);
		}
	}
}
