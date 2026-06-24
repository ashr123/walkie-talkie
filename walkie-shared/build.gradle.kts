plugins {
	id("walkietalkie.java-conventions")
	`java-library`
}

dependencies {
	// The Spring Boot BOM is imported purely for dependency-version management,
	// so the Jackson annotations version stays aligned with the server's Jackson 3.
	api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

	// Jackson 3 keeps the annotations module under the legacy group/package for compatibility.
	api("com.fasterxml.jackson.core:jackson-annotations")
}
