package com.github.catatafishen.agentbridge.integration

import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Shared harness for the IDE × MCP-tool integration matrix.
 *
 * Each cell launches a REAL product (selected by [IdeUnderTest.current]) via the IntelliJ
 * Platform Starter framework, opens the matching committed fixture so the real language
 * backend starts, installs the plugin, waits for the auto-started MCP server and for indexing,
 * then hands an [McpClient] to the caller's assertion lambda — the same HTTP boundary an agent
 * hits. One IDE launch per (tool, IDE) keeps cells isolated: a launch failure for one IDE marks
 * only that column's cells red, not the whole run.
 */
object IdeBench {

    private val diConfigured = AtomicBoolean(false)

    /** Static MCP port pre-seeded by every fixture's `.idea/mcpServer.xml`. */
    private const val MCP_PORT = 8642

    /**
     * Installs the Starter DI override that turns IDE-reported test failures into JUnit
     * failures. Idempotent — repeated `extend(di)` calls would stack overrides, so we guard
     * with a flag and configure exactly once per test JVM.
     */
    private fun ensureDi() {
        if (diConfigured.compareAndSet(false, true)) {
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
    }

    /**
     * Launches [IdeUnderTest.current], drives it through [block], and dumps IDE logs on failure.
     *
     * @param contextName short Starter context name (also the on-disk test-home directory)
     * @param block assertion body; receives the active [IdeUnderTest] and a connected [McpClient]
     */
    fun run(contextName: String, block: (IdeUnderTest, McpClient) -> Unit) {
        ensureDi()
        val ide = IdeUnderTest.current()

        val pluginPath = System.getProperty("agentbridge.plugin.zip")
            ?: error("agentbridge.plugin.zip system property is required (build :plugin-core:buildPlugin first)")
        val fixturesDir = System.getProperty("agentbridge.fixtures.dir")
            ?: error("agentbridge.fixtures.dir system property is required")

        val fixture = Path(fixturesDir).resolve(ide.fixture)

        val context = Starter.newTestContainer().newContext(
            contextName,
            TestCase(ide.product, LocalProjectInfo(fixture)).withVersion(ide.version),
        )

        val pluginFile = Path(pluginPath)
        check(pluginFile.toFile().exists()) {
            "Plugin ZIP not found at $pluginFile — ensure :plugin-core:buildPlugin ran first"
        }
        println("[integration] ${ide.key}: installing plugin from $pluginFile into fixture ${ide.fixture}")
        PluginConfigurator(context).installPluginFromPath(pluginFile)

        var passed = false
        try {
            context.runIdeWithDriver().useDriverAndCloseIde {
                // Everything is driven over the plugin's MCP server — the same boundary an agent
                // (and the bug reporter) uses — so we deliberately do NOT depend on the Driver UI
                // DSL. The lambda body just keeps the IDE alive while we issue HTTP calls.
                val mcp = McpClient(port = MCP_PORT)
                val version = mcp.awaitHealthy(timeout = 2.minutes)
                println("[integration] ${ide.key}: MCP healthy — plugin version $version")

                // Gate on indexing + backend warm-up via the plugin's own tool: more reliable than
                // guessing the Driver's wait DSL, and exercises the MCP path end-to-end.
                val indexing = mcp.callTool("get_indexing_status", mapOf("wait" to true, "timeout" to 300))
                println("[integration] ${ide.key}: get_indexing_status → $indexing")

                block(ide, mcp)
                passed = true
            }
        } finally {
            if (!passed) IdeLogDump.dump(context.paths.testHome)
        }
    }
}

/** Best-effort IDE-log dump for a failed integration cell; aids CI diagnosis. */
internal object IdeLogDump {

    private val PRIORITY = listOf("idea.log", "backend.")
    private val KEYWORDS = setOf(
        "agentbridge", "mcpserver", "psibridgestartup", "autostart", "psibridgeservice",
        "conversationdatabase", "error", "fatal", "exception", "caused by",
    )

    fun dump(testHome: Path) {
        println("[integration] === Searching for log files under: $testHome ===")
        val root = testHome.toFile()
        if (!root.exists()) {
            println("[integration] testHome does not exist")
            return
        }
        println("[integration] testHome contents: ${root.listFiles()?.joinToString { it.name }}")

        val allLogs = root.walkTopDown().filter { it.isFile && it.name.endsWith(".log") }.toList()
        println("[integration] Found ${allLogs.size} .log file(s): ${allLogs.map { it.relativeTo(root).path }}")

        val sorted = allLogs.sortedWith(
            compareBy { f -> PRIORITY.indexOfFirst { f.name.startsWith(it) }.let { if (it == -1) 99 else it } },
        )
        for (logFile in sorted.take(3)) {
            val lines = logFile.readLines()
            println("[integration] === $logFile (${lines.size} lines) ===")
            val relevant = lines.filter { line -> KEYWORDS.any { line.lowercase().contains(it) } }
            println("[integration] --- Relevant lines (${relevant.size}) ---")
            relevant.forEach { println("[integration] $it") }
            println("[integration] --- Last 30 lines ---")
            lines.takeLast(30).forEach { println("[integration] $it") }
        }
    }
}
