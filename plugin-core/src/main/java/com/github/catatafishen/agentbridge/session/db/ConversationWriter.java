package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.services.hooks.HookStageResult;
import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.FileRef;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@link EntryData} batches to rows in the {@link ConversationDatabase}.
 *
 * <p>This is the single write path into the SQLite conversation history. During
 * Phase 1 of the JSONL→SQLite migration the writer runs in parallel with the
 * existing JSONL writes; once Phase 3 disables JSONL the writer becomes the
 * primary store.
 *
 * <p>Mapping rules (one row per {@link EntryData} subtype, joined to a parent
 * {@code events} row when applicable):
 * <ul>
 *   <li>{@link EntryData.Prompt} → opens a new {@code turns} row and becomes
 *       the "current turn" for the session.</li>
 *   <li>{@link EntryData.Text}/{@code Thinking}/{@code ToolCall}/{@code SubAgent}
 *       /{@code Nudge} (sent only) → append an {@code events} row plus the
 *       matching subtype row, linked to the current turn (or {@code NULL} for
 *       standalone tool calls).</li>
 *   <li>{@link EntryData.TurnStats} → finalises the current turn:
 *       fills in {@code ended_at} + totals, and inserts {@code commits} rows
 *       for any git hashes recorded.</li>
 *   <li>{@link EntryData.ContextFiles} → inserts {@code turn_context_files}
 *       rows for the current turn.</li>
 *   <li>{@link EntryData.Status}/{@code SessionSeparator} → ignored (UI-only,
 *       not part of the schema).</li>
 * </ul>
 *
 * <p>All write methods are best-effort: SQL failures are logged at WARN and
 * never propagate, mirroring the JSONL writer's behaviour. The writer is
 * thread-safe — every public entry point synchronises on the connection.
 */
public final class ConversationWriter {

    private static final Logger LOG = Logger.getInstance(ConversationWriter.class);

    private static final String INSERT_CONTEXT_FILE_SQL =
        "INSERT INTO turn_context_files (turn_id, file_name, file_path, file_line) VALUES (?, ?, ?, ?)";

    private final ConversationDatabase database;

    /** Per-session cursor: tracks the most recently opened turn for sequencing. */
    private final Map<String, SessionCursor> cursors = new HashMap<>();

    public ConversationWriter(@NotNull ConversationDatabase database) {
        this.database = database;
    }

    /**
     * Records a batch of entries appended to {@code sessionId} in writer order.
     *
     * @param sessionId session UUID (matches JSONL session id)
     * @param agentName display name of the writing agent (e.g. "GitHub Copilot")
     * @param clientId  ACP client identifier (e.g. "copilot", "opencode"); may be empty
     * @param entries   batch of entries in chronological order
     */
    public synchronized void recordEntries(
        @NotNull String sessionId,
        @NotNull String agentName,
        @NotNull String clientId,
        @NotNull List<EntryData> entries
    ) {
        if (entries.isEmpty()) return;
        Connection conn = database.getConnection();
        if (conn == null) {
            LOG.debug("ConversationDatabase not initialised — skipping write of "
                + entries.size() + " entries");
            return;
        }
        try {
            writeEntriesInTransaction(conn, sessionId, agentName, clientId, entries);
        } catch (SQLException e) {
            LOG.warn("ConversationWriter: failed to record " + entries.size()
                + " entries for session " + sessionId, e);
        }
    }

    private void writeEntriesInTransaction(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String agentName,
        @NotNull String clientId,
        @NotNull List<EntryData> entries
    ) throws SQLException {
        conn.setAutoCommit(false);
        try {
            ensureSession(conn, sessionId, agentName, clientId);
            for (EntryData entry : entries) {
                writeEntry(conn, sessionId, clientId, entry);
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ── Per-entry mapping ─────────────────────────────────────────────────────

    private void writeEntry(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String clientId,
        @NotNull EntryData entry
    ) throws SQLException {
        if (entry instanceof EntryData.Prompt p) {
            openTurn(conn, sessionId, p);
        } else if (entry instanceof EntryData.Text t) {
            insertEvent(conn, sessionId, t.getEntryId(), "text", t.getTimestamp(),
                t.getAgent(), t.getModel());
            insertSubtype(conn,
                "INSERT INTO text_events (event_id, content) VALUES (?, ?)",
                t.getEntryId(), t.getRaw());
        } else if (entry instanceof EntryData.Thinking th) {
            insertEvent(conn, sessionId, th.getEntryId(), "thinking", th.getTimestamp(),
                th.getAgent(), th.getModel());
            insertSubtype(conn,
                "INSERT INTO thinking_events (event_id, content) VALUES (?, ?)",
                th.getEntryId(), th.getRaw());
        } else if (entry instanceof EntryData.ToolCall tc) {
            insertEvent(conn, sessionId, tc.getEntryId(), "tool_call", tc.getTimestamp(),
                tc.getAgent(), tc.getModel());
            insertToolCall(conn, tc, clientId);
        } else if (entry instanceof EntryData.SubAgent sa) {
            insertEvent(conn, sessionId, sa.getEntryId(), "sub_agent", sa.getTimestamp(),
                sa.getAgent(), sa.getModel());
            insertSubAgent(conn, sa);
        } else if (entry instanceof EntryData.Nudge n) {
            writeNudge(conn, sessionId, n);
        } else if (entry instanceof EntryData.TurnStats ts) {
            finaliseTurn(conn, sessionId, ts);
        } else if (entry instanceof EntryData.ContextFiles cf) {
            insertContextFiles(conn, sessionId, cf.getFiles());
        }
        // Status / SessionSeparator are intentionally not persisted (UI markers only).
    }

    private void writeNudge(@NotNull Connection conn, @NotNull String sessionId,
                            @NotNull EntryData.Nudge n) throws SQLException {
        // Only persist sent nudges (pending nudges are transient UI state).
        if (!n.getSent()) return;
        insertEvent(conn, sessionId, n.getEntryId(), "nudge", n.getTimestamp(), "", "");
        insertSubtype(conn,
            "INSERT INTO nudge_events (event_id, text, nudge_id) VALUES (?, ?, ?)",
            n.getEntryId(), n.getText(), n.getId());
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    private void ensureSession(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String agentName,
        @NotNull String clientId
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO sessions (id, agent_name, client_id, started_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, agentName);
            ps.setString(3, clientId);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    // ── Turns ─────────────────────────────────────────────────────────────────

    private void openTurn(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull EntryData.Prompt prompt
    ) throws SQLException {
        String turnId = prompt.getEntryId();
        String startedAt = nonEmpty(prompt.getTimestamp(), Instant.now().toString());
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO turns (id, session_id, prompt_text, started_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, turnId);
            ps.setString(2, sessionId);
            ps.setString(3, prompt.getText());
            ps.setString(4, startedAt);
            ps.executeUpdate();
        }
        SessionCursor cursor = cursors.computeIfAbsent(sessionId, k -> new SessionCursor());
        cursor.turnId = turnId;
        cursor.sequenceNum = 0;

        // Persist any context files attached to this prompt.
        List<ContextFileRef> contextFiles = prompt.getContextFiles();
        if (contextFiles != null && !contextFiles.isEmpty()) {
            insertPromptContextFiles(conn, turnId, contextFiles);
        }
    }

    private void finaliseTurn(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull EntryData.TurnStats stats
    ) throws SQLException {
        String turnId = resolveTurnId(stats.getTurnId(), sessionId);
        if (turnId == null) return;
        updateTurnTotals(conn, turnId, stats);
        insertCommitHashes(conn, turnId, stats.getCommitHashes());
    }

    @Nullable
    private String resolveTurnId(@Nullable String explicitTurnId, @NotNull String sessionId) {
        if (explicitTurnId != null && !explicitTurnId.isEmpty()) {
            return explicitTurnId;
        }
        SessionCursor cursor = cursors.get(sessionId);
        return (cursor != null) ? cursor.turnId : null;
    }

    private void updateTurnTotals(
        @NotNull Connection conn,
        @NotNull String turnId,
        @NotNull EntryData.TurnStats stats
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            UPDATE turns SET
                ended_at        = COALESCE(?, ended_at),
                model           = COALESCE(?, model),
                token_multiplier= COALESCE(?, token_multiplier),
                input_tokens    = ?,
                output_tokens   = ?,
                cost_usd        = ?,
                duration_ms     = ?,
                tool_call_count = ?,
                lines_added     = ?,
                lines_removed   = ?
            WHERE id = ?
            """)) {
            ps.setString(1, nonEmpty(stats.getTimestamp(), Instant.now().toString()));
            ps.setString(2, emptyToNull(stats.getModel()));
            setMultiplier(ps, 3, stats.getMultiplier());
            ps.setLong(4, stats.getInputTokens());
            ps.setLong(5, stats.getOutputTokens());
            ps.setDouble(6, stats.getCostUsd());
            ps.setLong(7, stats.getDurationMs());
            ps.setInt(8, stats.getToolCallCount());
            ps.setInt(9, stats.getLinesAdded());
            ps.setInt(10, stats.getLinesRemoved());
            ps.setString(11, turnId);
            ps.executeUpdate();
        }
    }

    private static void setMultiplier(@NotNull PreparedStatement ps, int index,
                                      @Nullable String multiplier) throws SQLException {
        if (multiplier == null || multiplier.isEmpty()) {
            ps.setNull(index, Types.REAL);
            return;
        }
        try {
            ps.setDouble(index, Double.parseDouble(multiplier));
        } catch (NumberFormatException e) {
            ps.setNull(index, Types.REAL);
        }
    }

    private void insertCommitHashes(
        @NotNull Connection conn,
        @NotNull String turnId,
        @Nullable List<String> hashes
    ) throws SQLException {
        if (hashes == null || hashes.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO commits (turn_id, commit_hash) VALUES (?, ?)")) {
            ps.setString(1, turnId);
            for (String hash : hashes) {
                if (hash == null || hash.isEmpty()) continue;
                ps.setString(2, hash);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── Events + subtypes ─────────────────────────────────────────────────────

    private void insertEvent(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String eventId,
        @NotNull String eventType,
        @NotNull String timestamp,
        @NotNull String agentName,
        @NotNull String model
    ) throws SQLException {
        SessionCursor cursor = cursors.computeIfAbsent(sessionId, k -> new SessionCursor());
        int seq = cursor.nextSequence();
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO events "
                + "(id, turn_id, sequence_num, event_type, agent_name, model, timestamp) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, eventId);
            if (cursor.turnId == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, cursor.turnId);
            }
            ps.setInt(3, seq);
            ps.setString(4, eventType);
            ps.setString(5, emptyToNull(agentName));
            ps.setString(6, emptyToNull(model));
            ps.setString(7, nonEmpty(timestamp, Instant.now().toString()));
            ps.executeUpdate();
        }
    }

    private void insertSubtype(
        @NotNull Connection conn,
        @NotNull String sql,
        @NotNull String... params
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private void insertToolCall(
        @NotNull Connection conn,
        @NotNull EntryData.ToolCall tc,
        @NotNull String clientId
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT OR IGNORE INTO tool_call_events (
                event_id, tool_name, tool_kind, client_id, display_name,
                arguments, result, status, file_path, auto_denied, denial_reason, is_mcp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, tc.getEntryId());
            ps.setString(2, tc.getTitle());
            ps.setString(3, tc.getKind());
            ps.setString(4, emptyToNull(clientId));
            ps.setString(5, tc.getPluginTool());
            ps.setString(6, tc.getArguments());
            ps.setString(7, tc.getResult());
            ps.setString(8, tc.getStatus());
            ps.setString(9, tc.getFilePath());
            ps.setInt(10, tc.getAutoDenied() ? 1 : 0);
            ps.setString(11, tc.getDenialReason());
            ps.setInt(12, isMcpToolName(tc.getTitle()) ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /**
     * Detects whether a tool name belongs to our MCP server based on known prefixes.
     * Different ACP clients use different naming conventions for our tools.
     */
    private static boolean isMcpToolName(@Nullable String toolName) {
        if (toolName == null) return false;
        return toolName.startsWith("agentbridge-")
            || toolName.startsWith("agentbridge_")
            || toolName.startsWith("@agentbridge/");
    }

    private void insertSubAgent(
        @NotNull Connection conn,
        @NotNull EntryData.SubAgent sa
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT OR IGNORE INTO sub_agent_events (
                event_id, agent_type, description, prompt_text, result_text,
                status, call_id, auto_denied, denial_reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, sa.getEntryId());
            ps.setString(2, sa.getAgentType());
            ps.setString(3, sa.getDescription());
            ps.setString(4, sa.getPrompt());
            ps.setString(5, sa.getResult());
            ps.setString(6, sa.getStatus());
            ps.setString(7, sa.getCallId());
            ps.setInt(8, sa.getAutoDenied() ? 1 : 0);
            ps.setString(9, sa.getDenialReason());
            ps.executeUpdate();
        }
    }

    private void insertContextFiles(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull List<FileRef> files
    ) throws SQLException {
        SessionCursor cursor = cursors.get(sessionId);
        if (cursor == null || cursor.turnId == null) return;
        if (files.isEmpty()) return;
        String turnId = cursor.turnId;
        try (PreparedStatement ps = conn.prepareStatement(INSERT_CONTEXT_FILE_SQL)) {
            ps.setString(1, turnId);
            for (FileRef ref : files) {
                ps.setString(2, ref.getName());
                ps.setString(3, ref.getPath());
                ps.setInt(4, 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertPromptContextFiles(
        @NotNull Connection conn,
        @NotNull String turnId,
        @NotNull List<ContextFileRef> files
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_CONTEXT_FILE_SQL)) {
            ps.setString(1, turnId);
            for (ContextFileRef ref : files) {
                ps.setString(2, ref.getName());
                ps.setString(3, ref.getPath());
                ps.setInt(4, ref.getLine());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── MCP stats enrichment + hook executions ─────────────────────────────────

    /**
     * Enriches an existing tool-call event row with performance stats collected
     * at MCP dispatch time. Called from {@code McpProtocolHandler} after a tool
     * returns (or throws). This is a best-effort UPDATE — if the row doesn't
     * exist yet (ACP entry hasn't been written), it simply does nothing.
     *
     * @param toolUseId       the tool_use ID from the MCP request (maps to event_id)
     * @param inputSizeBytes  size of the arguments JSON in bytes
     * @param outputSizeBytes size of the result text in bytes
     * @param durationMs      total wall-clock time of the tool execution
     * @param success         whether the tool completed without error
     * @param errorMessage    error message if !success; null otherwise
     * @param category        tool category (e.g. "git", "file", "editor"); may be null
     */
    public synchronized void enrichToolCallStats(
        @NotNull String toolUseId,
        long inputSizeBytes,
        long outputSizeBytes,
        long durationMs,
        boolean success,
        @Nullable String errorMessage,
        @Nullable String category
    ) {
        Connection conn = database.getConnection();
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("""
            UPDATE tool_call_events SET
                input_size_bytes  = ?,
                output_size_bytes = ?,
                duration_ms       = ?,
                success           = ?,
                error_message     = COALESCE(?, error_message),
                category          = COALESCE(?, category),
                is_mcp            = 1
            WHERE event_id = ?
            """)) {
            ps.setLong(1, inputSizeBytes);
            ps.setLong(2, outputSizeBytes);
            ps.setLong(3, durationMs);
            ps.setInt(4, success ? 1 : 0);
            ps.setString(5, errorMessage);
            ps.setString(6, category);
            ps.setString(7, toolUseId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("ConversationWriter: failed to enrich stats for event " + toolUseId, e);
        }
    }

    /**
     * Marks an existing tool-call event as handled by our MCP server. Called from
     * the MCP dispatch boundary after a tool returns (or is denied).
     */
    public synchronized void markToolCallMcp(@NotNull String eventId) {
        Connection conn = database.getConnection();
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE tool_call_events SET is_mcp = 1 WHERE event_id = ?")) {
            ps.setString(1, eventId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("ConversationWriter: failed to mark MCP for event " + eventId, e);
        }
    }

    /**
     * Records hook stage results (from {@code McpProtocolHandler}'s hook evaluation)
     * as {@code hook_executions} rows linked to the given tool event.
     *
     * @param toolEventId the event_id of the tool call these hooks ran for
     * @param stages      collected hook stage results (permission, pre, success, failure)
     */
    public synchronized void recordHookStages(
        @NotNull String toolEventId,
        @NotNull List<HookStageResult> stages
    ) {
        if (stages.isEmpty()) return;
        Connection conn = database.getConnection();
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO hook_executions (
                tool_event_id, trigger_kind, entry_id, command,
                duration_ms, input_payload, output_payload, outcome, outcome_reason, timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            String now = Instant.now().toString();
            ps.setString(1, toolEventId);
            ps.setString(10, now);
            ps.setNull(6, Types.VARCHAR);
            ps.setNull(7, Types.VARCHAR);
            for (HookStageResult stage : stages) {
                ps.setString(2, stage.trigger());
                ps.setString(3, stage.scriptName());
                ps.setString(4, stage.scriptName());
                ps.setLong(5, stage.durationMs());
                ps.setString(8, stage.outcome());
                ps.setString(9, stage.detail());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.warn("ConversationWriter: failed to record hook stages for " + toolEventId, e);
        }
    }

    /**
     * Records a single hook execution with full detail (exit code, payloads).
     * Used when per-entry granularity is available (e.g. from a modified HookPipeline).
     */
    public synchronized void recordHookExecution(@NotNull HookExecutionRecord record) {
        Connection conn = database.getConnection();
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO hook_executions (
                tool_event_id, trigger_kind, entry_id, command, exit_code,
                duration_ms, input_payload, output_payload, outcome, outcome_reason, timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, record.toolEventId());
            ps.setString(2, record.triggerKind());
            ps.setString(3, record.entryId());
            ps.setString(4, record.command());
            if (record.exitCode() == null) ps.setNull(5, Types.INTEGER);
            else ps.setInt(5, record.exitCode());
            ps.setLong(6, record.durationMs());
            ps.setString(7, record.input());
            ps.setString(8, record.output());
            ps.setString(9, record.outcome());
            ps.setString(10, record.outcomeReason());
            ps.setString(11, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("ConversationWriter: failed to record hook execution for "
                + record.toolEventId(), e);
        }
    }

    /**
     * Full-detail hook execution record — used when per-entry granularity is available.
     */
    public record HookExecutionRecord(
        @NotNull String toolEventId,
        @NotNull String triggerKind,
        @NotNull String entryId,
        @Nullable String command,
        @Nullable Integer exitCode,
        long durationMs,
        @Nullable String input,
        @Nullable String output,
        @NotNull String outcome,
        @Nullable String outcomeReason
    ) {
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @NotNull
    private static String nonEmpty(@Nullable String value, @NotNull String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    /** Per-session position cursor: latest turn id and next event sequence number. */
    private static final class SessionCursor {
        @Nullable String turnId;
        int sequenceNum;

        int nextSequence() {
            return sequenceNum++;
        }
    }

    /**
     * Restores in-memory cursor state for a session — used at startup to resume
     * sequencing past existing rows. Reads {@code MAX(sequence_num)} for the most
     * recent turn and seeds the cursor accordingly.
     */
    public synchronized void restoreCursor(@NotNull String sessionId) {
        Connection conn = database.getConnection();
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM turns WHERE session_id = ? ORDER BY started_at DESC LIMIT 1")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                String turnId = rs.getString(1);
                SessionCursor cursor = cursors.computeIfAbsent(sessionId,
                    k -> new SessionCursor());
                cursor.turnId = turnId;
                try (PreparedStatement ps2 = conn.prepareStatement(
                    "SELECT COALESCE(MAX(sequence_num), -1) + 1 FROM events WHERE turn_id = ?")) {
                    ps2.setString(1, turnId);
                    try (ResultSet rs2 = ps2.executeQuery()) {
                        if (rs2.next()) cursor.sequenceNum = rs2.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("ConversationWriter: failed to restore cursor for " + sessionId, e);
        }
    }
}
