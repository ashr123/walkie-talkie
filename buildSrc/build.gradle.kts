import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	// Lets us write precompiled script plugins (*.gradle.kts under src/main/kotlin)
	// that other modules apply by id.
	`kotlin-dsl`
}

repositories {
	gradlePluginPortal()
	mavenCentral()
}

// buildSrc itself is a mixed Kotlin/Java Gradle project. On a host running JDK 26, Gradle's Java
// task defaulted to target 26 while Kotlin DSL compilation can currently target only JVM 25 here,
// which triggers Gradle's inconsistent-target warning. Keep both compilers pinned to Java 25
// bytecode without requiring a separate JDK 25 installation.

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
}

tasks.withType<KotlinCompile>().configureEach {
	compilerOptions {
		jvmTarget.set(JvmTarget.JVM_25)
	}
}
