package com.github.catatafishen.agentbridge.integration

import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

/**
 * First cell of the IDE × MCP-tool integration matrix: CLion × {@code get_file_outline}.
 *
 * <p>This is the spike that validates the whole out-of-process architecture. It launches a
 * REAL CLion (so the Nova/Radler C++ backend starts), opens the committed
 * {@code fixtures/cpp-cmake} project, lets the plugin auto-start its MCP server (configured
 * via the fixture's {@code .idea/mcpServer.xml}), then calls {@code get_file_outline} over
 * HTTP and asserts the C++ symbols come back.</p>
 *
 * <p>Unlike the headless {@code :ide-compat-tests}, a pass here means the bug is actually fixed
 * against the real backend — the same path the reporter hits.</p>
 *
 * <p>{@code get_file_outline} is bug #1 in issue #794 (already fixed via PR #837), so a green
 * result here primarily validates the harness. Once proven, additional cells are added by
 * (a) new fixtures per language/IDE and (b) new tool-call assertions.</p>
 */
class GetFileOutlineClionIntegrationTest {

    init {
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?,
                    ) {
                        fail { "$testName failed: $message\n$details" }
                    }
                }
            }
        }
    }

    @Test
    fun `get_file_outline returns C++ symbols from CLion Nova`() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?: error("path.to.build.plugin system property is required (build :plugin-core:buildPlugin first)")
        val fixturesDir = System.getProperty("agentbridge.fixtures.dir")
            ?: error("agentbridge.fixtures.dir system property is required")
        val clionVersion = System.getProperty("agentbridge.clion.version", "2026.1")

        val fixture = Path(fixturesDir).resolve("cpp-cmake")

        // Register a setup hook that fires during TestContextInitializedEvent inside newContext(),
        // after the plugins dir is initialized and ide-integration-tests is installed. Using
        // PluginConfigurator ensures JBZipFile-based extraction with correct Unix permissions.
        //
        // Note: trailing lambda syntax does not work with vararg params in Kotlin — the hook
        // must be passed inside parentheses so Kotlin resolves 'this' as IDETestContext.
        val context = Starter.newTestContainer({
            PluginConfigurator(this).installPluginFromPath(Path(pluginPath))
            println(
                "[integration] Plugins dir after install: ${
                    paths.pluginsDir.toFile().listFiles()?.map { it.name }
                }"
            )
        }).newContext(
            "clionGetFileOutline",
            TestCase(IdeProductProvider.CL, LocalProjectInfo(fixture)).withVersion(clionVersion),
        )

        var testPassed = false
        try {
            context.runIdeWithDriver().useDriverAndCloseIde {
                // We drive everything over the plugin's MCP server — the same boundary an agent uses —
                // so we deliberately do NOT depend on the Starter Driver's UI DSL here. The lambda body
                // simply keeps the IDE alive while we issue HTTP calls.
                // The plugin auto-started the MCP server on the fixed port (fixture .idea/mcpServer.xml).
                val mcp = McpClient(port = 8642)
                val version = mcp.awaitHealthy(timeout = 2.minutes)
                println("[integration] MCP server healthy — plugin version $version")

                // Gate on indexing + backend warm-up using the plugin's own tool. More reliable than
                // guessing the Driver's wait DSL, and exercises the MCP path end-to-end.
                val indexing = mcp.callTool("get_indexing_status", mapOf("wait" to true, "timeout" to 300))
                println("[integration] get_indexing_status → $indexing")

                val outline = mcp.callTool("get_file_outline", mapOf("path" to "classdef.h"))
                println("[integration] get_file_outline(classdef.h) →\n$outline")

                assertTrue(
                    outline.contains("Widget"),
                    "Expected class 'Widget' in C++ outline, got:\n$outline",
                )
                assertTrue(
                    outline.contains("Point"),
                    "Expected struct 'Point' in C++ outline, got:\n$outline",
                )
                testPassed = true
            }
        } finally {
            if (!testPassed) {
                dumpIdeLog(context.paths.testHome)
            }
        }
    }

    private fun dumpIdeLog(testHome: java.nio.file.Path) {
        println("[integration] === Searching for log files under: $testHome ===")
        val root = testHome.toFile()
        if (!root.exists()) {
            println("[integration] testHome does not exist")
            return
        }
        println("[integration] testHome contents: ${root.listFiles()?.joinToString { it.name }}")

        val allLogs = root.walkTopDown().filter { it.isFile && it.name.endsWith(".log") }.toList()
        println("[integration] Found ${allLogs.size} .log file(s): ${allLogs.map { it.relativeTo(root).path }}")

        // Prioritize the files most likely to contain plugin startup info
        val priority = listOf("idea.log", "backend.")
        val sorted = allLogs.sortedWith(
            compareBy { f ->
                priority.indexOfFirst { f.name.startsWith(it) }.let { if (it == -1) 99 else it }
            },
        )

        val keywords = setOf(
            "agentbridge", "mcpserver", "psibridgestartup", "autostart", "psibridgeservice",
            "conversationdatabase", "error", "fatal", "exception", "caused by",
        )
        for (logFile in sorted.take(3)) {
            val lines = logFile.readLines()
            println("[integration] === $logFile (${lines.size} lines) ===")
            val relevant = lines.filter { line -> keywords.any { line.lowercase().contains(it) } }
            println("[integration] --- Relevant lines (${relevant.size}) ---")
            relevant.forEach { println("[integration] $it") }
            println("[integration] --- Last 30 lines ---")
            lines.takeLast(30).forEach { println("[integration] $it") }
        }
    }
}
