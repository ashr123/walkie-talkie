import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Fails the build when a DOM-free browser module is missing from the inventory in CLAUDE.md, or has no test.
///
/// Why this exists: the browser client's pure rules live in sibling modules of `app.js` so they can be unit-tested,
/// and CLAUDE.md carries the list of them with a sentence each on WHY each exists — the first thing anyone reads
/// before touching that code. Five commits in one sitting added `mic-errors.js`, `names.js` and four new rules to
/// `talk.js`, and not one of them updated that list; it still claimed "There are two". Each change was
/// self-contained and correct, and the documentation sat one directory up, so nothing pointed at the drift. This
/// task points at it.
///
/// A "module" is not a hand-maintained list — it is whatever `app.js` imports from `./`, which is exactly the set of
/// DOM-free siblings and nothing else (`audio-worklet.js` is loaded by URL at runtime, not imported, so it is
/// correctly absent). Deriving the set means a new module is covered the moment it is wired up, and an exclusion
/// list cannot rot.
@CacheableTask
public abstract class BrowserModuleDocCheck extends DefaultTask {

	/// `import … from './name.js'` — the only way a DOM-free sibling reaches app.js.
	private static final Pattern SIBLING_IMPORT = Pattern.compile("from\\s+'\\./([A-Za-z0-9._-]+)\\.js'");

	/// The "There are N:" claim that introduces the list. Checked when present, because a stale COUNT is what made
	/// the drift visible in the first place; a rewording simply skips this assertion rather than failing.
	///
	/// `\s+` rather than a literal space: the surrounding Markdown is hard-wrapped, so an edit anywhere before the
	/// claim can reflow it onto a line break — and a claim this failed to match would silently stop being checked.
	/// Only the FIRST match counts, so the phrase must appear exactly once; that is why the prose describing this
	/// task does not quote it.
	private static final Pattern COUNT_CLAIM = Pattern.compile("There are\\s+([a-z]+):");

	private static final Map<String, Integer> NUMBER_WORDS = Map.of(
			"two", 2, "three", 3, "four", 4, "five", 5, "six", 6, "seven", 7, "eight", 8, "nine", 9, "ten", 10);

	/// The browser client entry point, whose imports define the module set. Absent in modules that serve no browser
	/// client, which makes this task a no-op for them.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getEntryPoint();

	/// The test files, so a module can be required to HAVE one — the pattern is only worth anything if the module
	/// that was extracted for testability is actually tested.
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getModuleTests();

	/// The document expected to name every module. Optional so the task degrades to a no-op rather than failing the
	/// build of a checkout that does not carry it.
	@InputFiles
	@Optional
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getDocumentation();

	/// A record of what was checked, so the task is up-to-date-checkable and cacheable rather than always running.
	@OutputFile
	public abstract RegularFileProperty getReport();

	@TaskAction
	public void check() {
		// Modules that serve no browser client (walkie-shared, walkie-client-java) have nothing to say about the
		// list. Bailing on the ENTRY POINT rather than on an empty module set matters: an app.js that imports
		// nothing would leave the same empty set, and that IS worth reporting against a documented count.
		List<File> entryPoints = getEntryPoint().getFiles().stream().filter(File::isFile).toList();
		if (entryPoints.isEmpty()) {
			write("No browser client in this module (no static/assets/app.js), nothing to check.\n");
			return;
		}

		Set<String> modules = importedModules(entryPoints);
		String documentation = getDocumentation().getFiles().stream()
				.filter(File::isFile)
				.map(BrowserModuleDocCheck::read)
				.collect(Collectors.joining("\n"));
		Set<String> tested = getModuleTests().getFiles().stream()
				.map(File::getName)
				.filter(name -> name.endsWith(".test.js"))
				.map(name -> name.substring(0, name.length() - ".test.js".length()))
				.collect(Collectors.toCollection(LinkedHashSet::new));

		List<String> problems = new ArrayList<>();
		if (!documentation.isEmpty()) {
			modules.stream()
					// The list writes them as `static/assets/<name>.js`; accept any mention of the file name so a
					// reworded path still counts — the point is that the module is DESCRIBED, not how it is spelled.
					.filter(module -> !documentation.contains(module + ".js"))
					.forEach(module -> problems.add(
							"  " + module + ".js is imported by app.js but never mentioned in the documentation"));
			problems.addAll(countProblems(documentation, modules.size()));
		}
		modules.stream()
				.filter(module -> !tested.contains(module))
				.forEach(module -> problems.add("  " + module + ".js has no " + module + ".test.js — a module is "
						+ "extracted from app.js precisely so it can be tested"));

		writeReport(modules, tested, problems);
		if (!problems.isEmpty()) {
			throw new GradleException("Browser modules are out of sync with their documentation:\n"
					+ String.join("\n", problems)
					+ "\n\nAdd a bullet to the browser-module list in CLAUDE.md saying what the module is FOR, "
					+ "and keep the count that introduces the list correct.");
		}
	}

	/// The modules `app.js` imports from `./`, in the order they appear.
	private static Set<String> importedModules(List<File> entryPoints) {
		Set<String> modules = new LinkedHashSet<>();
		entryPoints.stream()
				.map(BrowserModuleDocCheck::read)
				.forEach(source -> {
					Matcher matcher = SIBLING_IMPORT.matcher(source);
					while (matcher.find()) {
						modules.add(matcher.group(1));
					}
				});
		return modules;
	}

	/// The "There are N:" claim, when the documentation makes one and the word is one this understands.
	private static List<String> countProblems(String documentation, int actual) {
		Matcher matcher = COUNT_CLAIM.matcher(documentation);
		if (!matcher.find()) {
			return List.of();
		}
		Integer claimed = NUMBER_WORDS.get(matcher.group(1));
		return claimed == null || claimed == actual
				? List.of()
				: List.of("  the documentation says \"There are " + matcher.group(1) + ":\" but app.js imports "
				+ actual + " module(s)");
	}

	private void writeReport(Set<String> modules, Set<String> tested, List<String> problems) {
		StringBuilder report = new StringBuilder("Browser modules imported by app.js: ")
				.append(modules.size()).append('\n');
		modules.forEach(module -> report.append("  ").append(module).append(".js")
				.append(tested.contains(module) ? "  (tested)" : "  (NO TEST)").append('\n'));
		report.append(problems.isEmpty() ? "\nAll documented.\n" : "\nProblems:\n" + String.join("\n", problems) + "\n");
		write(report.toString());
	}

	/// The report is an `@OutputFile`, so every path through the task — including the one that checks nothing —
	/// has to produce it, or Gradle re-runs the task forever.
	private void write(String report) {
		File file = getReport().get().getAsFile();
		try {
			Files.createDirectories(file.toPath().getParent());
			Files.writeString(file.toPath(), report, StandardCharsets.UTF_8);
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
