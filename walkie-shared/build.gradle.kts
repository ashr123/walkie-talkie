plugins {
	id("walkietalkie.java-conventions")
	`java-library`
}

dependencies {
	// Compile this module's annotated protocol records against Jackson 3, but don't impose a version on
	// consumers. `compileOnly` keeps the BOM private to this module's own compilation (it is exported to
	// neither the consumer's compile nor runtime classpath), so the server and client each choose their
	// own Jackson version.
	compileOnly(platform("tools.jackson:jackson-bom:3.2.0"))

	// Jackson 3 keeps the annotations module under the legacy group/package for compatibility.
	// This module only needs the annotations jar to compile its protocol types; consumers get their
	// runtime/compile Jackson modules from the server/client module BOMs instead of from walkie-shared.
	compileOnly("com.fasterxml.jackson.core:jackson-annotations")
}
