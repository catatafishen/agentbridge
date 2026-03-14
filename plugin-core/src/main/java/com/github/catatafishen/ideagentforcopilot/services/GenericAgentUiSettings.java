package com.github.catatafishen.ideagentforcopilot.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory that creates an {@link AgentUiSettings} facade backed by a {@link GenericSettings} instance.
 * Used by generic agent services (Kiro, Gemini, OpenCode, Cline) to avoid duplicating the
 * verbose delegation boilerplate that Copilot and Claude services have.
 */
final class GenericAgentUiSettings implements AgentUiSettings {

    private final GenericSettings settings;

    GenericAgentUiSettings(@NotNull GenericSettings settings) {
        this.settings = settings;
    }

    @Override
    public @Nullable String getSelectedModel() {
        return settings.getSelectedModel();
    }

    @Override
    public void setSelectedModel(@NotNull String modelId) {
        settings.setSelectedModel(modelId);
    }

    @Override
    public @NotNull String getSelectedAgent() {
        return settings.getSelectedAgent();
    }

    @Override
    public void setSelectedAgent(@NotNull String agentName) {
        settings.setSelectedAgent(agentName);
    }

    @Override
    public @NotNull String getSessionOptionValue(@NotNull String optionKey) {
        return settings.getSessionOptionValue(optionKey);
    }

    @Override
    public void setSessionOptionValue(@NotNull String optionKey, @NotNull String value) {
        settings.setSessionOptionValue(optionKey, value);
    }

    @Override
    public @Nullable String getActiveAgentLabel() {
        return settings.getActiveAgentLabel();
    }

    @Override
    public void setActiveAgentLabel(@Nullable String label) {
        settings.setActiveAgentLabel(label);
    }

    @Override
    public int getPromptTimeout() {
        return settings.getPromptTimeout();
    }

    @Override
    public void setPromptTimeout(int seconds) {
        settings.setPromptTimeout(seconds);
    }

    @Override
    public int getMaxToolCallsPerTurn() {
        return settings.getMaxToolCallsPerTurn();
    }

    @Override
    public void setMaxToolCallsPerTurn(int count) {
        settings.setMaxToolCallsPerTurn(count);
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return settings.getToolPermission(toolId);
    }

    @Override
    public void setToolPermission(@NotNull String toolId, @NotNull ToolPermission perm) {
        settings.setToolPermission(toolId, perm);
    }

    @Override
    public @NotNull ToolPermission getToolPermissionInsideProject(@NotNull String toolId) {
        return settings.getToolPermissionInsideProject(toolId);
    }

    @Override
    public void setToolPermissionInsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        settings.setToolPermissionInsideProject(toolId, perm);
    }

    @Override
    public @NotNull ToolPermission getToolPermissionOutsideProject(@NotNull String toolId) {
        return settings.getToolPermissionOutsideProject(toolId);
    }

    @Override
    public void setToolPermissionOutsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        settings.setToolPermissionOutsideProject(toolId, perm);
    }

    @Override
    public void clearToolSubPermissions(@NotNull String toolId) {
        settings.clearToolSubPermissions(toolId);
    }
}
