package com.github.catatafishen.agentbridge.psi

import com.github.catatafishen.agentbridge.psi.tools.project.ExternalDirRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Computable
import com.intellij.openapi.wm.ToolWindowManager

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

        cleanupStaleExternalModules(project)
        LegacyAgentWorkCleanup.cleanupAsync(project)

        // Initialize ConversationDatabase FIRST — PsiBridgeService needs it for GraphToolFactory
        val db = com.github.catatafishen.agentbridge.session.db.ConversationDatabase.getInstance(project)
        if (!db.isReady) {
            try {
                db.initialize()
            } catch (e: Exception) {
                LOG.warn("Failed to initialize ConversationDatabase at startup", e)
            }
        }

        // Force-initialize PsiBridgeService so tools are registered before any agent connects
        PsiBridgeService.getInstance(project)

        // Wire Code Graph auto-refresh: when settings.autoRefreshOnAgentEdit is true, any VFS
        // change to a project source file triggers an incremental re-extraction. The indexer
        // skips files whose content hash is unchanged, so this is cheap when nothing edited.
        try {
            val graphSettings = com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings.getInstance(project)
            // BulkFileListener.TOPIC is application-scoped — subscribe via the application bus,
            // passing the project as the parent disposable so the connection is released when
            // the project closes.
            val startupTime = System.currentTimeMillis()
            ApplicationManager.getApplication().messageBus.connect(project).subscribe(
                com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
                object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
                    override fun after(events: List<com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                        if (!graphSettings.isEnabled || !graphSettings.isAutoRefreshOnAgentEdit) return
                        // Skip VFS events during IDE startup — thousands of events fire as IntelliJ
                        // scans the project, and processing them all starves the EDT of write locks.
                        if (System.currentTimeMillis() - startupTime < 10_000) return
                        val touched = events.mapNotNull { it.file }
                            .filter { it.isValid && !it.isDirectory }
                            .distinct()
                        if (touched.isEmpty()) return
                        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                            // TypeScript/JS PSI resolution requires a ProgressIndicator in context
                            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess({
                                val indexer =
                                    com.github.catatafishen.agentbridge.psi.graph.CodeGraphIndexer.getInstance(project)
                                for (vf in touched) {
                                    try {
                                        indexer.refreshFile(vf)
                                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                                        throw e
                                    } catch (e: Exception) {
                                        LOG.debug("Code Graph refresh failed for ${vf.path}: ${e.message}")
                                    }
                                }
                            }, com.intellij.openapi.progress.EmptyProgressIndicator())
                        }
                    }
                }
            )
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to wire Code Graph auto-refresh listener", e)
        }

        // Log graph tool status (diagnostic only — factory now always registers it)
        try {
            val graphSettings = com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings.getInstance(project)
            val registry = com.github.catatafishen.agentbridge.services.ToolRegistry.getInstance(project)
            val registered = registry.findById("query_code_graph") != null
            LOG.info("Code Graph startup: registered=$registered, enabled=${graphSettings.isEnabled}")
        } catch (e: Exception) {
            LOG.warn("Code Graph startup check failed", e)
        }

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
     * Removes all agentbridge-ext-* modules left over from a previous session.
     * External modules are temporary by design and should not persist across restarts.
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
