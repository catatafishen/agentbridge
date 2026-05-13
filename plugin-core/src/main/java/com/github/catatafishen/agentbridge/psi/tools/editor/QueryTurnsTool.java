package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.TimeArgParser;
import com.github.catatafishen.agentbridge.services.PromptDbService;
import com.github.catatafishen.agentbridge.session.db.ConversationQuery;
import com.github.catatafishen.agentbridge.session.db.ConversationService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Queries conversation history with SQL-backed filtering and returns structured turn summaries.
 *
 * <p>Default retrieval (no params): 5 most recent turns across all sessions.
 * Each turn is identified by a stable UUID — no fragile positional {@code t<N>} IDs.
 * Use {@code prev_turn_id} from any turn to navigate backwards in history.
 *
 * <p>Content filters search the SQLite conversation DB directly:
 * {@code user_message} matches both the user prompt text and any human-typed nudges;
 * {@code assistant_text} matches the assistant reply; {@code tool_name} and
 * {@code file_path} match tool-call events.
 */
public final class QueryTurnsTool extends EditorTool {

    private static final String PARAM_TURN_ID = "turn_id";
    private static final String PARAM_SESSION_ID = "session_id";
    private static final String PARAM_LAST_N = "last_n";
    private static final String PARAM_OFFSET = "offset";
    private static final String PARAM_USER_MESSAGE = "user_message";
    private static final String PARAM_ASSISTANT_TEXT = "assistant_text";
    private static final String PARAM_TOOL_NAME = "tool_name";
    private static final String PARAM_FILE_PATH = "file_path";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_AGENT_NAME = "agent_name";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_UNTIL = "until";
    private static final String PARAM_INCLUDE_THINKING = "include_thinking";
    private static final String PARAM_INCLUDE_TOOL_CALLS = "include_tool_calls";
    private static final String PARAM_MAX_CHARS = "max_chars";

    private static final DateTimeFormatter DISPLAY_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public QueryTurnsTool(@Nullable Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "query_turns";
    }

    @Override
    public @NotNull String displayName() {
        return "Query Turns";
    }

    @Override
    public @NotNull String description() {
        return """
            Query conversation history with SQL-backed filters. Returns structured turn summaries \
            with stable UUID identifiers — no fragile positional t<N> IDs.

            Default (no params): 5 most recent turns, prompt + assistant text only.

            Retrieval (pick at most one; omit for last 5):
            - turn_id: fetch a specific turn by UUID
            - session_id: fetch all turns in a session
            - last_n + offset: most recent N turns (across all sessions)

            Content filters (each is a case-insensitive substring match):
            - user_message: matches user prompt text AND human-typed nudges in that turn
            - assistant_text: matches assistant reply text
            - tool_name: turns where a tool matching this name was called (e.g. "read_file")
            - file_path: turns where a file matching this path was touched by a tool
            - branch: turns started on this git branch (prefix match)
            - agent_name: turns from sessions where agent name contains this (e.g. "copilot")
            - since / until: time range (flexible formats: "5m", "2h", "2026-05-10", ISO 8601)

            Output control (all off by default):
            - include_thinking: include model reasoning blocks
            - include_tool_calls: include tool name, arguments, and output size
            - max_chars: total character budget (default 8000)

            Navigation: each turn includes prev_turn_id (UUID of the next-older turn).
            To drill into a turn: query_turns(turn_id="uuid", include_tool_calls=true)
            To page backward: query_turns(last_n=5, offset=5) or use the prev_turn_id.""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.SEARCH;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_TURN_ID, TYPE_STRING,
                "Fetch a specific turn by UUID. Use prev_turn_id from a prior result to navigate."),
            Param.optional(PARAM_SESSION_ID, TYPE_STRING,
                "Fetch all turns in a specific session by UUID."),
            Param.optional(PARAM_LAST_N, TYPE_INTEGER,
                "Number of most-recent turns to return (default 5 when no other retrieval param)."),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER,
                "Skip N most-recent turns for pagination (use with last_n)."),
            Param.optional(PARAM_USER_MESSAGE, TYPE_STRING,
                "Filter: turns where user prompt OR any human nudge contains this text (case-insensitive)."),
            Param.optional(PARAM_ASSISTANT_TEXT, TYPE_STRING,
                "Filter: turns where the assistant reply contains this text (case-insensitive)."),
            Param.optional(PARAM_TOOL_NAME, TYPE_STRING,
                "Filter: turns where a tool matching this name was called (e.g. \"read_file\")."),
            Param.optional(PARAM_FILE_PATH, TYPE_STRING,
                "Filter: turns where a file matching this path was touched by any tool."),
            Param.optional(PARAM_BRANCH, TYPE_STRING,
                "Filter: turns started on this git branch (prefix match)."),
            Param.optional(PARAM_AGENT_NAME, TYPE_STRING,
                "Filter: turns from sessions where agent name contains this (e.g. \"copilot\", \"opencode\")."),
            Param.optional(PARAM_SINCE, TYPE_STRING,
                "Filter: turns since this time. Formats: \"5m\", \"2h\", \"2026-05-10\", ISO 8601."),
            Param.optional(PARAM_UNTIL, TYPE_STRING,
                "Filter: turns until this time. Same formats as since."),
            Param.optional(PARAM_INCLUDE_THINKING, TYPE_BOOLEAN,
                "Include model reasoning blocks (default false)."),
            Param.optional(PARAM_INCLUDE_TOOL_CALLS, TYPE_BOOLEAN,
                "Include tool names, arguments, and output sizes (default false)."),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER,
                "Total character budget for the response (default 8000).")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        ConversationService store = ConversationService.getInstance(project);

        String turnId = stringOrNull(args, PARAM_TURN_ID);
        String sessionId = stringOrNull(args, PARAM_SESSION_ID);
        Integer lastN = intOrNull(args, PARAM_LAST_N);
        Integer offset = intOrNull(args, PARAM_OFFSET);
        String userMessage = stringOrNull(args, PARAM_USER_MESSAGE);
        String assistantText = stringOrNull(args, PARAM_ASSISTANT_TEXT);
        String toolName = stringOrNull(args, PARAM_TOOL_NAME);
        String filePath = stringOrNull(args, PARAM_FILE_PATH);
        String branch = stringOrNull(args, PARAM_BRANCH);
        String agentName = stringOrNull(args, PARAM_AGENT_NAME);
        boolean includeThinking = boolOrDefault(args, PARAM_INCLUDE_THINKING, false);
        boolean includeToolCalls = boolOrDefault(args, PARAM_INCLUDE_TOOL_CALLS, false);
        int maxChars = Math.max(10, intOrDefault(args, PARAM_MAX_CHARS, 8000));

        Instant since;
        Instant until;
        try {
            since = parseTimestamp(args, PARAM_SINCE);
            until = parseTimestamp(args, PARAM_UNTIL);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        // Default to last_n=5 when no retrieval param is given.
        // Reject conflicting combinations where more than one retrieval selector is set.
        boolean hasRetrievalParam = turnId != null || sessionId != null || lastN != null;
        if (hasRetrievalParam) {
            int retrievalCount = (turnId != null ? 1 : 0) + (sessionId != null ? 1 : 0) + (lastN != null ? 1 : 0);
            if (retrievalCount > 1) {
                return "Error: Only one of turn_id, session_id, or last_n may be used at a time. Pick the one that matches your intent.";
            }
        }
        if (!hasRetrievalParam) lastN = 5;

        ConversationQuery.QueryParams params = new ConversationQuery.QueryParams(
            turnId, sessionId, lastN, offset,
            userMessage, assistantText, toolName, filePath, branch, agentName,
            since, until,
            includeThinking, includeToolCalls, maxChars,
            null, null
        );

        List<ConversationQuery.TurnSummary> turns = store.query(params);
        if (turns.isEmpty()) return "No turns found matching the given parameters.";

        if (ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            PromptDbService.getInstance(project).navigateToSearch(params);
        }

        return formatTurns(turns, maxChars);
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    @NotNull
    private static String formatTurns(
        @NotNull List<ConversationQuery.TurnSummary> turns, int maxChars) {
        StringBuilder sb = new StringBuilder();

        for (ConversationQuery.TurnSummary turn : turns) {
            if (sb.length() >= maxChars) break;

            // Header line
            sb.append("=== [turn_id:").append(turn.turnId()).append("] ");
            sb.append(formatInstant(turn.timestamp()));
            if (!turn.branch().isEmpty()) sb.append("  branch:").append(turn.branch());
            if (!turn.model().isEmpty()) sb.append("  model:").append(shortModelName(turn.model()));
            if (!turn.agentName().isEmpty()) sb.append("  agent:").append(turn.agentName());
            sb.append("  tools:").append(turn.toolCallCount());
            sb.append(" ===\n");

            // Session + navigation
            sb.append("[session:").append(turn.sessionId()).append("]");
            if (turn.prevTurnId() != null) {
                sb.append("  [prev_turn:").append(turn.prevTurnId()).append("]");
            }
            sb.append("\n");

            // User message
            sb.append(">>> ").append(turn.userMessage()).append("\n");

            // Human nudges appended under the prompt
            for (String nudge : turn.humanNudges()) {
                sb.append(">>> [nudge] ").append(nudge).append("\n");
            }

            sb.append("\n");

            // Assistant text
            if (!turn.assistantText().isEmpty()) {
                sb.append(turn.assistantText()).append("\n");
            }

            // Thinking blocks
            if (!turn.thinkingBlocks().isEmpty()) {
                for (String block : turn.thinkingBlocks()) {
                    sb.append("[thinking] ").append(block).append("\n");
                }
            }

            // Tool calls
            if (!turn.toolCalls().isEmpty()) {
                for (ConversationQuery.ToolCallSummary tc : turn.toolCalls()) {
                    sb.append("  → ").append(tc.toolName());
                    if (tc.arguments() != null && !tc.arguments().isEmpty()) {
                        String args = tc.arguments().replace("\n", " ");
                        if (args.length() > 120) args = args.substring(0, 117) + "...";
                        sb.append(" ").append(args);
                    }
                    if (tc.outputSizeBytes() != null) {
                        sb.append(" → ").append(tc.outputSizeBytes()).append(" bytes");
                    }
                    if (tc.status() != null && !tc.status().isEmpty()) {
                        sb.append(" [").append(tc.status()).append("]");
                    }
                    sb.append("\n");
                }
            }

            sb.append("---\n");
        }

        String result = sb.toString();
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars - 3) + "...";
        }
        return result;
    }

    @NotNull
    private static String formatInstant(@NotNull Instant instant) {
        if (instant.equals(Instant.EPOCH)) return "(unknown time)";
        return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DISPLAY_FMT);
    }

    @NotNull
    private static String shortModelName(@NotNull String model) {
        // Trim long model identifiers (e.g. "claude-sonnet-4-6-20250514" → "claude-sonnet")
        // Returns broad family buckets: "claude-sonnet", "claude-haiku", "gpt-4", etc.
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.contains("sonnet")) return "claude-sonnet";
        if (lower.contains("haiku")) return "claude-haiku";
        if (lower.contains("opus")) return "claude-opus";
        if (lower.contains("gpt-4")) return "gpt-4";
        if (lower.contains("gpt")) return "gpt";
        return model.length() > 30 ? model.substring(0, 30) : model;
    }

    // ── Parameter helpers ─────────────────────────────────────────────────────

    @Nullable
    private static String stringOrNull(@NotNull JsonObject args, @NotNull String key) {
        return args.has(key) ? args.get(key).getAsString() : null;
    }

    @Nullable
    private static Integer intOrNull(@NotNull JsonObject args, @NotNull String key) {
        return args.has(key) ? args.get(key).getAsInt() : null;
    }

    private static int intOrDefault(@NotNull JsonObject args, @NotNull String key, int def) {
        return args.has(key) ? args.get(key).getAsInt() : def;
    }

    private static boolean boolOrDefault(
        @NotNull JsonObject args, @NotNull String key, boolean def) {
        return args.has(key) ? args.get(key).getAsBoolean() : def;
    }

    @Nullable
    private static Instant parseTimestamp(
        @NotNull JsonObject args, @NotNull String key) {
        if (!args.has(key)) return null;
        String value = args.get(key).getAsString();
        try {
            return TimeArgParser.parseInstant(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid '" + key + "' value: " + e.getMessage());
        }
    }
}
