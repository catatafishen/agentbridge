import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("intellijPlatform.localPath").orNull
        if (localPath != null) {
            local(localPath)
        } else {
            intellijIdeaUltimate("2025.3")
        }
        instrumentationTools()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // Compile against plugin-core to reuse PsiBridgeService, ToolRegistry, etc.
    compileOnly(project(":plugin-core"))

    // JSON processing (Gson)
    implementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    // Force annotations version to match the platform
    implementation("org.jetbrains:annotations:26.0.2")

    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
    testImplementation("junit:junit:${providers.gradleProperty("junit4Version").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${providers.gradleProperty("junitVersion").get()}")
}

configurations.all {
    resolutionStrategy.force("org.jetbrains:annotations:26.0.2")
}

// Include plugin-core classes in the standalone plugin
tasks.named("prepareSandbox") {
    dependsOn(project(":plugin-core").tasks.named("jar"))
    doLast {
        val coreJar = project(":plugin-core").tasks.named("jar").get().outputs.files.singleFile
        val ideDirs = File(
            layout.buildDirectory.asFile.get(),
            "idea-sandbox"
        ).listFiles { f -> f.isDirectory && f.name.startsWith("IU-") }
        ideDirs?.forEach { ideDir ->
            val sandboxLib = File(ideDir, "plugins/standalone-mcp/lib")
            sandboxLib.mkdirs()
            coreJar.copyTo(File(sandboxLib, "plugin-core.jar"), overwrite = true)
        }
    }
}

tasks.named<Zip>("buildPlugin") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(project(":plugin-core").tasks.named("jar"))
    from(project(":plugin-core").tasks.named("jar")) {
        into("lib")
        rename { "plugin-core.jar" }
    }
    // Also include mcp-server.jar for stdio-based agents
    dependsOn(project(":mcp-server").tasks.named("jar"))
    from(project(":mcp-server").tasks.named("jar")) {
        into("lib")
        rename { "mcp-server.jar" }
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.catatafishen.idemcpserver"
        name = "IDE MCP Server"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        failureLevel.set(
            listOf(
                FailureLevel.INVALID_PLUGIN,
                FailureLevel.INTERNAL_API_USAGES,
                FailureLevel.OVERRIDE_ONLY_API_USAGES,
                FailureLevel.NON_EXTENDABLE_API_USAGES,
                FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            )
        )
        ides {
            recommended()
        }
    }
}

tasks {
    withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }

    test {
        useJUnitPlatform()
    }
}
