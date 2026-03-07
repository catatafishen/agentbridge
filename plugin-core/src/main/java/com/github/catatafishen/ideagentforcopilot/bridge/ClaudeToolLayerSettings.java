package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.psi.ToolLayerSettings;
import com.github.catatafishen.ideagentforcopilot.services.ClaudeSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Claude-specific implementation of {@link ToolLayerSettings}.
 * Delegates to {@link ClaudeSettings} for all values.
 *
 * <p>Not registered as the default project service — {@link CopilotToolLayerSettings}
 * remains the default. This implementation is available for future multi-agent
 * scenarios where the active agent determines which settings to use.</p>
 */
public final class ClaudeToolLayerSettings implements ToolLayerSettings {

    private final Project project;

    public ClaudeToolLayerSettings(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public boolean getFollowAgentFiles() {
        return ClaudeSettings.getFollowAgentFiles(project);
    }

    @Override
    public @Nullable String getActiveAgentLabel() {
        return ClaudeSettings.getActiveAgentLabel();
    }

    @Override
    public @Nullable String getSelectedModel() {
        return ClaudeSettings.getSelectedModel();
    }

    @Override
    public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
        return ClaudeSettings.resolveEffectivePermission(toolId, insideProject);
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return ClaudeSettings.getToolPermission(toolId);
    }
}
