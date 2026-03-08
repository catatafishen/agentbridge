package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ClaudeSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import org.jetbrains.annotations.NotNull;

/**
 * Claude-specific implementation of {@link AgentSettings}.
 * Delegates to {@link ClaudeSettings} for all values.
 */
public class ClaudeAgentSettings implements AgentSettings {

    @Override
    public boolean isAutopilotMode() {
        return "autopilot".equals(ClaudeSettings.getSessionMode());
    }

    @Override
    public int getPromptTimeout() {
        return ClaudeSettings.getPromptTimeout();
    }

    @Override
    public int getMaxToolCallsPerTurn() {
        return ClaudeSettings.getMaxToolCallsPerTurn();
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
