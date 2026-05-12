package com.github.catatafishen.agentbridge.psi

import com.github.catatafishen.agentbridge.psi.tools.project.ExternalDirRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Computable
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts the PSI Bridge HTTP server when a project opens.
 * Uses [ProjectActivity] (not legacy StartupActivity) so the plugin supports
 * dynamic loading/unloading without IDE restart.
 */
class PsiBridgeStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (PlatformApiCompat.isJetBrainsClient()) {
            LOG.info("Running in JetBrains thin client — skipping backend initialization")
            return
        }

        LOG.info("Initializing plugin for project: ${project.name}")

        createAgentWorkspace(project)
        cleanupStaleExternalModules(project)

        // Force-initialize PsiBridgeService so tools are registered before any agent connects
        PsiBridgeService.getInstance(project)

        // Force-initialize ConversationDatabase at startup so statistics are available from the start
        com.github.catatafishen.agentbridge.session.db.ConversationDatabase.getInstance(project)

        // Auto-start MCP HTTP server (required for agent CLI to access tools)
        val mcpSettings = com.github.catatafishen.agentbridge.settings.McpServerSettings.getInstance(project)
        if (mcpSettings.isAutoStart) {
            try {
                val mcpServer =
                    com.github.catatafishen.agentbridge.services.McpServerControl.getInstance(project)
                mcpServer?.start()
                LOG.info("MCP server auto-started on port ${mcpSettings.port} (${mcpSettings.transportMode.displayName})")
            } catch (e: Exception) {
                LOG.error("Failed to auto-start MCP HTTP server", e)
            }
        }

        // Auto-start web chat server if enabled
        val webSettings = com.github.catatafishen.agentbridge.settings.ChatWebServerSettings.getInstance(project)
        if (webSettings.isEnabled) {
            try {
                com.github.catatafishen.agentbridge.services.ChatWebServer.getInstance(project)?.start()
                LOG.info("Web chat server auto-started on port ${webSettings.port}")
            } catch (e: Exception) {
                LOG.error("Failed to auto-start web chat server", e)
            }
        }

        // In Remote Dev backend mode, show the tool window so the thin client user sees it
        // without having to manually open it via View → Tool Windows on first use.
        if (PlatformApiCompat.isRemoteDevBackend()) {
            ApplicationManager.getApplication().invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow("AgentBridge")?.show(null)
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

    /**
     * Removes any stale agentbridge-ext-* modules left by an unclean shutdown.
     * All agentbridge-ext-* modules are removed unconditionally on startup since
     * attached external dirs are not persisted across sessions.
     * Runs asynchronously on a pooled thread.
     */
    private fun cleanupStaleExternalModules(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val manager = ModuleManager.getInstance(project)
                val prefix = ExternalDirRegistry.MODULE_NAME_PREFIX
                val staleModules = ApplicationManager.getApplication().runReadAction(
                    Computable {
                        manager.modules.filter { it.name.startsWith(prefix) }
                    }
                )
                if (staleModules.isEmpty()) return@executeOnPooledThread
                EdtUtil.invokeAndWait {
                    ApplicationManager.getApplication().runWriteAction {
                        staleModules.forEach { manager.disposeModule(it) }
                    }
                }
                LOG.info("Cleaned up ${staleModules.size} stale external dir module(s)")
            } catch (e: Exception) {
                LOG.warn("Failed to clean up stale external dir modules", e)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PsiBridgeStartup::class.java)
    }
}
