package com.github.catatafishen.ideagentforcopilot.psi

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
        LOG.info("Initializing plugin for project: ${project.name}")

        createAgentWorkspace(project)

        // Force-initialize PsiBridgeService so tools are registered before any agent connects
        PsiBridgeService.getInstance(project)

        // Auto-start MCP HTTP server (required for agent CLI to access tools)
        val mcpSettings = com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings.getInstance(project)
        if (mcpSettings.isAutoStart) {
            try {
                val mcpServer =
                    com.github.catatafishen.ideagentforcopilot.services.McpServerControl.getInstance(project)
                mcpServer?.start()
                LOG.info("MCP server auto-started on port ${mcpSettings.port} (${mcpSettings.transportMode.displayName})")
            } catch (e: Exception) {
                LOG.error("Failed to auto-start MCP HTTP server", e)
            }
        }

        // Auto-start web chat server if enabled
        val webSettings = com.github.catatafishen.ideagentforcopilot.settings.ChatWebServerSettings.getInstance(project)
        if (webSettings.isEnabled) {
            try {
                com.github.catatafishen.ideagentforcopilot.services.ChatWebServer.getInstance(project)?.start()
                LOG.info("Web chat server auto-started on port ${webSettings.port}")
            } catch (e: Exception) {
                LOG.error("Failed to auto-start web chat server", e)
            }
        }
    }

    /**
     * Creates the .agent-work/ directory structure for agent session state.
     * This directory is typically gitignored and provides a safe workspace
     * for the agent to store session artifacts.
     */
    private fun createAgentWorkspace(project: Project) {
        val basePath = project.basePath ?: return

        try {
            val agentWork = Path.of(basePath, ".agent-work")
            Files.createDirectories(agentWork.resolve("session-state"))
            Files.createDirectories(agentWork.resolve("files"))

            LOG.info("Agent workspace initialized at: $agentWork")
        } catch (e: Exception) {
            LOG.warn("Failed to create agent workspace", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PsiBridgeStartup::class.java)
    }
}
