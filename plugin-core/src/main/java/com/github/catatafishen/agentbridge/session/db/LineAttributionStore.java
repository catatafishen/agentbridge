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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistence for prompt annotations: which turn (prompt) last wrote which lines of a file through
 * an editing tool call. One row is a <em>run</em> of consecutive lines with the same owner. The rows of
 * a file are always replaced as a set, together with a hash of the whole file at that moment.
 *
 * <p>Pure JDBC with no platform dependencies, so it is testable against in-memory SQLite.
 */
public final class LineAttributionStore {

    private static final Logger LOG = Logger.getInstance(LineAttributionStore.class);

    private final ConversationDatabase database;

    public LineAttributionStore(@NotNull ConversationDatabase database) {
        this.database = database;
    }

    public record RunRow(
        @NotNull String turnId,
        long timestampMs,
        @NotNull String kind,
        int startLine,
        int endLine,
        @NotNull String contentHash,
        boolean inferred
    ) {
    }

    public record Stored(@Nullable String fileHash, @NotNull List<RunRow> runs) {
    }

    /**
     * What the annotation shows about a turn.
     */
    public record TurnInfo(
        @NotNull String turnId,
        @NotNull String sessionId,
        @NotNull String prompt,
        @NotNull String agentName,
        @NotNull String sessionName,
        @Nullable String model,
        long startedAtMs
    ) {
    }

    /**
     * Replaces all runs of {@code path}. Runs whose turn is not (yet) in the {@code turns} table are
     * skipped, so a flush that is too early never violates the foreign key; the in-memory model still
     * has them and the next flush retries.
     *
     * @return the number of runs actually stored
     */
    public int replaceFile(@NotNull String path, @NotNull String fileHash, @NotNull List<RunRow> runs) {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return 0;
            try {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM line_attribution WHERE file_path = ?")) {
                        del.setString(1, path);
                        del.executeUpdate();
                    }
                    int stored = 0;
                    try (PreparedStatement ins = conn.prepareStatement("""
                        INSERT INTO line_attribution
                            (turn_id, file_path, timestamp, kind, start_line, end_line, content_hash,
                             file_hash, inferred)
                        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                        WHERE EXISTS (SELECT 1 FROM turns WHERE id = ?)""")) {
                        for (RunRow r : runs) {
                            ins.setString(1, r.turnId());
                            ins.setString(2, path);
                            ins.setString(3, Instant.ofEpochMilli(r.timestampMs()).toString());
                            ins.setString(4, r.kind());
                            ins.setInt(5, r.startLine());
                            ins.setInt(6, r.endLine());
                            ins.setString(7, r.contentHash());
                            ins.setString(8, fileHash);
                            ins.setInt(9, r.inferred() ? 1 : 0);
                            ins.setString(10, r.turnId());
                            stored += ins.executeUpdate();
                        }
                    }
                    conn.commit();
                    return stored;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                LOG.warn("LineAttributionStore: failed to store attribution for " + path, e);
                return 0;
            }
        }
    }

    @Nullable
    public Stored load(@NotNull String path) {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT turn_id, timestamp, kind, start_line, end_line, content_hash, file_hash, inferred
                FROM line_attribution WHERE file_path = ? ORDER BY start_line""")) {
                ps.setString(1, path);
                List<RunRow> runs = new ArrayList<>();
                String fileHash = null;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        runs.add(new RunRow(rs.getString(1), parseMs(rs.getString(2)), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getInt(8) != 0));
                        fileHash = rs.getString(7);
                    }
                }
                return runs.isEmpty() ? null : new Stored(fileHash, runs);
            } catch (SQLException e) {
                LOG.warn("LineAttributionStore: failed to load attribution for " + path, e);
                return null;
            }
        }
    }

    /**
     * A recorded, successful editing tool call on a file.
     */
    public record ToolEditRow(
        @NotNull String turnId,
        long timestampMs,
        @NotNull String toolName,
        @Nullable String arguments
    ) {
    }

    /**
     * Successful editing tool calls that touched {@code relativeOrAbsolutePath}, oldest first. Both the
     * project-relative and the absolute spelling are matched, because tools record whichever they were given.
     */
    @NotNull
    public List<ToolEditRow> editsForFile(@NotNull String relativePath, @NotNull String absolutePath) {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT e.turn_id, e.timestamp, tc.tool_name, tc.arguments
                FROM tool_call_events tc
                  JOIN events e ON e.id = tc.event_id
                WHERE tc.file_path IN (?, ?)
                  AND e.turn_id IS NOT NULL
                  AND tc.success = 1
                  AND tc.tool_name IN ('edit_text', 'write_file', 'create_file',
                                       'replace_symbol_body', 'insert_after_symbol', 'insert_before_symbol')
                ORDER BY e.timestamp""")) {
                ps.setString(1, relativePath);
                ps.setString(2, absolutePath);
                List<ToolEditRow> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ToolEditRow(rs.getString(1), parseMs(rs.getString(2)),
                            rs.getString(3), rs.getString(4)));
                    }
                }
                return rows;
            } catch (SQLException e) {
                LOG.warn("LineAttributionStore: failed to load recorded edits for " + absolutePath, e);
                return List.of();
            }
        }
    }

    /**
     * All files that currently have stored attribution.
     */
    @NotNull
    public Set<String> pathsWithAttribution() {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return Set.of();
            try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT file_path FROM line_attribution");
                 ResultSet rs = ps.executeQuery()) {
                Set<String> paths = new LinkedHashSet<>();
                while (rs.next()) paths.add(rs.getString(1));
                return paths;
            } catch (SQLException e) {
                LOG.warn("LineAttributionStore: failed to list attributed files", e);
                return Set.of();
            }
        }
    }

    /**
     * Files that still carry lines attributed to the given turn, with the number of such lines.
     */
    @NotNull
    public Map<String, Integer> filesForTurn(@NotNull String turnId) {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return Map.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT file_path, SUM(end_line - start_line + 1) FROM line_attribution
                WHERE turn_id = ? GROUP BY file_path ORDER BY file_path""")) {
                ps.setString(1, turnId);
                Map<String, Integer> files = new java.util.LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) files.put(rs.getString(1), rs.getInt(2));
                }
                return files;
            } catch (SQLException e) {
                LOG.warn("LineAttributionStore: failed to list files of turn " + turnId, e);
                return Map.of();
            }
        }
    }

    @NotNull
    public Map<String, TurnInfo> turnInfo(@NotNull Collection<String> turnIds) {
        if (turnIds.isEmpty()) return Map.of();
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return Map.of();
            String placeholders = String.join(",", java.util.Collections.nCopies(turnIds.size(), "?"));
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT t.id, t.session_id, t.prompt_text, s.agent_name, COALESCE(s.display_name, ''),
                       t.model, t.started_at
                FROM turns t JOIN sessions s ON s.id = t.session_id
                WHERE t.id IN (""" + placeholders + ")")) {
                int i = 1;
                for (String id : turnIds) ps.setString(i++, id);
                Map<String, TurnInfo> result = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), new TurnInfo(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            parseMs(rs.getString(7))));
                    }
                }
                return result;
            } catch (SQLException e) {
                LOG.warn("LineAttributionStore: failed to load turn info", e);
                return Map.of();
            }
        }
    }

    private static long parseMs(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
