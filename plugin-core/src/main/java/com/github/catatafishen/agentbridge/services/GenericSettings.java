package com.github.catatafishen.agentbridge.services;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Reusable settings storage for ACP agents, parameterized by key prefix.
 * Agents that don't need Copilot-specific extras (monthly cost tracking,
 * format-after-edit, etc.) use this instead of duplicating a full settings class.
 *
 * <p>Thread-safe: all reads/writes go through IntelliJ's PropertiesComponent.</p>
 */
public final class GenericSettings {

    private static final int DEFAULT_MAX_TOOL_CALLS = 0;
    private static final String OUTSIDE_PROJECT_ACCESS_KEY = "tool.outsideProjectAccess";

    // Key suffixes (after the profile prefix) of the options the user chose for an agent.
    private static final String SELECTED_MODEL = "selectedModel";
    private static final String SELECTED_AGENT = "selectedAgent";
    private static final String SESSION_MODE = "sessionMode";
    private static final String SESSION_OPTION_PREFIX = "sessionOpt.";
    private static final String CONTEXT_HISTORY_LIMIT = "contextHistoryLimit";
    private static final String MAX_TOOL_CALLS_PER_TURN = "maxToolCallsPerTurn";
    private static final String TOOL_PERMISSION_PREFIX = "tool.perm.";

    /**
     * The one session option every agent has a fixed key for. Agents that report their own options over ACP
     * use ids this class cannot know in advance, so those are not part of {@link #userChoiceKeys}.
     */
    private static final String EFFORT_OPTION_KEY = "effort";

    /**
     * Keys, for the agent {@code profileId}, that hold what the user chose (model, agent, mode, effort,
     * limits) as opposed to runtime state (resume id, billing counters). The global defaults copy exactly these.
     */
    @NotNull
    public static List<String> userChoiceKeys(@NotNull String profileId) {
        String p = profileId + ".";
        return List.of(
            p + SELECTED_MODEL,
            p + SELECTED_AGENT,
            p + SESSION_MODE,
            p + SESSION_OPTION_PREFIX + EFFORT_OPTION_KEY,
            p + CONTEXT_HISTORY_LIMIT,
            p + MAX_TOOL_CALLS_PER_TURN
        );
    }

    /**
     * The key holding the permission for {@code toolId}.
     */
    @NotNull
    public static String toolPermissionKey(@NotNull String toolId) {
        return TOOL_PERMISSION_PREFIX + toolId;
    }

    /**
     * The key holding the outside-project access policy.
     */
    @NotNull
    public static String outsideProjectAccessKey() {
        return OUTSIDE_PROJECT_ACCESS_KEY;
    }

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
        String model = getProperties().getValue(key(SELECTED_MODEL));
        if (model == null || model.isEmpty()) {
            return activeAgentLabel;
        }
        return model;
    }

    public void setSelectedModel(@NotNull String modelId) {
        getProperties().setValue(key(SELECTED_MODEL), modelId);
    }

    // ── Agent selection ──────────────────────────────────────────────────────

    /**
     * Returns the selected agent name (e.g. "ide-explore"), or empty string for "Default".
     */
    @NotNull
    public String getSelectedAgent() {
        return getProperties().getValue(key(SELECTED_AGENT), "");
    }

    public void setSelectedAgent(@NotNull String agentName) {
        getProperties().setValue(key(SELECTED_AGENT), agentName, "");
    }

    // ── Mode selection ───────────────────────────────────────────────────────

    /**
     * Returns the persisted mode slug (e.g. an ACP session mode), or empty string for the
     * agent's own default.
     */
    @NotNull
    public String getSelectedMode() {
        return getProperties().getValue(key(SESSION_MODE), "");
    }

    public void setSelectedMode(@NotNull String modeSlug) {
        getProperties().setValue(key(SESSION_MODE), modeSlug, "");
    }

    // ── Session options ──────────────────────────────────────────────────────

    /**
     * Returns the persisted value for a session option (e.g. "effort"), or empty string.
     */
    @NotNull
    public String getSessionOptionValue(@NotNull String optionKey) {
        return getProperties().getValue(key(SESSION_OPTION_PREFIX + optionKey), "");
    }

    public void setSessionOptionValue(@NotNull String optionKey, @NotNull String value) {
        getProperties().setValue(key(SESSION_OPTION_PREFIX + optionKey), value, "");
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
        return getProperties().getInt(key(CONTEXT_HISTORY_LIMIT), defaultLimit);
    }

    public void setContextHistoryLimit(int limit) {
        getProperties().setValue(key(CONTEXT_HISTORY_LIMIT), limit, 0);
    }

    public int getMaxToolCallsPerTurn() {
        return getProperties().getInt(key(MAX_TOOL_CALLS_PER_TURN), DEFAULT_MAX_TOOL_CALLS);
    }

    public void setMaxToolCallsPerTurn(int count) {
        getProperties().setValue(key(MAX_TOOL_CALLS_PER_TURN), count, DEFAULT_MAX_TOOL_CALLS);
    }

    // ── Per-tool permissions (project-global, not per-profile) ──────────────

    @NotNull
    public ToolPermission getToolPermission(@NotNull String toolId) {
        return parseToolPermission(getProperties().getValue(toolPermissionKey(toolId)), ToolPermission.ALLOW);
    }

    public void setToolPermission(@NotNull String toolId, @NotNull ToolPermission perm) {
        getProperties().setValue(toolPermissionKey(toolId), perm.name());
    }

    /**
     * The single, project-wide "outside-project access" policy: the permission applied to any
     * path-aware tool whose target path falls outside the project root. Defaults to
     * {@link ToolPermission#ALLOW} — i.e. no extra restriction beyond each tool's own permission.
     */
    @NotNull
    public ToolPermission getOutsideProjectAccess() {
        return parseToolPermission(getProperties().getValue(OUTSIDE_PROJECT_ACCESS_KEY), ToolPermission.ALLOW);
    }

    public void setOutsideProjectAccess(@NotNull ToolPermission perm) {
        getProperties().setValue(OUTSIDE_PROJECT_ACCESS_KEY, perm.name());
    }

    @NotNull
    public ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean isInsideProject,
                                                     @NotNull ToolRegistry registry) {
        ToolPermission base = getToolPermission(toolId);
        ToolDefinition entry = registry.findById(toolId);
        boolean pathAware = entry != null && entry.supportsPathSubPermissions();
        return resolveEffective(base, pathAware, isInsideProject, getOutsideProjectAccess());
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
     * Resolves the effective permission for a tool. Inside the project — or for a non-path tool —
     * the tool's own permission applies. Outside the project, a path-aware tool is additionally
     * subject to the global outside-project policy, taking whichever is stricter. Pure function.
     */
    static ToolPermission resolveEffective(@NotNull ToolPermission base, boolean pathAware,
                                           boolean isInsideProject, @NotNull ToolPermission outsidePolicy) {
        if (isInsideProject || !pathAware) {
            return base;
        }
        return base.stricterOf(outsidePolicy);
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
