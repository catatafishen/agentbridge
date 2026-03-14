package com.github.catatafishen.ideagentforcopilot.bridge;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    // ── sessionUpdate event protocol ─────────────────────────────────────────

    protected static final String FIELD_SESSION_UPDATE = "sessionUpdate";
    protected static final String SESSION_UPDATE_TOOL_CALL = "tool_call";
    protected static final String SESSION_UPDATE_TOOL_CALL_UPDATE = "tool_call_update";
    protected static final String SESSION_UPDATE_THOUGHT = "agent_thought_chunk";
    protected static final String STATUS_COMPLETED = "completed";
    protected static final String STATUS_FAILED = "failed";

    // ── Session state ────────────────────────────────────────────────────────

    /**
     * Per-session model override.
     */
    protected final Map<String, String> sessionModels = new ConcurrentHashMap<>();

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
    public boolean requiresResourceContentDuplication() {
        return false;
    }

    @Override
    public void setPermissionRequestListener(@Nullable Consumer<PermissionRequest> listener) {
        // Claude-based clients manage permissions outside the plugin.
    }

    @Override
    public void setSubAgentActive(boolean active) {
        // Not applicable — Claude manages its own sub-agent lifecycle.
    }

    @Override
    @NotNull
    public String getModelMultiplier(@NotNull String modelId) {
        return "1x";
    }

    @Override
    @Nullable
    public AuthMethod getAuthMethod() {
        return null;
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

    /**
     * Returns the UI kind string ({@code "read"}, {@code "edit"}, {@code "execute"},
     * {@code "search"}, or {@code "other"}) for a normalized tool name.
     */
    protected static String resolveToolKind(@NotNull String name) {
        String lower = name.toLowerCase();
        if (EXECUTE_TOOLS.contains(lower)
            || lower.startsWith("run_") || lower.startsWith("build_")) return "execute";
        if (lower.startsWith("write_") || lower.startsWith("create_") || lower.startsWith("delete_")
            || lower.startsWith("edit_") || lower.startsWith("move_") || lower.startsWith("rename_")
            || lower.startsWith("replace_") || lower.startsWith("insert_") || lower.startsWith("format_")
            || lower.startsWith("apply_") || lower.startsWith("suppress_") || lower.startsWith("intellij_write")
            || lower.startsWith("optimize_") || lower.startsWith("update_") || lower.startsWith("add_to")
            || lower.equals("undo") || lower.equals("redo") || lower.equals("refactor")) return "edit";
        if (lower.startsWith("search_") || lower.startsWith("find_")
            || lower.equals("grep") || lower.equals("glob")) return "search";
        if (lower.startsWith("read_") || lower.startsWith("get_") || lower.startsWith("list_")
            || lower.startsWith("intellij_read") || lower.equals("view")
            || lower.equals("web_fetch") || lower.equals("web_search")
            || lower.startsWith("git_status") || lower.startsWith("git_diff")
            || lower.startsWith("git_log") || lower.startsWith("git_blame")
            || lower.startsWith("git_show")) return "read";
        return "other";
    }

    // ── sessionUpdate emission ───────────────────────────────────────────────

    /**
     * Emits a {@code tool_call} sessionUpdate event to the UI layer.
     * The tool name is normalised (MCP prefix stripped) and the kind resolved
     * before the event is forwarded.
     *
     * @param toolUseId unique tool-call ID
     * @param toolName  raw tool name (may include MCP server prefix)
     * @param input     tool input arguments
     * @param onUpdate  update consumer (no-op if null)
     */
    protected void emitToolCallStart(@NotNull String toolUseId, @NotNull String toolName,
                                     @NotNull JsonObject input,
                                     @Nullable Consumer<JsonObject> onUpdate) {
        if (onUpdate == null) return;
        String normalized = normalizeToolName(toolName);
        JsonObject update = new JsonObject();
        update.addProperty(FIELD_SESSION_UPDATE, SESSION_UPDATE_TOOL_CALL);
        update.addProperty("toolCallId", toolUseId);
        update.addProperty("title", normalized);
        update.addProperty("status", "in_progress");
        update.addProperty("kind", resolveToolKind(normalized));
        update.add(FIELD_INPUT, input);
        onUpdate.accept(update);
    }

    /**
     * Emits a {@code tool_call_update} sessionUpdate event signalling tool completion.
     *
     * @param toolUseId unique tool-call ID (must match the corresponding {@link #emitToolCallStart})
     * @param result    result text (may be null)
     * @param success   whether the tool succeeded
     * @param onUpdate  update consumer (no-op if null)
     */
    protected void emitToolCallEnd(@NotNull String toolUseId, @Nullable String result,
                                   boolean success, @Nullable Consumer<JsonObject> onUpdate) {
        if (onUpdate == null) return;
        JsonObject update = new JsonObject();
        update.addProperty(FIELD_SESSION_UPDATE, SESSION_UPDATE_TOOL_CALL_UPDATE);
        update.addProperty("toolCallId", toolUseId);
        update.addProperty("status", success ? STATUS_COMPLETED : STATUS_FAILED);
        if (result != null) update.addProperty("result", result);
        onUpdate.accept(update);
    }

    /**
     * Emits an {@code agent_thought_chunk} sessionUpdate event carrying a thinking text chunk.
     *
     * @param text     thinking text to surface in the UI
     * @param onUpdate update consumer (no-op if null)
     */
    protected void emitThought(@NotNull String text, @Nullable Consumer<JsonObject> onUpdate) {
        if (onUpdate == null || text.isEmpty()) return;
        JsonObject update = new JsonObject();
        update.addProperty(FIELD_SESSION_UPDATE, SESSION_UPDATE_THOUGHT);
        JsonObject content = new JsonObject();
        content.addProperty("text", text);
        update.add(FIELD_CONTENT, content);
        onUpdate.accept(update);
    }
}
