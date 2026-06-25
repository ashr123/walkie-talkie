package io.github.ashr123.walkietalkie.client;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Microphone capture, Opus (de)coding and speaker playback for the desktop client — everything between
/// the audio devices and the wire. It is **transport- and crypto-agnostic**: each captured frame (a
/// `[1-byte codec tag][Opus payload]`) is handed to the `frameSink` for the caller to encrypt and send,
/// and already-decrypted frames are pushed back in via [#play] to be decoded and played. Capture and
/// playback each run on their own Java 25 virtual thread; the Opus encoder is confined to the capture
/// thread and the decoder to the playback thread.
///
/// Audio is 48 kHz fullband — **stereo when the capture/playback device supports it, otherwise mono**.
/// Opus decoders emit their configured channel count and down/upmix as needed, so a stereo client and a
/// mono client still understand each other.
final class AudioEngine implements AutoCloseable {

	private static final float SAMPLE_RATE = 48_000f;
	private static final int SAMPLES_PER_CHANNEL = 960;    // 20 ms @ 48 kHz, per channel
	private static final int MAX_PACKET_BYTES = 4000;      // generous upper bound for one Opus packet
	private static final int MONO_BITRATE = 64_000;
	private static final int STEREO_BITRATE = 128_000;
	private static final byte CODEC_OPUS = 1;
	private static final byte CODEC_PCM = 2;
	/// Marker substrings of well-known virtual / loopback audio drivers that present as capture devices but
	/// usually carry no microphone signal (they capture silence unless an app routes through them). The
	/// no-`--input` auto-selection skips these so it lands on a real mic instead of, e.g., ZoomAudioDevice.
	private static final List<String> VIRTUAL_DEVICE_MARKERS = List.of(
			"zoomaudiodevice", "blackhole", "soundflower", "loopback", "vb-cable", "vb-audio",
			"aggregate", "multi-output", "teams audio", "krisp", "ndi", "obs virtual", "background music");

	private final ClientOptions options;
	private final Consumer<byte[]> frameSink;            // captured [tag][opus] frames -> caller (encrypt + send)
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean closed = new AtomicBoolean(false);   // guards close() so it is idempotent
	private final AtomicBoolean transmitting = new AtomicBoolean(false);
	/// Toggled live ('f' on the console); the capture thread rebuilds the encoder when it changes.
	private final AtomicBoolean highFidelity;
	private final BlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>();

	private int channels;          // 1 or 2, negotiated against the audio device
	private int frameSamples;      // SAMPLES_PER_CHANNEL * channels (interleaved)
	private int pcmFrameBytes;     // frameSamples * 2
	private AudioFormat format;
	private Mixer.Info inputMixerInfo;  // resolved capture device (null = system default)
	private OpusEncoder encoder;   // confined to the capture thread
	private OpusDecoder decoder;   // confined to the playback thread
	private TargetDataLine mic;
	private SourceDataLine speaker;

	AudioEngine(ClientOptions options, Consumer<byte[]> frameSink) {
		this.options = options;
		this.frameSink = frameSink;
		this.highFidelity = new AtomicBoolean(options.highFidelity());
	}

	/// Lists the audio mixers that expose a capture line, for use with `--input`.
	static void listInputDevices() {
		System.out.println("Available input devices (match with --input \"<name>\"):");
		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			if (AudioSystem.getMixer(info).getTargetLineInfo().length > 0) {
				System.out.println("  - " + info.getName());
			}
		}
	}

	private static boolean lineSupported(Mixer.Info mixerInfo, DataLine.Info lineInfo) {
		return mixerInfo == null
				? AudioSystem.isLineSupported(lineInfo)
				: AudioSystem.getMixer(mixerInfo).isLineSupported(lineInfo);
	}

	/// Whether `name` looks like a virtual/loopback audio driver (see [#VIRTUAL_DEVICE_MARKERS]) rather than a
	/// real microphone, so the no-`--input` auto-selection can skip it.
	private static boolean isVirtualDevice(String name) {
		return VIRTUAL_DEVICE_MARKERS.parallelStream().unordered()
				.anyMatch(name.toLowerCase(Locale.ROOT)::contains);
	}

	// --- lifecycle --------------------------------------------------------------------------------

	/// Negotiates the format, opens the mic + speaker, builds the codec, and starts the capture and
	/// playback loops on virtual threads. Call once, before frames flow.
	void start() throws LineUnavailableException, OpusException {
		negotiateAudioFormat();
		setupCodec();
		openAudio();
		Thread.ofVirtual().name("ptt-playback").start(this::playbackLoop);
		Thread.ofVirtual().name("ptt-capture").start(this::captureLoop);
	}

	/// Stops the capture/playback loops and releases the audio lines. Idempotent (a second call is a no-op),
	/// so it works in a try-with-resources block and is safe to call from [WalkieClient]'s shutdown path.
	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;   // already closed
		}
		running.set(false);
		if (mic != null) {
			mic.stop();
			mic.close();
		}
		if (speaker != null) {
			speaker.drain();
			speaker.stop();
			speaker.close();
		}
	}

	// --- caller-facing API ------------------------------------------------------------------------

	/// A human-readable summary for the startup banner, e.g. `Opus 48 kHz + FEC, mono (voice profile)`.
	String description() {
		return "Opus 48 kHz + FEC, " + (channels == 2 ? "stereo" : "mono")
				+ " (" + (highFidelity.get() ? "music" : "voice") + " profile)";
	}

	/// Queues an already-decrypted `[codec tag][payload]` frame for decoding and playback.
	void play(byte[] frame) {
		playbackQueue.offer(frame);
	}

	/// Whether captured audio is currently being emitted to the sink (mic is "live").
	boolean isTransmitting() {
		return transmitting.get();
	}

	void setTransmitting(boolean on) {
		transmitting.set(on);
	}

	/// Flips the hi-fi (Opus music vs voice) profile; the capture thread rebuilds the encoder on its next
	/// transmitted frame, so the change applies without reconnecting. Returns the new state.
	boolean toggleHiFi() {
		boolean hifi = !highFidelity.get();
		highFidelity.set(hifi);
		return hifi;
	}

	// --- device negotiation -----------------------------------------------------------------------

	/// Resolves the input device (`--input`, or the system default) and picks stereo only when THAT mic
	/// and the playback device both support a 48 kHz stereo line — otherwise mono. Negotiating against the
	/// chosen device (not "any device on the system") avoids selecting stereo for a mono mic.
	private void negotiateAudioFormat() {
		inputMixerInfo = resolveInputMixer();
		AudioFormat stereo = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
		channels = lineSupported(inputMixerInfo, new DataLine.Info(TargetDataLine.class, stereo))
				&& AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, stereo))
				? 2
				: 1;
		format = new AudioFormat(SAMPLE_RATE, 16, channels, true, false);
		frameSamples = SAMPLES_PER_CHANNEL * channels;
		pcmFrameBytes = frameSamples * 2;
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

	/// Opens the capture line of the resolved input device (already logged in [#resolveInputMixer]), or the
	/// raw system default when none was resolved. The line uses the negotiated [#format].
	private TargetDataLine openMic(DataLine.Info micInfo) throws LineUnavailableException {
		return (TargetDataLine) (inputMixerInfo != null
				? AudioSystem.getMixer(inputMixerInfo).getLine(micInfo)
				: AudioSystem.getLine(micInfo));
	}

	// --- codec ------------------------------------------------------------------------------------

	private void setupCodec() throws OpusException {
		configureEncoder();
		decoder = new OpusDecoder((int) SAMPLE_RATE, channels);
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

	// --- capture + playback loops -----------------------------------------------------------------

	private void captureLoop() {
		byte[] buffer = new byte[pcmFrameBytes];
		short[] pcm = new short[frameSamples];
		byte[] packet = new byte[MAX_PACKET_BYTES];
		boolean appliedHifi = highFidelity.get();
		while (running.get()) {
			// keep the mic flowing but don't transmit unless live; re-check running after a drained/closed line
			if (!readFully(buffer) || !transmitting.get()) {
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
				frameSink.accept(frame);
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
}
