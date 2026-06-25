package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

import java.util.Locale;
import java.util.concurrent.Callable;

/// Entry point for the desktop walkie-talkie client, parsed with picocli.
///
/// Example:
///
/// ```shell
/// ./gradlew :walkie-client-java:run --args="--server http://localhost:8080 \
///     --user alice --channel team1 --mode ptt --display Alice --hifi"
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

	@Option(names = "--server", defaultValue = "http://localhost:8080",
			description = "Base HTTP URL of the server (default: ${DEFAULT-VALUE}).")
	private String server;

	@Option(names = "--user", defaultValue = "guest",
			description = "Username used to obtain a bearer token (default: ${DEFAULT-VALUE}).")
	private String user;

	@Option(names = "--channel", defaultValue = "lobby",
			description = "Channel to join; ignored for global mode (default: ${DEFAULT-VALUE}).")
	private String channel;

	@Option(names = "--mode", defaultValue = "ptt", converter = ChannelModeConverter.class,
			description = "Conversation mode: ptt | global | duplex (default: ${DEFAULT-VALUE}).")
	private ChannelMode mode;

	@Option(names = "--display", description = "Display name shown to others (default: the username).")
	private String display;

	@Option(names = "--hifi", description = "Use the Opus music profile instead of the voice profile.")
	private boolean highFidelity;

	@Option(names = "--input",
			description = "Capture from the input device whose name contains this text (default: the system default). See --list-inputs.")
	private String input;

	@Option(names = "--list-inputs", description = "List available audio input devices and exit.")
	private boolean listInputs;

	static void main(String... args) {
		System.exit(new CommandLine(new WalkieClientLauncher()).execute(args));
	}

	@Override
	public Integer call() throws Exception {
		if (listInputs) {
			WalkieClient.listInputDevices();
			return 0;
		}
		new WalkieClient(new ClientOptions(
				server,
				user,
				channel,
				mode,
				display == null || display.isBlank() ? user : display,
				highFidelity,
				input
		))
				.run();
		return 0;
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
