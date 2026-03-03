plugins {
    id("java")
    application
}

dependencies {
    implementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
}

application {
    mainClass.set("com.github.copilot.mcp.McpServer")
}

// Single source of truth: copy startup instructions from plugin-core at build time
tasks.processResources {
    from(project(":plugin-core").file("src/main/resources/default-startup-instructions.md"))
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.github.copilot.mcp.McpServer"
    }
    // Fat JAR — include all dependencies
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
