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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Fails the build on a `///` Javadoc reference that points at something which does not exist — `[SomeType]`,
/// `[Type#member]` or `[#member]` naming a type or member that is not (or is no longer) in the source.
///
/// Why this exists: the project's Markdown Javadoc links are plain text as far as the compiler is concerned, so a
/// renamed or not-yet-written symbol leaves a silently broken reference. The build does not run `javadoc`, and
/// `javadoc` would not catch the case that earned this check its place anyway — links to protocol records that a
/// feature had *documented before implementing*.
///
/// It is deliberately conservative, because a false positive fails an innocent build and teaches people to distrust
/// the check. Anything it cannot resolve with confidence is skipped:
///   - Markdown links (`[text](url)`) and bracketed prose (`[tag][payload]`, `[a,b]`, `[sid]`) are not references.
///     A reference names a type, so its simple name starts upper-case.
///   - `java.lang` types, anything the file imports, and fully-qualified names outside this project's own package
///     are external and not indexable here.
///   - A bare SIMPLE name in a file with a wildcard import is skipped: it may come from that package. This is the
///     one accepted hole — a typo'd project type in such a file goes unreported — and it beats guessing. An
///     `Outer.Nested` is still checked though; see [#namesNestedTypeOfAKnownType].
@CacheableTask
public abstract class JavadocReferenceCheck extends DefaultTask {

	/// A reference must name a type from THIS project to be resolvable; anything else is a third-party symbol.
	private static final String PROJECT_PACKAGE = "io.github.ashr123.walkietalkie";

	/// `java.lang` is imported implicitly, so these never appear in an import statement and so cannot be told apart
	/// from a project type by that route.
	private static final Set<String> JAVA_LANG = Set.of(
			"AutoCloseable", "Boolean", "Byte", "CharSequence", "Character", "Class", "Comparable", "Double",
			"Enum", "Error", "Exception", "Float", "IllegalArgumentException", "IllegalStateException", "Integer",
			"Iterable", "Long", "Math", "NullPointerException", "Object", "Override", "Record", "Runnable",
			"Runtime", "SafeVarargs", "Short", "String", "StringBuilder", "System", "Thread", "Throwable",
			"UnsupportedOperationException"
	);

	private static final Pattern DECLARATION = Pattern.compile("\\b(?:class|interface|record|enum)\\s+(\\w+)");
	private static final Pattern REFERENCE = Pattern.compile("\\[([^\\[\\]]+)]");
	private static final Pattern IMPORT = Pattern.compile("import\\s+(?:static\\s+)?([\\w.]+);");
	private static final Pattern WILDCARD_IMPORT = Pattern.compile("import\\s+[\\w.]+\\.\\*;");
	/// Method declarations and calls — enough to see that a referenced method exists somewhere in its file.
	private static final Pattern MEMBER_CALL = Pattern.compile("\\b(\\w+)\\s*\\(");
	/// Fields, parameters and record components.
	private static final Pattern MEMBER_NAME = Pattern.compile("\\b(\\w+)\\s*[;=,)]");
	/// An enum constant on its own line — including the LAST one, which is followed by `}`, not a comma.
	private static final Pattern ENUM_CONSTANT = Pattern.compile("^\\s*([A-Z][A-Z_0-9]*)\\s*[,;]?\\s*$", Pattern.MULTILINE);

	/// Every Java source in the build, used only to build the index of known types and members. It spans all modules
	/// on purpose: a reference in one module routinely names a type declared in another (the server's Javadoc citing
	/// a shared protocol record), and a per-module index would report every one of those as missing.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getIndexSources();

	/// The sources actually checked — this module's own, so a failure names files the module owns.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getScanSources();

	/// A record of what was checked, so the task is up-to-date-checkable and cacheable rather than always running.
	@OutputFile
	public abstract RegularFileProperty getReport();

	/// One broken reference: where it is, what it said, and why it could not be resolved.
	private record Problem(File file, int line, String reference, String why) {

		String describe() {
			return "  " + file + ":" + line + System.lineSeparator() + "      [" + reference + "] -> " + why;
		}
	}

	/// The indexed source: which files declare each type name (recorded both as `Nested` and as `Outer.Nested`, since
	/// the Javadoc here uses both forms), and which member names appear in each file.
	private record Index(Map<String, Set<File>> types, Map<File, Set<String>> members) {

		/// The member names of `file`, or an empty set for a file outside the index.
		Set<String> membersOf(File file) {
			return members.getOrDefault(file, Set.of());
		}
	}

	/// The per-file context a reference is resolved against: what that file imports (an imported name is external,
	/// so unresolvable here), whether it imports a package wholesale, and its own members for a `[#member]` link.
	private record FileContext(Set<String> imported, boolean hasWildcardImport, Set<String> ownMembers) {
	}

	@TaskAction
	public void check() {
		Index index = index();
		List<Problem> problems = getScanSources().getFiles().stream()
				.filter(File::isFile)
				.flatMap(file -> problemsIn(file, index).stream())
				.toList();

		writeReport(index, problems);

		if (!problems.isEmpty()) {
			throw new GradleException(problems.stream()
					.map(Problem::describe)
					.collect(Collectors.joining(
							System.lineSeparator(),
							"Javadoc references point at symbols that do not exist:" + System.lineSeparator(),
							""
					)));
		}
	}

	private void writeReport(Index index, List<Problem> problems) {
		StringBuilder report = new StringBuilder()
				.append("checked ").append(getScanSources().getFiles().size())
				.append(" file(s) against ").append(index.types().size()).append(" known type name(s)")
				.append(System.lineSeparator())
				.append(problems.size()).append(" problem(s)").append(System.lineSeparator());
		problems.forEach(problem -> report
				.append(problem.file()).append(':').append(problem.line())
				.append(" [").append(problem.reference()).append("] -> ").append(problem.why())
				.append(System.lineSeparator()));
		File file = getReport().get().getAsFile();
		try {
			Files.createDirectories(file.getParentFile().toPath());
			Files.writeString(file.toPath(), report, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("could not write " + file, e);
		}
	}

	private Index index() {
		Map<String, Set<File>> types = new HashMap<>();
		Map<File, Set<String>> members = new HashMap<>();
		getIndexSources().getFiles().stream().filter(File::isFile).forEach(file -> {
			String text = read(file);
			List<String> declared = matches(DECLARATION, text);
			if (!declared.isEmpty()) {
				// The first declaration in a file is its top-level type, so every later one is nested in it and is
				// recorded under both names — Javadoc here refers to nested types either way.
				String outer = declared.getFirst();
				declared.forEach(name -> {
					types.computeIfAbsent(name, _ -> new HashSet<>()).add(file);
					if (!name.equals(outer)) {
						types.computeIfAbsent(outer + '.' + name, _ -> new HashSet<>()).add(file);
					}
				});
			}
			Set<String> found = new HashSet<>(matches(MEMBER_CALL, text));
			found.addAll(matches(MEMBER_NAME, text));
			found.addAll(matches(ENUM_CONSTANT, text));
			members.put(file, found);
		});
		return new Index(types, members);
	}

	private List<Problem> problemsIn(File file, Index index) {
		String text = read(file);
		FileContext context = new FileContext(
				Set.copyOf(matches(IMPORT, text).stream().map(JavadocReferenceCheck::simpleName).toList()),
				WILDCARD_IMPORT.matcher(text).find(),
				index.membersOf(file)
		);
		List<Problem> problems = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
			String line = lines[lineIndex];
			if (!line.stripLeading().startsWith("///")) {
				continue;
			}
			Matcher matcher = REFERENCE.matcher(line);
			while (matcher.find()) {
				// "[text](url)" is a Markdown link, not a symbol reference.
				if (matcher.end() < line.length() && line.charAt(matcher.end()) == '(') {
					continue;
				}
				String reference = matcher.group(1).strip();
				String why = resolve(reference, index, context);
				if (why != null) {
					problems.add(new Problem(file, lineIndex + 1, reference, why));
				}
			}
		}
		return problems;
	}

	/// The reason `reference` is broken, or `null` when it resolves — or is not a symbol reference at all.
	private String resolve(String reference, Index index, FileContext context) {
		// Prose rather than a reference: a list ("[a,b]") or a sentence. A method signature like
		// [Foo#bar(String, int)] legitimately contains spaces, so spaces only disqualify it when there are no
		// parentheses to explain them.
		if (reference.isEmpty()
				|| reference.indexOf(',') >= 0
				|| reference.indexOf(' ') >= 0 && reference.indexOf('(') < 0) {
			return null;
		}
		String body = before(reference, '(');   // a signature's parameter list adds nothing to the resolution
		String type = before(body, '#').strip();
		String member = body.indexOf('#') < 0 ? "" : body.substring(body.indexOf('#') + 1).strip();

		if (type.isEmpty()) {   // "[#member]" — a member of the file being scanned
			return member.isEmpty() || context.ownMembers().contains(member)
					? null
					: "no member \"" + member + "\" in this file";
		}

		String simple = simpleName(type);
		// Wire-format notation and other prose ("[tag][payload]", "[sid]"): a type reference starts upper-case.
		if (!Character.isUpperCase(simple.charAt(0))) {
			return null;
		}
		boolean fullyQualified = type.indexOf('.') >= 0 && Character.isLowerCase(type.charAt(0));
		if (fullyQualified && !type.startsWith(PROJECT_PACKAGE)) {
			return null;   // a third-party or JDK type — not in this index, and not this check's business
		}
		if (!fullyQualified && (JAVA_LANG.contains(simple) || context.imported().contains(simple))) {
			return null;
		}

		String owner = knownName(type, simple, index);
		if (owner == null) {
			return context.hasWildcardImport() && !fullyQualified && !namesNestedTypeOfAKnownType(type, index)
					? null   // a bare simple name in a wildcard-import file: it may come from that package
					: "unknown type \"" + type + "\"";
		}
		if (member.isEmpty()) {
			return null;
		}
		// A simple name can be declared in more than one file (the same name in two modules), so the member counts as
		// found if ANY declaring file has it.
		return index.types().get(owner).stream().anyMatch(declaring -> index.membersOf(declaring).contains(member))
				? null
				: "no member \"" + member + "\" on " + owner;
	}

	/// The name `type` is indexed under — as written, its simple name, or the `Outer.Nested` tail of a longer path —
	/// or `null` when this project declares no such type.
	private static String knownName(String type, String simple, Index index) {
		if (index.types().containsKey(type)) {
			return type;
		}
		if (index.types().containsKey(simple)) {
			return simple;
		}
		String[] segments = type.split("\\.");
		if (segments.length >= 2) {
			String tail = segments[segments.length - 2] + '.' + segments[segments.length - 1];
			if (index.types().containsKey(tail)) {
				return tail;
			}
		}
		return null;
	}

	/// Whether `type` is an `Outer.Nested` whose OUTER half is a known project type — in which case an unresolved
	/// whole is a genuine error even in a file with a wildcard import. A wildcard import contributes simple names
	/// only, so it can never explain away `Channel.Ghost` once `Channel` itself is ours; without this, the most
	/// common shape in this codebase's Javadoc (`ClientMessage.Join`, `Channel.Defaults`) would go unchecked in every
	/// file that imports a package wholesale.
	private static boolean namesNestedTypeOfAKnownType(String type, Index index) {
		return type.indexOf('.') >= 0 && index.types().containsKey(before(type, '.'));
	}

	private static List<String> matches(Pattern pattern, String text) {
		return pattern.matcher(text).results().map(result -> result.group(1)).toList();
	}

	private static String simpleName(String qualified) {
		int lastDot = qualified.lastIndexOf('.');
		return lastDot < 0 ? qualified : qualified.substring(lastDot + 1);
	}

	private static String before(String value, char separator) {
		int index = value.indexOf(separator);
		return index < 0 ? value : value.substring(0, index);
	}

	private static String read(File file) {
		try {
			return Files.readString(file.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("could not read " + file, e);
		}
	}
}
