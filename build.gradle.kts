plugins {
    id("java")
    id("org.sonarqube") version "7.3.0.8198"
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.intellij.platform") version "2.14.0" apply false
    idea
}

idea {
    module {
        excludeDirs.addAll(
            listOf(
                file(".sandbox-config"),
                file(".intellijPlatform"),
            )
        )
    }
}

val baseVersion = "0.0.0"
val buildTimestamp = providers.exec {
    commandLine("date", "+%Y%m%d-%H%M")
}.standardOutput.asText.get().trim()
val ciVersion = providers.environmentVariable("PLUGIN_VERSION").orNull
val gitHash: String = try {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
} catch (_: Exception) {
    "unknown"
}

allprojects {
    group = "com.github.catatafishen.agentbridge"
    version = ciVersion
        ?: if (providers.gradleProperty("release").isPresent) baseVersion else "$baseVersion-dev-$buildTimestamp-$gitHash"

    repositories {
        mavenCentral()
    }
}

sonar {
    properties {
        property(
            "sonar.projectVersion",
            providers.environmentVariable("SONAR_PROJECT_VERSION")
                .orElse(providers.environmentVariable("PLUGIN_VERSION"))
                .orElse(providers.provider { project.version.toString() })
                .get()
        )
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            listOf(
                "mcp-server/build/reports/jacoco/test/jacocoTestReport.xml",
                "plugin-core/build/reports/jacoco/test/jacocoTestReport.xml",
                "plugin-experimental/build/reports/jacoco/test/jacocoTestReport.xml",
            ).joinToString(",")
        )
        property("sonar.javascript.lcov.reportPaths", "plugin-core/js-tests/coverage/lcov.info")
        // MCP tool implementations are intentionally formulaic: each Tool subclass declares
        // id(), displayName(), description(), kind(), category(), and isReadOnly() in nearly
        // identical shapes. CPD treats this required boilerplate as duplication, producing
        // false positives that drown out real findings. The structural similarity is enforced
        // by the ToolDefinition contract — refactoring it away would mean reflection or
        // generated code, both worse than the current explicit form.
        property(
            "sonar.cpd.exclusions",
            "plugin-core/src/main/java/com/github/catatafishen/agentbridge/psi/tools/**/*.java"
        )

        // S3776 (cognitive complexity, default threshold 15) on protocol/codec methods,
        // typescript:S3776 on chat-ui renderers, and typescript:S2004 on the DOM-update
        // pipeline. The files listed below are linearly branchy by nature: ACP/MCP JSON
        // deserializers, IDE-client config exporters, the embedded HTTP server's request
        // dispatcher, shell-startup parsers, and performance-critical rendering loops
        // whose nesting/branchiness reflects the underlying state machine, not accidental
        // complexity. Each is reviewed and accepted at the file level; new files still
        // get scanned.
        data class IgnoredIssue(val key: String, val ruleKey: String, val resourceKey: String)

        val ignoredIssues = listOf(
            // java:S3776 — protocol/codec methods
            "**/agent/claude/ClaudeCliClient.java",
            "**/acp/client/AcpClient.java",
            "**/acp/client/AcpFileSystemHandler.java",
            "**/acp/model/NewSessionResponseDeserializer.java",
            "**/session/exporters/KiroClientExporter.java",
            "**/session/exporters/OpenCodeClientExporter.java",
            "**/session/exporters/CopilotClientExporter.java",
            "**/services/ChatWebServer.java",
            "**/psi/review/AgentEditSession.java",
            "**/psi/review/AgentEditHighlighter.java",
            "**/psi/tools/project/GetProjectDependenciesTool.java",
            "**/psi/tools/database/ExecuteQueryTool.java",
            "**/psi/tools/testing/RunTestsTool.java",
            "**/psi/tools/debug/inspection/DebugReadConsoleTool.java",
            "**/psi/tools/file/WriteFileTool.java",
            "**/psi/tools/navigation/ListProjectFilesTool.java",
            "**/psi/tools/terminal/TerminalTool.java",
            "**/psi/PsiBridgeService.java",
            "**/psi/ToolUtils.java",
            "**/psi/review/ChangeNavigator.java",
            "**/services/ToolCallStatisticsBackfill.java"
        ).mapIndexed { i, path -> IgnoredIssue("s3776_$i", "java:S3776", path) } + listOf(
            IgnoredIssue("ts_s3776_render", "typescript:S3776", "**/chat-ui/src/renderMarkdown.ts"),
            IgnoredIssue("ts_s3776_batch", "typescript:S3776", "**/chat-ui/src/BatchRenderer.ts"),
            IgnoredIssue("ts_s2004_app", "typescript:S2004", "**/chat-ui/src/web-app.ts")
        ) + listOf(
            // java:S3077 (volatile non-primitive). All these fields hold either an immutable
            // value (String, Path) or a snapshot reference that is only ever wholesale
            // reassigned — never mutated through the reference. volatile is the correct and
            // minimal mechanism for safe publication; AtomicReference would add overhead
            // without changing semantics.
            "**/services/ChatWebServer.java",
            "**/memory/MemoryService.java",
            "**/memory/embedding/EmbeddingService.java",
            "**/acp/client/AcpClient.java",
            "**/acp/client/AcpTerminalHandler.java",
            "**/services/ActiveAgentManager.java",
            "**/session/SessionSwitchService.java",
            "**/settings/ShellEnvironment.java"
        ).mapIndexed { i, path -> IgnoredIssue("s3077_$i", "java:S3077", path) } + listOf(
            // java:S125 — both occurrences are descriptive comment blocks (close-signal
            // best-effort note, EDT-freeze rationale) that Sonar's heuristics misclassify
            // as commented-out code. They are not dead code.
            IgnoredIssue("s125_chatwebserver", "java:S125", "**/services/ChatWebServer.java"),
            IgnoredIssue("s125_runinspections", "java:S125", "**/psi/tools/quality/RunInspectionsTool.java")
        )

        property("sonar.issue.ignore.multicriteria", ignoredIssues.joinToString(",") { it.key })
        ignoredIssues.forEach {
            property("sonar.issue.ignore.multicriteria.${it.key}.ruleKey", it.ruleKey)
            property("sonar.issue.ignore.multicriteria.${it.key}.resourceKey", it.resourceKey)
        }
    }
}

project(":plugin-core") {
    sonar {
        properties {
            property("sonar.sources", "src/main/java,chat-ui/src")
            property("sonar.tests", "src/test/java,js-tests")
            property("sonar.test.inclusions", "src/test/java/**,js-tests/**/*.test.*")
        }
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
