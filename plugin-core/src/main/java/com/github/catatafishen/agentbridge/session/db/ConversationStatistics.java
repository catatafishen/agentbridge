package com.github.catatafishen.agentbridge.session.db;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregation queries for usage statistics over the unified {@link ConversationDatabase}.
 *
 * <p>All methods synchronise on the {@link ConversationDatabase} instance and return
 * empty results when the database is not yet initialised. This replaces the now-deleted
 * {@code ToolCallStatisticsService} which maintained a separate {@code tool-stats.db}.
 *
 * <p>Turn-level statistics ({@link #queryDailyTurnStats}, {@link #queryBranchTotals})
 * are derived from the {@code turns} and {@code sessions} tables. Tool-level statistics
 * ({@link #queryToolAggregates}, {@link #querySummary}) are derived from
 * {@code tool_call_events} joined with {@code events}.
 */
public final class ConversationStatistics {

    private static final Logger LOG = Logger.getInstance(ConversationStatistics.class);

    private ConversationStatistics() {
    }

    // ── Record types ─────────────────────────────────────────────────────────

    /**
     * Aggregated turn statistics for a single (date, agent) bucket.
     */
    public record DailyTurnAggregate(
        @NotNull String date,
        @NotNull String agentId,
        int turns,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        long durationMs,
        int linesAdded,
        int linesRemoved,
        double premiumRequests
    ) {
    }

    /**
     * Aggregated turn statistics for a single git branch.
     */
    public record BranchAggregate(
        @NotNull String branch,
        @NotNull String firstDetectedDate,
        int turns,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        long durationMs,
        int linesAdded,
        int linesRemoved,
        double premiumRequests
    ) {
    }

    public record ToolAggregate(
        @NotNull String toolName,
        @Nullable String category,
        long callCount,
        long avgDurationMs,
        long totalInputBytes,
        long totalOutputBytes,
        long avgTotalBytes,
        long errorCount
    ) {
    }

    // ── Turn-level queries ───────────────────────────────────────────────────

    /**
     * Returns per-day, per-agent turn aggregates in the inclusive date range [{@code start}, {@code end}].
     * Dates are ISO-8601 strings (e.g. {@code "2025-01-15"}).
     */
    @NotNull
    public static List<DailyTurnAggregate> queryDailyTurnStats(
        @NotNull ConversationDatabase db,
        @Nullable String start,
        @Nullable String end) {

        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT date(t.started_at)                         AS date,
                       s.agent_name                               AS agent_id,
                       COUNT(*)                                   AS turns,
                       COALESCE(SUM(t.input_tokens),    0)        AS input_tokens,
                       COALESCE(SUM(t.output_tokens),   0)        AS output_tokens,
                       COALESCE(SUM(t.tool_call_count), 0)        AS tool_calls,
                       COALESCE(SUM(t.duration_ms),     0)        AS duration_ms,
                       COALESCE(SUM(t.lines_added),     0)        AS lines_added,
                       COALESCE(SUM(t.lines_removed),   0)        AS lines_removed,
                       COALESCE(SUM(COALESCE(t.token_multiplier, 1.0)), 0) AS premium_requests
                FROM turns t
                JOIN sessions s ON t.session_id = s.id
                WHERE (? IS NULL OR date(t.started_at) >= ?)
                  AND (? IS NULL OR date(t.started_at) <= ?)
                GROUP BY date(t.started_at), s.agent_name
                ORDER BY date(t.started_at), s.agent_name
                """)) {
                ps.setString(1, start);
                ps.setString(2, start);
                ps.setString(3, end);
                ps.setString(4, end);
                List<DailyTurnAggregate> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new DailyTurnAggregate(
                            rs.getString("date"),
                            rs.getString("agent_id"),
                            rs.getInt("turns"),
                            rs.getLong("input_tokens"),
                            rs.getLong("output_tokens"),
                            rs.getInt("tool_calls"),
                            rs.getLong("duration_ms"),
                            rs.getInt("lines_added"),
                            rs.getInt("lines_removed"),
                            rs.getDouble("premium_requests")
                        ));
                    }
                }
                return result;
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to query daily turn stats", e);
                return List.of();
            }
        }
    }

    /**
     * Returns per-branch turn aggregates in the inclusive date range [{@code start}, {@code end}].
     * Turns with no git branch at start are excluded — use {@link #countUnattributedTurns} for those.
     */
    @NotNull
    public static List<BranchAggregate> queryBranchTotals(
        @NotNull ConversationDatabase db,
        @Nullable String start,
        @Nullable String end) {

        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT t.git_branch_at_start                          AS branch,
                       MIN(date(t.started_at))                        AS first_detected,
                       COUNT(*)                                        AS turns,
                       COALESCE(SUM(t.input_tokens),    0)            AS input_tokens,
                       COALESCE(SUM(t.output_tokens),   0)            AS output_tokens,
                       COALESCE(SUM(t.tool_call_count), 0)            AS tool_calls,
                       COALESCE(SUM(t.duration_ms),     0)            AS duration_ms,
                       COALESCE(SUM(t.lines_added),     0)            AS lines_added,
                       COALESCE(SUM(t.lines_removed),   0)            AS lines_removed,
                       COALESCE(SUM(COALESCE(t.token_multiplier, 1.0)), 0) AS premium_requests
                FROM turns t
                WHERE t.git_branch_at_start IS NOT NULL
                  AND (? IS NULL OR date(t.started_at) >= ?)
                  AND (? IS NULL OR date(t.started_at) <= ?)
                GROUP BY t.git_branch_at_start
                ORDER BY turns DESC
                """)) {
                ps.setString(1, start);
                ps.setString(2, start);
                ps.setString(3, end);
                ps.setString(4, end);
                List<BranchAggregate> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new BranchAggregate(
                            rs.getString("branch"),
                            rs.getString("first_detected"),
                            rs.getInt("turns"),
                            rs.getLong("input_tokens"),
                            rs.getLong("output_tokens"),
                            rs.getInt("tool_calls"),
                            rs.getLong("duration_ms"),
                            rs.getInt("lines_added"),
                            rs.getInt("lines_removed"),
                            rs.getDouble("premium_requests")
                        ));
                    }
                }
                return result;
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to query branch totals", e);
                return List.of();
            }
        }
    }

    /**
     * Returns the number of turns in [{@code start}, {@code end}] that have no git branch attribution.
     */
    public static int countUnattributedTurns(
        @NotNull ConversationDatabase db,
        @Nullable String start,
        @Nullable String end) {

        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return 0;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM turns
                WHERE git_branch_at_start IS NULL
                  AND (? IS NULL OR date(started_at) >= ?)
                  AND (? IS NULL OR date(started_at) <= ?)
                """)) {
                ps.setString(1, start);
                ps.setString(2, start);
                ps.setString(3, end);
                ps.setString(4, end);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to count unattributed turns", e);
                return 0;
            }
        }
    }

    /**
     * Returns the total number of turns recorded in the database.
     * Used to decide whether the SQLite path has data or JSONL fallback is needed.
     */
    public static int getTurnCount(@NotNull ConversationDatabase db) {
        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return 0;
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM turns")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to get turn count", e);
                return 0;
            }
        }
    }

    /**
     * Returns the date of the earliest recorded turn, or {@code null} if the table is empty.
     */
    @Nullable
    public static LocalDate getEarliestTurnDate(@NotNull ConversationDatabase db) {
        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return null;
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MIN(date(started_at)) FROM turns")) {
                if (rs.next()) {
                    String raw = rs.getString(1);
                    return raw != null ? LocalDate.parse(raw) : null;
                }
                return null;
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to get earliest turn date", e);
                return null;
            }
        }
    }

    /**
     * Returns the distinct agent names that appear in the sessions table.
     */
    @NotNull
    public static Set<String> getDistinctAgents(@NotNull ConversationDatabase db) {
        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return Set.of();
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT agent_name FROM sessions ORDER BY agent_name")) {
                Set<String> result = new LinkedHashSet<>();
                while (rs.next()) result.add(rs.getString(1));
                return result;
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to get distinct agents", e);
                return Set.of();
            }
        }
    }

    // ── Tool-level queries ───────────────────────────────────────────────────

    @NotNull
    public static List<ToolAggregate> queryToolAggregates(
        @NotNull ConversationDatabase db,
        @Nullable String since,
        @Nullable String clientId) {

        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT tce.tool_name,
                       tce.category,
                       COUNT(*)                                               AS call_count,
                       ROUND(AVG(tce.duration_ms))                           AS avg_duration_ms,
                       COALESCE(SUM(tce.input_size_bytes),  0)               AS total_input_bytes,
                       COALESCE(SUM(tce.output_size_bytes), 0)               AS total_output_bytes,
                       ROUND(AVG(tce.input_size_bytes + tce.output_size_bytes)) AS avg_total_bytes,
                       SUM(CASE WHEN tce.success = 0 THEN 1 ELSE 0 END)     AS error_count
                FROM tool_call_events tce
                JOIN events e ON tce.event_id = e.id
                WHERE (? IS NULL OR e.timestamp >= ?)
                  AND (? IS NULL OR tce.client_id = ?)
                GROUP BY tce.tool_name, tce.category
                ORDER BY call_count DESC
                """)) {
                ps.setString(1, since);
                ps.setString(2, since);
                ps.setString(3, clientId);
                ps.setString(4, clientId);
                List<ToolAggregate> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ToolAggregate(
                            rs.getString("tool_name"),
                            rs.getString("category"),
                            rs.getLong("call_count"),
                            rs.getLong("avg_duration_ms"),
                            rs.getLong("total_input_bytes"),
                            rs.getLong("total_output_bytes"),
                            rs.getLong("avg_total_bytes"),
                            rs.getLong("error_count")
                        ));
                    }
                }
                return result;
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to query tool aggregates", e);
                return List.of();
            }
        }
    }

    /**
     * Returns a summary map with keys: {@code totalCalls}, {@code totalDurationMs},
     * {@code totalInputBytes}, {@code totalOutputBytes}, {@code totalErrors}.
     */
    @NotNull
    public static Map<String, Long> querySummary(
        @NotNull ConversationDatabase db,
        @Nullable String since,
        @Nullable String clientId) {

        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return Map.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*)                                          AS total_calls,
                       COALESCE(SUM(tce.duration_ms),       0)          AS total_duration,
                       COALESCE(SUM(tce.input_size_bytes),  0)          AS total_input,
                       COALESCE(SUM(tce.output_size_bytes), 0)          AS total_output,
                       SUM(CASE WHEN tce.success = 0 THEN 1 ELSE 0 END) AS total_errors
                FROM tool_call_events tce
                JOIN events e ON tce.event_id = e.id
                WHERE (? IS NULL OR e.timestamp >= ?)
                  AND (? IS NULL OR tce.client_id = ?)
                """)) {
                ps.setString(1, since);
                ps.setString(2, since);
                ps.setString(3, clientId);
                ps.setString(4, clientId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Long> summary = new LinkedHashMap<>();
                        summary.put("totalCalls", rs.getLong("total_calls"));
                        summary.put("totalDurationMs", rs.getLong("total_duration"));
                        summary.put("totalInputBytes", rs.getLong("total_input"));
                        summary.put("totalOutputBytes", rs.getLong("total_output"));
                        summary.put("totalErrors", rs.getLong("total_errors"));
                        return summary;
                    }
                    return Map.of();
                }
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to query tool summary", e);
                return Map.of();
            }
        }
    }

    /**
     * Returns distinct client IDs that have made tool calls, for the filter combo box.
     */
    @NotNull
    public static List<String> getDistinctClients(@NotNull ConversationDatabase db) {
        synchronized (db) {
            Connection conn = db.getConnection();
            if (conn == null) return List.of();
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT client_id FROM tool_call_events WHERE client_id IS NOT NULL ORDER BY client_id")) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString(1));
                return result;
            } catch (SQLException e) {
                LOG.warn("ConversationStatistics: failed to get distinct clients", e);
                return List.of();
            }
        }
    }
}
