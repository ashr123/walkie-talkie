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
