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
/// WALKIE_KEY=hunter2 ./gradlew :walkie-client-java:run --args="--server https://localhost:8443 \
///     --channel team1 --mode ptt --display Alice --hifi"
/// ```
///
/// `--display`, `--channel` and `--key` are required — every channel but the global room is end-to-end encrypted
/// (see [#validate]). The passphrase comes from `WALKIE_KEY` above rather than `--key` so it stays out of shell
/// history.
///
/// Run with `--help` for the full option list.
@Command(
		name = "walkie-client",
		mixinStandardHelpOptions = true,
		version = "walkie-client 0.1.0",
		description = "Console walkie-talkie client (WebSocket-relay transport, 48 kHz Opus)."
)
public final class WalkieClientLauncher implements Callable<Integer> {

	/// The server-managed room every global-mode join is pinned to (`ConnectionService.GLOBAL_CHANNEL`). Sent
	/// explicitly rather than left null so the wire carries the name the server will use anyway.
	private static final String GLOBAL_CHANNEL = "global";

	@Option(names = "--server", defaultValue = "https://localhost:8443",
			description = "Base URL of the server (default: ${DEFAULT-VALUE}). Use http://... for a server run with walkie.tls.enabled=false.")
	private String server;

	/// No `defaultValue`, and no picocli `required = true` either: picocli treats an option with a `defaultValue`
	/// as never required (`ArgSpec.required()` folds the two together), so the pair would silently keep working
	/// as before — and `required` alone cannot express "unless `--mode global`", where the server forces the name
	/// to `global`. Both are enforced in [#validate] instead. `${DEFAULT-VALUE}` is gone from the description
	/// because picocli resolves it from the field's initial value, which would print "(default: null)".
	@Option(names = "--channel",
			description = "Channel to join. Required, except for global mode, which always uses the 'global' room.")
	private String channel;

	@Option(names = "--mode", defaultValue = "ptt", converter = ChannelModeConverter.class,
			description = "Conversation mode: ptt | global | duplex (default: ${DEFAULT-VALUE}).")
	private ChannelMode mode;

	/// Required, enforced in [#validate] for the reason [#channel] documents.
	@Option(names = "--display",
			description = "Display name shown to others; 1-32 letters, digits or spaces in any language, plus _ . - "
					+ "(quote it if it contains spaces, e.g. --display \"Roy Ash\"). Required.")
	private String display;

	@Option(names = "--hifi", description = "Use the Opus music profile instead of the voice profile.")
	private boolean highFidelity;

	@Option(names = "--input", completionCandidates = InputDeviceCandidates.class,
			description = "Capture from the input device whose name contains this text (default: the system "
					+ "default). Detected input devices: ${COMPLETION-CANDIDATES}")
	private String input;

	/// Kept as an env-var default so the passphrase need not appear in a command line (and thus in shell history),
	/// but an empty value no longer means "no encryption": there is nothing to disable any more. Enforced in
	/// [#validate], which is also why this keeps a `defaultValue` where [#channel] and [#display] cannot — the
	/// default here supplies a real value from the environment rather than inventing one.
	@Option(names = "--key", defaultValue = "${env:WALKIE_KEY:-}",
			description = "Passphrase for end-to-end audio encryption (AES-256-GCM). Every participant in a "
					+ "channel must use the same one. Required (except for global mode, which is never "
					+ "encrypted); reads the WALKIE_KEY env var when the flag is omitted.")
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

	/// The rules picocli's annotations cannot state, checked before anything is constructed.
	///
	/// Two of them are conditional on the mode, which `required = true` has no way to express: the server forces a
	/// global join's channel to `global` and refuses it a passphrase outright (`ENCRYPTION_NOT_ALLOWED`), while
	/// every other channel now demands both (`PASSPHRASE_REQUIRED`). Validating here rather than leaving the
	/// fields null also keeps a missing flag a usage error: `WalkieClient`'s constructor would otherwise reach
	/// `CHANNEL_NAME.matcher(null)` and die with a bare NullPointerException stack trace.
	///
	/// @return the message to print, or `null` when the options are usable
	private String validate() {
		if (display == null || display.isBlank()) {
			return "Missing required option: '--display=<display>' (the name others see).";
		}
		if (mode == ChannelMode.GLOBAL_PTT) {
			return null;   // the global room names itself and is never encrypted
		}
		if (channel == null || channel.isBlank()) {
			return "Missing required option: '--channel=<channel>' (required for every mode but global).";
		}
		if (key == null || key.isBlank()) {
			return "Missing required option: '--key=<key>' — every channel except the global room is end-to-end "
					+ "encrypted, so a passphrase is required. Set WALKIE_KEY to keep it out of your shell history.";
		}
		return null;
	}

	@Override
	public Integer call() throws Exception {
		String invalid = validate();
		if (invalid != null) {
			System.err.println(invalid);
			return CommandLine.ExitCode.USAGE;
		}
		//noinspection EmptyTryBlock
		try (WalkieClient _ = new WalkieClient(new ClientOptions(
				server,
				// Global forces the room name server-side, and --channel is not asked for in that mode, so give the
				// wire the name it will be pinned to instead of a null (mirrors ConnectionService.handleJoin).
				mode == ChannelMode.GLOBAL_PTT ? GLOBAL_CHANNEL : channel,
				mode,
				display,
				highFidelity,
				input,
				key,
				tlsTruststore,
				startMuted
		))) {
		} catch (IllegalArgumentException e) {
			// Bad --display / --channel etc. — a usage error, so print the message cleanly (no stack trace) and
			// return picocli's usage exit code rather than letting it surface as an uncaught exception.
			System.err.println(e.getMessage());
			return CommandLine.ExitCode.USAGE;
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
