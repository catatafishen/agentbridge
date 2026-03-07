package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.psi.ToolLayerSettings;
import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Copilot-specific implementation of {@link ToolLayerSettings}.
 * Delegates to {@link CopilotSettings} for all values.
 *
 * <p>Registered as a project service in plugin-core's {@code plugin.xml}
 * so the MCP tool layer gets Copilot's settings without importing
 * {@code CopilotSettings} directly.</p>
 */
public final class CopilotToolLayerSettings implements ToolLayerSettings {

    private final Project project;

    public CopilotToolLayerSettings(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public boolean getFollowAgentFiles() {
        return CopilotSettings.getFollowAgentFiles(project);
    }

    @Override
    public @Nullable String getActiveAgentLabel() {
        return CopilotSettings.getActiveAgentLabel();
    }

    @Override
    public @Nullable String getSelectedModel() {
        return CopilotSettings.getSelectedModel();
    }

    @Override
    public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
        return CopilotSettings.resolveEffectivePermission(toolId, insideProject);
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return CopilotSettings.getToolPermission(toolId);
    }
}
