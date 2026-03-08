package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import org.jetbrains.annotations.NotNull;

/**
 * Strategy interface for agent-specific settings that the generic {@link AcpClient} needs
 * at runtime. Each agent implementation provides its own settings (timeouts, permissions, etc.).
 *
 * <p>This decouples {@link AcpClient} from any concrete settings storage
 * (e.g., {@code CopilotSettings}), keeping the protocol layer agent-agnostic.</p>
 */
public interface AgentSettings {

    /**
     * Whether the agent is in autopilot mode (auto-approve all permission requests).
     * Default is {@code false} — each implementation reads from its own session-mode setting.
     */
    default boolean isAutopilotMode() {
        return false;
    }

    /**
     * Maximum seconds of inactivity before the agent is terminated.
     */
    int getPromptTimeout();

    /**
     * Maximum tool calls allowed per prompt turn (0 = unlimited).
     */
    int getMaxToolCallsPerTurn();

    /**
     * Resolve the effective permission for a tool, considering path-based sub-permissions
     * when the tool call targets a file inside or outside the project.
     *
     * @param toolId        the normalised tool ID (without prefix)
     * @param insideProject whether the target path is inside the project root
     * @return the effective permission level
     */
    @NotNull
    ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject);

    /**
     * Get the base permission level for a tool (ignoring path context).
     *
     * @param toolId the normalised tool ID
     * @return the configured permission level
     */
    @NotNull
    ToolPermission getToolPermission(@NotNull String toolId);
}
