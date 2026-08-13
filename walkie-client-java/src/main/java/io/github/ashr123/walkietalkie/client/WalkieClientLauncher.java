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
import java.awt.GraphicsEnvironment;
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
	/// Package-private because the WINDOW needs the same constant: its adaptive Apply button has to know that a
	/// GLOBAL_PTT join is pinned here regardless of what the channel field says, and a second copy of the name in
	/// SwingUi could drift from this one.
	static final String GLOBAL_CHANNEL = "global";

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

	/// Two options rather than one `negatable = true`, and the reason is measured rather than stylistic: picocli 4.7.7
	/// INVERTS both forms of a negatable flag whose default is true, so `--gui` yielded false and `--no-gui` yielded
	/// TRUE — a flag that opens the window when you ask it not to. An optional-value option plus an explicit shorthand
	/// behaves the way a reader expects for every spelling: bare `--gui` is the window (via `fallbackValue`),
	/// `--gui=false` and `--no-gui` are the prompt.
	@Option(names = "--gui", arity = "0..1", defaultValue = "true", fallbackValue = "true",
			description = "Open a window — the DEFAULT. The window adds hold-to-talk — press and hold the Talk button, "
					+ "or the space bar — which a console cannot offer, since a keystroke has no press/release edges, "
					+ "and it has a Connect form, so it opens without --display/--channel/--key up front. Use "
					+ "--gui=false or --no-gui for the terminal prompt. Where there is no display to open a window on, "
					+ "the terminal prompt runs instead and says so.")
	private boolean gui;

	/// Shorthand for `--gui=false`, and DECISIVE: given both, the prompt wins. Two independent flags have no relative
	/// order for picocli to honour, so rather than pretend "the last word wins" this states which one does.
	@Option(names = "--no-gui",
			description = "Run at the terminal prompt instead of opening a window; shorthand for --gui=false. Wins if "
					+ "--gui is given as well.")
	private boolean noGui;

	static void main(String... args) {
		System.exit(new CommandLine(new WalkieClientLauncher()).execute(args));
	}

	/// The command line as a starting point for the window's form. Never used to connect on its own — [#connectable]
	/// decides that — so a partly-filled command line is a convenience, not a half-open session.
	private ClientOptions prefill() {
		return new ClientOptions(
				server,
				mode == ChannelMode.GLOBAL_PTT ? GLOBAL_CHANNEL : channel == null ? "" : channel,
				mode,
				display == null ? "" : display,
				highFidelity,
				input,
				key,
				tlsTruststore,
				startMuted
		);
	}

	/// Whether the command line already says everything a connection needs, in which case the window connects on
	/// startup exactly as the console does. Otherwise it opens disconnected and waits for the form.
	private boolean connectable() {
		if (display == null || display.isBlank()) {
			return false;
		}
		if (mode == ChannelMode.GLOBAL_PTT) {
			return true;   // the global room names itself and takes no passphrase
		}
		return channel != null && !channel.isBlank() && key != null && !key.isBlank();
	}

	/// The rules picocli's annotations cannot state, checked before anything is constructed.
	///
	/// Two of them are conditional on the mode, which `required = true` has no way to express: the server forces a
	/// global join's channel to `global` and refuses it a passphrase outright (`ENCRYPTION_NOT_ALLOWED`), while
	/// every other channel now demands both (`PASSPHRASE_REQUIRED`). Validating here rather than leaving the
	/// fields null also keeps a missing flag a usage error: `WalkieClient`'s constructor would otherwise reach
	/// `CHANNEL_NAME.matcher(null)` and die with a bare NullPointerException stack trace.
	///
	/// These are the options a CONSOLE session must have up front, because a terminal has nowhere to ask for them once
	/// it is running. A window does: it has a Connect form, so for the window they are pre-fill values rather than
	/// requirements ([#prefill]), and the only thing that must be right is whatever the user finally types.
	///
	/// @return the message to print, or `null` when the options are usable
	/// Whether to open a window: `wanted` is what the command line asked for (the default is yes), and a headless run
	/// overrides it.
	///
	/// Separate and static because making the window the DEFAULT put weight on it. A headless run (over `ssh` without
	/// X11, in a container, in CI) would otherwise reach `new JFrame()` and die on a `HeadlessException` for a client
	/// that used to work there, so the terminal is the fallback rather than an error — and this is the one place that
	/// decides it, since [#validate] must agree: the console needs its options up front, and the window does not.
	static boolean windowWanted(boolean wanted, boolean headless) {
		return wanted && !headless;
	}

	private String validate(boolean window) {
		if (window) {
			return null;
		}
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
		// Decided ONCE, because three things follow from it: which validation applies (a console must have its options
		// up front; a window asks for them), which front end runs, and what the user is told when the two disagree.
		boolean headless = GraphicsEnvironment.isHeadless();
		boolean window = windowWanted(gui && !noGui, headless);
		if (gui && !noGui && headless) {
			System.err.println("No display is available, so the window cannot open — running at the terminal prompt "
					+ "instead. Pass --no-gui to ask for the prompt without this notice.");
		}
		String invalid = validate(window);
		if (invalid != null) {
			System.err.println(invalid);
			return CommandLine.ExitCode.USAGE;
		}
		// The two front ends differ in WHO owns the session. A console must be handed a live client — it has nowhere to
		// ask for a server or a passphrase once it is running — so the client is built here and closed by this
		// try-with-resources. A window has a Connect form, so it builds and closes its own client and simply outlives
		// any one session; all this does is hand it the command line as pre-fill and wait for the window to close.
		if (window) {
			SwingUi ui = new SwingUi();
			ui.start(prefill(), connectable());
			ui.awaitClose();
			return CommandLine.ExitCode.OK;
		}
		ConsoleUi console = new ConsoleUi();
		try (WalkieClient client = new WalkieClient(new ClientOptions(
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
		), console)) {
			console.run(client);
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
