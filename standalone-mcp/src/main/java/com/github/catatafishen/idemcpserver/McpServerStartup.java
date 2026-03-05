package com.github.catatafishen.idemcpserver;

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.github.catatafishen.idemcpserver.settings.McpServerSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity that initializes the PSI Bridge and optionally starts
 * the MCP HTTP server when a project opens.
 */
public final class McpServerStartup implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(McpServerStartup.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // Start the PSI Bridge (tool handlers)
        PsiBridgeService.getInstance(project).start();

        // Auto-start MCP server if configured
        McpServerSettings settings = McpServerSettings.getInstance(project);
        if (settings.isAutoStart()) {
            try {
                McpHttpServer.getInstance(project).start();
                LOG.info("MCP HTTP server auto-started on port " + settings.getPort());
            } catch (Exception e) {
                LOG.error("Failed to auto-start MCP HTTP server", e);
            }
        }

        return Unit.INSTANCE;
    }
}
