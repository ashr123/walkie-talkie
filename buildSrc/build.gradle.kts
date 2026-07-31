plugins {
	// Publishes the build logic in src/main/java as a plugin other modules apply by id (mapped to its class in the
	// `gradlePlugin` block below), and brings the `java` plugin those sources compile with.
	`java-gradle-plugin`
}

repositories {
	gradlePluginPortal()
	mavenCentral()
}

// buildSrc is a plain Java Gradle project. On a host running JDK 26 the Java compile task would default to targeting
// 26, so pin it to the same Java 25 bytecode the modules it configures produce — without requiring a separate JDK 25
// installation. (This used to have to pin the Kotlin compiler too, back when the conventions were a precompiled
// `*.gradle.kts` script plugin: Kotlin DSL compilation could only target JVM 25 here, and the mismatch tripped
// Gradle's inconsistent-target warning. Writing the conventions in Java removed both the second compiler and that
// whole class of problem.)
tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
}

gradlePlugin {
	plugins {
		create("javaConventions") {
			// The id every module applies: `plugins { id("walkietalkie.java-conventions") }`. As a precompiled script
			// plugin this came from the FILE NAME; a binary plugin declares it explicitly, and it is deliberately the
			// same string so no module's build file had to change.
			id = "walkietalkie.java-conventions"
			implementationClass = "JavaConventionsPlugin"
		}
	}
}
