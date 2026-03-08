package com.github.catatafishen.idemcpserver;

import com.github.catatafishen.ideagentforcopilot.services.McpHttpServer;
import com.github.catatafishen.ideagentforcopilot.settings.TransportMode;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Main toolbar toggle for starting/stopping the MCP HTTP server.
 * Shows a server icon that reflects the running state.
 */
public final class McpServerToggleAction extends ToggleAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(McpServerToggleAction.class);

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return false;
        return McpHttpServer.getInstance(project).isRunning();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;

        McpHttpServer server = McpHttpServer.getInstance(project);
        if (state) {
            try {
                server.start();
            } catch (Exception ex) {
                LOG.error("Failed to start MCP server", ex);
            }
        } else {
            server.stop();
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        super.update(e);
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            e.getPresentation().setEnabled(false);
            return;
        }

        McpHttpServer server = McpHttpServer.getInstance(project);
        boolean running = server.isRunning();
        if (running) {
            TransportMode mode = server.getActiveTransportMode();
            String modeLabel = mode != null ? mode.getDisplayName() : "HTTP";
            e.getPresentation().setText("MCP Server (port " + server.getPort() + ", " + modeLabel + ")");
        } else {
            e.getPresentation().setText("MCP Server (stopped)");
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
