import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
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
