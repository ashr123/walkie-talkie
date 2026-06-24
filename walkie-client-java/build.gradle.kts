plugins {
    id("walkietalkie.java-conventions")
    application
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(project(":walkie-shared"))

    // Jackson 3 databind, used to (de)serialize the shared protocol records.
    implementation("tools.jackson.core:jackson-databind")

    // Concentus: pure-Java Opus codec (BSD-licensed) for the audio-relay transport.
    implementation("io.github.jaredmdobson:concentus:1.0.2")

    // picocli: declarative command-line parsing (Apache 2.0). Plain library — no Spring runtime.
    implementation("info.picocli:picocli:4.7.7")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.ashr123.walkietalkie.client.WalkieClientLauncher")
}
