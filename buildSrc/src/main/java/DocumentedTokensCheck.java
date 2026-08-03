import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
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

/// Fails the build when a set of tokens DERIVED from source is not documented, in the same spirit as
/// [BrowserModuleDocCheck]: the truth is extracted from the code, so no hand-maintained list can rot, and
/// documentation that nothing verifies cannot decay in silence.
///
/// This one is generic where [BrowserModuleDocCheck] is bespoke — the same shape recurs for the wire-level enums,
/// the configuration keys and the client's command-line flags, so the extraction pattern and the document are
/// properties rather than hard-coded. [JavaConventionsPlugin] registers one instance per set.
///
/// Two questions decide whether a check like this is worth having, and both have to be answered per set:
///
/// **Where does the token have to appear?** "Somewhere in the file" is nearly worthless for a prose-heavy
/// document. Measured on `docs/CLIENT_PROTOCOL.md`: deleting BOTH message tables outright still leaves 39 of the
/// 40 wire names matching a word-boundary search, because the later sections cross-reference almost all of them.
/// So there are two bars. Without [#getDocRowPattern] a token must appear inside an inline code span — it must
/// have been written as CODE, not brushed past in prose. With one, it must be the KEY of a Markdown table row,
/// which is the real bar whenever the document has a table that IS the reference for the set.
///
/// **Can the token collide with ordinary English?** A set of `UPPER_SNAKE` names or dotted-and-hyphenated
/// configuration keys cannot. A set containing `join`, `error` and `answer` very much can, which is why those
/// live in [ProtocolMessageDocCheck] against a table instead of here.
@CacheableTask
public abstract class DocumentedTokensCheck extends DefaultTask {

	/// Inline code spans are delimited by single backticks and cannot span lines, so splitting a line on
	/// backticks puts the code at the ODD indices. Worth doing properly rather than with one regex: a pattern
	/// like ``` `[^`\n]*TOKEN[^`\n]*` ``` also matches the PROSE BETWEEN two code spans on the same line
	/// (the text from the closing backtick of one to the opening backtick of the next contains no backtick), so
	/// "the `a` field never mentions join, see `b`" would count as documenting `join`.
	private static final char CODE_SPAN_DELIMITER = '`';

	/// A token is documented only as a whole word. `.` and `-` join the lookarounds because the tokens here are
	/// dotted, hyphenated or prefixed with `--`: without them `walkie.floor-max-hold` would be credited by
	/// `walkie.floor-max-hold-extra`. The trailing side is why `UNKNOWN` is not credited by `UNKNOWN_TARGET`,
	/// which occurs four times in the protocol document and would otherwise make that constant permanently
	/// impossible to fail.
	private static final String BOUNDARY_BEFORE = "(?<![\\w.$-])";
	private static final String BOUNDARY_AFTER = "(?![\\w-])";

	/// Spring's relaxed binding: `maxTextMessageBytes` is configured as `max-text-message-bytes`.
	private static final Pattern CAMEL_HUMP = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

	/// The files the truth is read from. Empty is a hard failure rather than a silent pass — a check that
	/// quietly stops checking is worse than no check, and these are registered against specific files that a
	/// module is known to own.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getSources();

	/// Extracts the tokens from [#getSources], group 1 being the token.
	@Input
	public abstract Property<String> getTokenPattern();

	/// A token [#getTokenPattern] must find, asserted so that a pattern which has quietly stopped matching fails
	/// LOUDLY instead of reporting that everything is documented. Pick one from the END of the set: the failure
	/// mode these patterns actually have is truncating (an added annotation, a reordered modifier), which drops
	/// the tail first. Not a hand-maintained list — one real token, chosen once.
	@Input
	@Optional
	public abstract Property<String> getSentinel();

	/// The document expected to describe every token.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getDocumentation();

	/// When set, the bar is a Markdown table row rather than any inline code span: this matches a row of the
	/// table that IS the reference for this set, group 1 being the row's key. Setting it also switches the check
	/// to BIDIRECTIONAL — a row whose key is no longer a token is reported too, which is the only way to catch a
	/// rename that left the old name sitting in the table. That reverse direction is safe only because it is
	/// scoped to one table; run loose over the prose it would fire on every incidental mention.
	@Input
	@Optional
	public abstract Property<String> getDocRowPattern();

	/// Prepended to every token before it is looked up, for sets whose documented form carries a namespace the
	/// source does not (`maxJoinRequests` in Java is `walkie.max-join-requests` in a properties file).
	@Input
	public abstract Property<String> getTokenPrefix();

	/// Convert each token from `camelCase` to `kebab-case` before looking it up — Spring Boot's relaxed binding,
	/// which is how a `@ConfigurationProperties` record component is spelled in configuration.
	@Input
	public abstract Property<Boolean> getRelaxedBinding();

	/// What this set IS, in a couple of words, for the failure message: "wire error codes", "configuration keys".
	@Input
	public abstract Property<String> getSubject();

	/// What the author should DO about a failure — where the list lives and what a new entry looks like. A check
	/// that says only "this is wrong" gets suppressed; one that says where to type gets fixed.
	@Input
	public abstract Property<String> getHint();

	/// A record of what was checked, making the task up-to-date-checkable and cacheable rather than always
	/// running.
	@OutputFile
	public abstract RegularFileProperty getReport();

	@TaskAction
	public void check() {
		String subject = getSubject().get();
		String sources = concatenate(getSources());
		if (sources.isEmpty()) {
			throw new GradleException("No source file to derive the " + subject + " from. This check is "
					+ "registered against specific files; if one moved, point the task at its new home rather "
					+ "than leaving a check that silently verifies nothing.");
		}

		Set<String> tokens = extract(sources);
		if (tokens.isEmpty()) {
			throw new GradleException("The extraction pattern for the " + subject + " matched nothing. It has "
					+ "stopped tracking the source rather than the source having become empty — fix the pattern "
					+ "in JavaConventionsPlugin.");
		}
		if (getSentinel().isPresent() && !tokens.contains(getSentinel().get())) {
			throw new GradleException("The extraction pattern for the " + subject + " no longer finds '"
					+ getSentinel().get() + "', so it is probably now finding only PART of the set: " + tokens
					+ ". Fix the pattern in JavaConventionsPlugin. (If that token was legitimately removed, point "
					+ "the sentinel at another one — near the end of the set, since a pattern that breaks usually "
					+ "breaks by truncating. Do not simply delete it: it is what turns a silent under-count, which "
					+ "reports everything as documented, into this message.)");
		}

		String documentation = concatenate(getDocumentation());
		List<String> problems = new ArrayList<>();
		List<String> documented = new ArrayList<>();
		List<String> orphans = new ArrayList<>();

		if (getDocRowPattern().isPresent()) {
			Set<String> rowKeys = matches(Pattern.compile(getDocRowPattern().get()), documentation);
			tokens.forEach(token -> {
				if (rowKeys.contains(documentedForm(token))) {
					documented.add(token);
				} else {
					problems.add("  " + documentedForm(token) + " has no row in the table");
				}
			});
			// The reverse direction: a row nothing in the source produces any more. Only reachable here, where
			// the rows of ONE table are the population being examined.
			rowKeys.stream()
					.filter(key -> tokens.stream().noneMatch(token -> documentedForm(token).equals(key)))
					.forEach(key -> {
						orphans.add(key);
						problems.add("  the table has a row for " + key + ", which no longer exists in the source");
					});
		} else {
			List<String> codeSpans = codeSpans(documentation);
			tokens.forEach(token -> {
				if (mentioned(codeSpans, documentedForm(token))) {
					documented.add(token);
				} else {
					problems.add("  " + documentedForm(token) + " is never written as code in the documentation");
				}
			});
		}

		writeReport(subject, tokens, documented, orphans, problems);
		if (!problems.isEmpty()) {
			throw new GradleException("The " + subject + " are out of sync with their documentation:\n"
					+ String.join("\n", problems) + "\n\n" + getHint().get());
		}
	}

	/// How a token is spelled in the documentation.
	private String documentedForm(String token) {
		return getTokenPrefix().get()
				+ (getRelaxedBinding().get() ? CAMEL_HUMP.matcher(token).replaceAll("-").toLowerCase() : token);
	}

	private Set<String> extract(String sources) {
		return matches(Pattern.compile(getTokenPattern().get()), sources);
	}

	private static Set<String> matches(Pattern pattern, String text) {
		Set<String> found = new LinkedHashSet<>();
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		return found;
	}

	/// Every inline code span in the document, in order. See [#CODE_SPAN_DELIMITER] for why this is a split
	/// rather than a regex.
	private static List<String> codeSpans(String documentation) {
		List<String> spans = new ArrayList<>();
		documentation.lines().forEach(line -> {
			String[] parts = line.split(String.valueOf(CODE_SPAN_DELIMITER), -1);
			for (int i = 1; i < parts.length; i += 2) {
				spans.add(parts[i]);
			}
		});
		return spans;
	}

	private static boolean mentioned(List<String> codeSpans, String token) {
		Pattern whole = Pattern.compile(BOUNDARY_BEFORE + Pattern.quote(token) + BOUNDARY_AFTER);
		return codeSpans.stream().anyMatch(span -> whole.matcher(span).find());
	}

	private static String concatenate(ConfigurableFileCollection files) {
		return files.getFiles().stream()
				.filter(File::isFile)
				.map(DocumentedTokensCheck::read)
				.collect(Collectors.joining("\n"));
	}

	private void writeReport(String subject,
	                         Set<String> tokens,
	                         List<String> documented,
	                         List<String> orphans,
	                         List<String> problems) {
		StringBuilder report = new StringBuilder(subject).append(": ")
				.append(documented.size()).append('/').append(tokens.size()).append(" documented\n");
		tokens.forEach(token -> report.append("  ").append(documentedForm(token))
				.append(documented.contains(token) ? "" : "   <- MISSING").append('\n'));
		orphans.forEach(orphan -> report.append("  ").append(orphan).append("   <- ORPHANED ROW\n"));
		report.append(problems.isEmpty() ? "\nAll documented.\n" : "\nProblems:\n" + String.join("\n", problems) + "\n");

		File file = getReport().get().getAsFile();
		try {
			Files.createDirectories(file.toPath().getParent());
			Files.writeString(file.toPath(), report.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(File file) {
		try {
			return Files.readString(file.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
