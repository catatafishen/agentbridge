package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Unified definition for a tool the agent can use.
 * <p>
 * Co-locates all metadata that was previously scattered across
 * {@link ToolRegistry} (entries), popup renderers, and the handler registration
 * in {@code PsiBridgeService}.
 * <p>
 * Implementations are created by subclassing {@link com.github.catatafishen.agentbridge.psi.tools.Tool}
 * for tools that need execution logic.
 */
public interface ToolDefinition {

    // ── Identity ─────────────────────────────────────────────

    /**
     * Unique tool identifier (e.g. {@code "git_push"}, {@code "intellij_write_file"}).
     */
    @NotNull
    String id();

    /**
     * Semantic kind used for UI coloring and Copilot agent-set filtering.
     * Each tool must explicitly declare its kind.
     */
    @NotNull Kind kind();

    /**
     * Semantic kind for a tool.
     * <p>
     * Used to color tool chips in the UI and to derive which tools belong
     * to which Copilot agent set (ALL / Explore / Edit).
     */
    enum Kind {
        /**
         * Read-only operation — included in ALL, Explore, and Edit agent sets.
         */
        READ,
        /**
         * Search/query operation (symbol lookup, text search, find references).
         * Rendered with the same color as READ in the UI.
         * Included in ALL, Explore, and Edit agent sets.
         */
        SEARCH,
        /**
         * Mutates IDE state (file edits, refactoring, settings) — included in ALL and Edit.
         */
        EDIT,
        /**
         * Deletes a file or resource. Rendered with the same color as EDIT in the UI.
         * Included in ALL and Edit agent sets.
         */
        DELETE,
        /**
         * Moves or renames a file or resource. Rendered with the same color as EDIT in the UI.
         * Included in ALL and Edit agent sets.
         */
        MOVE,
        /**
         * Mutates debug state (stepping, breakpoints). Rendered with the same color as EDIT in the UI.
         * Included in ALL only.
         */
        WRITE,
        /**
         * Runs external process or irreversible action — included in ALL only.
         */
        EXECUTE,
        /**
         * Fallback for tools that don't fit any other kind.
         */
        OTHER;

        /**
         * Lowercase wire value used for serialization and {@link ToolChipRegistry}.
         */
        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Human-readable name shown in the UI (e.g. "Git Push").
     */
    @NotNull
    String displayName();

    /**
     * One-line description shown as a tooltip in the settings panel.
     */
    @NotNull
    String description();

    /**
     * Functional category for grouping in settings and permissions UI.
     */
    @NotNull
    ToolRegistry.Category category();

    // ── Behavior flags ───────────────────────────────────────

    /**
     * True if this is a built-in agent tool (bash, edit, etc.) rather than
     * an MCP tool we provide. Built-in tools are excluded via
     * {@code excludedTools} in the session configuration.
     */
    default boolean isBuiltIn() {
        return false;
    }

    /**
     * True if this built-in tool fires a permission request that we can
     * intercept. Meaningless for MCP tools (always false).
     */
    default boolean hasDenyControl() {
        return false;
    }

    /**
     * True if the tool accepts a file path and supports inside-project /
     * outside-project sub-permissions.
     */
    default boolean supportsPathSubPermissions() {
        return false;
    }

    /**
     * True if the tool only reads data and never modifies state.
     * This value is exposed as the MCP {@code readOnlyHint} annotation to clients.
     */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * True if this tool must acquire the global write semaphore before executing.
     * Defaults to {@code !isReadOnly()}, but long-running execution tools (build, test, run-command)
     * should override this to return {@code false} so they do not block PSI-mutating tools for
     * their full execution duration (which can be minutes).
     */
    default boolean needsWriteLock() {
        return !isReadOnly();
    }

    /**
     * True if the tool can permanently delete or irreversibly modify data.
     */
    default boolean isDestructive() {
        return false;
    }

    /**
     * True if the tool interacts with systems outside the IDE
     * (network, external processes).
     */
    default boolean isOpenWorld() {
        return false;
    }

    /**
     * Whether this tool should be denied when called by a sub-agent.
     * Override to return {@code true} for tools that sub-agents must not use
     * (e.g., git write operations that bypass IntelliJ's VCS layer).
     */
    default boolean denyForSubAgent() {
        return false;
    }

    /**
     * Detect abuse patterns in a permission request for this tool.
     * Called during {@code session/request_permission} handling before the
     * normal allow/deny/ask flow.
     *
     * @param toolCall the {@code toolCall} POJO ({@link com.github.catatafishen.agentbridge.bridge.SessionUpdate.Protocol.ToolCall})
     *                 or JSON object from the permission request
     * @return a human-readable abuse description if detected, null if clean
     */
    default @Nullable String detectPermissionAbuse(@Nullable Object toolCall) {
        return null;
    }

    // ── Schema ───────────────────────────────────────────────

    /**
     * MCP input schema for this tool. Each tool class overrides this
     * to define its own schema inline.
     */
    default @Nullable JsonObject inputSchema() {
        return null;
    }

    // ── Permission question ──────────────────────────────────

    /**
     * Template for the permission question bubble, with {@code {param}}
     * placeholders that get substituted with actual argument values.
     * <p>
     * Example: {@code "Push to {remote} ({branch})"}
     * <p>
     * Returns null to use the generic "Can I use {displayName}?" question.
     */
    default @Nullable String permissionTemplate() {
        return null;
    }

    /**
     * Resolves a human-readable permission question for this tool with the given arguments.
     * Substitutes {@code {paramName}} placeholders in {@link #permissionTemplate()} with
     * the corresponding argument values.
     */
    default @Nullable String resolvePermissionQuestion(@Nullable JsonObject args) {
        String template = permissionTemplate();
        if (template == null) return null;
        if (args == null) return PermissionTemplateUtil.stripPlaceholders(template);
        String q = PermissionTemplateUtil.substituteArgs(template, args);
        return PermissionTemplateUtil.stripPlaceholders(q);
    }

    // ── MCP Annotations ────────────────────────────────────────

    /**
     * Returns MCP tool annotations built from this tool's metadata.
     */
    default @NotNull JsonObject mcpAnnotations() {
        JsonObject ann = new JsonObject();
        ann.addProperty("title", displayName());
        ann.addProperty("readOnlyHint", isReadOnly());
        ann.addProperty("destructiveHint", isDestructive());
        ann.addProperty("openWorldHint", isOpenWorld());
        return ann;
    }

    // ── Renderer (optional — for custom tool-result rendering in popups) ──

    /**
     * Returns a custom result renderer for this tool's output in tool-call popups.
     * Returns null to use the default monospace text fallback.
     */
    default @Nullable Object resultRenderer() {
        return null;
    }

    // ── Execution (optional — null for built-in agent tools) ─

    /**
     * Executes the tool with the given JSON arguments.
     * Returns null if this definition does not provide an execution handler
     * (e.g. built-in agent tools that are handled by the Copilot CLI).
     *
     * <p>The {@code throws Exception} declaration is intentional: this is an open extension
     * point — any tool implementation may need to propagate checked exceptions (ExecutionException,
     * InterruptedException, IOException, ReflectiveOperationException, …). Enumerating them all
     * here would not add value and would force every implementation to declare an equally long list.</p>
     */
    // Interface extension point — implementations may throw any checked exception; enumerating all
    // possibilities at the interface level would be more verbose without adding safety.
    @SuppressWarnings("java:S112")
    default @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return null;
    }

    /**
     * Executes the tool with the given JSON arguments and an optional hash for identification.
     * The hash can be used by the tool to update its own chip in the UI.
     */
    @SuppressWarnings("java:S112")
    default @Nullable String execute(@NotNull JsonObject args, @Nullable String argumentsHash) throws Exception {
        return execute(args);
    }

    /**
     * Whether this definition provides an execution handler.
     */
    default boolean hasExecutionHandler() {
        return false;
    }
}
