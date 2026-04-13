package com.github.catatafishen.agentbridge.services;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
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

    private static final int DEFAULT_MAX_TOOL_CALLS = 0;
    private static final String TOOL_PERM_IN_PREFIX = "tool.perm.in.";
    private static final String TOOL_PERM_OUT_PREFIX = "tool.perm.out.";

    private final String prefix;
    private final Project project;
    private volatile String activeAgentLabel;

    /**
     * @param prefix settings key prefix (e.g., "copilot", "opencode").
     *               Keys are stored as {@code prefix.selectedModel}, {@code prefix.sessionMode}, etc.
     */
    public GenericSettings(@NotNull String prefix) {
        this(prefix, null);
    }

    /**
     * @param prefix  settings key prefix (e.g., "copilot", "opencode").
     * @param project optional project for project-level persistence.
     *                If null, uses application-level persistence.
     */
    public GenericSettings(@NotNull String prefix, @Nullable Project project) {
        this.prefix = prefix + ".";
        this.project = project;
    }

    private PropertiesComponent getProperties() {
        return project != null ? PropertiesComponent.getInstance(project) : PropertiesComponent.getInstance();
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
        String model = getProperties().getValue(key("selectedModel"));
        if (model == null || model.isEmpty()) {
            return activeAgentLabel;
        }
        return model;
    }

    public void setSelectedModel(@NotNull String modelId) {
        getProperties().setValue(key("selectedModel"), modelId);
    }

    // ── Agent selection ──────────────────────────────────────────────────────

    /**
     * Returns the selected agent name (e.g. "ide-explore"), or empty string for "Default".
     */
    @NotNull
    public String getSelectedAgent() {
        return getProperties().getValue(key("selectedAgent"), "");
    }

    public void setSelectedAgent(@NotNull String agentName) {
        getProperties().setValue(key("selectedAgent"), agentName, "");
    }

    // ── Session options ──────────────────────────────────────────────────────

    /**
     * Returns the persisted value for a session option (e.g. "effort"), or empty string.
     */
    @NotNull
    public String getSessionOptionValue(@NotNull String optionKey) {
        return getProperties().getValue(key("sessionOpt." + optionKey), "");
    }

    public void setSessionOptionValue(@NotNull String optionKey, @NotNull String value) {
        getProperties().setValue(key("sessionOpt." + optionKey), value, "");
    }

    // ── Active agent label (runtime-only) ────────────────────────────────────

    @Nullable
    public String getActiveAgentLabel() {
        return activeAgentLabel;
    }

    public void setActiveAgentLabel(@Nullable String label) {
        activeAgentLabel = label;
    }

    // ── Limits ───────────────────────────────────────────────────────────────

    // ── Context history limits ────────────────────────────────────────────────

    /**
     * Maximum total characters to export in conversation history when switching agents.
     * 0 means unlimited.
     *
     * @param defaultLimit the value to return when no override is stored
     */
    public int getContextHistoryLimit(int defaultLimit) {
        return getProperties().getInt(key("contextHistoryLimit"), defaultLimit);
    }

    public void setContextHistoryLimit(int limit) {
        getProperties().setValue(key("contextHistoryLimit"), limit, 0);
    }

    public int getMaxToolCallsPerTurn() {
        return getProperties().getInt(key("maxToolCallsPerTurn"), DEFAULT_MAX_TOOL_CALLS);
    }

    public void setMaxToolCallsPerTurn(int count) {
        getProperties().setValue(key("maxToolCallsPerTurn"), count, DEFAULT_MAX_TOOL_CALLS);
    }

    // ── Per-tool permissions (project-global, not per-profile) ──────────────

    @NotNull
    public ToolPermission getToolPermission(@NotNull String toolId) {
        return parseToolPermission(getProperties().getValue("tool.perm." + toolId), ToolPermission.ALLOW);
    }

    public void setToolPermission(@NotNull String toolId, @NotNull ToolPermission perm) {
        getProperties().setValue("tool.perm." + toolId, perm.name());
    }

    @NotNull
    public ToolPermission getToolPermissionInsideProject(@NotNull String toolId) {
        return parseToolPermission(getProperties().getValue(TOOL_PERM_IN_PREFIX + toolId), getToolPermission(toolId));
    }

    public void setToolPermissionInsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        getProperties().setValue(TOOL_PERM_IN_PREFIX + toolId, perm.name());
    }

    @NotNull
    public ToolPermission getToolPermissionOutsideProject(@NotNull String toolId) {
        return parseToolPermission(getProperties().getValue(TOOL_PERM_OUT_PREFIX + toolId), getToolPermission(toolId));
    }

    public void setToolPermissionOutsideProject(@NotNull String toolId, @NotNull ToolPermission perm) {
        getProperties().setValue(TOOL_PERM_OUT_PREFIX + toolId, perm.name());
    }

    @NotNull
    public ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean isInsideProject,
                                                     @NotNull ToolRegistry registry) {
        ToolPermission top = getToolPermission(toolId);
        ToolDefinition entry = registry.findById(toolId);
        boolean supportsSubPerms = entry != null && entry.supportsPathSubPermissions();
        return resolveEffective(top, supportsSubPerms, isInsideProject,
            getToolPermissionInsideProject(toolId), getToolPermissionOutsideProject(toolId));
    }

    /**
     * Parses a stored ToolPermission string. Returns defaultValue if null or invalid.
     * Pure function — no IDE dependency.
     */
    static ToolPermission parseToolPermission(@Nullable String stored, @NotNull ToolPermission defaultValue) {
        if (stored == null) return defaultValue;
        try {
            return ToolPermission.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    /**
     * Resolves the effective permission considering top-level, sub-permission support, and location.
     * Pure function — no IDE dependency.
     */
    static ToolPermission resolveEffective(@NotNull ToolPermission top, boolean supportsSubPerms,
                                           boolean isInsideProject,
                                           @NotNull ToolPermission insidePerm,
                                           @NotNull ToolPermission outsidePerm) {
        if (top != ToolPermission.ALLOW) return top;
        if (!supportsSubPerms) return top;
        return isInsideProject ? insidePerm : outsidePerm;
    }

    public void clearToolSubPermissions(@NotNull String toolId) {
        getProperties().unsetValue(TOOL_PERM_IN_PREFIX + toolId);
        getProperties().unsetValue(TOOL_PERM_OUT_PREFIX + toolId);
    }

    private static final String KEY_RESUME_SESSION_ID = "resumeSessionId";

    // ── Session resumption ───────────────────────────────────────────────────

    /**
     * Returns the ACP session ID to pass as {@code resumeSessionId} in the next {@code session/new}
     * request, or {@code null} if no previous session was saved.
     */
    @Nullable
    public String getResumeSessionId() {
        String val = getProperties().getValue(key(KEY_RESUME_SESSION_ID));
        return (val == null || val.isEmpty()) ? null : val;
    }

    /**
     * Persists the ACP session ID for future session resumption.
     * Pass {@code null} to clear (e.g. after a "Clear and Restart").
     */
    public void setResumeSessionId(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            getProperties().unsetValue(key(KEY_RESUME_SESSION_ID));
        } else {
            getProperties().setValue(key(KEY_RESUME_SESSION_ID), sessionId);
        }
    }

    // ── Billing persistence ──────────────────────────────────────────────────

    public int getMonthlyRequests() {
        return getProperties().getInt(key("monthlyRequests"), 0);
    }

    public void setMonthlyRequests(int count) {
        getProperties().setValue(key("monthlyRequests"), count, 0);
    }

    public double getMonthlyCost() {
        return parseDoubleSafe(getProperties().getValue(key("monthlyCost")));
    }

    /** Parses a double from a nullable string, returning 0.0 on null or parse failure. Pure function. */
    static double parseDoubleSafe(@Nullable String val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public void setMonthlyCost(double cost) {
        getProperties().setValue(key("monthlyCost"), String.valueOf(cost));
    }

    @NotNull
    public String getUsageResetMonth() {
        return getProperties().getValue(key("usageResetMonth"), "");
    }

    public void setUsageResetMonth(@NotNull String month) {
        getProperties().setValue(key("usageResetMonth"), month, "");
    }
}
