import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.util.zip.ZipInputStream

plugins {
    id("java")
    kotlin("jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    jacoco
}

sourceSets {
    main {
        java {
            srcDirs("src/main/java")
        }
        kotlin {
            srcDirs("src/main/java")
        }
        resources {
            srcDir(layout.buildDirectory.dir("generated/resources/chat-ui"))
        }
    }
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

    // Kotlin stdlib for UI layer
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Force annotations version to match the platform (TYPE_USE support required for lambdas)
    implementation("org.jetbrains:annotations:${providers.gradleProperty("annotationsVersion").get()}")

    // JSON processing (Gson)
    implementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")

    // QR code generation (ZXing)
    implementation("com.google.zxing:core:${providers.gradleProperty("zxingVersion").get()}")
    implementation("com.google.zxing:javase:${providers.gradleProperty("zxingVersion").get()}")

    // SQLite JDBC (used by OpenCode session import)
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")

    // ONNX Runtime for embedding model inference (semantic memory)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.24.3")

    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
    testImplementation(
        "junit:junit:${
            providers.gradleProperty("junit4Version").get()
        }"
    )  // Required by IntelliJ test framework
    testImplementation("org.mockito:mockito-core:${providers.gradleProperty("mockitoVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${providers.gradleProperty("junitVersion").get()}")
}

// Ensure annotations 26.x is used everywhere (needed for TYPE_USE @NotNull on functional interfaces)
configurations.all {
    resolutionStrategy.force("org.jetbrains:annotations:${providers.gradleProperty("annotationsVersion").get()}")
}

// Copy MCP server JAR into plugin lib for bundling
tasks.named("prepareSandbox") {
    dependsOn(project(":mcp-server").tasks.named("jar"))
    doLast {
        val mcpJar = project(":mcp-server").tasks.named("jar").get().outputs.files.singleFile
        // Copy to the versioned sandbox directory where the IDE actually runs
        val ideDirs = File(
            layout.buildDirectory.asFile.get(),
            "idea-sandbox"
        ).listFiles { f -> f.isDirectory && f.name.startsWith("IU-") }
        ideDirs?.forEach { ideDir ->
            val sandboxLib = File(ideDir, "plugins/plugin-core/lib")
            sandboxLib.mkdirs()
            mcpJar.copyTo(File(sandboxLib, "mcp-server.jar"), overwrite = true)
        }

        // Restore persisted sandbox config (disabled plugins, settings, etc.)
        val persistentConfig = rootProject.file(".sandbox-config")
        if (persistentConfig.exists() && persistentConfig.isDirectory) {
            ideDirs?.forEach { ideDir ->
                val configDir = File(ideDir, "config")
                configDir.mkdirs()
                persistentConfig.walkTopDown().forEach { src ->
                    if (src.isFile) {
                        val rel = src.relativeTo(persistentConfig)
                        val dest = File(configDir, rel.path)
                        dest.parentFile.mkdirs()
                        src.copyTo(dest, overwrite = true)
                    }
                }
            }
            logger.lifecycle("Restored sandbox config from .sandbox-config/")
        }

        // Restore marketplace-installed plugins (zips/jars in system/plugins/)
        val persistentPlugins = rootProject.file(".sandbox-plugins")
        if (persistentPlugins.exists() && persistentPlugins.isDirectory) {
            ideDirs?.forEach { ideDir ->
                // Extract plugin zips into the plugins/ directory (alongside plugin-core)
                // IntelliJ loads plugins from plugins/, not system/plugins/
                val pluginsDir = File(ideDir, "plugins")
                pluginsDir.mkdirs()
                persistentPlugins.listFiles()?.filter { it.extension == "zip" }?.forEach { zipFile ->
                    val pluginName = zipFile.nameWithoutExtension
                    val extractedDir = File(pluginsDir, pluginName)
                    if (!extractedDir.exists()) {
                        logger.lifecycle("Extracting marketplace plugin: ${zipFile.name}")
                        ZipInputStream(zipFile.inputStream()).use { zis: ZipInputStream ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val dest = File(pluginsDir, entry.name)
                                if (entry.isDirectory) dest.mkdirs()
                                else {
                                    dest.parentFile.mkdirs(); dest.outputStream().use { zis.copyTo(it) }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
                // Copy standalone jars
                persistentPlugins.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                    val dest = File(pluginsDir, jarFile.name)
                    if (!dest.exists()) {
                        jarFile.copyTo(dest)
                    }
                }
                // Also keep the zips in system/plugins/ for IntelliJ's plugin manager UI
                val systemPlugins = File(ideDir, "system/plugins")
                systemPlugins.mkdirs()
                persistentPlugins.listFiles()?.filter { it.extension == "zip" || it.extension == "jar" }
                    ?.forEach { src ->
                        val dest = File(systemPlugins, src.name)
                        if (!dest.exists()) {
                            src.copyTo(dest)
                        }
                    }
            }
            logger.lifecycle("Restored marketplace plugins from .sandbox-plugins/")
        }
    }
}

// Resolve nvm Node so Gradle's exec tasks use the right version.
// Prefers the nvm default alias (~/.nvm/alias/default) so the same
// Node version used in the terminal is used here. Falls back to the
// highest installed version, then to system PATH.
val nvmNodeBin: String? by lazy {
    val home = System.getProperty("user.home")
    val nvmVersionsDir = File(home, ".nvm/versions/node")
    if (!nvmVersionsDir.isDirectory) return@lazy null

    // Try the nvm default alias first
    val defaultAlias = File(home, ".nvm/alias/default")
    if (defaultAlias.isFile) {
        val defaultVersion = defaultAlias.readText().trim()
        val defaultBin = File(nvmVersionsDir, "$defaultVersion/bin")
        if (File(defaultBin, "node").exists()) return@lazy defaultBin.absolutePath
    }

    // Fall back to highest installed version
    nvmVersionsDir.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.map { File(it, "bin") }
        ?.firstOrNull { File(it, "node").exists() }
        ?.absolutePath
}

// Full path to npm from nvm, or bare "npm" if nvm is not available.
// Using a full path ensures Gradle doesn't resolve the executable from the
// system PATH before our environment override takes effect.
val npmCmd: String by lazy { nvmNodeBin?.let { "$it/npm" } ?: "npm" }

fun runProcess(workDir: File, vararg cmd: String) {
    val pb = ProcessBuilder(*cmd).directory(workDir).inheritIO()
    nvmNodeBin?.let { bin -> pb.environment()["PATH"] = "$bin:${System.getenv("PATH")}" }
    val exit = pb.start().waitFor()
    if (exit != 0) error("Command failed (exit $exit): ${cmd.joinToString(" ")}")
}

// Build chat-ui TypeScript → bundled JS + copy static assets
val buildChatUi by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/chat-ui/chat")
    inputs.dir("chat-ui/src")
    outputs.dir(outputDir)

    doLast {
        // Ensure dist exists
        file("chat-ui/dist").mkdirs()

        runProcess(file("chat-ui"), npmCmd, "run", "build")

        // Sync to generated resources directory
        copy {
            from("chat-ui/dist")              // chat-components.js, web-app.js, sw.js
            from("chat-ui/src/chat.css")      // chat component styles
            from("chat-ui/src/web-app.css")   // web app layout styles
            from("chat-ui/src/manifest.json") // PWA manifest
            into(outputDir)
        }
    }
}

// Run chat-ui JavaScript tests (Vitest + happy-dom)
val jsTest by tasks.registering {
    group = "verification"
    description = "Run chat-ui JavaScript unit tests (Vitest)"
    inputs.dir("chat-ui/src")
    inputs.dir("js-tests")

    doLast {
        runProcess(file("js-tests"), npmCmd, "test")
    }
}

tasks.named("check") {
    dependsOn(jsTest)
}

tasks.named("processResources") {
    dependsOn(buildChatUi)
}

// Also include in the distribution ZIP
tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("agentbridge")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(project(":mcp-server").tasks.named("jar"))
    from(project(":mcp-server").tasks.named("jar")) {
        into("lib")
        rename { "mcp-server.jar" }
    }
}

// Deploy built plugin to the main (outer) IDE installation
tasks.register("deployToMainIde") {
    group = "deployment"
    description = "Builds the plugin ZIP and installs it into the running IDE's plugin directory."
    dependsOn("buildPlugin")
    doLast {
        val distDir = layout.buildDirectory.dir("distributions").get().asFile
        val latestZip = distDir.listFiles()
            ?.filter { it.extension == "zip" }
            ?.maxByOrNull { it.lastModified() }
            ?: error("No ZIP found in $distDir — build failed?")

        logger.lifecycle("📦 ZIP: ${latestZip.name}")

        val installDir = detectPluginInstallDir()
        logger.lifecycle("📂 Target: $installDir")
        if (installDir.exists()) installDir.deleteRecursively()
        ZipInputStream(latestZip.inputStream()).use { zis: ZipInputStream ->
            var entry = zis.nextEntry
            while (entry != null) {
                val dest = File(installDir.parentFile, entry.name)
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile.mkdirs(); dest.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        logger.lifecycle("✅ Plugin deployed to $installDir")
        logger.lifecycle("⚠️  Restart IntelliJ to apply the new version.")
    }
}

/** Finds the plugin install directory in the running IDE's plugin folder. */
fun detectPluginInstallDir(): File {
    val home = System.getProperty("user.home")
    val pluginDirNames = listOf("agentbridge", "ide-agent-for-copilot", "plugin-core")

    // 1. Toolbox per-IDE plugin dir: ~/.local/share/JetBrains/IntelliJIdea*/<plugin>
    val dataBase = File(home, ".local/share/JetBrains")
    if (dataBase.exists()) {
        val found = dataBase.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("IntelliJIdea") }
            ?.sortedByDescending { it.name }
            ?.firstNotNullOfOrNull { ideDir ->
                pluginDirNames.map { ideDir.resolve(it) }.firstOrNull { it.exists() }
            }
        if (found != null) return found
    }

    // 2. Toolbox app-level plugins: ~/.local/share/JetBrains/Toolbox/apps/.../plugins/<plugin>
    val toolboxBase = File(home, ".local/share/JetBrains/Toolbox/apps")
    if (toolboxBase.exists()) {
        val found = toolboxBase.walkTopDown().maxDepth(3)
            .filter { it.isDirectory && it.name == "plugins" }
            .firstNotNullOfOrNull { pluginsDir ->
                pluginDirNames.map { File(pluginsDir, it) }.firstOrNull { it.exists() }
            }
        if (found != null) return found
    }

    // 3. Standard config layout: ~/.config/JetBrains/IntelliJIdea*/plugins/<plugin>
    val configBase = File(home, ".config/JetBrains")
    if (configBase.exists()) {
        val found = configBase.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("IntelliJIdea") }
            ?.sortedByDescending { it.name }
            ?.firstNotNullOfOrNull { ideDir ->
                pluginDirNames.map { ideDir.resolve("plugins").resolve(it) }.firstOrNull { it.exists() }
            }
        if (found != null) return found
    }
    error("Could not find plugin install directory. Install the plugin first via IDE.")
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/buildinfo"))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.catatafishen.agentbridge"
        name = "AgentBridge"
        version = project.version.toString()
        // Description is maintained in plugin.xml as rich HTML for the marketplace.

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.environmentVariable("INTELLIJ_PUBLISH_TOKEN")
    }

    pluginVerification {
        // Don't fail on COMPATIBILITY_PROBLEMS or MISSING_DEPENDENCIES: our Java support
        // classes (psi.java package) reference Java PSI and Compiler APIs that are absent in
        // non-Java IDEs (PY, WS, GO). These classes are guarded at runtime by
        // isPluginInstalled("com.intellij.modules.java") + NoClassDefFoundError catch.
        // TODO: Move psi.java classes to a separate Gradle module (separate JAR) so the
        //       verifier only checks them against IDEs with Java support.
        //
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
            // Verify against non-Java JetBrains IDEs
            create(IntelliJPlatformType.PyCharmProfessional, "2025.3")
            create(IntelliJPlatformType.WebStorm, "2025.3")
            create(IntelliJPlatformType.GoLand, "2025.3")
            // Note: Android Studio verification via Gradle plugin is broken
            // (URL resolution bug in IntelliJPlatformGradlePlugin). Android Studio
            // Panda 2 (2025.3.2) uses platform build 253.30387.90 — same base as
            // IntelliJ IDEA 2025.3 which we verify above via recommended().
        }
    }
}

tasks {
    // Generate build info properties file
    val generateBuildInfo by registering {
        val outputDir = layout.buildDirectory.dir("generated/buildinfo")
        val pluginVersion = project.version.toString()
        outputs.dir(outputDir)
        outputs.upToDateWhen { false }
        doLast {
            val propsFile = outputDir.get().file("build-info.properties").asFile
            propsFile.parentFile.mkdirs()
            val gitHash = try {
                providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                    .standardOutput.asText.get().trim()
            } catch (_: Exception) {
                "unknown"
            }
            val timestamp = System.currentTimeMillis().toString()
            propsFile.writeText("build.timestamp=$timestamp\nbuild.git.hash=$gitHash\nbuild.version=$pluginVersion\n")
        }
    }

    named("processResources") {
        dependsOn(generateBuildInfo)
        dependsOn(buildChatUi)
    }

    withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }

    test {
        useJUnitPlatform {
            excludeTags("integration")
        }
        // IntelliJ Platform loads classes via a custom classloader that doesn't
        // provide class file locations. Without this flag, JaCoCo reports 0% coverage.
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
        finalizedBy(named("jacocoTestReport"))
    }

    named<JacocoReport>("jacocoTestReport") {
        dependsOn(named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        // IntelliJ Platform's instrumentCode task applies @NotNull bytecode checks,
        // changing class hashes. Tests run against these instrumented classes (in
        // build/instrumented/instrumentCode/), so the report must use them too —
        // otherwise JaCoCo reports "execution data does not match" for every class.
        //
        // Exclude Swing/JCEF UI classes that require the full IDE runtime — these
        // can only be tested via integration tests. Pure-logic classes in ui/ (e.g.
        // ConversationSerializer, MessageFormatter, UsageStatisticsData) are kept.
        //
        // NOTE: These are TRULY UNTESTABLE in unit tests (require IDE runtime).
        // Missing tests for testable logic should NOT be hidden via exclusions.
        val uiExcludes = listOf(
            "**/ui/*Panel*",                   // Swing panels (AcpConnect, ChatConsole, Permissions, etc.)
            "**/ui/*Banner*",                  // Swing banners (AuthSetup, GitWarning, Status)
            "**/ui/ChatToolWindow*",           // ChatToolWindowFactory, ChatToolWindowContent
            "**/ui/ChatConsolePanel*",         // JCEF chat panel
            "**/ui/ChatPanelApi*",             // Chat panel API interface (Swing-dependent)
            "**/ui/ChatTheme*",               // JBColor theme constants
            "**/ui/ThemeColor*",              // JBColor wrappers
            "**/ui/ToolKindColors*",          // JBColor tool-kind palette
            "**/ui/ToolCallPopup*",           // Swing popup
            "**/ui/AgentIconProvider*",       // Icon loading (needs IDE runtime)
            "**/ui/FileNavigator*",           // VFS navigation (needs Project)
            "**/ui/PromptOrchestrator*",      // Prompt orchestration (needs Project + services)
            "**/ui/PromptContextManager*",    // Context management (needs Project)
            "**/ui/ConversationReplayer*",    // Replay (needs Project)
            "**/ui/PromptShortcut*",          // AnAction + Swing
            "**/ui/ContextChipRenderer*",     // Swing renderer
            "**/ui/ContextItemData*",         // Data class with Swing dependencies
            "**/ui/AuthLoginService*",        // OAuth flow (needs IDE runtime)
            "**/ui/AuthTerminalHelper*",      // Terminal auth (needs IDE runtime)
            "**/ui/BillingManager*",          // Billing API (needs HTTP client + IDE)
            "**/ui/CopilotBillingClient*",    // Billing HTTP client
            "**/ui/PasteToScratchHandler*",   // Editor paste handler (needs IDE runtime)
            "**/ui/renderers/**",             // All Swing renderers
            "**/ui/statistics/*Chart*",       // Swing chart component
            "**/ui/statistics/*Panel*",       // Swing statistics panel
            "**/ui/statistics/*Dialog*",      // Swing statistics dialog
        )
        val otherExcludes = listOf(
            "**/actions/**",                   // AnAction subclasses (need ActionManager)
            "**/settings/*Configurable*",      // Settings UI configurables (need IDE runtime)
            "**/settings/QrCodePanel*",        // QR code panel (UI, needs IDE runtime)
            "**/settings/ThemeColorComboBox*", // Theme color combo (UI, needs IDE runtime)
            "**/memory/*Configurable*",        // MemorySettingsConfigurable (Swing, needs IDE runtime)
            "**/custommcp/*Configurable*",     // CustomMcpConfigurable (Swing, needs IDE runtime)
            // ── Raw I/O infrastructure (socket/HTTP/subprocess) ───────────────────────
            // These are excluded because they're thin wrappers around OS/network I/O.
            // Protocol logic (parsing, state machines) should be extracted and tested.
            "**/services/McpHttpServer*",      // Raw HTTP server (Javalin wrapper)
            "**/services/McpSseTransport*",    // SSE transport (raw I/O)
            "**/acp/client/AgentProcessRegistry*", // OS-process lifecycle (ProcessBuilder wrapper)
            "**/bridge/ChatWebServer*",        // Embedded web server (Javalin wrapper)
            "**/psi/RunConfigurationService*", // IDE run framework wrapper
            // ── External-process integrations ─────────────────────────────────────────
            "**/psi/SonarQubeIntegration*",    // Runs external SonarQube process
            "**/psi/QodanaAnalyzer*",          // Runs external Qodana process
            "**/psi/SonarRuleDescriptions*",   // Fetches Sonar rule data via HTTP
        )
        // TODO: The following are DIFFICULT to test but NOT impossible. Exclusions hide
        // missing test coverage. Extract testable logic or add integration tests:
        //   - AcpClient* (protocol clients) — extract protocol logic, mock I/O
        //   - *Client (agent clients) — extract request/response logic, mock HTTP
        //   - SessionSwitchService — extract state machine, mock dependencies
        val allExcludes = uiExcludes + otherExcludes

        val instrumentedClasses = fileTree("${layout.buildDirectory.get()}/instrumented/instrumentCode") {
            exclude(allExcludes)
        }
        // Fallback to raw classes if instrumentCode hasn't run (e.g., standalone report)
        val rawClasses = fileTree("${layout.buildDirectory.get()}/classes/java/main") {
            exclude(allExcludes)
        }
        classDirectories.setFrom(
            if (file("${layout.buildDirectory.get()}/instrumented/instrumentCode").exists())
                instrumentedClasses
            else
                rawClasses
        )
        sourceDirectories.setFrom(files("src/main/java"))
    }

    runIde {
        maxHeapSize = "2g"
        // Enable auto-reload of plugin when changes are built
        autoReload = true

        // Auto-open this project in the sandbox IDE (skips welcome screen)
        args = listOf(project.rootDir.absolutePath)

        // System properties to skip setup and preserve state
        jvmArgs = listOf(
            "-Didea.trust.all.projects=true",           // Skip trust dialog
            "-Didea.is.internal=true",                   // Enable internal mode
            "-Deap.require.license=false",               // Skip license checks
            "-Didea.suppressed.plugins.id=",             // Don't suppress any plugins
            "-Didea.plugin.in.sandbox.mode=true"         // Sandbox mode
        )
    }
}
