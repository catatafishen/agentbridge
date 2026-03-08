package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.GenericSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import org.jetbrains.annotations.NotNull;

/**
 * Shared {@link AgentSettings} implementation backed by a {@link GenericSettings} instance.
 * New ACP agents (Kiro, Gemini, OpenCode, Cline) use this instead of a dedicated
 * settings class.
 */
public final class GenericAgentSettings implements AgentSettings {

    private final GenericSettings settings;

    public GenericAgentSettings(@NotNull GenericSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean isAutopilotMode() {
        return "autopilot".equals(settings.getSessionMode());
    }

    @Override
    public int getPromptTimeout() {
        return settings.getPromptTimeout();
    }

    @Override
    public int getMaxToolCallsPerTurn() {
        return settings.getMaxToolCallsPerTurn();
    }

    @Override
    public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
        return settings.resolveEffectivePermission(toolId, insideProject);
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return settings.getToolPermission(toolId);
    }
}
