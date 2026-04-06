package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.GenericSettings;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class GenericAgentSettings implements AgentSettings {

    private final GenericSettings settings;
    private final Project project;

    public GenericAgentSettings(@NotNull GenericSettings settings, @NotNull Project project) {
        this.settings = settings;
        this.project = project;
    }

    @Override
    public boolean isAutoApprovePermissions() {
        var profile = ActiveAgentManager.getInstance(project).getActiveProfile();
        return !profile.isUsePluginPermissions();
    }

    @Override
    public int getTurnTimeout() {
        return ActiveAgentManager.getInstance(project).getSharedTurnTimeoutSeconds();
    }

    @Override
    public int getInactivityTimeout() {
        return ActiveAgentManager.getInstance(project).getSharedInactivityTimeoutSeconds();
    }

    @Override
    public int getMaxToolCallsPerTurn() {
        return ActiveAgentManager.getInstance(project).getSharedMaxToolCallsPerTurn();
    }

    @Override
    public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
        return settings.resolveEffectivePermission(toolId, insideProject, ToolRegistry.getInstance(project));
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return settings.getToolPermission(toolId);
    }

    @Override
    public void setActiveAgentLabel(String label) {
        settings.setActiveAgentLabel(label);
    }
}
