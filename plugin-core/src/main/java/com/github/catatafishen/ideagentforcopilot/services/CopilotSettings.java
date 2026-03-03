package com.github.catatafishen.ideagentforcopilot.services;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persists plugin settings using IntelliJ's PropertiesComponent.
 */
public final class CopilotSettings {
    private static final String KEY_SELECTED_MODEL = "copilot.selectedModel";

    /**
     * Runtime-only label for the currently active agent (e.g. "ui-reviewer").
     * Set by the UI layer when a sub-agent starts/stops; read by FileTools for inlay labels.
     * Null means the main agent is active (no sub-agent running).
     */
    private static volatile String activeAgentLabel;

    @Nullable
    public static String getActiveAgentLabel() {
        return activeAgentLabel;
    }

    public static void setActiveAgentLabel(@Nullable String label) {
        activeAgentLabel = label;
    }
    private static final String KEY_SESSION_MODE = "copilot.sessionMode";
    private static final String KEY_MONTHLY_REQUESTS = "copilot.monthlyRequests";
    private static final String KEY_MONTHLY_COST = "copilot.monthlyCost";
    private static final String KEY_USAGE_RESET_MONTH = "copilot.usageResetMonth";
    private static final String KEY_PROMPT_TIMEOUT = "copilot.promptTimeout";
    private static final String KEY_MAX_TOOL_CALLS = "copilot.maxToolCallsPerTurn";
    private static final String KEY_FOLLOW_AGENT_FILES = "copilot.followAgentFiles";
    private static final String KEY_FORMAT_AFTER_EDIT = "copilot.formatAfterEdit";
    private static final String KEY_BUILD_BEFORE_END = "copilot.buildBeforeEnd";
    private static final String KEY_TEST_BEFORE_END = "copilot.testBeforeEnd";
    private static final String KEY_COMMIT_BEFORE_END = "copilot.commitBeforeEnd";
    private static final int DEFAULT_PROMPT_TIMEOUT = 300;
    private static final int DEFAULT_MAX_TOOL_CALLS = 0;

    private CopilotSettings() {
    }

    /**
     * Inactivity timeout in seconds (no activity = stop agent).
     */
    public static int getPromptTimeout() {
        return PropertiesComponent.getInstance().getInt(KEY_PROMPT_TIMEOUT, DEFAULT_PROMPT_TIMEOUT);
    }

    public static void setPromptTimeout(int seconds) {
        PropertiesComponent.getInstance().setValue(KEY_PROMPT_TIMEOUT, seconds, DEFAULT_PROMPT_TIMEOUT);
    }

    /**
     * Max tool calls per turn (0 = unlimited).
     */
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

    /**
     * Returns the month string (YYYY-MM) for the last usage reset.
     */
    @NotNull
    public static String getUsageResetMonth() {
        return PropertiesComponent.getInstance().getValue(KEY_USAGE_RESET_MONTH, "");
    }

    public static void setUsageResetMonth(@NotNull String month) {
        PropertiesComponent.getInstance().setValue(KEY_USAGE_RESET_MONTH, month);
    }

    /**
     * Whether to open files in the editor when the agent reads/writes them.
     */
    public static boolean getFollowAgentFiles() {
        return PropertiesComponent.getInstance().getBoolean(KEY_FOLLOW_AGENT_FILES, false);
    }

    public static void setFollowAgentFiles(boolean enabled) {
        PropertiesComponent.getInstance().setValue(KEY_FOLLOW_AGENT_FILES, enabled, false);
    }

    public static boolean getFormatAfterEdit() {
        return PropertiesComponent.getInstance().getBoolean(KEY_FORMAT_AFTER_EDIT, true);
    }

    public static void setFormatAfterEdit(boolean enabled) {
        PropertiesComponent.getInstance().setValue(KEY_FORMAT_AFTER_EDIT, enabled, true);
    }

    public static boolean getBuildBeforeEnd() {
        return PropertiesComponent.getInstance().getBoolean(KEY_BUILD_BEFORE_END, false);
    }

    public static void setBuildBeforeEnd(boolean enabled) {
        PropertiesComponent.getInstance().setValue(KEY_BUILD_BEFORE_END, enabled, false);
    }

    public static boolean getTestBeforeEnd() {
        return PropertiesComponent.getInstance().getBoolean(KEY_TEST_BEFORE_END, false);
    }

    public static void setTestBeforeEnd(boolean enabled) {
        PropertiesComponent.getInstance().setValue(KEY_TEST_BEFORE_END, enabled, false);
    }

    public static boolean getCommitBeforeEnd() {
        return PropertiesComponent.getInstance().getBoolean(KEY_COMMIT_BEFORE_END, false);
    }

    public static void setCommitBeforeEnd(boolean enabled) {
        PropertiesComponent.getInstance().setValue(KEY_COMMIT_BEFORE_END, enabled, false);
    }

    // ── Per-tool permissions ─────────────────────────────────────────────────

    private static final String KEY_TOOL_PERM = "copilot.tool.perm.";
    private static final String KEY_TOOL_PERM_IN = "copilot.tool.perm.in.";
    private static final String KEY_TOOL_PERM_OUT = "copilot.tool.perm.out.";
    private static final String KEY_TOOL_ENABLED = "copilot.tool.enabled.";

    /**
     * Built-in CLI tools that have a permission hook default to DENY;
     * all others (MCP tools) default to ALLOW.
     */
    private static ToolPermission defaultPermissionFor(@NotNull String toolId) {
        return switch (toolId) {
            case "edit", "create", "execute", "runInTerminal" -> ToolPermission.DENY;
            default -> ToolPermission.ALLOW;
        };
    }

    /**
     * MCP tools are enabled by default; built-in tools cannot be disabled.
     */
    public static boolean isToolEnabled(@NotNull String toolId) {
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolId);
        if (entry != null && entry.isBuiltIn) return true; // can't disable built-ins
        return PropertiesComponent.getInstance().getBoolean(KEY_TOOL_ENABLED + toolId, true);
    }

    public static void setToolEnabled(@NotNull String toolId, boolean enabled) {
        PropertiesComponent.getInstance().setValue(KEY_TOOL_ENABLED + toolId, enabled, true);
    }

    /**
     * Returns a comma-separated list of MCP tool IDs that are currently disabled.
     * Used to pass --disabled-tools to the MCP server JAR at startup.
     */
    @NotNull
    public static String getDisabledMcpToolIds() {
        StringBuilder sb = new StringBuilder();
        for (ToolRegistry.ToolEntry tool : ToolRegistry.getAllTools()) {
            if (!tool.isBuiltIn && !isToolEnabled(tool.id)) {
                if (sb.length() > 0) sb.append(',');
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

    /**
     * Resolves the effective runtime permission for a tool, enforcing the top-level
     * as a ceiling over sub-permissions.
     * If the top-level is ASK or DENY, sub-permissions are irrelevant — the ceiling wins.
     * Sub-permissions only apply when the top-level is ALLOW.
     */
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

    /**
     * Clears stored sub-permissions for a tool, restoring fallback-to-top-level behavior.
     * Called when the top-level permission is set to something other than ALLOW.
     */
    public static void clearToolSubPermissions(@NotNull String toolId) {
        PropertiesComponent.getInstance().unsetValue(KEY_TOOL_PERM_IN + toolId);
        PropertiesComponent.getInstance().unsetValue(KEY_TOOL_PERM_OUT + toolId);
    }
}
