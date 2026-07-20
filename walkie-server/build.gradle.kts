import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
	id("walkietalkie.java-conventions")
	id("org.springframework.boot") version "4.1.0"
	// GraalVM Native Build Tools. Version pinned to what Spring Boot 4.1.0 aligns with
	// (`native-build-tools-plugin.version` in the spring-boot-dependencies BOM) — plugin versions aren't
	// managed by the BOM's dependency constraints, so it is stated explicitly. Applying this also makes Spring
	// Boot register its AOT tasks (`processAot` / `processTestAot`), which run the AOT engine and fold in the
	// hints from ProtocolRuntimeHints. `build`/`test` also generate and compile those AOT sources as a dependency
	// (so a context that can't be AOT-processed fails the ordinary build — cheap native-readiness insurance). The
	// `test` task still EXECUTES reflectively; `bootRun` and the boot jar execute AOT-processed (wired below);
	// only the `native*` tasks build/run an actual native image and need a GraalVM JDK on PATH.
	id("org.graalvm.buildtools.native") version "1.1.5"
}

dependencies {
	implementation(platform(SpringBootPlugin.BOM_COORDINATES))

	implementation(project(":walkie-shared"))

	implementation("io.github.ashr123:option:1.3.1")

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// Dev-only: fast auto-restart + LiveReload for the browser client (edits to src/main/resources/static/ reload
	// the page). `developmentOnly` keeps it off the test and production classpaths, and the Spring Boot plugin
	// excludes it from the boot jar, so it never ships. The BOM is re-imported on THIS configuration because
	// `developmentOnly` does not extend `implementation` (where the platform above is applied), so it would
	// otherwise get no managed version (resolves to an empty version and fails). NOTE: `bootRun` defaults to AOT
	// (spring.aot.enabled=true), which DevTools' reflective restart doesn't pair with — use `-Paot=false` for an
	// auto-restart dev loop; static-resource LiveReload works regardless of AOT.
	developmentOnly(platform(SpringBootPlugin.BOM_COORDINATES))
	developmentOnly("org.springframework.boot:spring-boot-devtools")

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
	// Capture the provider in a LOCAL so the argument provider (which runs at execution time and is serialized into
	// the configuration cache) closes over the Provider, not the enclosing build script. Referencing the
	// script-level `mockitoAgentJar` directly from the lambda captures the script object, which the configuration
	// cache cannot serialize ("cannot serialize Gradle script object references").
	val agentJar = mockitoAgentJar
	jvmArgumentProviders.add(CommandLineArgumentProvider {
		listOf("-javaagent:${agentJar.get().absolutePath}")
	})
}

// Probe for `node` on PATH. A ValueSource (not a plain script-level `by lazy`) so it is configuration-cache
// correct: Gradle re-runs it to decide whether a cached configuration is still valid, and — unlike a script-level
// val captured by the task's `onlyIf` — it never drags the build script into the serialized task graph.
abstract class NodeOnPath : ValueSource<Boolean, ValueSourceParameters.None> {
	override fun obtain(): Boolean =
		runCatching { ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0 }
			.getOrDefault(false)
}

// Browser-client tests (src/test/js, plain ES modules) run under Node's built-in test runner — no npm
// dependencies, no build step. Files are passed explicitly (this Node treats a bare directory arg as a script to
// execute, not a folder to scan). Guarded with onlyIf so the Java build still succeeds on a machine without Node
// (it is skipped with a reason); hooked into `check` so `JAVA_OPTS= ./gradlew build` runs them when Node is present.
val nodeAvailable = providers.of(NodeOnPath::class) {}

val jsTest = tasks.register<Exec>("jsTest") {
	group = "verification"
	description = "Runs the browser client's ES-module tests with Node's built-in test runner (node --test)."
	// Capture the provider in a LOCAL so the onlyIf Spec (serialized into the configuration cache) closes over the
	// Provider, not the build script. Referencing the script-level `nodeAvailable` from the Spec lambda would
	// capture the script object (its `this$0`), which the configuration cache cannot serialize.
	val nodeOnPath = nodeAvailable
	val jsTestFiles = fileTree(layout.projectDirectory.dir("src/test/js")) { include("**/*.test.js") }
	inputs.files(jsTestFiles).withPropertyName("jsTestFiles")   // declared input (Gradle serializes this itself)
	inputs.dir(layout.projectDirectory.dir("src/main/resources/static/assets")).withPropertyName("browserClient")
	workingDir = layout.projectDirectory.asFile
	executable = "node"
	// Resolve the matched files to a plain List<String> at CONFIGURATION time. The execution-time lambdas below
	// (argument provider, doFirst) then close over this immutable list — NOT the FileTree, which holds a reference
	// to the build script that the configuration cache cannot serialize ("cannot serialize Gradle script object
	// references"). The configuration cache fingerprints this config-time directory read, so adding or removing a
	// test file invalidates the entry and the list is recomputed — it is not frozen stale.
	val jsTestFilePaths = jsTestFiles.files.map { it.absolutePath }
	argumentProviders.add(CommandLineArgumentProvider { listOf("--test") + jsTestFilePaths })
	// The Spec closes over the `nodeAvailable` provider (serializable), not the build script; the reason string is
	// shown when the task is skipped, replacing the old captured-`logger` warning.
	onlyIf("`node` is on PATH (browser-client ES-module tests need Node's test runner)") { nodeOnPath.get() }
	// Fail loudly if the glob matched nothing: passing zero files makes `node --test` scan the working dir and
	// exit 0 on finding no tests, which would silently skip the browser interop suite while `build` stays green.
	doFirst {
		if (jsTestFilePaths.isEmpty()) {
			throw GradleException("jsTest: no browser client tests matched src/test/js/**/*.test.js — refusing to run " +
					"`node --test` with no files (it would pass vacuously). Did the tests move or get renamed?")
		}
	}
}

tasks.named("check") { dependsOn(jsTest) }

// Native-image tuning. The reachability-metadata repository supplies hints for the third-party stack (embedded
// Tomcat, Jackson, Spring internals) so only our own types need hand-registered hints (see ProtocolRuntimeHints).
// `nativeCompile` requires a GraalVM JDK; see README "Native image / AOT readiness" for the deployment caveat
// (`keytool` is absent in the image, so the dev-cert auto-gen path can't run there — supply WALKIE_TLS_KEYSTORE
// or terminate TLS at a proxy).
graalvmNative {
	metadataRepository {
		enabled.set(true)
	}
	binaries {
		named("main") {
			imageName.set("walkie-server")
		}
	}
}

// --- Run the AOT-optimized application on the JVM by default -------------------------------------
// `bootRun` and the runnable boot jar use the AOT-generated context (spring.aot.enabled=true) instead of
// reflective startup: that needs BOTH the flag AND the generated classes/resources on the runtime classpath, so
// both are wired here. AotDetector reads the flag from system properties / a classpath `spring.properties`, NOT
// application.yml (it is consulted before the Environment loads), so bootRun sets a system property and the jar
// bundles src/aot-launch/spring.properties into BOOT-INF/classes. Referencing the `aot` source set output makes
// each task depend on the AOT chain (processAot -> compileAotJava -> ...), so the generated code is fresh.
//
// Tests stay REFLECTIVE on purpose: the flag reaches neither the `test` task nor the main/test classpath (the
// spring.properties lives outside src/main/resources), so a @SpringBootTest — which has no generated context on
// the plain test classpath — is unaffected.
//
// The TLS on/off toggle survives AOT: TlsConfiguration reads walkie.tls.enabled at runtime (not a build-time
// @ConditionalOnProperty), so one AOT build serves HTTPS:8443 (default) or HTTP:8080 (--walkie.tls.enabled=false).
val aotOutput = sourceSets["aot"].output

tasks.named<BootRun>("bootRun") {
	classpath(aotOutput)
	// AOT on by default; `-Paot=false` is a general reflective-startup escape hatch (e.g. to compare AOT vs
	// reflective behaviour when debugging). It is NOT needed for the plain-HTTP dev mode — TlsConfiguration reads
	// walkie.tls.enabled at runtime, so `--walkie.tls.enabled=false` works under AOT too. The packaged jar has no
	// such switch: its bundled spring.properties wins over any -D (SpringProperties reads the file before the
	// system property), so `java -jar` is always AOT.
	systemProperty("spring.aot.enabled", providers.gradleProperty("aot").getOrElse("true"))
}

tasks.named<BootJar>("bootJar") {
	classpath(aotOutput)
	from(layout.projectDirectory.dir("src/aot-launch")) {
		into("BOOT-INF/classes")
	}
}
