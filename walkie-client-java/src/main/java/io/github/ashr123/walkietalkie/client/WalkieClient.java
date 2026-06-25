package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.*;
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
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
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
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
			"yyyy-MM-dd HH:mm:ss,SSS", Locale.getDefault(Locale.Category.FORMAT));
	/// Marker substrings of well-known virtual / loopback audio drivers that present as capture devices but
	/// usually carry no microphone signal (they capture silence unless an app routes through them). The
	/// no-`--input` auto-selection skips these so it lands on a real mic instead of, e.g., ZoomAudioDevice.
	private static final List<String> VIRTUAL_DEVICE_MARKERS = List.of(
			"zoomaudiodevice", "blackhole", "soundflower", "loopback", "vb-cable", "vb-audio",
			"aggregate", "multi-output", "teams audio", "krisp", "ndi", "obs virtual", "background music");

	private final ClientOptions options;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();
	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean transmitting = new AtomicBoolean(false);
	private final BlockingQueue<Outbound> sendQueue = new LinkedBlockingQueue<>();
	private final BlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>();
	private final Map<String, String> memberNames = new ConcurrentHashMap<>(); // session id -> display name
	/// Toggled live from the console ('f'); read by the capture thread, which rebuilds the encoder when it changes.
	private final AtomicBoolean highFidelity;
	private int channels;          // 1 or 2, negotiated against the audio device
	private int frameSamples;      // SAMPLES_PER_CHANNEL * channels (interleaved)
	private int pcmFrameBytes;     // frameSamples * 2
	private AudioFormat format;
	private OpusEncoder encoder;   // confined to the capture thread
	private OpusDecoder decoder;   // confined to the playback thread
	private TargetDataLine mic;
	private Mixer.Info inputMixerInfo;  // resolved capture device (null = system default)
	private SourceDataLine speaker;
	private WebSocket webSocket;
	private volatile String token;
	private volatile String selfId = "";
	private volatile String ownerId;
	private volatile ChannelMode currentMode;
	private FrameCrypto crypto;          // AES-256-GCM E2EE, or null when no passphrase; set before the loops start
	private volatile boolean warnedDecrypt; // listener thread only today, but volatile so the warn-once intent survives a threading refactor

	public WalkieClient(ClientOptions options) {
		this.options = options;
		this.currentMode = options.mode();
		this.highFidelity = new AtomicBoolean(options.highFidelity());
	}

	private static boolean lineSupported(Mixer.Info mixerInfo, DataLine.Info lineInfo) {
		return mixerInfo == null
				? AudioSystem.isLineSupported(lineInfo)
				: AudioSystem.getMixer(mixerInfo).isLineSupported(lineInfo);
	}

	// --- HTTP login -------------------------------------------------------------------------------

	/// Lists the audio mixers that expose a capture line, for use with `--input`.
	static void listInputDevices() {
		System.out.println("Available input devices (match with --input \"<name>\"):");
		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			if (AudioSystem.getMixer(info).getTargetLineInfo().length > 0) {
				System.out.println("  - " + info.getName());
			}
		}
	}

	// --- Audio format + codec ---------------------------------------------------------------------

	/// Prints a status line prefixed with the local timestamp (`yyyy-MM-dd HH:mm:ss,SSS`).
	private static void log(String message) {
		System.out.println(LocalDateTime.now().format(DATE_TIME_FORMATTER) + " " + message);
	}

	private static String modeHint(ChannelMode mode) {
		return mode == ChannelMode.FULL_DUPLEX
				? "Full-duplex: type 't' to start/stop your mic."
				: "Push-to-talk: type 't' to grab/release the floor.";
	}

	public void run() throws Exception {
		System.out.println("Connecting to " + options.server() + " as '" + options.display() + "' ...");
		token = login();
		negotiateAudioFormat();
		setupCodec();
		openAudio();
		crypto = setupCrypto();
		System.out.println("Audio: Opus 48 kHz + FEC, " + (channels == 2 ? "stereo" : "mono")
				+ " (" + (highFidelity.get() ? "music" : "voice") + " profile)"
				+ (crypto != null ? ", end-to-end encrypted (AES-256-GCM)" : ""));

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

	/// Resolves the input device (`--input`, or the system default) and picks stereo only when THAT mic
	/// and the playback device both support a 48 kHz stereo line — otherwise mono. Negotiating against the
	/// chosen device (not "any device on the system") avoids selecting stereo for a mono mic.
	private void negotiateAudioFormat() {
		inputMixerInfo = resolveInputMixer();
		AudioFormat stereo = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
		boolean stereoSupported =
				lineSupported(inputMixerInfo, new DataLine.Info(TargetDataLine.class, stereo))
						&& AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, stereo));
		channels = stereoSupported ? 2 : 1;
		format = new AudioFormat(SAMPLE_RATE, 16, channels, true, false);
		frameSamples = SAMPLES_PER_CHANNEL * channels;
		pcmFrameBytes = frameSamples * 2;
	}

	private String login() throws IOException, InterruptedException {
		// Login takes no input: it just mints a signed, short-lived token. The token is an opaque string.
		HttpRequest request = HttpRequest.newBuilder(URI.create(options.server() + "/api/auth/login"))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Login failed: HTTP " + response.statusCode() + " " + response.body());
		}
		return jsonMapper.readValue(response.body(), LoginResponse.class).token();
	}

	// --- WebSocket --------------------------------------------------------------------------------

	/// Whether `name` looks like a virtual/loopback audio driver (see [#VIRTUAL_DEVICE_MARKERS]) rather than a
	/// real microphone, so the no-`--input` auto-selection can skip it.
	private static boolean isVirtualDevice(String name) {
		return VIRTUAL_DEVICE_MARKERS.parallelStream().unordered()
				.anyMatch(name.toLowerCase(Locale.ROOT)::contains);
	}

	/// Resolves the capture device, logging the choice and why. With `--input`, returns the first
	/// capture-capable mixer whose name contains it. Without `--input` (or when it doesn't match), auto-selects
	/// the first *real* mic — skipping known virtual/loopback drivers (ZoomAudioDevice, BlackHole, aggregates,
	/// …) that the JVM might otherwise default to and that capture silence — preferring one that reports 48 kHz
	/// support. Returns `null` only when nothing suitable is found, letting [AudioSystem#getLine] pick the raw default.
	private Mixer.Info resolveInputMixer() {
		String wanted = options.inputDevice();
		boolean wantSpecific = wanted != null && !wanted.isBlank();
		DataLine.Info captureLine = new DataLine.Info(TargetDataLine.class, new AudioFormat(SAMPLE_RATE, 16, 1, true, false));

		Mixer.Info firstReal48k = null;   // non-virtual mic reporting 48 kHz capture support — preferred
		Mixer.Info firstRealAny = null;   // any non-virtual mic — fallback if none confirm 48 kHz
		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			Mixer mixer = AudioSystem.getMixer(info);
			if (mixer.getTargetLineInfo().length == 0) {
				continue;   // not a capture device
			}
			if (wantSpecific && info.getName().toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
				System.out.println("Capturing from input device: " + info.getName() + " (matched --input \"" + wanted + "\")");
				return info;
			}
			if (!isVirtualDevice(info.getName())) {
				if (firstRealAny == null) {
					firstRealAny = info;
				}
				if (firstReal48k == null && mixer.isLineSupported(captureLine)) {
					firstReal48k = info;
				}
			}
		}
		if (wantSpecific) {
			System.out.println("No input device matching '" + wanted + "'; auto-selecting a real mic instead.");
		}
		Mixer.Info auto = firstReal48k != null ? firstReal48k : firstRealAny;
		if (auto != null) {
			System.out.println("Capturing from input device: " + auto.getName()
					+ " (auto-selected, skipping virtual devices — pass --input to override, --list-inputs to see all)");
			return auto;
		}
		System.out.println("No real capture device identified; using the system default (may be silent — see --list-inputs).");
		return null;
	}

	/// Builds the AES-256-GCM frame cipher from `--key` (or the WALKIE_KEY env var), or null to disable
	/// E2EE. Salted with the effective channel (the server forces "global" for global mode), so every
	/// client in the channel derives the same key.
	private FrameCrypto setupCrypto() throws GeneralSecurityException {
		String passphrase = options.key();
		return passphrase == null || passphrase.isBlank()
				? null
				: FrameCrypto.fromPassphrase(
				passphrase,
				options.mode() == ChannelMode.GLOBAL_PTT ? "global" : options.channel()
		);
	}

	// --- Audio ------------------------------------------------------------------------------------

	private void setupCodec() throws OpusException {
		configureEncoder();
		decoder = new OpusDecoder((int) SAMPLE_RATE, channels);
	}

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

	private void openAudio() throws LineUnavailableException {
		DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
		DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
		if (!AudioSystem.isLineSupported(micInfo) || !AudioSystem.isLineSupported(speakerInfo)) {
			throw new LineUnavailableException(
					"48 kHz " + channels + "-channel PCM is not supported by the default audio device");
		}
		mic = openMic(micInfo);
		mic.open(format);
		mic.start();
		speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
		speaker.open(format);
		speaker.start();
	}

	/// (Re)builds the Opus encoder for the current [#highFidelity] setting — music profile (full-band audio)
	/// when on, voice profile (VoIP) when off. Called once at startup and again by the capture thread when
	/// the setting is toggled live, so it is only ever touched from that one thread (the encoder is confined
	/// to it). The Opus *application* mode is fixed at construction, so a live switch rebuilds the encoder.
	private void configureEncoder() throws OpusException {
		boolean hifi = highFidelity.get();
		encoder = new OpusEncoder(
				(int) SAMPLE_RATE,
				channels,
				hifi ? OpusApplication.OPUS_APPLICATION_AUDIO : OpusApplication.OPUS_APPLICATION_VOIP
		);
		encoder.setBitrate(channels == 2 ? STEREO_BITRATE : MONO_BITRATE);
		encoder.setComplexity(10);
		encoder.setUseInbandFEC(true);
		encoder.setPacketLossPercent(10);
		encoder.setSignalType(hifi ? OpusSignal.OPUS_SIGNAL_MUSIC : OpusSignal.OPUS_SIGNAL_VOICE);
	}

	/// Opens the capture line of the resolved input device (already logged in [#resolveInputMixer]), or the
	/// raw system default when none was resolved. The line uses the negotiated [#format].
	private TargetDataLine openMic(DataLine.Info micInfo) throws LineUnavailableException {
		return (TargetDataLine) (inputMixerInfo != null
				? AudioSystem.getMixer(inputMixerInfo).getLine(micInfo)
				: AudioSystem.getLine(micInfo));
	}

	private void captureLoop() {
		byte[] buffer = new byte[pcmFrameBytes];
		short[] pcm = new short[frameSamples];
		byte[] packet = new byte[MAX_PACKET_BYTES];
		boolean appliedHifi = highFidelity.get();
		while (running.get()) {
			// line drained/closed; re-check running flag
			if (!readFully(buffer) ||
					// keep the mic flowing but don't transmit
					!transmitting.get()) {
				continue;
			}
			// Apply a live hi-fi toggle: rebuild the encoder (music vs voice profile) when it changed.
			if (highFidelity.get() != appliedHifi) {
				try {
					configureEncoder();
					appliedHifi = highFidelity.get();
				} catch (OpusException _) {
					// keep the existing encoder if the rebuild fails
				}
			}
			ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
			try {
				// frame size is samples *per channel*; pcm holds frameSamples interleaved shorts.
				int len = encoder.encode(pcm, 0, SAMPLES_PER_CHANNEL, packet, 0, packet.length);
				byte[] frame = new byte[len + 1];
				frame[0] = CODEC_OPUS;
				System.arraycopy(packet, 0, frame, 1, len);
				sendQueue.offer(new Outbound.Binary(crypto == null ? frame : crypto.encrypt(frame)));
			} catch (OpusException | GeneralSecurityException _) {
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

	// --- Server messages --------------------------------------------------------------------------

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

	private void handleServerMessage(String json) {
		switch (jsonMapper.readValue(json, ServerMessage.class)) {
			case ServerMessage.Joined(
					String selfId, String channel, ChannelMode mode, String ownerId, List<MemberInfo> members
			) -> {
				this.selfId = selfId;
				this.ownerId = ownerId;
				this.currentMode = mode;
				memberNames.clear();
				members.forEach(member -> memberNames.put(member.id(), member.displayName()));
				log("[joined] channel=" + channel + " mode=" + mode + " members=" + members.size()
						+ (selfId.equals(ownerId) ? " (you own this channel)" : "") + System.lineSeparator()
						+ modeHint(mode));
			}
			case ServerMessage.MemberJoined(MemberInfo member) -> announceJoin(member);
			case ServerMessage.MemberLeft(String memberId) -> announceLeave(memberId);
			case ServerMessage.FloorGranted _ -> {
				transmitting.set(true);
				log("[floor granted] talking — type 't' to stop");
			}
			case ServerMessage.FloorDenied(String currentHolderId, _) ->
					log("[floor busy] currently held by " + name(currentHolderId));
			case ServerMessage.FloorTaken(String holderId) -> log("[talking] " + name(holderId));
			case ServerMessage.FloorIdle _ -> log("[floor free]");
			case ServerMessage.ModeChanged(ChannelMode mode) -> {
				currentMode = mode;
				transmitting.set(false);
				log("[mode changed] now " + mode + System.lineSeparator()
						+ modeHint(mode));
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

	/// Resolves a member's display name from its session id, falling back to the raw id if unknown. When
	/// more than one member shares that display name, a short session-id prefix is appended so the two are
	/// distinguishable — the session id is the real identity.
	private String name(String id) {
		String display = memberNames.get(id);
		if (display == null) {
			return id;
		}
		long sharing = memberNames.values().stream().filter(display::equals).count();
		return sharing > 1 ? display + " (#" + id.substring(0, Math.min(8, id.length())) + ")" : display;
	}

	private void announceJoin(MemberInfo member) {
		memberNames.put(member.id(), member.displayName());
		log("[+] " + name(member.id()));
	}

	private void announceLeave(String memberId) {
		log("[-] " + name(memberId));
		memberNames.remove(memberId);
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
					case "q", "quit", "exit" -> running.set(false);
					case "h", "help" -> printHelp();
					case "" -> { /* ignore blank lines */ }
					default ->
							System.out.println("Commands: 't' talk/stop, 'm <ptt|global|duplex>' mode, 'f' hi-fi, 'q' quit, 'h' help.");
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
			log("[stopped]");
		} else if (currentMode == ChannelMode.FULL_DUPLEX) {
			transmitting.set(true);
			log("[talking]");
		} else {
			enqueue(new ClientMessage.RequestFloor());
			log("[requesting floor...]");
		}
	}

	/// Flips the hi-fi (Opus music vs voice) profile live. The capture thread rebuilds the encoder on its
	/// next transmitted frame, so the change applies without reconnecting.
	private void toggleFidelity() {
		boolean hifi = !highFidelity.get();
		highFidelity.set(hifi);
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

	private void printHelp() {
		System.out.println("""
				--------------------------------------------------------------------------------------------------
				 Commands:  t = talk/stop   m <ptt|global|duplex> = mode   f = hi-fi on/off   q = quit   h = help
				--------------------------------------------------------------------------------------------------""");
	}

	// --- helpers ----------------------------------------------------------------------------------

	private void enqueue(ClientMessage message) {
		sendQueue.offer(new Outbound.Text(jsonMapper.writeValueAsString(message)));
	}

	private void sendJoin() {
		enqueue(new ClientMessage.Join(options.channel(), options.mode(), options.display(),
				crypto == null ? null : crypto.keyCheck()));
	}

	private void shutdown() {
		running.set(false);
		// Closing the WebSocket ends the session — the bearer token is stateless and self-expiring, so
		// there is nothing to revoke server-side.
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
				if (crypto == null) {
					playbackQueue.offer(frame);
				} else {
					try {
						playbackQueue.offer(crypto.decrypt(frame));
					} catch (GeneralSecurityException _) {
						if (!warnedDecrypt) {
							warnedDecrypt = true;
							log("[warn] could not decrypt audio — confirm everyone uses the same --key, --channel, and --mode");
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
