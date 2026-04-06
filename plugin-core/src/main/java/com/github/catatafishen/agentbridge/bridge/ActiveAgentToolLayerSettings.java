package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentUiSettings;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Profile-aware implementation of {@link ToolLayerSettings}.
 * Delegates to the active agent's settings via {@link ActiveAgentManager}.
 *
 * <p>Registered as a project service in plugin.xml so the MCP tool layer
 * gets the active agent's settings without importing any agent-specific class.</p>
 */
public final class ActiveAgentToolLayerSettings implements ToolLayerSettings {

    private final Project project;

    public ActiveAgentToolLayerSettings(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public boolean getFollowAgentFiles() {
        return ActiveAgentManager.getFollowAgentFiles(project);
    }

    @Override
    public @Nullable String getActiveAgentLabel() {
        return ActiveAgentManager.getInstance(project).getSettings().getActiveAgentLabel();
    }

    @Override
    public @Nullable String getSelectedModel() {
        return ActiveAgentManager.getInstance(project).getSettings().getSelectedModel();
    }

    @Override
    public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
        AgentUiSettings settings = ActiveAgentManager.getInstance(project).getSettings();
        ToolPermission top = settings.getToolPermission(toolId);
        if (top != ToolPermission.ALLOW) return top;

        ToolDefinition entry =
            ToolRegistry.getInstance(project).findById(toolId);
        if (entry == null || !entry.supportsPathSubPermissions()) return top;

        return insideProject
            ? settings.getToolPermissionInsideProject(toolId)
            : settings.getToolPermissionOutsideProject(toolId);
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return ActiveAgentManager.getInstance(project).getSettings().getToolPermission(toolId);
    }
}
