package com.github.catatafishen.ideagentforcopilot.services;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reusable settings storage for ACP agents, parameterized by key prefix.
 * Agents that don't need Copilot-specific extras (monthly cost tracking,
 * format-after-edit, etc.) use this instead of duplicating a full settings class.
 *
 * <p>Thread-safe: all reads/writes go through IntelliJ's PropertiesComponent.</p>
 */
public final class GenericSettings {

    private static final int DEFAULT_PROMPT_TIMEOUT = 300;
    private static final int DEFAULT_MAX_TOOL_CALLS = 0;
    private static final String TOOL_PERM_IN_PREFIX = "tool.perm.in.";
    private static final String TOOL_PERM_OUT_PREFIX = "tool.perm.out.";

    private final String prefix;
    private volatile String activeAgentLabel;

    /**
     * @param prefix settings key prefix (e.g., "copilot", "opencode").
     *               Keys are stored as {@code prefix.selectedModel}, {@code prefix.sessionMode}, etc.
     */
    public GenericSettings(@NotNull String prefix) {
        this.prefix = prefix + ".";
    }

    /**
     * Returns the full prefix (including trailing dot) used for key generation.
     */
    @NotNull
    public String getPrefix() {
        return prefix;
    }

    private String key(@NotNull String suffix) {
        return prefix + suffix;
    }

    // ── Model selection ──────────────────────────────────────────────────────

    @Nullable
    public String getSelectedModel() {
        return PropertiesComponent.getInstance().getValue(key("selectedModel"));
    }

    public void setSelectedModel(@NotNull String modelId) {
        PropertiesComponent.getInstance().setValue(key("selectedModel"), modelId);
    }

    // ── Agent selection ──────────────────────────────────────────────────────

    /**
     * Returns the selected agent name (e.g. "ide-explore"), or empty string for "Default".
     */
    @NotNull
    public String getSelectedAgent() {
        return PropertiesComponent.getInstance().getValue(key("selectedAgent"), "");
    }

    public void setSelectedAgent(@NotNull String agentName) {
        PropertiesComponent.getInstance().setValue(key("selectedAgent"), agentName, "");
    }

    // ── Session options ──────────────────────────────────────────────────────

    /**
     * Returns the persisted value for a session option (e.g. "effort"), or empty string.
     */
    @NotNull
    public String getSessionOptionValue(@NotNull String optionKey) {
        return PropertiesComponent.getInstance().getValue(key("sessionOpt." + optionKey), "");
    }

    public void setSessionOptionValue(@NotNull String optionKey, @NotNull String value) {
        PropertiesComponent.getInstance().setValue(key("sessionOpt." + optionKey), value, "");
    }

    // ── Active agent label (runtime-only) ────────────────────────────────────

    @Nullable
    public String getActiveAgentLabel() {
        return activeAgentLabel;
    }

    public void setActiveAgentLabel(@Nullable String label) {
        activeAgentLabel = label;
    }

    // ── Timeouts & limits ────────────────────────────────────────────────────

    public int getPromptTimeout() {
        return PropertiesComponent.getInstance().getInt(key("promptTimeout"), DEFAULT_PROMPT_TIMEOUT);
    }

    public void setPromptTimeout(int seconds) {
        PropertiesComponent.getInstance().setValue(key("promptTimeout"), seconds, DEFAULT_PROMPT_TIMEOUT);
    }

    public int getMaxToolCallsPerTurn() {
        return PropertiesComponent.getInstance().getInt(key("maxToolCallsPerTurn"), DEFAULT_MAX_TOOL_CALLS);
    }

    public void setMaxToolCallsPerTurn(int count) {
        PropertiesComponent.getInstance().setValue(key("maxToolCallsPerTurn"), count, DEFAULT_MAX_TOOL_CALLS);
    }

    // ── Per-tool permissions ─────────────────────────────────────────────────

    @NotNull
    public ToolPermission getToolPermission(@NotNull String toolId) {
        String stored = PropertiesComponent.getInstance().getValue(key("tool.perm." + toolId));
        if (stored == null) return ToolPermission.ALLOW;
        try {
            return ToolPermission.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return ToolPermission.ALLOW;
        }
    }

    public void setToolPermission(@NotNull String toolId, @NotNull ToolPermission perm) {
        PropertiesComponent.getInstance().setValue(key("tool.perm." + toolId), perm.name());
    }

    @NotNull
    public ToolPermission getToolPermissionInsideProject(@NotNull String toolId) {
        String stored = PropertiesComponent.getInstance().getValue(key(TOOL_PERM_IN_PREFIX + toolId));
        if (stored == null) return getToolPermission(toolId);
        try {
            return ToolPermission.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return getToolPermission(toolId);
        }
    }

    public void setToolPermissionInsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        PropertiesComponent.getInstance().setValue(key(TOOL_PERM_IN_PREFIX + toolId), perm.name());
    }

    @NotNull
    public ToolPermission getToolPermissionOutsideProject(@NotNull String toolId) {
        String stored = PropertiesComponent.getInstance().getValue(key(TOOL_PERM_OUT_PREFIX + toolId));
        if (stored == null) return getToolPermission(toolId);
        try {
            return ToolPermission.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return getToolPermission(toolId);
        }
    }

    public void setToolPermissionOutsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        PropertiesComponent.getInstance().setValue(key(TOOL_PERM_OUT_PREFIX + toolId), perm.name());
    }

    @NotNull
    public ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean isInsideProject,
                                                     @NotNull ToolRegistry registry) {
        ToolPermission top = getToolPermission(toolId);
        if (top != ToolPermission.ALLOW) return top;

        ToolDefinition entry = registry.findById(toolId);
        if (entry == null || !entry.supportsPathSubPermissions()) return top;

        return isInsideProject
            ? getToolPermissionInsideProject(toolId)
            : getToolPermissionOutsideProject(toolId);
    }

    public void clearToolSubPermissions(@NotNull String toolId) {
        PropertiesComponent.getInstance().unsetValue(key(TOOL_PERM_IN_PREFIX + toolId));
        PropertiesComponent.getInstance().unsetValue(key(TOOL_PERM_OUT_PREFIX + toolId));
    }

    // ── Billing persistence ──────────────────────────────────────────────────

    public int getMonthlyRequests() {
        return PropertiesComponent.getInstance().getInt(key("monthlyRequests"), 0);
    }

    public void setMonthlyRequests(int count) {
        PropertiesComponent.getInstance().setValue(key("monthlyRequests"), count, 0);
    }

    public double getMonthlyCost() {
        String val = PropertiesComponent.getInstance().getValue(key("monthlyCost"));
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public void setMonthlyCost(double cost) {
        PropertiesComponent.getInstance().setValue(key("monthlyCost"), String.valueOf(cost));
    }

    @NotNull
    public String getUsageResetMonth() {
        return PropertiesComponent.getInstance().getValue(key("usageResetMonth"), "");
    }

    public void setUsageResetMonth(@NotNull String month) {
        PropertiesComponent.getInstance().setValue(key("usageResetMonth"), month, "");
    }
}
