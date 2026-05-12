package com.github.catatafishen.agentbridge.session.db;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes structured SQL queries against {@link ConversationDatabase} and returns
 * typed {@link TurnSummary} records.
 *
 * <p>This is the query engine for {@code query_turns} MCP tool and the
 * {@code PromptsPanel} filter controls. It has no IntelliJ platform dependencies —
 * all logic is pure JDBC, making it trivially testable against an in-memory SQLite DB.
 *
 * <p>Thread-safety: all public methods synchronise on the database connection,
 * matching the pattern used by {@link ConversationReader}.
 */
public final class ConversationQuery {

    private static final Logger LOG = Logger.getInstance(ConversationQuery.class);

    private final ConversationDatabase database;

    public ConversationQuery(@NotNull ConversationDatabase database) {
        this.database = database;
    }

    // ── Records ───────────────────────────────────────────────────────────────

    /**
     * Input parameters for a conversation query. Use a builder or named construction.
     * All fields are optional except where noted. Null means "no constraint".
     */
    public record QueryParams(
        @Nullable String turnId,
        @Nullable String sessionId,
        @Nullable Integer lastN,
        @Nullable Integer offset,
        @Nullable String userMessage,
        @Nullable String assistantText,
        @Nullable String toolName,
        @Nullable String filePath,
        @Nullable String branch,
        @Nullable String agentName,
        @Nullable Instant since,
        @Nullable Instant until,
        boolean includeThinking,
        boolean includeToolCalls,
        int maxChars
    ) {
        /**
         * Default output: last 5 turns, prompt + assistant text only.
         */
        public static QueryParams lastN(int n) {
            return new QueryParams(null, null, n, null,
                null, null, null, null, null, null,
                null, null, false, false, 8000);
        }

        /**
         * Fetch a single turn by UUID with full content.
         */
        public static QueryParams byTurnId(String turnId) {
            return new QueryParams(turnId, null, null, null,
                null, null, null, null, null, null,
                null, null, false, false, 8000);
        }
    }

    /**
     * Single tool call within a turn, used when {@code includeToolCalls} is true.
     */
    public record ToolCallSummary(
        @NotNull String toolName,
        @Nullable String arguments,
        @Nullable String status,
        @Nullable Integer outputSizeBytes
    ) {
    }

    /**
     * A turn summary returned by {@link #query(QueryParams)}.
     */
    public record TurnSummary(
        @NotNull String turnId,
        @NotNull String sessionId,
        @Nullable String prevTurnId,
        @NotNull String agentName,
        @NotNull String agentDisplayName,
        @NotNull String userMessage,
        @NotNull List<String> humanNudges,
        @NotNull String assistantText,
        @NotNull String model,
        @NotNull String branch,
        @NotNull Instant timestamp,
        int toolCallCount,
        @NotNull List<ToolCallSummary> toolCalls,
        @NotNull List<String> thinkingBlocks
    ) {
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Executes a query and returns matching turns, most-recent-first.
     */
    @NotNull
    public List<TurnSummary> query(@NotNull QueryParams params) {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try {
                return queryInternal(conn, params);
            } catch (SQLException e) {
                LOG.warn("ConversationQuery: query failed", e);
                return List.of();
            }
        }
    }

    /**
     * Returns distinct branch names that have turns, for use in filter dropdowns.
     */
    @NotNull
    public List<String> listDistinctBranches() {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT git_branch_at_start FROM turns
                WHERE git_branch_at_start IS NOT NULL AND git_branch_at_start != ''
                ORDER BY git_branch_at_start
                """)) {
                ResultSet rs = ps.executeQuery();
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString(1));
                return result;
            } catch (SQLException e) {
                LOG.warn("ConversationQuery: failed to list branches", e);
                return List.of();
            }
        }
    }

    /**
     * Returns distinct agent names from sessions, for use in filter dropdowns.
     */
    @NotNull
    public List<String> listDistinctAgents() {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT s.agent_name FROM sessions s
                JOIN turns t ON t.session_id = s.id
                WHERE s.agent_name IS NOT NULL AND s.agent_name != ''
                ORDER BY s.agent_name
                """)) {
                ResultSet rs = ps.executeQuery();
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString(1));
                return result;
            } catch (SQLException e) {
                LOG.warn("ConversationQuery: failed to list agents", e);
                return List.of();
            }
        }
    }

    // ── Internal query ────────────────────────────────────────────────────────

    private List<TurnSummary> queryInternal(
        @NotNull Connection conn, @NotNull QueryParams p) throws SQLException {

        // Build WHERE clauses and parameter list dynamically.
        // Using a List<Object> for params and setting them positionally.
        List<String> whereClauses = new ArrayList<>();
        List<Object> sqlParams = new ArrayList<>();

        if (p.turnId() != null) {
            whereClauses.add("t.id = ?");
            sqlParams.add(p.turnId());
        }
        if (p.sessionId() != null) {
            whereClauses.add("t.session_id = ?");
            sqlParams.add(p.sessionId());
        }
        if (p.branch() != null) {
            whereClauses.add("t.git_branch_at_start LIKE ?");
            sqlParams.add(p.branch() + "%");
        }
        if (p.agentName() != null) {
            whereClauses.add("lower(s.agent_name) LIKE ?");
            sqlParams.add("%" + p.agentName().toLowerCase(Locale.ROOT) + "%");
        }
        if (p.since() != null) {
            whereClauses.add("t.started_at >= ?");
            sqlParams.add(p.since().toString());
        }
        if (p.until() != null) {
            whereClauses.add("t.started_at <= ?");
            sqlParams.add(p.until().toString());
        }
        if (p.userMessage() != null) {
            String likePattern = "%" + p.userMessage().toLowerCase(Locale.ROOT) + "%";
            whereClauses.add("""
                (lower(t.prompt_text) LIKE ?
                 OR EXISTS (
                   SELECT 1 FROM events e2
                   JOIN nudge_events ne ON e2.id = ne.event_id
                   WHERE e2.turn_id = t.id AND ne.source = 'human' AND lower(ne.text) LIKE ?
                 ))
                """);
            sqlParams.add(likePattern);
            sqlParams.add(likePattern);
        }
        if (p.assistantText() != null) {
            String likePattern = "%" + p.assistantText().toLowerCase(Locale.ROOT) + "%";
            whereClauses.add("""
                EXISTS (
                  SELECT 1 FROM events e3
                  JOIN text_events te ON e3.id = te.event_id
                  WHERE e3.turn_id = t.id AND lower(te.content) LIKE ?
                )
                """);
            sqlParams.add(likePattern);
        }
        if (p.toolName() != null) {
            String likePattern = "%" + p.toolName().toLowerCase(Locale.ROOT) + "%";
            whereClauses.add("""
                EXISTS (
                  SELECT 1 FROM events e4
                  JOIN tool_call_events tc ON e4.id = tc.event_id
                  WHERE e4.turn_id = t.id AND lower(tc.tool_name) LIKE ?
                )
                """);
            sqlParams.add(likePattern);
        }
        if (p.filePath() != null) {
            String likePattern = "%" + p.filePath().toLowerCase(Locale.ROOT) + "%";
            whereClauses.add("""
                EXISTS (
                  SELECT 1 FROM events e5
                  JOIN tool_call_events tc ON e5.id = tc.event_id
                  WHERE e5.turn_id = t.id AND lower(tc.file_path) LIKE ?
                )
                """);
            sqlParams.add(likePattern);
        }

        String whereClause = whereClauses.isEmpty()
            ? ""
            : "WHERE " + String.join(" AND ", whereClauses);

        String limitClause;
        if (p.lastN() != null) {
            limitClause = "LIMIT ? OFFSET ?";
            sqlParams.add(p.lastN());
            sqlParams.add(p.offset() != null ? p.offset() : 0);
        } else if (p.turnId() != null) {
            limitClause = ""; // WHERE t.id = ? already constrains to one row
        } else {
            // No explicit limit: apply a hard cap to prevent full-table scans
            limitClause = "LIMIT 500";
        }

        String sql = """
            SELECT t.id, t.session_id, t.prompt_text, t.started_at, t.model,
                   t.git_branch_at_start, t.tool_call_count,
                   s.agent_name, COALESCE(s.display_name, ''),
                   (SELECT id FROM turns
                     WHERE started_at < t.started_at
                        OR (started_at = t.started_at AND id < t.id)
                     ORDER BY started_at DESC, id DESC LIMIT 1) AS prev_turn_id
            FROM turns t
            JOIN sessions s ON t.session_id = s.id
            %s
            ORDER BY t.started_at DESC
            %s
            """.formatted(whereClause, limitClause);

        List<TurnSummary> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < sqlParams.size(); i++) {
                Object val = sqlParams.get(i);
                if (val instanceof String s) ps.setString(i + 1, s);
                else if (val instanceof Integer iv) ps.setInt(i + 1, iv);
                else ps.setObject(i + 1, val);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String turnId = rs.getString(1);
                    String sessionId = rs.getString(2);
                    String promptText = rs.getString(3);
                    String startedAt = rs.getString(4);
                    String model = nullToEmpty(rs.getString(5));
                    String branch = nullToEmpty(rs.getString(6));
                    int toolCallCount = rs.getInt(7);
                    String agentName = nullToEmpty(rs.getString(8));
                    String agentDisplayName = nullToEmpty(rs.getString(9));
                    String prevTurnId = rs.getString(10);
                    Instant timestamp = parseInstant(startedAt);

                    List<String> humanNudges = loadHumanNudges(conn, turnId);
                    String assistantText = loadAssistantText(conn, turnId);
                    List<String> thinkingBlocks = p.includeThinking()
                        ? loadThinkingBlocks(conn, turnId) : List.of();
                    List<ToolCallSummary> toolCalls = p.includeToolCalls()
                        ? loadToolCalls(conn, turnId) : List.of();

                    results.add(new TurnSummary(
                        turnId, sessionId, prevTurnId,
                        agentName, agentDisplayName,
                        promptText, humanNudges, assistantText,
                        model, branch, timestamp, toolCallCount,
                        toolCalls, thinkingBlocks
                    ));
                }
            }
        }
        return results;
    }

    // ── Event loaders ─────────────────────────────────────────────────────────

    @NotNull
    private List<String> loadHumanNudges(
        @NotNull Connection conn, @NotNull String turnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT ne.text FROM events e
            JOIN nudge_events ne ON e.id = ne.event_id
            WHERE e.turn_id = ? AND ne.source = 'human'
            ORDER BY e.sequence_num
            """)) {
            ps.setString(1, turnId);
            ResultSet rs = ps.executeQuery();
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString(1));
            return result;
        }
    }

    @NotNull
    private String loadAssistantText(
        @NotNull Connection conn, @NotNull String turnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT te.content FROM events e
            JOIN text_events te ON e.id = te.event_id
            WHERE e.turn_id = ?
            ORDER BY e.sequence_num
            """)) {
            ps.setString(1, turnId);
            ResultSet rs = ps.executeQuery();
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(rs.getString(1));
            }
            return sb.toString();
        }
    }

    @NotNull
    private List<String> loadThinkingBlocks(
        @NotNull Connection conn, @NotNull String turnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT te.content FROM events e
            JOIN thinking_events te ON e.id = te.event_id
            WHERE e.turn_id = ?
            ORDER BY e.sequence_num
            """)) {
            ps.setString(1, turnId);
            ResultSet rs = ps.executeQuery();
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString(1));
            return result;
        }
    }

    @NotNull
    private List<ToolCallSummary> loadToolCalls(
        @NotNull Connection conn, @NotNull String turnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT tc.tool_name, tc.arguments, tc.status, tc.output_size_bytes
            FROM events e
            JOIN tool_call_events tc ON e.id = tc.event_id
            WHERE e.turn_id = ?
            ORDER BY e.sequence_num
            """)) {
            ps.setString(1, turnId);
            ResultSet rs = ps.executeQuery();
            List<ToolCallSummary> result = new ArrayList<>();
            while (rs.next()) {
                int outputSize = rs.getInt(4);
                Integer outputSizeOrNull = rs.wasNull() ? null : outputSize;
                result.add(new ToolCallSummary(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    outputSizeOrNull
                ));
            }
            return result;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    @NotNull
    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    @NotNull
    private static Instant parseInstant(@Nullable String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isEmpty()) return Instant.EPOCH;
        try {
            return Instant.parse(isoTimestamp);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
