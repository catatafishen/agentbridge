package com.github.catatafishen.ideagentforcopilot.psi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts the PSI Bridge HTTP server when a project opens.
 * Uses [ProjectActivity] (not legacy StartupActivity) so the plugin supports
 * dynamic loading/unloading without IDE restart.
 */
class PsiBridgeStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("Starting PSI Bridge for project: ${project.name}")

        createAgentWorkspace(project)
        ensureCopilotInstructions(project)

        PsiBridgeService.getInstance(project).start()
    }

    /**
     * Creates the .agent-work/ directory structure for agent session state.
     * This directory is typically gitignored and provides a safe workspace
     * for the agent to store plans, checkpoints, and analysis files.
     */
    private fun createAgentWorkspace(project: Project) {
        val basePath = project.basePath ?: return

        try {
            val agentWork = Path.of(basePath, ".agent-work")
            Files.createDirectories(agentWork.resolve("checkpoints"))
            Files.createDirectories(agentWork.resolve("files"))

            val planFile = agentWork.resolve("plan.md")
            if (!Files.exists(planFile)) {
                Files.writeString(planFile, PLAN_TEMPLATE)
            }

            LOG.info("Agent workspace initialized at: $agentWork")
        } catch (e: Exception) {
            LOG.warn("Failed to create agent workspace", e)
        }
    }

    /**
     * One-time prepend of plugin instructions to copilot-instructions.md.
     *
     * GitHub Copilot reads copilot-instructions.md and injects it into the model context,
     * but ignores the MCP `instructions` field from the initialize response.
     * See: https://github.com/github/copilot-cli/issues/1486
     *
     * As a workaround, we prepend our default startup instructions to the project's
     * copilot-instructions.md on first load (detected by a sentinel comment).
     * The file may be under .copilot/ or .github/.
     */
    private fun ensureCopilotInstructions(project: Project) {
        val basePath = project.basePath ?: return

        try {
            // Detect existing file location (.copilot/ takes precedence, then .github/)
            val dotCopilotFile = Path.of(basePath, ".copilot", "copilot-instructions.md")
            val dotGithubFile = Path.of(basePath, ".github", "copilot-instructions.md")
            val targetFile = when {
                Files.isRegularFile(dotCopilotFile) -> dotCopilotFile
                Files.isRegularFile(dotGithubFile) -> dotGithubFile
                else -> dotCopilotFile // will create in .copilot/
            }

            val pluginInstructions = loadPluginInstructions()

            if (Files.isRegularFile(targetFile)) {
                val existing = Files.readString(targetFile, StandardCharsets.UTF_8)
                if (existing.contains(INSTRUCTIONS_SENTINEL)) {
                    return // already prepended
                }
                // Prepend plugin instructions to existing user content
                val merged = pluginInstructions + "\n\n" + existing
                Files.writeString(targetFile, merged, StandardCharsets.UTF_8)
                LOG.info("Prepended plugin instructions to existing $targetFile")
            } else {
                // Create new file with plugin instructions only
                targetFile.parent?.let { Files.createDirectories(it) }
                Files.writeString(targetFile, pluginInstructions, StandardCharsets.UTF_8)
                LOG.info("Created $targetFile with plugin instructions")
            }

            notifyInstructionsUpdated(project, targetFile)
        } catch (e: Exception) {
            LOG.warn("Failed to ensure copilot-instructions.md", e)
        }
    }

    private fun loadPluginInstructions(): String {
        val stream = javaClass.getResourceAsStream("/default-startup-instructions.md")
        val instructions = stream?.bufferedReader()?.use { it.readText() }
            ?: "You are running inside an IntelliJ IDEA plugin with IDE tools."
        return "$INSTRUCTIONS_SENTINEL\n\n$instructions\n\n$INSTRUCTIONS_END"
    }

    private fun notifyInstructionsUpdated(project: Project, file: Path) {
        val relativePath = Path.of(project.basePath ?: "").relativize(file).toString()
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Copilot Notifications")
            .createNotification(
                "IDE Agent for Copilot",
                "Plugin instructions added to $relativePath. " +
                    "Copilot ignores MCP server instructions, so this file is used instead. " +
                    "You can edit or remove the added section at any time.",
                com.intellij.notification.NotificationType.INFORMATION
            )
            .notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(PsiBridgeStartup::class.java)

        /** Sentinel markers used to detect/delimit plugin-injected instructions in copilot-instructions.md. */
        private const val INSTRUCTIONS_SENTINEL =
            "---\n> ⚙️ Auto-generated by IDE Agent for Copilot — do not edit this section"
        private const val INSTRUCTIONS_END =
            "> End of auto-generated instructions\n---"

        private val PLAN_TEMPLATE = """
            # Agent Work Plan

            ## Project Principles

            When working on this IntelliJ plugin project:
            - **Write clean, well-formatted code** following Java/Kotlin best practices
            - **Use IntelliJ tools first**: `intellij_read_file`, `intellij_write_file`, `search_symbols`, `find_references`
            - **Always format and optimize** after changes: `format_code` + `optimize_imports`
            - **Test before commit**: `build_project` + `run_tests` to ensure nothing breaks
            - **Make logical commits**: Group related changes, separate unrelated changes

            ## Multi-Step Task Workflow

            When fixing multiple issues:
            1. Scan and group by problem TYPE (not by file)
            2. Fix ONE problem type completely (may span multiple files)
            3. Format, build, test, commit
            4. ⚠️ **STOP and ASK** before continuing to next type

            ## Current Tasks

            _Use checkboxes below to track your progress:_

            - [ ] Task 1
            - [ ] Task 2

            ## Notes

            _Add any context, decisions, or findings here_
        """.trimIndent()
    }
}
