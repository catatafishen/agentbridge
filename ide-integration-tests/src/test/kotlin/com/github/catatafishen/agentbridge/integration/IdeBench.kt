package com.github.catatafishen.agentbridge.integration

import com.github.catatafishen.agentbridge.integration.LoggedIdeErrors.OUTPUT_FILE_PROPERTY
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path

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
     * Installs the Starter DI override that intercepts IDE-reported problems. Idempotent —
     * repeated `extend(di)` calls would stack overrides, so we guard with a flag and configure
     * exactly once per test JVM.
     *
     * The Starter framework calls [CIServer.reportTestFailure] for problems it scrapes from the
     * launched IDE's log (logged errors, freezes) — NOT for our assertions. A matrix cell's real
     * contract is the tool's MCP response, asserted in each test body; a logged IDE error is a
     * non-gating diagnostic. So we hand it to [LoggedIdeErrors] (rendered as a section beneath the
     * matrix) instead of failing the cell. Genuine tool failures still fail the cell: a non-healthy
     * MCP server, a boot failure, or an `Error`-prefixed tool response all throw from the test body.
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
                            LoggedIdeErrors.record(testName, message, details)
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

        // Rider needs extra configuration the other IDEs don't, applied before launch. Without it
        // the bench times out: a modal trust dialog blocks the EDT for the whole run, and the MCP
        // server never auto-starts. This block (see the bench README for the full rationale):
        //   1. migrate.config — clears the Starter's intellij.first.ide.session=true so Rider
        //      skips the first-run onboarding wizard that pops the trust dialog.
        //   2. -Didea.trust.all.projects=true — the only reliable trust-dialog bypass under xvfb
        //      (a real X display, so the headless bypass never fires); see the detailed comment below.
        //   3. wizard-suppression option files — belt-and-suspenders against the startup wizard.
        //   4. mcpServer.xml at Rider's project-config path — makes the plugin auto-start the MCP
        //      server (Rider reads project config from a different location than IU/CL; see below).
        if (ide.key == "RD") {
            val testsRoot = Path.of("out/ide-tests/tests")
            val rdDir = testsRoot.toFile()
                .listFiles { f -> f.isDirectory && f.name.startsWith("RD-") }
                ?.firstOrNull()
            val configDir = if (rdDir != null) {
                rdDir.toPath().resolve(contextName).resolve("config")
            } else {
                testsRoot.resolve("RD-261.22158.335").resolve(contextName).resolve("config")
            }
            configDir.toFile().mkdirs()
            println("[integration] RD: configDir=$configDir, exists=${configDir.toFile().exists()}")

            // Override the Starter's "intellij.first.ide.session=true" that triggers
            // Rider's onboarding wizard (and thus the trust dialog).
            java.io.File(configDir.toFile(), "migrate.config").writeText(
                "merge-configs\nset-properties intellij.first.ide.session false\n"
            )
            println("[integration] RD: wrote migrate.config with first.ide.session=false")

            val optionsDir = configDir.resolve("options")
            optionsDir.toFile().mkdirs()

            // Root cause (confirmed by decompiling Rider + the platform): opening the solution
            // calls TrustedSolutionManager.isTrusted, which pops a modal trust dialog on the EDT
            // and blocks the entire 10-minute run. Its private l(solutionDirectory) returns early
            // (no dialog) when TrustedProjects.isTrustedCheckDisabled() is true:
            //
            //   if (Boolean.getBoolean("idea.trust.all.projects")) return true
            //   else return isHeadlessMode && Boolean.parseBoolean(
            //                                   getProperty("idea.trust.headless.disabled","true"))
            //
            // The bench runs under xvfb (a REAL X display), so isHeadlessMode is false and the
            // default headless bypass never fires — which is why every prior attempt at writing
            // trust config files (trustedSolutions.xml, Trusted.Paths) failed to suppress the
            // dialog. Setting -Didea.trust.all.projects=true short-circuits the check regardless
            // of display mode, config-file load order, or path normalization. Use the Starter's
            // VMOptions.addSystemProperty (NOT com.intellij.diagnostic.VMOptions, which edits the
            // wrong JVM's options file and corrupted launches in an earlier attempt).
            context.applyVMOptionsPatch {
                addSystemProperty("idea.trust.all.projects", true)
            }
            println("[integration] RD: set -Didea.trust.all.projects=true via VM options")

            // Approach 1A: suppress IdeStartupWizard via ide.general.xml
            java.io.File(optionsDir.toFile(), "ide.general.xml").writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<application>
  <component name="WelcomeScreenComponent">
    <option name="isStartupWizardCompleted" value="true" />
  </component>
</application>"""
            )
            println("[integration] RD: wrote ide.general.xml (WelcomeScreenComponent.isStartupWizardCompleted=true)")

            // Approach 1B: suppress IdeStartupWizard via ideStartupWizardSettings.xml
            java.io.File(optionsDir.toFile(), "ideStartupWizardSettings.xml").writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<application>
  <component name="IdeStartupWizardSettings">
    <option name="myIsEnabled" value="false" />
  </component>
</application>"""
            )
            println("[integration] RD: wrote ideStartupWizardSettings.xml (myIsEnabled=false)")

            // McpServerSettings is a PROJECT-level @State(@Storage("mcpServer.xml")) component
            // (plugin-core .../settings/McpServerSettings.java); PsiBridgeStartup.autoStartMcpServer()
            // only launches the MCP HTTP server when its autoStart flag is true. IntelliJ IDEA and
            // CLion read project component config from <project>/.idea/, so the java-app and cpp-cmake
            // fixtures commit .idea/mcpServer.xml there. Rider, however, stores a .sln solution's
            // project component config under <solutionDir>/.idea/.idea.<SolutionName>/.idea/ (alongside
            // vcs.xml, encodings.xml, indexLayout.xml) and ignores the IntelliJ-style .idea/mcpServer.xml.
            // That nested dir is .gitignored (Rider regenerates it locally), so it cannot be a committed
            // fixture file — without the setting, autoStart defaults to false on Rider and the server
            // never starts, so the bench times out (confirmed run 27939904788:
            // "autoStartMcpServer: isAutoStart=false"). Pre-seed it at the path Rider actually reads.
            val solutionName = fixture.fileName.toString().removeSuffix(".sln")
            val riderProjectConfigDir = fixture.parent
                .resolve(".idea").resolve(".idea.$solutionName").resolve(".idea")
            riderProjectConfigDir.toFile().mkdirs()
            java.io.File(riderProjectConfigDir.toFile(), "mcpServer.xml").writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="McpServerSettings">
    <option name="port" value="$MCP_PORT"/>
    <option name="staticPort" value="true"/>
    <option name="autoStart" value="true"/>
    <option name="transportMode" value="STREAMABLE_HTTP"/>
  </component>
</project>"""
            )
            println("[integration] RD: wrote mcpServer.xml (autoStart=true) at $riderProjectConfigDir")

            println("[integration] RD: all suppression configs written at $configDir")
        }

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
                val version = mcp.awaitHealthy(timeout = ide.bootTimeout)
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

/**
 * Collects IDE-reported problems (log-scraped errors and freezes the Starter framework surfaces
 * via [CIServer.reportTestFailure]) and writes them to a TSV the `report` job renders as a
 * non-gating "Logged IDE errors" section beneath the compatibility matrix. These never fail a
 * cell — the cell contract is the tool's MCP response, asserted in each test body.
 *
 * The output path comes from the [OUTPUT_FILE_PROPERTY] system property (set by the build to a
 * file inside the test-results dir so it rides the existing `test-xml-<IDE>` artifact). Each line
 * is `IDE \t testName \t summary`, with the summary flattened to a single line.
 */
internal object LoggedIdeErrors {

    const val OUTPUT_FILE_PROPERTY: String = "agentbridge.logged-errors.file"

    private val entries = mutableListOf<String>()

    @Synchronized
    fun record(testName: String, message: String, details: String) {
        val ide = System.getProperty(IdeUnderTest.IDE_PROPERTY, "CL")
        val summary = (message.trim() + "\n" + details.trim())
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(8)
            .joinToString(separator = " ⏎ ")
            .replace('\t', ' ')
        entries.add(listOf(ide, testName.replace('\t', ' '), summary).joinToString("\t"))
        println("[integration][logged-ide-error] $ide — $testName: ${message.lineSequence().firstOrNull().orEmpty()}")
        flush()
    }

    private fun flush() {
        val target = System.getProperty(OUTPUT_FILE_PROPERTY) ?: return
        val file = java.io.File(target)
        file.parentFile?.mkdirs()
        file.writeText(entries.joinToString(separator = "\n", postfix = "\n"))
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
        val root = testHome.toFile()
        val header = "[integration] === IdeLogDump: testHome=$testHome, exists=${root.exists()} ==="
        println(header)
        System.err.println(header)
        if (!root.exists()) {
            println("[integration] testHome does not exist")
            System.err.println("[integration] testHome does not exist")
            return
        }

        // Copy thread dumps to build dir so CI artifact upload picks them up
        val threadDumpDir = root.toPath().resolve("log/monitoring-thread-dumps-ide")
        val artifactDir = java.nio.file.Paths.get(
            System.getProperty("agentbridge.logged-errors.file", "")
        ).parent?.resolve("thread-dumps")
        if (artifactDir != null) {
            artifactDir.toFile().mkdirs()
            threadDumpDir.toFile().listFiles()?.forEach { td ->
                try {
                    java.nio.file.Files.copy(td.toPath(), artifactDir.resolve(td.name))
                } catch (_: Exception) {
                }
            }
        }

        // List ALL files under testHome
        val allFiles = root.walkTopDown().filter { it.isFile }.toList()
        val fileList = "[integration] All testHome files (${allFiles.size}):\n" +
            allFiles.joinToString("\n") { "  ${it.relativeTo(root).path} (${it.length()}B)" }
        println(fileList)
        System.err.println(fileList)

        // Dump thread dumps — print paths + first dump's content
        val threadDumps = allFiles.filter {
            it.parentFile?.name == "monitoring-thread-dumps-ide" || it.parentFile?.name == "threadDumps"
        }.sortedBy { it.name }
        if (threadDumps.isNotEmpty()) {
            val tdPaths = "[integration] === Thread dumps (${threadDumps.size}):\n" +
                threadDumps.joinToString("\n") { "  ${it.absolutePath}" }
            println(tdPaths)
            System.err.println(tdPaths)
            // Print first thread dump content (200 lines) to stderr
            val first = threadDumps.first()
            val lines = first.readLines()
            val tdContent = "[integration] === ${first.name} (${lines.size} lines, first 200) ===\n" +
                lines.take(200).joinToString("\n")
            println(tdContent)
            System.err.println(tdContent)
        }

        // Dump log files with keyword filtering
        val allLogs = allFiles.filter { it.name.endsWith(".log") }
        val sorted = allLogs.sortedWith(
            compareBy { f -> PRIORITY.indexOfFirst { f.name.startsWith(it) }.let { if (it == -1) 99 else it } },
        )
        for (logFile in sorted.take(3)) {
            val lines = logFile.readLines()
            val relevant = lines.filter { line -> KEYWORDS.any { line.lowercase().contains(it) } }
            val logOutput = "[integration] === ${logFile.name} (${lines.size} lines, ${relevant.size} relevant) ===\n" +
                relevant.joinToString("\n") + "\n" +
                "[integration] --- Last 30 lines ---\n" +
                lines.takeLast(30).joinToString("\n")
            println(logOutput)
            System.err.println(logOutput)
        }
    }
}
