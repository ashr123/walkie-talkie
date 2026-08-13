package io.github.ashr123.walkietalkie.client;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/// Pins which front end the launcher picks, now that the WINDOW is the default and the terminal is opt-in.
///
/// Both halves matter. The negation is picocli's, not ours — `negatable = true` with `defaultValue = "true"` is what
/// makes `--no-gui` mean the prompt, and a library upgrade that changed the semantics would silently swap the default
/// front end. And the headless fallback is what keeps a client that used to work over `ssh` working: the window would
/// otherwise reach `new JFrame()` and die on a HeadlessException.
class WalkieClientLauncherTest {

	/// What the command line asked for: the `--gui` value unless the decisive `--no-gui` shorthand is present.
	private static boolean windowAskedFor(String... args) {
		CommandLine parser = new CommandLine(new WalkieClientLauncher());
		parser.parseArgs(args);
		return (boolean) parser.getCommandSpec().findOption("--gui").getValue()
				&& !(boolean) parser.getCommandSpec().findOption("--no-gui").getValue();
	}

	@Test
	void theWindowIsTheDefaultAndTheTerminalIsAskedForByName() {
		assertTrue(windowAskedFor(), "no flag at all means the window");
		assertTrue(windowAskedFor("--gui"), "asking for it explicitly is still the window, not its negation");
		assertFalse(windowAskedFor("--no-gui"), "the terminal prompt is opt-in, and this is how it is asked for");
		assertFalse(windowAskedFor("--gui=false"), "the long form of the same request");
		assertTrue(windowAskedFor("--gui=true"));
		// Two independent flags have no relative order for picocli to honour, so the prompt is stated to win rather
		// than leaving it to whichever was typed last.
		assertFalse(windowAskedFor("--no-gui", "--gui"), "--no-gui is decisive");
		assertFalse(windowAskedFor("--gui", "--no-gui"), "and in either order");
	}

	@Test
	void aHeadlessRunFallsBackToTheTerminalRatherThanFailing() {
		// Not an error: a client that worked over `ssh` before the window became the default must keep working. The
		// caller prints why, and the same predicate decides validation, so the console still gets its options checked.
		assertTrue(WalkieClientLauncher.windowWanted(true, false), "a display and no --no-gui: the window");
		assertFalse(WalkieClientLauncher.windowWanted(true, true), "no display: the terminal, silently usable");
		assertFalse(WalkieClientLauncher.windowWanted(false, false), "--no-gui is honoured even with a display");
		assertFalse(WalkieClientLauncher.windowWanted(false, true));
	}
}
