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
import java.nio.file.Path
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
                        linkToLogs: String?
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

        Starter.newContext(
            "clionGetFileOutline",
            TestCase(IdeProductProvider.CL, LocalProjectInfo(fixture)).withVersion(clionVersion)
        ).apply {
            PluginConfigurator(this).installPluginFromPath(Path(pluginPath))
        }.runIdeWithDriver().useDriverAndCloseIde {
            // We drive everything over the plugin's MCP server — the same boundary an agent uses —
            // so we deliberately do NOT depend on the Starter Driver's UI DSL here. The lambda body
            // simply keeps the IDE alive while we issue HTTP calls.
            // The plugin auto-started the MCP server on the fixed port (fixture .idea/mcpServer.xml).
            val mcp = McpClient(port = 8642)
            val version = mcp.awaitHealthy(timeout = 3.minutes)
            println("[integration] MCP server healthy — plugin version $version")

            // Gate on indexing + backend warm-up using the plugin's own tool. More reliable than
            // guessing the Driver's wait DSL, and exercises the MCP path end-to-end.
            val indexing = mcp.callTool("get_indexing_status", mapOf("wait" to true, "timeout" to 300))
            println("[integration] get_indexing_status → $indexing")

            val outline = mcp.callTool("get_file_outline", mapOf("path" to "classdef.h"))
            println("[integration] get_file_outline(classdef.h) →\n$outline")

            assertTrue(
                outline.contains("Widget"),
                "Expected class 'Widget' in C++ outline, got:\n$outline"
            )
            assertTrue(
                outline.contains("Point"),
                "Expected struct 'Point' in C++ outline, got:\n$outline"
            )
        }
    }
}
