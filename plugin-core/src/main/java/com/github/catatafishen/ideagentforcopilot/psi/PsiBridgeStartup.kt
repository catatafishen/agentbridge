package com.github.catatafishen.ideagentforcopilot.psi

import com.github.catatafishen.ideagentforcopilot.bridge.CopilotInstructionsManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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

        // Ensure copilot-instructions.md exists with plugin instructions.
        // This is also called from AcpClient.start() as a safety net
        // against race conditions (tool window may start CLI before this runs).
        CopilotInstructionsManager.ensureInstructions(project.basePath)
        notifyIfNewInstructions(project)

        val mcpSettings = com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings.getInstance(project)
        if (mcpSettings.isBridgeAutoStart) {
            PsiBridgeService.getInstance(project).start(mcpSettings.bridgePort)
        }

        // Auto-start MCP HTTP server (required for agent CLI to access tools)
        if (mcpSettings.isAutoStart) {
            try {
                val mcpServer = com.github.catatafishen.ideagentforcopilot.services.McpServerControl.getInstance(project)
                mcpServer?.start()
                LOG.info("MCP server auto-started on port ${mcpSettings.port} (${mcpSettings.transportMode.displayName})")
            } catch (e: Exception) {
                LOG.error("Failed to auto-start MCP HTTP server", e)
            }
        }
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
     * Shows a one-time notification when instructions are first added to a project.
     * The actual file write is handled by [CopilotInstructionsManager.ensureInstructions].
     */
    private fun notifyIfNewInstructions(project: Project) {
        val basePath = project.basePath ?: return
        val dotCopilotFile = Path.of(basePath, ".copilot", "copilot-instructions.md")
        val dotGithubFile = Path.of(basePath, ".github", "copilot-instructions.md")
        val targetFile = when {
            Files.isRegularFile(dotCopilotFile) -> dotCopilotFile
            Files.isRegularFile(dotGithubFile) -> dotGithubFile
            else -> return
        }
        try {
            val content = Files.readString(targetFile)
            if (content.contains(CopilotInstructionsManager.INSTRUCTIONS_SENTINEL)) {
                // File exists with our sentinel — check if we should notify
                // (only on first creation, tracked by agent-work marker)
                val marker = Path.of(basePath, ".agent-work", ".instructions-notified")
                if (!Files.exists(marker)) {
                    notifyInstructionsUpdated(project, targetFile)
                    Files.writeString(marker, "done")
                }
            }
        } catch (_: Exception) {
            // Best-effort notification
        }
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
