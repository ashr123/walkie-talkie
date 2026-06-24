plugins {
	// Lets us write precompiled script plugins (*.gradle.kts under src/main/kotlin)
	// that other modules apply by id.
	`kotlin-dsl`
}

repositories {
	gradlePluginPortal()
	mavenCentral()
}
