package com.github.catatafishen.ideagentforcopilot.psi;

import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only settings contract for the MCP tool layer.
 * Tool handlers use this interface instead of referencing any agent-specific
 * settings class, keeping the tool layer agent-agnostic.
 *
 * <p>Each agent plugin registers its own implementation as a project service
 * (e.g., {@code CopilotToolLayerSettings}). The standalone MCP plugin registers
 * {@link DefaultToolLayerSettings}.</p>
 */
public interface ToolLayerSettings {

    /**
     * PropertiesComponent key for the "follow agent files" per-project setting.
     * Shared across implementations so the setting is consistent regardless of
     * which agent plugin is active.
     */
    String FOLLOW_AGENT_FILES_KEY = "copilot.followAgentFiles";

    /**
     * Look up the project-level {@link ToolLayerSettings} service.
     * Falls back to {@link DefaultToolLayerSettings} defaults if no implementation is registered.
     */
    @NotNull
    static ToolLayerSettings getInstance(@NotNull Project project) {
        ToolLayerSettings service = ((ComponentManager) project).getService(ToolLayerSettings.class);
        return service != null ? service : DefaultToolLayerSettings.FALLBACK;
    }

    /**
     * Whether to auto-navigate to files as the agent reads or edits them.
     */
    boolean getFollowAgentFiles();

    /**
     * Display label for the currently active agent or sub-agent (e.g., "ui-reviewer").
     * Returns {@code null} when the main agent is active (no sub-agent running).
     */
    @Nullable
    String getActiveAgentLabel();

    /**
     * Currently selected model name (e.g., "claude-sonnet-4.5").
     * Returns {@code null} if no model is selected.
     */
    @Nullable
    String getSelectedModel();

    /**
     * Resolve the effective permission for a tool, considering path-based sub-permissions.
     */
    @NotNull
    ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject);

    /**
     * Get the base permission level for a tool (ignoring path context).
     */
    @NotNull
    ToolPermission getToolPermission(@NotNull String toolId);
}
