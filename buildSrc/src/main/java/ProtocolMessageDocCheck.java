import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Fails the build when a control message, or one of its JSON fields, is missing from the message tables in
/// `docs/CLIENT_PROTOCOL.md`.
///
/// Why this one is not a [DocumentedTokensCheck] with a row pattern: a message is not one token but a message
/// name AND its field names, and the fields only mean anything inside their own row. Checking them
/// document-wide would be worse than useless here — `channel`, `member`, `mode`, `code`, `from`, `target` and
/// `id` are ordinary English that a 66 KB prose document satisfies by accident, so a deleted field would pass
/// unnoticed. Row-scoping is the entire check.
///
/// The tables are the reference a third-party client is implemented from, which is what makes this the
/// highest-value of the documentation checks: a field that exists on the wire and not in the table is a field
/// nobody else can implement, and the sealed-interface `switch` that forces the CODE to keep up exerts no
/// pressure at all on the table one directory away.
@CacheableTask
public abstract class ProtocolMessageDocCheck extends DefaultTask {

	/// `@JsonTypeName("x") record Name(components)` — the annotation carries the wire name, the record header the
	/// fields. Further annotations and `///` doc comments are allowed to sit between the two, because they are
	/// ordinary things to write there and a check that failed on one would be punishing a correct edit; anything
	/// else breaks the match and is caught by the [#WIRE_NAME] count guard.
	///
	/// `[^)]*` for the components is safe for THESE records specifically (no component type contains a `)`, and no
	/// generic contains a comma at depth zero) and correctly spans a header broken over several lines; it would
	/// not be safe over a file whose parameters carry annotations with arguments.
	private static final Pattern MESSAGE = Pattern.compile(
			"@JsonTypeName\\(\"(\\w+)\"\\)\\s*(?://[^\n]*\\s*|@\\w+(?:\\([^()]*\\))?\\s*)*record\\s+\\w+\\s*\\(([^)]*)\\)");

	/// Every `@JsonTypeName(` must have turned into a [#MESSAGE] match; if the two counts disagree, something
	/// (a `///` comment slipped between the annotation and the record, a reformat) is being skipped SILENTLY.
	private static final Pattern WIRE_NAME = Pattern.compile("@JsonTypeName\\(");

	/// One component of a record header: an optional annotation run, a type possibly generic or an array, then
	/// the name. Anchored to a comma or the end of the header so a generic's inner comma cannot start one.
	private static final Pattern COMPONENT =
			Pattern.compile("(?:^|,)\\s*(?:@\\w+(?:\\([^()]*\\))?\\s*)*[\\w.]+(?:<[^>]*>)?(?:\\[])?\\s+(\\w+)\\s*(?=,|$)");

	/// The row for one message: `| \`wireName\` | fields | meaning |`. Group 1 is the Fields cell.
	private static final String ROW_TEMPLATE = "(?m)^\\|\\s*`%s`\\s*\\|([^|]*)\\|";

	/// The backticked tokens inside a Fields cell.
	private static final Pattern CELL_TOKEN = Pattern.compile("`([^`]+)`");

	/// A Fields cell writes a collection-valued field with a trailing `[]` (`members[]`, `requests[]`) to say it
	/// is a list — a useful convention for the reader and not part of the JSON key, so it is stripped before
	/// comparing. Without this the check would fail on two rows that are perfectly correct.
	private static final String COLLECTION_SUFFIX = "[]";

	/// A cell may also annotate a field with its element type (`` `member` (`MemberInfo`) ``). That is why this
	/// check runs SOURCE to DOC only: every component must appear in the cell, but not every backticked token in
	/// the cell has to be a component.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getMessageSources();

	/// The protocol document carrying the tables.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getDocumentation();

	@OutputFile
	public abstract RegularFileProperty getReport();

	@TaskAction
	public void check() {
		String sources = read(getMessageSources());
		if (sources.isEmpty()) {
			throw new GradleException("No message source to derive the protocol tables from. If ClientMessage or "
					+ "ServerMessage moved, point this task at its new home rather than leaving a check that "
					+ "silently verifies nothing.");
		}

		List<Message> messages = messages(sources);
		long declared = WIRE_NAME.matcher(sources).results().count();
		if (messages.size() != declared) {
			throw new GradleException("Found " + declared + " @JsonTypeName annotations but could only parse "
					+ messages.size() + " record headers, so " + (declared - messages.size()) + " message(s) "
					+ "would be checked against nothing. The record pattern in ProtocolMessageDocCheck no longer "
					+ "matches how these records are written — fix it there.");
		}

		String documentation = read(getDocumentation());
		List<String> problems = new ArrayList<>();
		int slots = 0;
		int documented = 0;
		StringBuilder detail = new StringBuilder();

		for (Message message : messages) {
			slots += message.components().size();
			Matcher row = Pattern.compile(ROW_TEMPLATE.formatted(Pattern.quote(message.wireName()))).matcher(documentation);
			if (!row.find()) {
				problems.add("  `" + message.wireName() + "` has no row in the message tables of §3");
				detail.append("  ").append(message.wireName()).append("   <- NO ROW\n");
				continue;
			}
			Set<String> cell = fields(row.group(1));
			List<String> missing = message.components().stream().filter(component -> !cell.contains(component)).toList();
			documented += message.components().size() - missing.size();
			missing.forEach(component -> problems.add("  the `" + message.wireName() + "` row does not list its `"
					+ component + "` field"));
			detail.append("  ").append(message.wireName()).append("  ")
					.append(message.components().isEmpty() ? "(no fields)" : message.components())
					.append(missing.isEmpty() ? "" : "   <- MISSING " + missing).append('\n');
		}

		writeReport(messages.size(), slots, documented, detail.toString(), problems);
		if (!problems.isEmpty()) {
			throw new GradleException("The control protocol is out of sync with docs/CLIENT_PROTOCOL.md:\n"
					+ String.join("\n", problems)
					+ "\n\nEvery message needs a row in the §3 \"Client → server\" or \"Server → client\" table "
					+ "keyed by its wire name, listing every field as `backticked` tokens and saying what the "
					+ "message means. That table is what a third-party client is written from. (This check reads "
					+ "the tables' Markdown, so replacing them with prose will fail it — change the check too if "
					+ "that is deliberate.)");
		}
	}

	private static List<Message> messages(String sources) {
		List<Message> messages = new ArrayList<>();
		Matcher matcher = MESSAGE.matcher(sources);
		while (matcher.find()) {
			List<String> components = new ArrayList<>();
			Matcher component = COMPONENT.matcher(matcher.group(2));
			while (component.find()) {
				components.add(component.group(1));
			}
			messages.add(new Message(matcher.group(1), List.copyOf(components)));
		}
		return messages;
	}

	private static Set<String> fields(String cell) {
		Set<String> fields = new LinkedHashSet<>();
		Matcher matcher = CELL_TOKEN.matcher(cell);
		while (matcher.find()) {
			String token = matcher.group(1);
			fields.add(token.endsWith(COLLECTION_SUFFIX)
					? token.substring(0, token.length() - COLLECTION_SUFFIX.length())
					: token);
		}
		return fields;
	}

	private void writeReport(int messages, int slots, int documented, String detail, List<String> problems) {
		String report = "Control messages: " + messages + ", field slots: " + documented + '/' + slots
				+ " documented\n" + detail
				+ (problems.isEmpty() ? "\nAll documented.\n" : "\nProblems:\n" + String.join("\n", problems) + "\n");
		File file = getReport().get().getAsFile();
		try {
			Files.createDirectories(file.toPath().getParent());
			Files.writeString(file.toPath(), report, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(ConfigurableFileCollection files) {
		return files.getFiles().stream()
				.filter(File::isFile)
				.map(file -> {
					try {
						return Files.readString(file.toPath(), StandardCharsets.UTF_8);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				})
				.collect(Collectors.joining("\n"));
	}

	/// One control message: its wire `type` and the JSON fields its record declares.
	private record Message(String wireName, List<String> components) {
	}
}
