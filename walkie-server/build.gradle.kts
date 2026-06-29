plugins {
	id("walkietalkie.java-conventions")
	id("org.springframework.boot") version "4.1.0"
}

dependencies {
	implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

	implementation(project(":walkie-shared"))

	implementation("io.github.ashr123:option:1.3.1")

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
}

// Mockito's inline mock-maker otherwise self-attaches its Byte Buddy agent at runtime, which is deprecated and
// future JDKs will forbid (dynamic agent loading). Load it as a -javaagent at JVM start instead, reusing the
// exact mockito-core jar already on the test runtime classpath — so the agent and the library are always the
// same (BOM-managed) version, with no extra configuration to declare and no duplicated platform.
val mockitoAgentJar = configurations.testRuntimeClasspath.map { classpath ->
	classpath.files.single { it.name.startsWith("mockito-core-") && it.name.endsWith(".jar") }
}

tasks.named<Test>("test") {
	jvmArgumentProviders.add(CommandLineArgumentProvider {
		listOf("-javaagent:${mockitoAgentJar.get().absolutePath}")
	})
}

// Browser-client tests (src/test/js, plain ES modules) run under Node's built-in test runner — no npm
// dependencies, no build step. Files are passed explicitly (this Node treats a bare directory arg as a script to
// execute, not a folder to scan). Guarded with onlyIf so the Java build still succeeds on a machine without Node
// (it logs a skip); hooked into `check` so `JAVA_OPTS= ./gradlew build` runs them when Node is present.
val isNodeAvailable: Boolean by lazy {
	runCatching { ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0 }
		.getOrDefault(false)
}

val jsTest = tasks.register<Exec>("jsTest") {
	group = "verification"
	description = "Runs the browser client's ES-module tests with Node's built-in test runner (node --test)."
	val jsTestFiles = fileTree(layout.projectDirectory.dir("src/test/js")) { include("**/*.test.js") }
	inputs.files(jsTestFiles).withPropertyName("jsTestFiles")
	inputs.dir(layout.projectDirectory.dir("src/main/resources/static/assets")).withPropertyName("browserClient")
	workingDir = layout.projectDirectory.asFile
	executable = "node"
	argumentProviders.add(CommandLineArgumentProvider {
		listOf("--test") + jsTestFiles.files.map { it.absolutePath }
	})
	onlyIf {
		isNodeAvailable.also { available ->
			if (!available) logger.warn("jsTest: skipping browser client tests — `node` is not on PATH.")
		}
	}
	// Fail loudly if the glob matched nothing: passing zero files makes `node --test` scan the working dir and
	// exit 0 on finding no tests, which would silently skip the browser interop suite while `build` stays green.
	doFirst {
		if (jsTestFiles.files.isEmpty()) {
			throw GradleException("jsTest: no browser client tests matched src/test/js/**/*.test.js — refusing to run " +
					"`node --test` with no files (it would pass vacuously). Did the tests move or get renamed?")
		}
	}
}

tasks.named("check") { dependsOn(jsTest) }
