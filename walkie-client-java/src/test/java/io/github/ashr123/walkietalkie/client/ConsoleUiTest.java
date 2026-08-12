package io.github.ashr123.walkietalkie.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// Pins what the terminal actually prints, now that the client no longer names a stream.
///
/// Worth pinning precisely because the client can no longer see it. Every one of the ~115 places that used to write
/// to `System.out` now hands a line to [WalkieUi], so the console's format lives in exactly one class — and a change
/// here would silently reformat the whole session log with nothing else failing. The end-to-end check for the port
/// extraction was a scripted console session diffed byte-for-byte against the same session before it; these
/// assertions are that check's standing residue, in a form that runs in milliseconds.
class ConsoleUiTest {

	/// `yyyy-MM-dd HH:mm:ss,SSS` followed by the line — comma before the millis, matching the server's log pattern.
	private static final String TIMESTAMPED = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} .*";
	/// ASCII BEL (0x07) — what [WalkieUi#attention] means on a terminal.
	private static final char BELL = '\u0007';

	/// Runs `action` with `System.out` captured, and returns what it wrote. Restores the real stream even on failure,
	/// so one broken assertion cannot silence the rest of the suite's output.
	private static String captureStdout(Consumer<? super ConsoleUi> action) {
		PrintStream original = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			action.accept(new ConsoleUi());
		} finally {
			System.setOut(original);
		}
		return captured.toString(StandardCharsets.UTF_8);
	}

	@Test
	void aStatusLineIsTimestampedAndAnythingElseIsNot() {
		// The distinction the port draws, and the reason it has two prose methods rather than one: a channel event is
		// a log entry, a "Usage: …" reply to a mistyped command is an answer. Collapsing them would have timestamped
		// the help box.
		assertLinesMatch(
				List.of(TIMESTAMPED),
				captureStdout(ui -> ui.status("[joined] channel=team")).lines().toList()
		);
		assertEquals(
				"Usage: m <ptt|global|duplex>" + System.lineSeparator(),
				captureStdout(ui -> ui.note("Usage: m <ptt|global|duplex>")),
				"a note is the bare text — no timestamp, nothing added"
		);
	}

	@Test
	void aStatusLineEndsWithTheTextItWasGiven() {
		String written = captureStdout(ui -> ui.status("[e2ee] ON (AES-256-GCM)"));

		assertTrue(written.endsWith("[e2ee] ON (AES-256-GCM)" + System.lineSeparator()), written);
		assertTrue(written.matches("(?s)" + TIMESTAMPED + System.lineSeparator()), written);
	}

	@Test
	void attentionWritesTheBellAndNothingElse() {
		// Nothing else on purpose: the words that accompany it are a separate status() call, so a front end that
		// cannot ring a bell still reports the turn. If this ever printed its own text the two would double up.
		assertEquals(String.valueOf(BELL), captureStdout(WalkieUi::attention));
	}

	@Test
	void aStateChangeIsSilentOnAConsole() {
		// The console narrates each change as prose where it happens, so it has nothing to repaint. The signal exists
		// for a window; asserting the console stays quiet is what stops someone "implementing" it here later and
		// double-reporting every event.
		assertEquals("", captureStdout(WalkieUi::stateChanged));
	}

	// --- the console's own GRAMMAR ---------------------------------------------------------------------------------
	//
	// These moved here with `splitChannelArgs` when the command loop left WalkieClient: they were always tests of the
	// console's parsing rather than of the client, and the `c` grammar is exactly the sort of thing the front end owns.

	@Test
	void theChannelCommandQuotesANameWithSpaces() {
		// `c <channel> [mode] [passphrase]` used to split on whitespace, which stopped working once a name could
		// contain one. Only the CHANNEL is quotable; the rest stays a single remainder because the passphrase may
		// itself contain spaces.
		assertArrayEquals(new String[]{"my room", "ptt secret"}, ConsoleUi.splitChannelArgs("\"my room\" ptt secret"));
		assertArrayEquals(new String[]{"team-1", "ptt secret"}, ConsoleUi.splitChannelArgs("team-1 ptt secret"));
		assertArrayEquals(new String[]{"team-1", ""}, ConsoleUi.splitChannelArgs("  team-1  "));
		assertArrayEquals(new String[]{"my room", ""}, ConsoleUi.splitChannelArgs("\"my room\""));
	}

	@Test
	void aPassphraseWithSpacesStillSurvivesTheChannelCommand() {
		// The behaviour that must NOT regress: the trailing passphrase was always the remainder of the line, so a
		// four-word passphrase worked. Splitting the whole line into tokens would have broken it silently.
		String[] split = ConsoleUi.splitChannelArgs("\"my room\" ptt correct horse battery staple");
		assertEquals("my room", split[0]);
		String[] rest = split[1].split("\\s+", 2);
		assertEquals("ptt", rest[0]);
		assertEquals("correct horse battery staple", rest[1]);
	}

	@Test
	void anUnterminatedQuoteTakesTheRestOfTheLineAsTheName() {
		assertArrayEquals(new String[]{"my room ptt", ""}, ConsoleUi.splitChannelArgs("\"my room ptt"));
	}
}
