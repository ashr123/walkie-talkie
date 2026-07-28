import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails the build on a `///` Javadoc reference that points at something which does not exist — `[SomeType]`,
 * `[Type#member]` or `[#member]` naming a type or member that is not (or is no longer) in the source.
 *
 * Why this exists: the project's Markdown Javadoc links are plain text as far as the compiler is concerned, so a
 * renamed or not-yet-written symbol leaves a silently broken reference. The build does not run `javadoc`, and
 * `javadoc` would not catch the case that earned this check its place anyway — links to protocol records that a
 * feature had *documented before implementing*.
 *
 * It is deliberately conservative, because a false positive fails an innocent build and teaches people to distrust
 * the check. Anything it cannot resolve with confidence is skipped:
 *
 *  - Markdown links (`[text](url)`) and prose in brackets (`[tag][payload]`, `[a,b]`, `[sid]`) are not references.
 *    A reference names a type, so its simple name starts upper-case.
 *  - `java.lang` types, anything the file imports, and fully-qualified names outside this project's own package are
 *    external and not indexable here.
 *  - In a file with a wildcard import, an unresolved SIMPLE name is skipped: it may come from that package. This is
 *    the one real hole — a typo'd project type in such a file goes unreported — and it beats guessing.
 */
@CacheableTask
abstract class JavadocReferenceCheck : DefaultTask() {

	/**
	 * Every Java source in the build, used only to build the index of known types and members. It spans all modules
	 * on purpose: a reference in one module routinely names a type declared in another (the server's Javadoc citing
	 * a shared protocol record), and a per-module index would report every one of those as missing.
	 */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val indexSources: ConfigurableFileCollection

	/** The sources actually checked — this module's own, so a failure names files the module owns. */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val scanSources: ConfigurableFileCollection

	/** A record of what was checked, so the task is up-to-date-checkable and cacheable rather than always running. */
	@get:OutputFile
	abstract val report: RegularFileProperty

	private companion object {
		/** A reference must name a type from THIS project to be resolvable; anything else is a third-party symbol. */
		const val PROJECT_PACKAGE = "io.github.ashr123.walkietalkie"

		/**
		 * `java.lang` is imported implicitly, so these never appear in an import statement and so cannot be told
		 * apart from a project type by that route.
		 */
		val JAVA_LANG = setOf(
			"AutoCloseable", "Boolean", "Byte", "CharSequence", "Character", "Class", "Comparable", "Double",
			"Enum", "Error", "Exception", "Float", "IllegalArgumentException", "IllegalStateException", "Integer",
			"Iterable", "Long", "Math", "NullPointerException", "Object", "Override", "Record", "Runnable",
			"Runtime", "SafeVarargs", "Short", "String", "StringBuilder", "System", "Thread", "Throwable",
			"UnsupportedOperationException"
		)

		val DECLARATION = Regex("""\b(?:class|interface|record|enum)\s+(\w+)""")
		val REFERENCE = Regex("""\[([^\[\]]+)]""")
		val IMPORT = Regex("""import\s+(?:static\s+)?([\w.]+);""")
		val WILDCARD_IMPORT = Regex("""import\s+[\w.]+\.\*;""")

		/** Method declarations and calls — enough to see that a referenced method exists somewhere in its file. */
		val MEMBER_CALL = Regex("""\b(\w+)\s*\(""")

		/** Fields, parameters and record components. */
		val MEMBER_NAME = Regex("""\b(\w+)\s*[;=,)]""")

		/** An enum constant on its own line — including the LAST one, which is followed by `}`, not a comma. */
		val ENUM_CONSTANT = Regex("""^\s*([A-Z][A-Z_0-9]*)\s*[,;]?\s*$""", RegexOption.MULTILINE)
	}

	/** One broken reference: where it is, what it said, and why it could not be resolved. */
	private data class Problem(val file: File, val line: Int, val reference: String, val why: String)

	/**
	 * The indexed source: which files declare each type name (recorded both as `Nested` and as `Outer.Nested`, since
	 * the Javadoc here uses both forms), and which member names appear in each file.
	 */
	private data class Index(val types: Map<String, Set<File>>, val members: Map<File, Set<String>>)

	@TaskAction
	fun check() {
		val index = index()
		val problems = scanSources.files
			.filter { it.isFile }
			.flatMap { problemsIn(it, index) }

		report.get().asFile.apply {
			parentFile.mkdirs()
			writeText(
				buildString {
					appendLine("checked ${scanSources.files.size} file(s) against ${index.types.size} known type name(s)")
					appendLine("${problems.size} problem(s)")
					problems.forEach { appendLine("${it.file}:${it.line} [${it.reference}] -> ${it.why}") }
				}
			)
		}

		if (problems.isNotEmpty()) {
			throw GradleException(
				problems.joinToString(
					separator = System.lineSeparator(),
					prefix = "Javadoc references point at symbols that do not exist:" + System.lineSeparator()
				) { "  ${it.file}:${it.line}${System.lineSeparator()}      [${it.reference}] -> ${it.why}" }
			)
		}
	}

	private fun index(): Index {
		val types = mutableMapOf<String, MutableSet<File>>()
		val members = mutableMapOf<File, Set<String>>()
		indexSources.files.filter { it.isFile }.forEach { file ->
			val text = file.readText()
			val declared = DECLARATION.findAll(text).map { it.groupValues[1] }.toList()
			declared.firstOrNull()?.let { outer ->
				declared.forEach { name ->
					types.getOrPut(name) { mutableSetOf() }.add(file)
					if (name != outer) {
						types.getOrPut("$outer.$name") { mutableSetOf() }.add(file)
					}
				}
			}
			members[file] = buildSet {
				MEMBER_CALL.findAll(text).forEach { add(it.groupValues[1]) }
				MEMBER_NAME.findAll(text).forEach { add(it.groupValues[1]) }
				ENUM_CONSTANT.findAll(text).forEach { add(it.groupValues[1]) }
			}
		}
		return Index(types, members)
	}

	private fun problemsIn(file: File, index: Index): List<Problem> {
		val text = file.readText()
		val imported = IMPORT.findAll(text).map { it.groupValues[1].substringAfterLast('.') }.toSet()
		val hasWildcardImport = WILDCARD_IMPORT.containsMatchIn(text)
		val ownMembers = index.members[file].orEmpty()
		return text.lineSequence().withIndex()
			.filter { (_, line) -> line.trimStart().startsWith("///") }
			.flatMap { (lineIndex, line) ->
				REFERENCE.findAll(line)
					// "[text](url)" is a Markdown link, not a symbol reference.
					.filter { match -> line.getOrNull(match.range.last + 1) != '(' }
					.mapNotNull { match ->
						val reference = match.groupValues[1].trim()
						resolve(reference, index, ownMembers, imported, hasWildcardImport)
							?.let { why -> Problem(file, lineIndex + 1, reference, why) }
					}
			}
			.toList()
	}

	/**
	 * Whether `type` is an `Outer.Nested` whose OUTER half is a known project type — in which case an unresolved
	 * whole is a genuine error even in a file with a wildcard import. A wildcard import contributes simple names
	 * only, so it can never explain away `Channel.Ghost` once `Channel` itself is ours; without this, the most
	 * common shape in this codebase's Javadoc (`ClientMessage.Join`, `Channel.Defaults`) would go unchecked in
	 * every file that imports a package wholesale.
	 */
	private fun namesNestedTypeOfAKnownType(type: String, index: Index): Boolean =
		type.contains('.') && index.types.containsKey(type.substringBefore('.'))

	/** The reason `reference` is broken, or null when it resolves — or is not a symbol reference at all. */
	private fun resolve(
		reference: String,
		index: Index,
		ownMembers: Set<String>,
		imported: Set<String>,
		hasWildcardImport: Boolean
	): String? {
		// Prose rather than a reference: a list ("[a,b]") or a sentence. A method signature like
		// [Foo#bar(String, int)] legitimately contains spaces, so spaces only disqualify it when there are no
		// parentheses to explain them.
		if (reference.isEmpty() || reference.contains(',') || (reference.contains(' ') && !reference.contains('('))) {
			return null
		}
		val body = reference.substringBefore('(')   // a signature's parameter list adds nothing to the resolution
		val type = body.substringBefore('#').trim()
		val member = body.substringAfter('#', "").trim()

		if (type.isEmpty()) {   // "[#member]" — a member of the file being scanned
			return if (member.isEmpty() || ownMembers.contains(member)) null else "no member \"$member\" in this file"
		}

		val simple = type.substringAfterLast('.')
		// Wire-format notation and other prose ("[tag][payload]", "[sid]"): a type reference starts upper-case.
		if (!simple.first().isUpperCase()) {
			return null
		}
		val fullyQualified = type.contains('.') && type.first().isLowerCase()
		if (fullyQualified && !type.startsWith(PROJECT_PACKAGE)) {
			return null   // a third-party or JDK type — not in this index, and not this check's business
		}
		if (!fullyQualified && (JAVA_LANG.contains(simple) || imported.contains(simple))) {
			return null
		}

		// Accept the reference under any name it might be indexed by: as written, its simple name, or the
		// Outer.Nested tail of a longer path.
		val candidates = buildSet {
			add(type)
			add(simple)
			type.split('.').takeLast(2).takeIf { it.size == 2 }?.let { add(it.joinToString(".")) }
		}
		val owner = candidates.firstOrNull { index.types.containsKey(it) }
			?: return if (hasWildcardImport && !fullyQualified && !namesNestedTypeOfAKnownType(type, index)) {
				null   // a bare simple name in a wildcard-import file: it may come from that package
			} else {
				"unknown type \"$type\""
			}
		if (member.isEmpty()) {
			return null
		}
		// A simple name can be declared in more than one file (the same name in two modules), so the member counts
		// as found if ANY declaring file has it.
		return if (index.types.getValue(owner).any { index.members[it].orEmpty().contains(member) }) {
			null
		} else {
			"no member \"$member\" on $owner"
		}
	}
}
