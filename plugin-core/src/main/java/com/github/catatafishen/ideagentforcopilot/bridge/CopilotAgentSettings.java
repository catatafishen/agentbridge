package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import org.jetbrains.annotations.NotNull;

/**
 * Copilot-specific implementation of {@link AgentSettings}.
 * Delegates to {@link CopilotSettings} for all values.
 */
public class CopilotAgentSettings implements AgentSettings {

    @Override
    public boolean isAutopilotMode() {
        return "autopilot".equals(CopilotSettings.getSessionMode());
    }

    @Override
    public int getPromptTimeout() {
        return CopilotSettings.getPromptTimeout();
    }

    @Override
    public int getMaxToolCallsPerTurn() {
        return CopilotSettings.getMaxToolCallsPerTurn();
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
