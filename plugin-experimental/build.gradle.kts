import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    jacoco
    `maven-publish`
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
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.database")
    }

    compileOnly(project(":plugin-core"))

    // Runtime dependencies that plugin-core needs (not pulled transitively from compileOnly)
    implementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.google.zxing:core:${providers.gradleProperty("zxingVersion").get()}")
    implementation("com.google.zxing:javase:${providers.gradleProperty("zxingVersion").get()}")

    // Force annotations version to match the platform
    implementation("org.jetbrains:annotations:${providers.gradleProperty("annotationsVersion").get()}")

    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
    testImplementation("junit:junit:${providers.gradleProperty("junit4Version").get()}")
    testImplementation(project(":plugin-core"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${providers.gradleProperty("junitVersion").get()}")
}

configurations.all {
    resolutionStrategy.force("org.jetbrains:annotations:${providers.gradleProperty("annotationsVersion").get()}")
}

// Repackage plugin-core without its plugin.xml descriptor.
// This module has its own generated plugin.xml (superset of plugin-core's).
val repackagePluginCore by tasks.registering(Jar::class) {
    archiveBaseName.set("plugin-core-classes")
    dependsOn(project(":plugin-core").tasks.named("jar"))
    from(provider {
        zipTree(project(":plugin-core").tasks.named("jar").get().outputs.files.singleFile)
    }) {
        exclude("META-INF/plugin.xml")
    }
}

// Generate plugin.xml by merging plugin-core's descriptor with macro extensions
val generatePluginXml by tasks.registering {
    val corePluginXml = project(":plugin-core").file("src/main/resources/META-INF/plugin.xml")
    val macroExtras = file("src/main/resources/META-INF/macro-extensions.xml")
    val outputDir = layout.buildDirectory.dir("generated/plugin-xml/META-INF")

    inputs.file(corePluginXml)
    inputs.file(macroExtras)
    outputs.dir(outputDir)

    doLast {
        var xml = corePluginXml.readText()
        val extras = macroExtras.readText()

        // Insert macro extensions before closing </extensions>
        xml = xml.replace("  </extensions>", "$extras\n  </extensions>")

        // Append "(Experimental)" to the plugin name
        xml = xml.replace(
            "<name>IDE Agent for Copilot</name>",
            "<name>IDE Agent for Copilot (Experimental)</name>"
        )

        val outputFile = outputDir.get().file("plugin.xml").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(xml)
    }
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/plugin-xml"))
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generatePluginXml)
    // Exclude the template file — only the generated plugin.xml should be in the JAR
    exclude("META-INF/macro-extensions.xml")
}

tasks.named("patchPluginXml") {
    dependsOn(generatePluginXml)
}

// Include plugin-core classes in the plugin sandbox.
// IPP 2.x places the sandbox at <rootProject>/.intellijPlatform/sandbox/<module>/<IDE-version>/
// The doLast runs after prepareSandbox has created the sandbox structure, so the lib dir exists.
tasks.named("prepareSandbox") {
    dependsOn(repackagePluginCore)
    doLast {
        val coreJar = repackagePluginCore.get().outputs.files.singleFile
        val sandboxBase = project.rootProject.projectDir.resolve(".intellijPlatform/sandbox/${project.name}")
        sandboxBase.listFiles { f -> f.isDirectory }?.forEach { ideVersionDir ->
            val libDir = ideVersionDir.resolve("plugins/${project.name}/lib")
            if (libDir.exists()) {
                coreJar.copyTo(libDir.resolve("plugin-core.jar"), overwrite = true)
            }
        }
    }
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("ide-agent-for-copilot-experimental")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(repackagePluginCore)
    from(repackagePluginCore) {
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
        id = "com.github.catatafishen.ideagentforcopilot"
        name = "IDE Agent for Copilot (Experimental)"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        // Experimental variant allows INTERNAL_API_USAGES (macro tools use internal APIs)
        failureLevel.set(
            listOf(
                FailureLevel.INVALID_PLUGIN,
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
        finalizedBy("jacocoTestReport")
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Publish the experimental plugin ZIP to GitHub Packages alongside plugin-core.
// See plugin-core/build.gradle.kts for the rationale (Scorecard packaging detection,
// real package-registry distribution channel for users).
configure<PublishingExtension> {
    publications {
        create<MavenPublication>("pluginZip") {
            groupId = "com.github.catatafishen"
            artifactId = "ide-agent-for-copilot-experimental"
            version = project.version.toString()
            artifact(tasks.named("buildPlugin")) {
                extension = "zip"
            }
            pom {
                name.set("IDE Agent for Copilot (Experimental)")
                description.set("Experimental variant of the IntelliJ plugin integrating GitHub Copilot via ACP/MCP.")
                url.set("https://github.com/catatafishen/agentbridge")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/catatafishen/agentbridge/blob/master/LICENSE")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/catatafishen/agentbridge")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
