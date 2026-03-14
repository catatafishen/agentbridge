package com.github.catatafishen.ideagentforcopilot.bridge;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared base for Claude-based {@link AgentClient} implementations.
 *
 * <p>Holds constants, session-lifecycle maps, and utility methods common to
 * {@link AnthropicDirectClient} (direct HTTPS) and {@link ClaudeCliClient}
 * (subprocess via {@code claude} CLI).
 */
abstract class AbstractClaudeAgentClient implements AgentClient {

    private static final Logger LOG = Logger.getInstance(AbstractClaudeAgentClient.class);

    // ── Claude model defaults ────────────────────────────────────────────────

    protected static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    // ── JSON field names ─────────────────────────────────────────────────────

    protected static final String FIELD_TYPE = "type";
    protected static final String FIELD_CONTENT = "content";
    protected static final String FIELD_INPUT = "input";

    // ── Session state ────────────────────────────────────────────────────────

    /**
     * Per-session model override.
     */
    protected final Map<String, String> sessionModels = new ConcurrentHashMap<>();

    /**
     * Per-session option overrides: sessionId → (optionKey → value).
     */
    private final Map<String, Map<String, String>> sessionOptions = new ConcurrentHashMap<>();

    /**
     * Per-session cancellation flag.
     */
    protected final Map<String, AtomicBoolean> sessionCancelled = new ConcurrentHashMap<>();

    /**
     * True after {@link #start()} completes successfully.
     */
    protected volatile boolean started = false;

    // ── AgentClient — shared concrete implementations ────────────────────────

    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) {
        sessionModels.put(sessionId, modelId);
        LOG.info("Model set to " + modelId + " for session " + sessionId);
    }

    @Override
    public void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        sessionOptions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
        LOG.info("Session option [" + key + "] set to '" + value + "' for session " + sessionId);
    }

    /**
     * Returns the current value of a session option, or {@code null} if not set.
     */
    @Nullable
    protected String getSessionOption(@NotNull String sessionId, @NotNull String key) {
        Map<String, String> opts = sessionOptions.get(sessionId);
        return opts != null ? opts.get(key) : null;
    }

    // ── Shared utilities ─────────────────────────────────────────────────────

    /**
     * Resolves the effective model for a session, preferring the per-call {@code model}
     * parameter, then the session-level override, then {@link #DEFAULT_MODEL}.
     */
    @NotNull
    protected String resolveModel(@NotNull String sessionId, @Nullable String model) {
        if (model != null && !model.isEmpty()) return model;
        String stored = sessionModels.get(sessionId);
        return (stored != null && !stored.isEmpty()) ? stored : DEFAULT_MODEL;
    }

    /**
     * Throws if {@link #start()} has not been called yet.
     */
    protected void ensureStarted() throws AcpException {
        if (!started) throw new AcpException(getClass().getSimpleName() + " not started", null, false);
    }

    // ── Tool name normalisation ──────────────────────────────────────────────

    /**
     * Strips MCP server-name prefixes from a raw tool name so the UI always
     * receives a clean, registry-compatible name.
     *
     * <p>Handles two formats:
     * <ul>
     *   <li>{@code mcp__server-name__tool_name} — Claude CLI double-underscore format</li>
     *   <li>{@code server-name-tool_name} / {@code server_name_tool_name} — dash/underscore format</li>
     * </ul>
     */
    protected static String normalizeToolName(@NotNull String name) {
        // Claude CLI format: mcp__server-name__tool_name
        if (name.startsWith("mcp__")) {
            int second = name.indexOf("__", 5);
            if (second > 0) return name.substring(second + 2);
        }
        // Dash/underscore format: intellij-code-tools-tool_name
        Matcher m = MCP_SERVER_PREFIX_PATTERN.matcher(name);
        return m.find() ? name.substring(m.end()) : name;
    }

    private static final Pattern MCP_SERVER_PREFIX_PATTERN =
        Pattern.compile("^(?i)(intellij[-_]code[-_]tools|github[-_]mcp[-_]server)[-_]");

    // ── Tool kind resolution ─────────────────────────────────────────────────

    private static final Set<String> EXECUTE_TOOLS = Set.of(
        "bash", "run_command", "run_in_terminal", "run_tests", "build_project",
        "run_configuration", "run_scratch_file", "run_inspections", "run_sonarqube_analysis",
        "run_qodana", "git_commit", "git_push", "git_pull", "git_merge", "git_rebase",
        "git_reset", "git_fetch", "git_stash", "git_cherry_pick", "git_tag"
    );

    protected static SessionUpdate.ToolKind resolveToolKind(@NotNull String name) {
        String lower = name.toLowerCase();
        if (EXECUTE_TOOLS.contains(lower)
            || lower.startsWith("run_") || lower.startsWith("build_")) return SessionUpdate.ToolKind.EXECUTE;
        if (lower.startsWith("write_") || lower.startsWith("create_") || lower.startsWith("delete_")
            || lower.startsWith("edit_") || lower.startsWith("move_") || lower.startsWith("rename_")
            || lower.startsWith("replace_") || lower.startsWith("insert_") || lower.startsWith("format_")
            || lower.startsWith("apply_") || lower.startsWith("suppress_") || lower.startsWith("intellij_write")
            || lower.startsWith("optimize_") || lower.startsWith("update_") || lower.startsWith("add_to")
            || lower.equals("undo") || lower.equals("redo") || lower.equals("refactor"))
            return SessionUpdate.ToolKind.EDIT;
        if (lower.startsWith("search_") || lower.startsWith("find_")
            || lower.equals("grep") || lower.equals("glob")) return SessionUpdate.ToolKind.SEARCH;
        if (lower.startsWith("read_") || lower.startsWith("get_") || lower.startsWith("list_")
            || lower.startsWith("intellij_read") || lower.equals("view")
            || lower.equals("web_fetch") || lower.equals("web_search")
            || lower.startsWith("git_status") || lower.startsWith("git_diff")
            || lower.startsWith("git_log") || lower.startsWith("git_blame")
            || lower.startsWith("git_show")) return SessionUpdate.ToolKind.READ;
        return SessionUpdate.ToolKind.OTHER;
    }

    // ── sessionUpdate emission ───────────────────────────────────────────────

    protected void emitToolCallStart(@NotNull String toolUseId, @NotNull String toolName,
                                     @NotNull JsonObject input,
                                     @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null) return;
        String normalized = normalizeToolName(toolName);
        String args = (!input.isEmpty()) ? input.toString() : null;
        onUpdate.accept(new SessionUpdate.ToolCall(toolUseId, normalized, resolveToolKind(normalized), args, List.of()));
    }

    protected void emitToolCallEnd(@NotNull String toolUseId, @NotNull String result,
                                   boolean isError,
                                   @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null) return;
        if (isError) {
            onUpdate.accept(new SessionUpdate.ToolCallUpdate(toolUseId, SessionUpdate.ToolCallStatus.FAILED, null, result));
        } else {
            onUpdate.accept(new SessionUpdate.ToolCallUpdate(toolUseId, SessionUpdate.ToolCallStatus.COMPLETED, result, null));
        }
    }

    private static final Pattern RATE_LIMIT_PATTERN =
        Pattern.compile("hit.*limit|rate.?limit|usage.*limit", Pattern.CASE_INSENSITIVE);

    /**
     * Returns {@code true} if the error text indicates a Claude usage/rate-limit error.
     */
    protected static boolean isRateLimitError(@NotNull String errorText) {
        return RATE_LIMIT_PATTERN.matcher(errorText).find();
    }

    protected void emitBannerEvent(@NotNull String message, @NotNull SessionUpdate.BannerLevel level,
                                   @NotNull SessionUpdate.ClearOn clearOn,
                                   @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null) return;
        onUpdate.accept(new SessionUpdate.Banner(message, level, clearOn));
    }

    /**
     * Convenience overload for the common rate-limit / usage-limit case.
     */
    protected void emitRateLimitBanner(@NotNull String message,
                                       @Nullable Consumer<SessionUpdate> onUpdate) {
        emitBannerEvent(message, SessionUpdate.BannerLevel.WARNING, SessionUpdate.ClearOn.NEXT_SUCCESS, onUpdate);
    }

    protected void emitThought(@NotNull String text, @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null || text.isEmpty()) return;
        onUpdate.accept(new SessionUpdate.AgentThought(text));
    }
}
