package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolCallStatisticsService} SQLite persistence and query logic.
 * Uses a direct JDBC connection (bypasses IntelliJ project service layer).
 */
class ToolCallStatisticsServiceTest {

    @TempDir
    Path tempDir;

    private Connection connection;
    private ToolCallStatisticsServiceTestHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbPath = tempDir.resolve("tool-stats.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        connection.setAutoCommit(true);
        helper = new ToolCallStatisticsServiceTestHelper(connection);
        helper.createSchema();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void recordAndQuerySingleCall() throws SQLException {
        helper.insertRecord(new ToolCallRecord(
            "read_file", "FILE", 256, 4096, 42, true, "copilot",
            Instant.parse("2026-01-15T10:30:00Z")));

        var aggregates = helper.queryAggregates(null, null);
        assertEquals(1, aggregates.size());

        var agg = aggregates.getFirst();
        assertEquals("read_file", agg.toolName());
        assertEquals("FILE", agg.category());
        assertEquals("copilot", agg.clientId());
        assertEquals(1, agg.callCount());
        assertEquals(42, agg.avgDurationMs());
        assertEquals(256, agg.totalInputBytes());
        assertEquals(4096, agg.totalOutputBytes());
        assertEquals(0, agg.errorCount());
    }

    @Test
    void aggregatesMultipleCallsSameTool() throws SQLException {
        Instant base = Instant.parse("2026-01-15T10:00:00Z");
        helper.insertRecord(new ToolCallRecord("search_text", "NAV", 100, 2000, 50, true, "copilot", base));
        helper.insertRecord(new ToolCallRecord("search_text", "NAV", 200, 3000, 150, true, "copilot", base.plusSeconds(60)));
        helper.insertRecord(new ToolCallRecord("search_text", "NAV", 150, 1000, 100, false, "copilot", base.plusSeconds(120)));

        var aggregates = helper.queryAggregates(null, null);
        assertEquals(1, aggregates.size());

        var agg = aggregates.getFirst();
        assertEquals(3, agg.callCount());
        assertEquals(100, agg.avgDurationMs()); // (50+150+100)/3
        assertEquals(450, agg.totalInputBytes());
        assertEquals(6000, agg.totalOutputBytes());
        assertEquals(1, agg.errorCount());
    }

    @Test
    void filterByTimestamp() throws SQLException {
        helper.insertRecord(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "copilot",
            Instant.parse("2026-01-10T00:00:00Z")));
        helper.insertRecord(new ToolCallRecord("read_file", "FILE", 300, 400, 20, true, "copilot",
            Instant.parse("2026-01-20T00:00:00Z")));

        var filtered = helper.queryAggregates("2026-01-15T00:00:00Z", null);
        assertEquals(1, filtered.size());
        assertEquals(300, filtered.getFirst().totalInputBytes());
    }

    @Test
    void filterByClient() throws SQLException {
        Instant ts = Instant.parse("2026-01-15T10:00:00Z");
        helper.insertRecord(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "copilot", ts));
        helper.insertRecord(new ToolCallRecord("read_file", "FILE", 300, 400, 20, true, "opencode", ts));

        var filtered = helper.queryAggregates(null, "opencode");
        assertEquals(1, filtered.size());
        assertEquals("opencode", filtered.getFirst().clientId());
        assertEquals(300, filtered.getFirst().totalInputBytes());
    }

    @Test
    void distinctClients() throws SQLException {
        Instant ts = Instant.now();
        helper.insertRecord(new ToolCallRecord("a", null, 0, 0, 0, true, "copilot", ts));
        helper.insertRecord(new ToolCallRecord("b", null, 0, 0, 0, true, "opencode", ts));
        helper.insertRecord(new ToolCallRecord("c", null, 0, 0, 0, true, "copilot", ts));

        List<String> clients = helper.getDistinctClients();
        assertEquals(2, clients.size());
        assertTrue(clients.contains("copilot"));
        assertTrue(clients.contains("opencode"));
    }

    @Test
    void querySummary() throws SQLException {
        Instant ts = Instant.now();
        helper.insertRecord(new ToolCallRecord("read_file", "FILE", 100, 200, 50, true, "copilot", ts));
        helper.insertRecord(new ToolCallRecord("write_file", "FILE", 300, 400, 150, false, "copilot", ts));

        Map<String, Long> summary = helper.querySummary(null, null);
        assertEquals(2L, summary.get("totalCalls"));
        assertEquals(200L, summary.get("totalDurationMs"));
        assertEquals(400L, summary.get("totalInputBytes"));
        assertEquals(600L, summary.get("totalOutputBytes"));
        assertEquals(1L, summary.get("totalErrors"));
    }

    @Test
    void emptyDatabaseReturnsEmptyResults() throws SQLException {
        assertTrue(helper.queryAggregates(null, null).isEmpty());
        assertTrue(helper.getDistinctClients().isEmpty());

        Map<String, Long> summary = helper.querySummary(null, null);
        assertEquals(0L, summary.get("totalCalls"));
    }

    @Test
    void nullCategoryStoredCorrectly() throws SQLException {
        helper.insertRecord(new ToolCallRecord("custom_tool", null, 50, 100, 30, true, "copilot", Instant.now()));

        var aggregates = helper.queryAggregates(null, null);
        assertEquals(1, aggregates.size());
        assertNull(aggregates.getFirst().category());
    }

    @Test
    void groupsByToolClientCombination() throws SQLException {
        Instant ts = Instant.now();
        helper.insertRecord(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "copilot", ts));
        helper.insertRecord(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "opencode", ts));
        helper.insertRecord(new ToolCallRecord("write_file", "FILE", 100, 200, 10, true, "copilot", ts));

        var aggregates = helper.queryAggregates(null, null);
        assertEquals(3, aggregates.size());
    }

    /**
     * Test helper that reimplements the service SQL queries against a test connection,
     * without needing the IntelliJ project service infrastructure.
     */
    private static class ToolCallStatisticsServiceTestHelper {
        private final Connection conn;

        ToolCallStatisticsServiceTestHelper(Connection conn) {
            this.conn = conn;
        }

        void createSchema() throws SQLException {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS tool_calls (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        tool_name  TEXT    NOT NULL,
                        category   TEXT,
                        input_size INTEGER NOT NULL,
                        output_size INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        success    INTEGER NOT NULL,
                        client_id  TEXT    NOT NULL,
                        timestamp  TEXT    NOT NULL
                    )
                    """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tool_calls_timestamp ON tool_calls(timestamp)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tool_calls_tool_name ON tool_calls(tool_name)");
            }
        }

        void insertRecord(ToolCallRecord rec) throws SQLException {
            try (var stmt = conn.prepareStatement("""
                INSERT INTO tool_calls (tool_name, category, input_size, output_size, duration_ms, success, client_id, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                stmt.setString(1, rec.toolName());
                stmt.setString(2, rec.category());
                stmt.setLong(3, rec.inputSizeBytes());
                stmt.setLong(4, rec.outputSizeBytes());
                stmt.setLong(5, rec.durationMs());
                stmt.setInt(6, rec.success() ? 1 : 0);
                stmt.setString(7, rec.clientId());
                stmt.setString(8, rec.timestamp().toString());
                stmt.executeUpdate();
            }
        }

        List<ToolCallStatisticsService.ToolAggregate> queryAggregates(String since, String clientId) throws SQLException {
            StringBuilder sql = new StringBuilder("""
                SELECT tool_name, category, client_id,
                       COUNT(*) AS call_count,
                       AVG(duration_ms) AS avg_duration,
                       SUM(input_size) AS total_input,
                       SUM(output_size) AS total_output,
                       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS error_count
                FROM tool_calls WHERE 1=1
                """);
            List<String> params = new java.util.ArrayList<>();
            if (since != null) { sql.append(" AND timestamp >= ?"); params.add(since); }
            if (clientId != null) { sql.append(" AND client_id = ?"); params.add(clientId); }
            sql.append(" GROUP BY tool_name, category, client_id ORDER BY call_count DESC");

            List<ToolCallStatisticsService.ToolAggregate> results = new java.util.ArrayList<>();
            try (var stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) stmt.setString(i + 1, params.get(i));
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new ToolCallStatisticsService.ToolAggregate(
                            rs.getString("tool_name"), rs.getString("category"),
                            rs.getString("client_id"), rs.getLong("call_count"),
                            rs.getLong("avg_duration"), rs.getLong("total_input"),
                            rs.getLong("total_output"), rs.getLong("error_count")));
                    }
                }
            }
            return results;
        }

        List<String> getDistinctClients() throws SQLException {
            List<String> clients = new java.util.ArrayList<>();
            try (var rs = conn.createStatement().executeQuery(
                "SELECT DISTINCT client_id FROM tool_calls ORDER BY client_id")) {
                while (rs.next()) clients.add(rs.getString("client_id"));
            }
            return clients;
        }

        Map<String, Long> querySummary(String since, String clientId) throws SQLException {
            StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS total_calls,
                       COALESCE(SUM(duration_ms), 0) AS total_duration,
                       COALESCE(SUM(input_size), 0) AS total_input,
                       COALESCE(SUM(output_size), 0) AS total_output,
                       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS total_errors
                FROM tool_calls WHERE 1=1
                """);
            List<String> params = new java.util.ArrayList<>();
            if (since != null) { sql.append(" AND timestamp >= ?"); params.add(since); }
            if (clientId != null) { sql.append(" AND client_id = ?"); params.add(clientId); }

            Map<String, Long> summary = new java.util.LinkedHashMap<>();
            try (var stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) stmt.setString(i + 1, params.get(i));
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        summary.put("totalCalls", rs.getLong("total_calls"));
                        summary.put("totalDurationMs", rs.getLong("total_duration"));
                        summary.put("totalInputBytes", rs.getLong("total_input"));
                        summary.put("totalOutputBytes", rs.getLong("total_output"));
                        summary.put("totalErrors", rs.getLong("total_errors"));
                    }
                }
            }
            return summary;
        }
    }
}
