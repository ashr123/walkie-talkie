pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
}

rootProject.name = "walkie-talkie"

include("walkie-shared", "walkie-server", "walkie-client-java")
