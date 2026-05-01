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

        // S3776 (cognitive complexity, default threshold 15) on protocol/codec methods.
        // The files listed below are linearly branchy by nature: ACP/MCP JSON
        // deserializers, IDE-client config exporters, the embedded HTTP server's request
        // dispatcher, and shell-startup parsers. Splitting them into helpers fragments a
        // single readable state-machine without reducing real complexity. Each is reviewed
        // and accepted at the file level; new files still get scanned.
        val s3776IgnoredFiles = listOf(
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
        )
        val s3776Keys = s3776IgnoredFiles.indices.map { "s3776_$it" }
        property("sonar.issue.ignore.multicriteria", s3776Keys.joinToString(","))
        s3776IgnoredFiles.forEachIndexed { i, path ->
            property("sonar.issue.ignore.multicriteria.s3776_$i.ruleKey", "java:S3776")
            property("sonar.issue.ignore.multicriteria.s3776_$i.resourceKey", path)
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
