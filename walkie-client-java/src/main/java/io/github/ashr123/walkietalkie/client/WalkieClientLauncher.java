package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/// Entry point for the desktop walkie-talkie client, parsed with picocli.
///
/// Example:
///
/// ```shell
/// ./gradlew :walkie-client-java:run --args="--server https://localhost:8443 \
///     --channel team1 --mode ptt --display Alice --hifi"
/// ```
///
/// Run with `--help` for the full option list.
@Command(
		name = "walkie-client",
		mixinStandardHelpOptions = true,
		version = "walkie-client 0.1.0",
		description = "Console walkie-talkie client (WebSocket-relay transport, 48 kHz Opus)."
)
public final class WalkieClientLauncher implements Callable<Integer> {

	@Option(names = "--server", defaultValue = "https://localhost:8443",
			description = "Base URL of the server (default: ${DEFAULT-VALUE}). Use http://... for a server run with walkie.tls.enabled=false.")
	private String server;

	@Option(names = "--channel", defaultValue = "lobby",
			description = "Channel to join; ignored for global mode (default: ${DEFAULT-VALUE}).")
	private String channel;

	@Option(names = "--mode", defaultValue = "ptt", converter = ChannelModeConverter.class,
			description = "Conversation mode: ptt | global | duplex (default: ${DEFAULT-VALUE}).")
	private ChannelMode mode;

	@Option(names = "--display", defaultValue = "guest",
			description = "Display name shown to others; 1-32 chars of [A-Za-z0-9_.-] (default: ${DEFAULT-VALUE}).")
	private String display;

	@Option(names = "--hifi", description = "Use the Opus music profile instead of the voice profile.")
	private boolean highFidelity;

	@Option(names = "--input", completionCandidates = InputDeviceCandidates.class,
			description = "Capture from the input device whose name contains this text (default: the system "
					+ "default). Detected input devices: ${COMPLETION-CANDIDATES}")
	private String input;

	@Option(names = "--key", defaultValue = "${env:WALKIE_KEY:-}",
			description = "Passphrase for end-to-end audio encryption (AES-256-GCM). Every participant in a "
					+ "channel must use the same one. Defaults to the WALKIE_KEY env var; omit to disable.")
	private String key;

	@Option(names = "--tls-truststore",
			description = "Path to a PEM certificate to additionally trust for TLS. The system CAs are always "
					+ "trusted, and on localhost the server's auto-generated dev cert is trusted automatically.")
	private String tlsTruststore;

	@Option(names = "--muted", description = "Full-duplex only: connect with the mic muted (type 't' to "
			+ "unmute). By default the mic is live on connect; ignored in push-to-talk modes.")
	private boolean startMuted;

	static void main(String... args) {
		System.exit(new CommandLine(new WalkieClientLauncher()).execute(args));
	}

	@Override
	public Integer call() throws Exception {
		//noinspection EmptyTryBlock
		try (WalkieClient _ = new WalkieClient(new ClientOptions(
				server,
				channel,
				mode,
				display,
				highFidelity,
				input,
				key,
				tlsTruststore,
				startMuted
		))) {
		}
		return 0;
	}

	/// Supplies the detected audio capture devices as picocli completion candidates, so they show up directly
	/// in `--help` (via the `${COMPLETION-CANDIDATES}` variable in the `--input` description) and in generated
	/// shell completions — there is no separate "list devices" run. Enumerated lazily, only when picocli
	/// renders help or completions, never on a normal launch.
	private static final class InputDeviceCandidates implements Iterable<String> {
		@Override
		public Iterator<String> iterator() {
			return Arrays.stream(AudioSystem.getMixerInfo())
					// A mixer is a real capture device only if it exposes a TargetDataLine. Testing
					// getTargetLineInfo().length > 0 is wrong: Java Sound "Port ..." mixers invert direction and
					// report OUTPUT ports (speakers, line-out) as target lines, so that test admits outputs (and
					// drops mic Port mixers, whose mic is a *source* line). Check the line class, matching what
					// AudioEngine.resolveInputMixer() actually requires when opening the capture line.
					.filter(info -> Arrays.stream(AudioSystem.getMixer(info).getTargetLineInfo())
							.anyMatch(line -> TargetDataLine.class.isAssignableFrom(line.getLineClass())))
					.map(Mixer.Info::getName)
					.filter(Predicate.not(AudioEngine::isVirtualDevice))
					.iterator();
		}
	}

	/// Accepts friendly aliases for [ChannelMode] in addition to the enum names.
	static final class ChannelModeConverter implements ITypeConverter<ChannelMode> {
		@Override
		public ChannelMode convert(String value) {
			return switch (value.toLowerCase(Locale.ROOT)) {
				case "ptt", "multi", "multi_channel_ptt", "multichannelptt" -> ChannelMode.MULTI_CHANNEL_PTT;
				case "global", "global_ptt", "globalptt" -> ChannelMode.GLOBAL_PTT;
				case "duplex", "full", "full_duplex", "fullduplex", "conference" -> ChannelMode.FULL_DUPLEX;
				default -> throw new TypeConversionException(
						"Unknown mode '" + value + "' (expected: ptt | global | duplex)");
			};
		}
	}
}
