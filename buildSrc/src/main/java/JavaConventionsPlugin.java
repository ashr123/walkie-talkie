import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.testing.jacoco.plugins.JacocoPlugin;
import org.gradle.testing.jacoco.tasks.JacocoReport;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;

/// Shared Java build conventions for every module in this build.
/// Modules apply it with: `plugins { id("walkietalkie.java-conventions") }` — the id is declared in
/// `buildSrc/build.gradle.kts`, which maps it to this class.
///
/// This replaces the old `subprojects {}`/`apply(plugin = ...)` cross-configuration, which Gradle discourages
/// (it breaks project isolation) and IntelliJ flags as legacy.
///
/// A **binary** plugin in Java rather than a precompiled `*.gradle.kts` script plugin: Gradle build *scripts* can only
/// be Kotlin or Groovy, but build *logic* is ordinary JVM code, and writing it in the same language as the rest of the
/// repository keeps the Kotlin compiler — and the JVM-target pinning its DSL needs on a JDK 26 host — out of the build
/// entirely. `buildSrc` is therefore all Java, alongside [JavadocReferenceCheck].
public final class JavaConventionsPlugin implements Plugin<Project> {

	/// The Java language level and class-file version every module targets, passed as `--release` so the host JDK
	/// (26 here) can produce Java 25 bytecode without a separate JDK 25 installation. It is the Java 25 LTS the
	/// codebase is written against — records, sealed types, pattern matching, `ScopedValue` — so lowering it is a
	/// source-compatibility change, not a flag flip.
	private static final int JAVA_RELEASE = 25;

	/// The Maven coordinates every module publishes under. `Project#setGroup`/`#setVersion` are typed `Object`, not
	/// `String` — Gradle only ever calls `toString()` on them when it composes artifact names — but there is no
	/// version type worth passing instead, so a plain String it is. Gradle's own are the wrong domain
	/// (`GradleVersion` is Gradle's version, `ModuleVersionIdentifier` describes a dependency), and
	/// `java.lang.Runtime.Version` implements the JAVA PLATFORM scheme of JEP 322, which cannot express this version
	/// at all: its version number must neither begin nor end with a zero element, so every pre-1.0 version and every
	/// `x.y.0` is rejected — `Runtime.Version.parse("0.1.0")` throws. It is also not `Serializable`, which the
	/// configuration cache would eventually care about.
	private static final String PROJECT_GROUP = "io.github.ashr123";
	private static final String PROJECT_VERSION = "0.1.0";

	/// Source encoding for every compile, fixed rather than inherited from the platform default so the build is
	/// reproducible on a machine whose `file.encoding` differs. [CompileOptions#setEncoding] takes the javac
	/// `-encoding` flag verbatim and has no `Charset` overload, hence the conversion here — via `name()`, the
	/// canonical locale-independent IANA name, NOT `displayName()`, which is documented as possibly localized.
	private static final String SOURCE_ENCODING = StandardCharsets.UTF_8.name();

	/// The compiles Spring Boot's AOT engine GENERATES into `build/generated/aotSources` + `aotTestSources`
	/// (bean-definition suppliers and friends), which get the strict lint switched off — see [#configureJavaCompile].
	private static final String GENERATED_AOT_COMPILE_PREFIX = "compileAot";

	@Override
	public void apply(@NonNull Project project) {
		project.getPluginManager().apply(JavaPlugin.class);
		project.getPluginManager().apply(JacocoPlugin.class);

		project.setGroup(PROJECT_GROUP);
		project.setVersion(PROJECT_VERSION);
		project.getRepositories().mavenCentral();

		project.getTasks().withType(JavaCompile.class).configureEach(this::configureJavaCompile);

		project.getTasks().withType(Test.class).configureEach(test -> {
			test.useJUnitPlatform();
			// Run the coverage report automatically after the tests, so `./gradlew test` (or build) always
			// refreshes build/reports/jacoco/test for that module.
			test.finalizedBy(project.getTasks().named("jacocoTestReport"));
		});

		registerJavadocReferenceCheck(project);
		registerBrowserModuleDocCheck(project);
		registerDocumentationChecks(project);

		project.getTasks().named("jacocoTestReport", JacocoReport.class, report -> {
			report.dependsOn(project.getTasks().named(JavaPlugin.TEST_TASK_NAME));
			report.reports(reports -> {
				reports.getXml().getRequired().set(true);
				reports.getCsv().getRequired().set(true);
				reports.getHtml().getRequired().set(true);
			});
		});
	}

	/// Target the Java 25 language level and class-file version using the host JDK (compiled with
	/// `--release 25`, so a separate JDK 25 install is not required).
	/// To pin a strict JDK 25 toolchain instead, replace the release line with
	/// `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` (i.e. configure the `java`
	/// extension here) and add the foojay-resolver-convention plugin in `settings.gradle.kts`.
	private void configureJavaCompile(JavaCompile compile) {
		CompileOptions options = compile.getOptions();
		options.getRelease().set(JAVA_RELEASE);
		options.setEncoding(SOURCE_ENCODING);
		options.getCompilerArgs().add("-parameters");
		// The AOT-generated code is not ours and legitimately uses raw generic types (e.g.
		// BeanInstanceSupplier<IncludeExcludeEndpointFilter> for the generic IncludeExcludeEndpointFilter<E>), so
		// -Xlint:all floods those compiles with [rawtypes] warnings we can neither fix nor act on — and which would
		// drown out a real warning in OUR code. Keep the strict lint on the hand-written sources; silence it only for
		// the generated AOT compiles. (removeIf guards against the native-build-tools plugin having added its own
		// -Xlint to these tasks before this action runs.)
		if (compile.getName().startsWith(GENERATED_AOT_COMPILE_PREFIX)) {   // compileAotJava + compileAotTestJava
			options.getCompilerArgs().removeIf(argument -> argument.startsWith("-Xlint"));
			options.getCompilerArgs().add("-Xlint:none");
		} else {
			options.getCompilerArgs().add("-Xlint:all");
		}
	}

	/// Keep README.md and docs/CLIENT_PROTOCOL.md honest the way [BrowserModuleDocCheck] keeps CLAUDE.md honest:
	/// derive the truth from source, and fail `check` when the documentation has fallen behind it.
	///
	/// Registered per OWNING module rather than everywhere, because each of these checks is about specific files.
	/// That is deliberate: it means an empty source set is a hard failure ("the file moved") instead of a silent
	/// pass, which is the failure mode a documentation check must not have. It also puts each failure in front of
	/// the person editing the code it describes — change `ErrorCode` and it is walkie-shared's `check` that stops.
	private void registerDocumentationChecks(Project project) {
		switch (project.getName()) {
			case "walkie-shared" -> {
				registerProtocolMessageDocCheck(project);
				registerErrorCodeDocCheck(project);
			}
			case "walkie-server" -> registerConfigurationKeyDocCheck(project);
			case "walkie-client-java" -> registerClientOptionDocCheck(project);
			default -> {   // the root project and anything added later document nothing of their own
			}
		}
	}

	/// Every control message and every one of its JSON fields must appear in the §3 tables of the protocol
	/// document. See [ProtocolMessageDocCheck] for why the fields are checked per row rather than document-wide.
	private void registerProtocolMessageDocCheck(Project project) {
		TaskProvider<ProtocolMessageDocCheck> task = project.getTasks()
				.register("checkProtocolMessageDocs", ProtocolMessageDocCheck.class, check -> {
					check.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
					check.setDescription("Fails when a control message or field is missing from CLIENT_PROTOCOL.md.");
					check.getMessageSources().from(project.fileTree(project.getProjectDir(), tree ->
							tree.include("src/main/java/**/protocol/ClientMessage.java",
									"src/main/java/**/protocol/ServerMessage.java")));
					check.getDocumentation().from(clientProtocol(project));
					check.getReport().set(report(project, "protocol-messages.txt"));
				});
		dependOnCheck(project, task);
	}

	/// Every wire error code must have a row in the §13 table. The `@JsonEnumDefaultValue` constant is excluded
	/// BY THE ANNOTATION rather than by name: it is the deserialization fallback for a code minted by a newer
	/// server, not something the server can ever send, so demanding a row for it would be a wrong failure — and
	/// deriving the exemption keeps this from becoming the hand-maintained list the pattern exists to avoid.
	private void registerErrorCodeDocCheck(Project project) {
		TaskProvider<DocumentedTokensCheck> task = project.getTasks()
				.register("checkErrorCodeDocs", DocumentedTokensCheck.class, check -> {
					check.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
					check.setDescription("Fails when a wire error code has no row in CLIENT_PROTOCOL.md §13.");
					check.getSources().from(project.fileTree(project.getProjectDir(), tree ->
							tree.include("src/main/java/**/protocol/ErrorCode.java")));
					check.getTokenPattern().set("(?m)(?<!@JsonEnumDefaultValue\n)^\t([A-Z][A-Z0-9_]*),?$");
					check.getSentinel().set("CHANNEL_ROUTING_MISMATCH");
					check.getDocumentation().from(clientProtocol(project));
					check.getDocRowPattern().set("(?m)^\\|\\s*`([A-Z][A-Z0-9_]*)`\\s*\\|");
					check.getSubject().set("wire error codes");
					check.getHint().set("Add a row to the error table in §13 of docs/CLIENT_PROTOCOL.md saying what "
							+ "TRIGGERS the code. A client switches on these, so an undocumented one is a branch "
							+ "nobody else writes.");
					configure(check, project, "error-codes.txt");
				});
		dependOnCheck(project, task);
	}

	/// Every `walkie.*` knob must be discoverable in the README. A weaker bar than the table checks — the key has
	/// to appear as code SOMEWHERE — and deliberately so: the README documents these across several prose
	/// sections rather than in one table, and a dotted, hyphenated key cannot be satisfied by accident the way an
	/// English word could. It enforces "an operator can find out this knob exists", nothing more.
	///
	/// Default VALUES are not checked, and should not be: the same value has several correct spellings (`8 * 1024`
	/// against "8 KiB", `Duration.ofMinutes(5)` against "5m"), so a value check would both cry wolf and miss real
	/// drift.
	private void registerConfigurationKeyDocCheck(Project project) {
		TaskProvider<DocumentedTokensCheck> task = project.getTasks()
				.register("checkConfigurationKeyDocs", DocumentedTokensCheck.class, check -> {
					check.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
					check.setDescription("Fails when a walkie.* configuration key is undocumented in README.md.");
					check.getSources().from(project.fileTree(project.getProjectDir(), tree ->
							tree.include("src/main/java/**/config/WalkieProperties.java")));
					// Line-anchored at the record header's indent, so the compact constructor's statements and
					// any method parameter are out of reach.
					check.getTokenPattern()
							.set("(?m)^\t\t(?:@\\w+(?:\\([^()]*\\))?\\s+)*[\\w.]+(?:<[^>]*>)?(?:\\[])?\\s+([a-z]\\w*)\\s*[,)]");
					check.getSentinel().set("keepalivePingInterval");   // the LAST component: truncation drops it first
					check.getDocumentation().from(project.getRootProject().file("README.md"));
					check.getTokenPrefix().set("walkie.");
					check.getRelaxedBinding().set(true);
					check.getSubject().set("configuration keys");
					check.getHint().set("Document the key in README.md — in the properties table or the section "
							+ "about the feature it controls — spelled `walkie.the-key` in backticks. An "
							+ "undocumented knob is one nobody can find when they need it.");
					configure(check, project, "configuration-keys.txt");
				});
		dependOnCheck(project, task);
	}

	/// Every console-client flag must have a row in the README's options table. Bidirectional, which is safe only
	/// because it is scoped to that one table: the README's prose legitimately mentions `--args`, `--release` and
	/// `--test`, none of which are rows. `--version` and `--help` correctly never appear in the derived set —
	/// picocli's `mixinStandardHelpOptions` provides them, not an `@Option`.
	private void registerClientOptionDocCheck(Project project) {
		TaskProvider<DocumentedTokensCheck> task = project.getTasks()
				.register("checkClientOptionDocs", DocumentedTokensCheck.class, check -> {
					check.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
					check.setDescription("Fails when a client command-line flag has no row in README.md.");
					check.getSources().from(project.fileTree(project.getProjectDir(), tree ->
							tree.include("src/main/java/**/*.java")));
					check.getTokenPattern().set("@Option\\(\\s*names\\s*=\\s*\"(--[a-z0-9-]+)\"");
					check.getSentinel().set("--muted");
					check.getDocumentation().from(project.getRootProject().file("README.md"));
					// The row key carries a metavar (`--server <url>`), so match the flag as its prefix.
					check.getDocRowPattern().set("(?m)^\\|\\s*`(--[a-z0-9-]+)[^`]*`\\s*\\|");
					check.getSubject().set("client command-line options");
					check.getHint().set("Add a row to the options table in README.md's \"Java desktop client\" "
							+ "section with the flag, its default and what it does.");
					configure(check, project, "client-options.txt");
				});
		dependOnCheck(project, task);
	}

	/// The defaults every [DocumentedTokensCheck] needs but most registrations have nothing to say about.
	private void configure(DocumentedTokensCheck check, Project project, String reportName) {
		check.getTokenPrefix().convention("");
		check.getRelaxedBinding().convention(false);
		check.getReport().set(report(project, reportName));
	}

	private ConfigurableFileCollection clientProtocol(Project project) {
		return project.files(project.getRootProject().file("docs/CLIENT_PROTOCOL.md"));
	}

	private Provider<RegularFile> report(Project project, String name) {
		return project.getLayout().getBuildDirectory().file("reports/" + name);
	}

	private void dependOnCheck(Project project, TaskProvider<? extends Task> task) {
		project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(check -> check.dependsOn(task));
	}

	/// Verify that every DOM-free browser module is described in CLAUDE.md and has a test, and fail `check` when one
	/// is not. Registered for every module like the Javadoc check; only walkie-server has a browser client, and
	/// [BrowserModuleDocCheck] makes itself a no-op wherever `static/assets/app.js` is absent.
	///
	/// The documentation lives at the repository root, so `rootDir` — a plain File — is the only thing read from
	/// outside this project, exactly as in the Javadoc check.
	private void registerBrowserModuleDocCheck(Project project) {
		TaskProvider<BrowserModuleDocCheck> checkBrowserModuleDocs = project.getTasks()
				.register("checkBrowserModuleDocs", BrowserModuleDocCheck.class, task -> {
					task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
					task.setDescription("Fails when a browser module app.js imports is undocumented or untested.");
					task.getEntryPoint().from(project.fileTree(project.getProjectDir(),
							tree -> tree.include("src/main/resources/static/assets/app.js")));
					task.getModuleTests().from(project.fileTree(project.getProjectDir(),
							tree -> tree.include("src/test/js/*.test.js")));
					task.getDocumentation().from(project.fileTree(project.getRootDir(),
							tree -> tree.include("CLAUDE.md")));
					task.getReport().set(project.getLayout().getBuildDirectory().file("reports/browser-modules.txt"));
				});

		project.getTasks()
				.named(LifecycleBasePlugin.CHECK_TASK_NAME)
				.configure(check -> check.dependsOn(checkBrowserModuleDocs));
	}

	/// Verify this module's `///` Javadoc references actually resolve, and fail `check` when one doesn't. Registered
	/// per module (rather than on the root, which is a bare aggregator with no `check`) so each module checks the files
	/// it owns; the TYPE INDEX still spans the whole build, because a reference routinely names another module's type.
	/// Only `rootDir` — a plain File — is read from outside this project, so no other project's model is touched.
	private void registerJavadocReferenceCheck(Project project) {
		TaskProvider<JavadocReferenceCheck> checkJavadocReferences = project.getTasks()
				.register("checkJavadocReferences", JavadocReferenceCheck.class, task -> {
					task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
					task.setDescription("Fails on a /// Javadoc reference to a type or member that does not exist.");
					// Both inputs are hand-written sources only, matched by path rather than taken from the source
					// sets: the server's source sets also contain Spring's GENERATED AOT sources (hundreds of files
					// with no Javadoc), which would make this check slower, noisier, and dependent on a
					// code-generation task it has no business needing.
					task.getIndexSources()
							.from(project.fileTree(project.getRootDir(), tree -> tree.include("*/src/*/java/**/*.java")));
					task.getScanSources()
							.from(project.fileTree(project.getProjectDir(), tree -> tree.include("src/*/java/**/*.java")));
					task.getReport().set(project.getLayout().getBuildDirectory().file("reports/javadoc-references.txt"));
				});

		project.getTasks()
				.named(LifecycleBasePlugin.CHECK_TASK_NAME)
				.configure(check -> check.dependsOn(checkJavadocReferences));
	}
}
