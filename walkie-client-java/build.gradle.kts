plugins {
    id("walkietalkie.java-conventions")
    application
}

dependencies {
    // Dedicated BOMs for version management — no Spring Boot BOM, because this is a plain console
    // application, not a Spring Boot one. A BOM contributes only version constraints, no jars.
    implementation(platform("tools.jackson:jackson-bom:3.2.0"))
    implementation(project(":walkie-shared"))

    // Jackson 3 databind, used to (de)serialize the shared protocol records.
    implementation("tools.jackson.core:jackson-databind")

    // Concentus: pure-Java Opus codec (BSD-licensed) for the audio-relay transport.
    implementation("io.github.jaredmdobson:concentus:1.0.2")

    // picocli: declarative command-line parsing (Apache 2.0). Plain library — no Spring runtime.
    implementation("info.picocli:picocli:4.7.7")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.ashr123.walkietalkie.client.WalkieClientLauncher")
}

val fatJar = tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds an executable fat jar for the Java desktop client."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    from(sourceSets.main.get().output)
    // The fat jar bundles the runtime dependencies, including this project's own :walkie-shared jar. The `from({…})`
    // closure below reads those jars lazily at execution time, but Gradle can't infer the producing tasks from an
    // opaque closure — so declare the dependency explicitly (Gradle's own validation suggests this) to guarantee
    // :walkie-shared:jar (and any other jar-producing dependency) is built first, rather than relying on task order.
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map(::zipTree)
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.named("assemble") {
    dependsOn(fatJar)
}

// Gradle's `run` task does not attach the terminal's stdin to the forked JVM by default, so the
// interactive console would read EOF immediately and quit ("Goodbye."). Forward stdin so the
// `t` / `m` / `q` prompt commands work when launched via `:walkie-client-java:run`.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
