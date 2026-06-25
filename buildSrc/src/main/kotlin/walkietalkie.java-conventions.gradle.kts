import org.gradle.testing.jacoco.tasks.JacocoReport

// Shared Java build conventions for every module in this build.
// Modules apply it with: plugins { id("walkietalkie.java-conventions") }
// This replaces the old subprojects {}/apply(plugin = ...) cross-configuration, which Gradle
// discourages (it breaks project isolation) and IntelliJ flags as legacy.

plugins {
	java
	jacoco
}

group = "io.github.ashr123"
version = "0.1.0"

repositories {
	mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
	// Target the Java 25 language level and class-file version using the host JDK
	// (compiled with `--release 25`, so a separate JDK 25 install is not required).
	// To pin a strict JDK 25 toolchain instead, replace the release line with:
	//   java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
	// and add the foojay-resolver-convention plugin in settings.gradle.kts.
	options.release.set(25)
	options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
	options.encoding = "UTF-8"
}

// Use the explicitly-typed task API (named<Test>/named<JacocoReport>) rather than Gradle's generated
// type-safe accessors (tasks.test / tasks.jacocoTestReport). The accessors live in a hash-named package
// (gradle.kotlin.dsl.accessors._<hash>) that IntelliJ can't resolve without a brittle, plugin-set-specific
// import; the typed form below resolves from real Gradle types in both the IDE and the Gradle build.
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	// Run the coverage report automatically after the tests, so `./gradlew test` (or build) always
	// refreshes build/reports/jacoco/test for that module.
	finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
	dependsOn(tasks.named("test"))
	reports {
		xml.required.set(true)
		csv.required.set(true)
		html.required.set(true)
	}
}
