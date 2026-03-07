package com.github.catatafishen.ideagentforcopilot.services;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persists Claude Code settings using IntelliJ's PropertiesComponent.
 * All keys use the {@code claude.} prefix to avoid conflicts with
 * {@link CopilotSettings} ({@code copilot.} prefix).
 */
public final class ClaudeSettings {

    private static final String KEY_SELECTED_MODEL = "claude.selectedModel";
    private static final String KEY_SESSION_MODE = "claude.sessionMode";
    private static final String KEY_MONTHLY_REQUESTS = "claude.monthlyRequests";
    private static final String KEY_MONTHLY_COST = "claude.monthlyCost";
    private static final String KEY_USAGE_RESET_MONTH = "claude.usageResetMonth";
    private static final String KEY_PROMPT_TIMEOUT = "claude.promptTimeout";
    private static final String KEY_MAX_TOOL_CALLS = "claude.maxToolCallsPerTurn";
    private static final String KEY_FOLLOW_AGENT_FILES = "claude.followAgentFiles";
    private static final String KEY_TOOL_PERM = "claude.tool.perm.";
    private static final String KEY_TOOL_PERM_IN = "claude.tool.perm.in.";
    private static final String KEY_TOOL_PERM_OUT = "claude.tool.perm.out.";
    private static final String KEY_TOOL_ENABLED = "claude.tool.enabled.";
    private static final int DEFAULT_PROMPT_TIMEOUT = 300;
    private static final int DEFAULT_MAX_TOOL_CALLS = 0;

    /**
     * Runtime-only label for the currently active agent (e.g. "ui-reviewer").
     */
    private static volatile String activeAgentLabel;

    private ClaudeSettings() {
    }

    @Nullable
    public static String getActiveAgentLabel() {
        return activeAgentLabel;
    }

    public static void setActiveAgentLabel(@Nullable String label) {
        activeAgentLabel = label;
    }

    public static int getPromptTimeout() {
        return PropertiesComponent.getInstance().getInt(KEY_PROMPT_TIMEOUT, DEFAULT_PROMPT_TIMEOUT);
    }

    public static void setPromptTimeout(int seconds) {
        PropertiesComponent.getInstance().setValue(KEY_PROMPT_TIMEOUT, seconds, DEFAULT_PROMPT_TIMEOUT);
    }

    public static int getMaxToolCallsPerTurn() {
        return PropertiesComponent.getInstance().getInt(KEY_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS);
    }

    public static void setMaxToolCallsPerTurn(int count) {
        PropertiesComponent.getInstance().setValue(KEY_MAX_TOOL_CALLS, count, DEFAULT_MAX_TOOL_CALLS);
    }

    @Nullable
    public static String getSelectedModel() {
        return PropertiesComponent.getInstance().getValue(KEY_SELECTED_MODEL);
    }

    public static void setSelectedModel(@NotNull String modelId) {
        PropertiesComponent.getInstance().setValue(KEY_SELECTED_MODEL, modelId);
    }

    @NotNull
    public static String getSessionMode() {
        return PropertiesComponent.getInstance().getValue(KEY_SESSION_MODE, "agent");
    }

    public static void setSessionMode(@NotNull String mode) {
        PropertiesComponent.getInstance().setValue(KEY_SESSION_MODE, mode);
    }

    public static int getMonthlyRequests() {
        return PropertiesComponent.getInstance().getInt(KEY_MONTHLY_REQUESTS, 0);
    }

    public static void setMonthlyRequests(int count) {
        PropertiesComponent.getInstance().setValue(KEY_MONTHLY_REQUESTS, count, 0);
    }

    public static double getMonthlyCost() {
        String val = PropertiesComponent.getInstance().getValue(KEY_MONTHLY_COST, "0.0");
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static void setMonthlyCost(double cost) {
        PropertiesComponent.getInstance().setValue(KEY_MONTHLY_COST, String.valueOf(cost));
    }

    @NotNull
    public static String getUsageResetMonth() {
        return PropertiesComponent.getInstance().getValue(KEY_USAGE_RESET_MONTH, "");
    }

    public static void setUsageResetMonth(@NotNull String month) {
        PropertiesComponent.getInstance().setValue(KEY_USAGE_RESET_MONTH, month);
    }

    public static boolean getFollowAgentFiles(@NotNull com.intellij.openapi.project.Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(KEY_FOLLOW_AGENT_FILES, true);
    }

    public static void setFollowAgentFiles(@NotNull com.intellij.openapi.project.Project project, boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(KEY_FOLLOW_AGENT_FILES, enabled, true);
    }

    // ── Per-tool permissions ─────────────────────────────────────────────────

    /**
     * Claude Code respects tool filtering, so built-in CLI tools default to ALLOW
     * (unlike Copilot where they must be DENY due to CLI bug #556).
     */
    private static ToolPermission defaultPermissionFor(@NotNull String toolId) {
        return ToolPermission.ALLOW;
    }

    public static boolean isToolEnabled(@NotNull String toolId) {
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolId);
        if (entry != null && entry.isBuiltIn) return true;
        return PropertiesComponent.getInstance().getBoolean(KEY_TOOL_ENABLED + toolId, true);
    }

    public static void setToolEnabled(@NotNull String toolId, boolean enabled) {
        PropertiesComponent.getInstance().setValue(KEY_TOOL_ENABLED + toolId, enabled, true);
    }

    @NotNull
    public static String getDisabledMcpToolIds() {
        StringBuilder sb = new StringBuilder();
        for (ToolRegistry.ToolEntry tool : ToolRegistry.getAllTools()) {
            if (!tool.isBuiltIn && !isToolEnabled(tool.id)) {
                if (!sb.isEmpty()) sb.append(',');
                sb.append(tool.id);
            }
        }
        return sb.toString();
    }

    @NotNull
    public static ToolPermission getToolPermission(@NotNull String toolId) {
        String stored = PropertiesComponent.getInstance().getValue(KEY_TOOL_PERM + toolId);
        if (stored == null) return defaultPermissionFor(toolId);
        try {
            return ToolPermission.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return defaultPermissionFor(toolId);
        }
    }

    public static void setToolPermission(@NotNull String toolId, @NotNull ToolPermission perm) {
        PropertiesComponent.getInstance().setValue(KEY_TOOL_PERM + toolId, perm.name());
    }

    @NotNull
    public static ToolPermission getToolPermissionInsideProject(@NotNull String toolId) {
        String stored = PropertiesComponent.getInstance().getValue(KEY_TOOL_PERM_IN + toolId);
        if (stored == null) return getToolPermission(toolId);
        try {
            return ToolPermission.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return getToolPermission(toolId);
        }
    }

    public static void setToolPermissionInsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        PropertiesComponent.getInstance().setValue(KEY_TOOL_PERM_IN + toolId, perm.name());
    }

    @NotNull
    public static ToolPermission getToolPermissionOutsideProject(@NotNull String toolId) {
        String stored = PropertiesComponent.getInstance().getValue(KEY_TOOL_PERM_OUT + toolId);
        if (stored == null) return getToolPermission(toolId);
        try {
            return ToolPermission.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return getToolPermission(toolId);
        }
    }

    public static void setToolPermissionOutsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        PropertiesComponent.getInstance().setValue(KEY_TOOL_PERM_OUT + toolId, perm.name());
    }

    @NotNull
    public static ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean isInsideProject) {
        ToolPermission top = getToolPermission(toolId);
        if (top != ToolPermission.ALLOW) return top;

        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolId);
        if (entry == null || !entry.supportsPathSubPermissions) return top;

        return isInsideProject
            ? getToolPermissionInsideProject(toolId)
            : getToolPermissionOutsideProject(toolId);
    }

    public static void clearToolSubPermissions(@NotNull String toolId) {
        PropertiesComponent.getInstance().unsetValue(KEY_TOOL_PERM_IN + toolId);
        PropertiesComponent.getInstance().unsetValue(KEY_TOOL_PERM_OUT + toolId);
    }
}
