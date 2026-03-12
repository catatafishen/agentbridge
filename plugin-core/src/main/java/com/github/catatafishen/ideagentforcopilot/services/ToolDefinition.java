package com.github.catatafishen.ideagentforcopilot.services;

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
 * Implementations are created by subclassing {@link com.github.catatafishen.ideagentforcopilot.psi.tools.Tool}
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
     */
    default boolean isReadOnly() {
        return false;
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

    // ── Execution (optional — null for built-in agent tools) ─

    /**
     * Executes the tool with the given JSON arguments.
     * Returns null if this definition does not provide an execution handler
     * (e.g. built-in agent tools that are handled by the Copilot CLI).
     */
    default @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return null;
    }

    /**
     * Whether this definition provides an execution handler.
     */
    default boolean hasExecutionHandler() {
        return false;
    }
}
