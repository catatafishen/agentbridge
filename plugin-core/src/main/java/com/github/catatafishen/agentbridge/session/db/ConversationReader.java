package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads conversation history from the {@link ConversationDatabase} and
 * reconstructs {@link EntryData} objects from the normalised SQL schema.
 *
 * <p>This is the read counterpart to {@link ConversationWriter}. All methods
 * are pure JDBC — no IntelliJ platform dependencies — making this class
 * trivially testable against an in-memory SQLite database.
 *
 * <p>Thread-safety: all public methods synchronise on the database connection,
 * matching the writer's behaviour.
 */
public final class ConversationReader {

    private static final Logger LOG = Logger.getInstance(ConversationReader.class);

    private final ConversationDatabase database;

    public ConversationReader(@NotNull ConversationDatabase database) {
        this.database = database;
    }

    /**
     * Metadata record for a session, suitable for display in a session picker.
     *
     * <p>{@code lastActivity} is the most recent timestamp we can derive for the session:
     * {@code sessions.ended_at} if set, otherwise the latest turn end/start time, otherwise
     * {@code sessions.started_at}. It is always non-null and used as the display date in the
     * session picker.
     */
    public record SessionRecord(
        @NotNull String id,
        @NotNull String agentName,
        @NotNull String displayName,
        @NotNull String startedAt,
        @NotNull String lastActivity,
        int turnCount
    ) {
    }

    /**
     * A prompt paired with optional turn statistics from the same turn.
     */
    public record PromptWithStats(
        @NotNull String sessionId,
        @NotNull EntryData.Prompt prompt,
        @Nullable EntryData.TurnStats stats
    ) {
    }

    /**
     * Lists all sessions ordered by most recently started first.
     */
    @NotNull
    public synchronized List<SessionRecord> listSessions() {
        Connection conn = database.getConnection();
        if (conn == null) return List.of();
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT s.id, s.agent_name, COALESCE(s.display_name, ''), s.started_at,
                   COALESCE(
                       s.ended_at,
                       (SELECT MAX(COALESCE(t.ended_at, t.started_at)) FROM turns t WHERE t.session_id = s.id),
                       s.started_at
                   ) AS last_activity,
                   (SELECT COUNT(*) FROM turns WHERE session_id = s.id) AS turn_count
            FROM sessions s
            ORDER BY last_activity DESC
            """)) {
            ResultSet rs = ps.executeQuery();
            List<SessionRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new SessionRecord(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getInt(6)
                ));
            }
            return result;
        } catch (SQLException e) {
            LOG.warn("ConversationReader: failed to list sessions", e);
            return List.of();
        }
    }

    /**
     * Loads all entries for a session in chronological order, reconstructing
     * the full {@link EntryData} graph from the normalised schema.
     *
     * <p>Reconstruction order per turn: Prompt → events (by sequence_num) → TurnStats.
     * Context files are emitted as a {@link EntryData.ContextFiles} entry after the prompt.
     *
     * @return entries in chronological order, or empty list if session not found
     */
    @NotNull
    public synchronized List<EntryData> loadEntries(@NotNull String sessionId) {
        Connection conn = database.getConnection();
        if (conn == null) return List.of();
        try {
            return loadEntriesInternal(conn, sessionId);
        } catch (SQLException e) {
            LOG.warn("ConversationReader: failed to load entries for session " + sessionId, e);
            return List.of();
        }
    }

    /**
     * Loads entries for the most recent N turns of a session. Useful for
     * UI restore where only recent context is needed.
     */
    @NotNull
    public synchronized List<EntryData> loadRecentEntries(@NotNull String sessionId, int maxTurns) {
        Connection conn = database.getConnection();
        if (conn == null) return List.of();
        try {
            return loadRecentEntriesInternal(conn, sessionId, maxTurns);
        } catch (SQLException e) {
            LOG.warn("ConversationReader: failed to load recent entries for session " + sessionId, e);
            return List.of();
        }
    }

    /**
     * Loads prompts with their turn statistics from all sessions.
     * Ordered chronologically (oldest first).
     */
    @NotNull
    public synchronized List<PromptWithStats> loadAllPrompts() {
        Connection conn = database.getConnection();
        if (conn == null) return List.of();
        try {
            return loadAllPromptsInternal(conn);
        } catch (SQLException e) {
            LOG.warn("ConversationReader: failed to load all prompts", e);
            return List.of();
        }
    }

    /**
     * Loads all entries for a single turn by its turn ID.
     * Returns the prompt followed by all events in sequence order.
     */
    @NotNull
    public synchronized List<EntryData> loadTurnEntries(@NotNull String turnId) {
        Connection conn = database.getConnection();
        if (conn == null) return List.of();
        try {
            return loadTurnEntriesInternal(conn, turnId);
        } catch (SQLException e) {
            LOG.warn("ConversationReader: failed to load turn " + turnId, e);
            return List.of();
        }
    }

    /**
     * Returns ordered turn IDs for a session that are adjacent to a reference turn.
     * Negative {@code count} returns turns before the reference (earlier); positive returns after (later).
     */
    @NotNull
    public synchronized List<String> loadAdjacentTurnIds(
        @NotNull String sessionId, @NotNull String referenceTurnId, int count) {
        Connection conn = database.getConnection();
        if (conn == null) return List.of();
        try {
            return loadAdjacentTurnIdsInternal(conn, sessionId, referenceTurnId, count);
        } catch (SQLException e) {
            LOG.warn("ConversationReader: failed to load adjacent turns for " + referenceTurnId, e);
            return List.of();
        }
    }

    /**
     * Checks whether a session with the given ID exists in the database.
     */
    public synchronized boolean sessionExists(@NotNull String sessionId) {
        Connection conn = database.getConnection();
        if (conn == null) return false;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM sessions WHERE id = ?")) {
            ps.setString(1, sessionId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    // ── Internal query implementations ────────────────────────────────────────

    @NotNull
    private List<EntryData> loadEntriesInternal(
        @NotNull Connection conn, @NotNull String sessionId) throws SQLException {
        List<EntryData> result = new ArrayList<>();

        // Load turns in order
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id, prompt_text, started_at FROM turns WHERE session_id = ? ORDER BY started_at ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String turnId = rs.getString(1);
                    String promptText = rs.getString(2);
                    String startedAt = rs.getString(3);

                    // Reconstruct prompt with context files
                    List<ContextFileRef> ctxFiles = loadContextFiles(conn, turnId);
                    result.add(new EntryData.Prompt(promptText, startedAt,
                        ctxFiles.isEmpty() ? null : ctxFiles, turnId, turnId));

                    // Load events for this turn
                    loadEventsForTurn(conn, turnId, result);

                    // Emit TurnStats if turn is finalised
                    addTurnStatsIfPresent(conn, turnId, result);
                }
            }
        }
        return result;
    }

    @NotNull
    private List<EntryData> loadRecentEntriesInternal(
        @NotNull Connection conn, @NotNull String sessionId, int maxTurns) throws SQLException {
        List<EntryData> result = new ArrayList<>();

        // Get most recent N turn IDs
        List<String> turnIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM turns WHERE session_id = ? ORDER BY started_at DESC LIMIT ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, maxTurns);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) turnIds.add(rs.getString(1));
            }
        }
        // Reverse to chronological order
        java.util.Collections.reverse(turnIds);

        for (String turnId : turnIds) {
            // Load prompt
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT prompt_text, started_at FROM turns WHERE id = ?")) {
                ps.setString(1, turnId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        List<ContextFileRef> ctxFiles = loadContextFiles(conn, turnId);
                        result.add(new EntryData.Prompt(rs.getString(1), rs.getString(2),
                            ctxFiles.isEmpty() ? null : ctxFiles, turnId, turnId));
                    }
                }
            }
            loadEventsForTurn(conn, turnId, result);
            addTurnStatsIfPresent(conn, turnId, result);
        }
        return result;
    }

    @NotNull
    private List<PromptWithStats> loadAllPromptsInternal(@NotNull Connection conn) throws SQLException {
        List<PromptWithStats> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT t.id, t.session_id, t.prompt_text, t.started_at, t.ended_at,
                   t.model, t.token_multiplier, t.input_tokens, t.output_tokens,
                   t.cost_usd, t.duration_ms, t.tool_call_count, t.lines_added, t.lines_removed
            FROM turns t
            ORDER BY t.started_at ASC
            """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String turnId = rs.getString(1);
                    String sessionId = rs.getString(2);
                    String promptText = rs.getString(3);
                    String startedAt = rs.getString(4);

                    List<ContextFileRef> ctxFiles = loadContextFiles(conn, turnId);
                    EntryData.Prompt prompt = new EntryData.Prompt(promptText, startedAt,
                        ctxFiles.isEmpty() ? null : ctxFiles, turnId, turnId);

                    EntryData.TurnStats stats = reconstructTurnStats(rs, turnId);
                    result.add(new PromptWithStats(sessionId, prompt, stats));
                }
            }
        }
        return result;
    }

    @NotNull
    private List<EntryData> loadTurnEntriesInternal(
        @NotNull Connection conn, @NotNull String turnId) throws SQLException {
        List<EntryData> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id, prompt_text, started_at FROM turns WHERE id = ?")) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String promptText = rs.getString(2);
                    String startedAt = rs.getString(3);
                    List<ContextFileRef> ctxFiles = loadContextFiles(conn, turnId);
                    result.add(new EntryData.Prompt(promptText, startedAt,
                        ctxFiles.isEmpty() ? null : ctxFiles, turnId, turnId));
                    loadEventsForTurn(conn, turnId, result);
                    addTurnStatsIfPresent(conn, turnId, result);
                }
            }
        }
        return result;
    }

    @NotNull
    private List<String> loadAdjacentTurnIdsInternal(
        @NotNull Connection conn, @NotNull String sessionId,
        @NotNull String referenceTurnId, int count) throws SQLException {
        // Get the started_at of the reference turn for comparison
        String refStartedAt = null;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT started_at FROM turns WHERE id = ?")) {
            ps.setString(1, referenceTurnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) refStartedAt = rs.getString(1);
            }
        }
        if (refStartedAt == null) return List.of();

        List<String> ids = new ArrayList<>();
        if (count < 0) {
            // Earlier turns
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id FROM turns
                WHERE session_id = ? AND started_at < ?
                ORDER BY started_at DESC LIMIT ?
                """)) {
                ps.setString(1, sessionId);
                ps.setString(2, refStartedAt);
                ps.setInt(3, -count);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getString(1));
                }
            }
            java.util.Collections.reverse(ids);
        } else {
            // Later turns
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id FROM turns
                WHERE session_id = ? AND started_at > ?
                ORDER BY started_at ASC LIMIT ?
                """)) {
                ps.setString(1, sessionId);
                ps.setString(2, refStartedAt);
                ps.setInt(3, count);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    // ── Event loading ─────────────────────────────────────────────────────────

    private void loadEventsForTurn(
        @NotNull Connection conn, @NotNull String turnId,
        @NotNull List<EntryData> result) throws SQLException {

        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT id, event_type, agent_name, model, timestamp
            FROM events
            WHERE turn_id = ?
            ORDER BY sequence_num ASC
            """)) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String eventId = rs.getString(1);
                    String eventType = rs.getString(2);
                    String agent = nullToEmpty(rs.getString(3));
                    String model = nullToEmpty(rs.getString(4));
                    String timestamp = rs.getString(5);

                    EntryData entry = loadEventSubtype(conn, eventId, eventType, agent, model, timestamp);
                    if (entry != null) result.add(entry);
                }
            }
        }
    }

    @Nullable
    private EntryData loadEventSubtype(
        @NotNull Connection conn, @NotNull String eventId, @NotNull String eventType,
        @NotNull String agent, @NotNull String model, @NotNull String timestamp
    ) throws SQLException {
        return switch (eventType) {
            case "text" -> loadTextEvent(conn, eventId, agent, model, timestamp);
            case "thinking" -> loadThinkingEvent(conn, eventId, agent, model, timestamp);
            case "tool_call" -> loadToolCallEvent(conn, eventId, agent, model, timestamp);
            case "sub_agent" -> loadSubAgentEvent(conn, eventId, agent, model, timestamp);
            case "nudge" -> loadNudgeEvent(conn, eventId, timestamp);
            default -> null;
        };
    }

    @Nullable
    private EntryData loadTextEvent(
        @NotNull Connection conn, @NotNull String eventId,
        @NotNull String agent, @NotNull String model, @NotNull String timestamp
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT content FROM text_events WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new EntryData.Text(rs.getString(1), timestamp, agent, model, eventId);
            }
        }
    }

    @Nullable
    private EntryData loadThinkingEvent(
        @NotNull Connection conn, @NotNull String eventId,
        @NotNull String agent, @NotNull String model, @NotNull String timestamp
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT content FROM thinking_events WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new EntryData.Thinking(rs.getString(1), timestamp, agent, model, eventId);
            }
        }
    }

    @Nullable
    private EntryData loadToolCallEvent(
        @NotNull Connection conn, @NotNull String eventId,
        @NotNull String agent, @NotNull String model, @NotNull String timestamp
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT tool_name, arguments, tool_kind, result, status, file_path,
                   auto_denied, denial_reason, display_name
            FROM tool_call_events WHERE event_id = ?
            """)) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new EntryData.ToolCall(
                    rs.getString(1),  // title = tool_name
                    rs.getString(2),  // arguments
                    nullToEmpty(rs.getString(3)),  // kind
                    rs.getString(4),  // result
                    rs.getString(5),  // status
                    null,             // description
                    rs.getString(6),  // filePath
                    rs.getInt(7) == 1, // autoDenied
                    rs.getString(8),  // denialReason
                    rs.getString(9),  // pluginTool = display_name
                    timestamp, agent, model, eventId
                );
            }
        }
    }

    @Nullable
    private EntryData loadSubAgentEvent(
        @NotNull Connection conn, @NotNull String eventId,
        @NotNull String agent, @NotNull String model, @NotNull String timestamp
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT agent_type, description, prompt_text, result_text, status,
                   call_id, auto_denied, denial_reason
            FROM sub_agent_events WHERE event_id = ?
            """)) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new EntryData.SubAgent(
                    rs.getString(1),  // agentType
                    nullToEmpty(rs.getString(2)),  // description
                    rs.getString(3),  // prompt
                    rs.getString(4),  // result
                    rs.getString(5),  // status
                    0,                // colorIndex (UI-only, not persisted)
                    rs.getString(6),  // callId
                    rs.getInt(7) == 1, // autoDenied
                    rs.getString(8),  // denialReason
                    timestamp, agent, model, eventId
                );
            }
        }
    }

    @Nullable
    private EntryData loadNudgeEvent(
        @NotNull Connection conn, @NotNull String eventId, @NotNull String timestamp
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT text, nudge_id FROM nudge_events WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new EntryData.Nudge(
                    rs.getString(1),  // text
                    nullToEmpty(rs.getString(2)),  // nudge_id
                    true,             // sent (only sent nudges are persisted)
                    timestamp, eventId
                );
            }
        }
    }

    // ── Context files ─────────────────────────────────────────────────────────

    @NotNull
    private List<ContextFileRef> loadContextFiles(
        @NotNull Connection conn, @NotNull String turnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT file_name, file_path, file_line FROM turn_context_files WHERE turn_id = ?")) {
            ps.setString(1, turnId);
            List<ContextFileRef> files = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.add(new ContextFileRef(rs.getString(1), rs.getString(2), rs.getInt(3)));
                }
            }
            return files;
        }
    }

    // ── TurnStats reconstruction ──────────────────────────────────────────────

    private void addTurnStatsIfPresent(
        @NotNull Connection conn, @NotNull String turnId,
        @NotNull List<EntryData> result) throws SQLException {

        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT ended_at, model, token_multiplier, input_tokens, output_tokens,
                   cost_usd, duration_ms, tool_call_count, lines_added, lines_removed
            FROM turns WHERE id = ? AND ended_at IS NOT NULL
            """)) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                EntryData.TurnStats stats = reconstructTurnStats(rs, turnId);
                if (stats != null) result.add(stats);
            }
        }
        // Also load commit hashes
        List<String> hashes = loadCommitHashes(conn, turnId);
        if (!hashes.isEmpty()) {
            // The stats entry was already added; we need to replace it with one that has hashes.
            // Find the last TurnStats in result and replace it.
            for (int i = result.size() - 1; i >= 0; i--) {
                if (result.get(i) instanceof EntryData.TurnStats existingStats
                    && existingStats.getTurnId().equals(turnId)) {
                    result.set(i, new EntryData.TurnStats(
                        existingStats.getTurnId(),
                        existingStats.getDurationMs(),
                        existingStats.getInputTokens(),
                        existingStats.getOutputTokens(),
                        existingStats.getCostUsd(),
                        existingStats.getToolCallCount(),
                        existingStats.getLinesAdded(),
                        existingStats.getLinesRemoved(),
                        existingStats.getModel(),
                        existingStats.getMultiplier(),
                        0, 0, 0, 0.0, 0, 0, 0,
                        existingStats.getTimestamp(),
                        existingStats.getEntryId(),
                        hashes
                    ));
                    break;
                }
            }
        }
    }

    @Nullable
    private EntryData.TurnStats reconstructTurnStats(@NotNull ResultSet rs, @NotNull String turnId)
        throws SQLException {
        // For the allPrompts query, columns start at 5 (ended_at)
        // For the addTurnStatsIfPresent query, columns start at 1
        // We use column names to be safe
        String endedAt = rs.getString("ended_at");
        if (endedAt == null) return null;

        String model = nullToEmpty(rs.getString("model"));
        double multiplier = rs.getDouble("token_multiplier");
        String multiplierStr = rs.wasNull() ? "" : String.valueOf(multiplier);
        long inputTokens = rs.getLong("input_tokens");
        long outputTokens = rs.getLong("output_tokens");
        double costUsd = rs.getDouble("cost_usd");
        long durationMs = rs.getLong("duration_ms");
        int toolCallCount = rs.getInt("tool_call_count");
        int linesAdded = rs.getInt("lines_added");
        int linesRemoved = rs.getInt("lines_removed");

        return new EntryData.TurnStats(
            turnId, durationMs, inputTokens, outputTokens, costUsd,
            toolCallCount, linesAdded, linesRemoved, model, multiplierStr,
            0, 0, 0, 0.0, 0, 0, 0,
            endedAt, turnId + "-stats",
            List.of()
        );
    }

    @NotNull
    private List<String> loadCommitHashes(@NotNull Connection conn, @NotNull String turnId)
        throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT commit_hash FROM commits WHERE turn_id = ?")) {
            ps.setString(1, turnId);
            List<String> hashes = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) hashes.add(rs.getString(1));
            }
            return hashes;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @NotNull
    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
