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
