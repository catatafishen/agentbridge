package com.github.catatafishen.ideagentforcopilot.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-agent UI settings interface.
 * Each agent (Copilot, Claude, etc.) provides its own implementation that delegates
 * to its concrete settings class. The UI layer reads and writes settings through this
 * interface instead of referencing a concrete settings class directly.
 *
 * <p>Shared UI preferences (attach trigger, follow-agent-files) live on
 * {@link ActiveAgentManager} because they are not agent-specific.</p>
 */
public interface AgentUiSettings {

    // ── Model selection ──────────────────────────────────────────────────────

    @Nullable
    String getSelectedModel();

    void setSelectedModel(@NotNull String modelId);

    // ── Agent selection ──────────────────────────────────────────────────────

    /**
     * Returns the selected agent name (e.g. "ide-explore"), or empty string for "Default".
     */
    @NotNull
    String getSelectedAgent();

    void setSelectedAgent(@NotNull String agentName);

    // ── Session options ──────────────────────────────────────────────────────

    /**
     * Returns the persisted value for a named session option (e.g. "effort"), or empty string.
     */
    @NotNull
    String getSessionOptionValue(@NotNull String optionKey);

    void setSessionOptionValue(@NotNull String optionKey, @NotNull String value);

    // ── Active sub-agent label (runtime-only) ────────────────────────────────

    @Nullable
    String getActiveAgentLabel();

    void setActiveAgentLabel(@Nullable String label);

    // ── Tool permissions ─────────────────────────────────────────────────────

    // ── Timeout & limits ─────────────────────────────────────────────────────

    int getPromptTimeout();

    void setPromptTimeout(int seconds);

    int getMaxToolCallsPerTurn();

    void setMaxToolCallsPerTurn(int count);

    @NotNull
    ToolPermission getToolPermission(@NotNull String toolId);

    void setToolPermission(@NotNull String toolId, @NotNull ToolPermission perm);

    @NotNull
    ToolPermission getToolPermissionInsideProject(@NotNull String toolId);

    void setToolPermissionInsideProject(@NotNull String toolId, @NotNull ToolPermission perm);

    @NotNull
    ToolPermission getToolPermissionOutsideProject(@NotNull String toolId);

    void setToolPermissionOutsideProject(@NotNull String toolId, @NotNull ToolPermission perm);

    void clearToolSubPermissions(@NotNull String toolId);
}
