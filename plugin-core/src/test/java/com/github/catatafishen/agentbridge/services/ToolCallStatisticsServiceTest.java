package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolCallStatisticsService} — exercises the actual service code
 * (recordCall, queryAggregates, querySummary, getDistinctClients, queryRecentErrors)
 * against a test-owned in-memory SQLite database via {@code initializeWithConnection()}.
 */
class ToolCallStatisticsServiceTest {

    @TempDir
    Path tempDir;

    private ToolCallStatisticsService service;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbPath = tempDir.resolve("tool-stats.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

        // Use the package-private no-arg constructor for testing
        service = new ToolCallStatisticsService();
        service.initializeWithConnection(connection);
    }

    @AfterEach
    void tearDown() {
        service.dispose();
    }

    @Test
    void recordAndQuerySingleCall() {
        service.recordCall(new ToolCallRecord(
            "read_file", "FILE", 256, 4096, 42, true, null, "copilot",
            Instant.parse("2026-01-15T10:30:00Z")));

        var aggregates = service.queryAggregates(null, null);
        assertEquals(1, aggregates.size());

        var agg = aggregates.getFirst();
        assertEquals("read_file", agg.toolName());
        assertEquals("FILE", agg.category());
        assertEquals(1, agg.callCount());
        assertEquals(42, agg.avgDurationMs());
        assertEquals(256, agg.totalInputBytes());
        assertEquals(4096, agg.totalOutputBytes());
        assertEquals(4352, agg.avgTotalBytes());
        assertEquals(0, agg.errorCount());
    }

    @Test
    void aggregatesMultipleCallsSameTool() {
        Instant base = Instant.parse("2026-01-15T10:00:00Z");
        service.recordCall(new ToolCallRecord("search_text", "NAV", 100, 2000, 50, true, null, "copilot", base));
        service.recordCall(new ToolCallRecord("search_text", "NAV", 200, 3000, 150, true, null, "copilot", base.plusSeconds(60)));
        service.recordCall(new ToolCallRecord("search_text", "NAV", 150, 1000, 100, false, "Error: test", "copilot", base.plusSeconds(120)));

        var aggregates = service.queryAggregates(null, null);
        assertEquals(1, aggregates.size());

        var agg = aggregates.getFirst();
        assertEquals(3, agg.callCount());
        assertEquals(100, agg.avgDurationMs()); // (50+150+100)/3
        assertEquals(450, agg.totalInputBytes());
        assertEquals(6000, agg.totalOutputBytes());
        assertEquals(2150, agg.avgTotalBytes()); // (2100+3200+1150)/3 = 6450/3 = 2150
        assertEquals(1, agg.errorCount());
    }

    @Test
    void filterByTimestamp() {
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, null, "copilot",
            Instant.parse("2026-01-10T00:00:00Z")));
        service.recordCall(new ToolCallRecord("read_file", "FILE", 300, 400, 20, true, null, "copilot",
            Instant.parse("2026-01-20T00:00:00Z")));

        var filtered = service.queryAggregates("2026-01-15T00:00:00Z", null);
        assertEquals(1, filtered.size());
        assertEquals(300, filtered.getFirst().totalInputBytes());
    }

    @Test
    void filterByClient() {
        Instant ts = Instant.parse("2026-01-15T10:00:00Z");
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, null, "copilot", ts));
        service.recordCall(new ToolCallRecord("read_file", "FILE", 300, 400, 20, true, null, "opencode", ts));

        var filtered = service.queryAggregates(null, "opencode");
        assertEquals(1, filtered.size());
        assertEquals(300, filtered.getFirst().totalInputBytes());
    }

    @Test
    void filterByBothTimestampAndClient() {
        service.recordCall(new ToolCallRecord("tool_a", "CAT", 100, 200, 10, true, null, "copilot",
            Instant.parse("2026-01-10T00:00:00Z")));
        service.recordCall(new ToolCallRecord("tool_a", "CAT", 200, 300, 20, true, null, "copilot",
            Instant.parse("2026-01-20T00:00:00Z")));
        service.recordCall(new ToolCallRecord("tool_a", "CAT", 400, 500, 30, true, null, "opencode",
            Instant.parse("2026-01-20T00:00:00Z")));

        var filtered = service.queryAggregates("2026-01-15T00:00:00Z", "copilot");
        assertEquals(1, filtered.size());
        assertEquals(200, filtered.getFirst().totalInputBytes());
    }

    @Test
    void distinctClients() {
        Instant ts = Instant.now();
        service.recordCall(new ToolCallRecord("a", null, 0, 0, 0, true, null, "copilot", ts));
        service.recordCall(new ToolCallRecord("b", null, 0, 0, 0, true, null, "opencode", ts));
        service.recordCall(new ToolCallRecord("c", null, 0, 0, 0, true, null, "copilot", ts));

        List<String> clients = service.getDistinctClients();
        assertEquals(2, clients.size());
        assertTrue(clients.contains("copilot"));
        assertTrue(clients.contains("opencode"));
    }

    @Test
    void querySummary() {
        Instant ts = Instant.now();
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 50, true, null, "copilot", ts));
        service.recordCall(new ToolCallRecord("write_file", "FILE", 300, 400, 150, false, "Error: write failed", "copilot", ts));

        Map<String, Long> summary = service.querySummary(null, null);
        assertEquals(2L, summary.get("totalCalls"));
        assertEquals(200L, summary.get("totalDurationMs"));
        assertEquals(400L, summary.get("totalInputBytes"));
        assertEquals(600L, summary.get("totalOutputBytes"));
        assertEquals(1L, summary.get("totalErrors"));
    }

    @Test
    void emptyDatabaseReturnsEmptyResults() {
        assertTrue(service.queryAggregates(null, null).isEmpty());
        assertTrue(service.getDistinctClients().isEmpty());

        Map<String, Long> summary = service.querySummary(null, null);
        assertEquals(0L, summary.get("totalCalls"));
    }

    @Test
    void nullCategoryStoredCorrectly() {
        service.recordCall(new ToolCallRecord("custom_tool", null, 50, 100, 30, true, null, "copilot", Instant.now()));

        var aggregates = service.queryAggregates(null, null);
        assertEquals(1, aggregates.size());
        assertNull(aggregates.getFirst().category());
    }

    @Test
    void groupsByToolNameAndCategory() {
        // Calls from different clients with the same tool are collapsed into one aggregate row
        Instant ts = Instant.now();
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, null, "copilot", ts));
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, null, "opencode", ts));
        service.recordCall(new ToolCallRecord("write_file", "FILE", 100, 200, 10, true, null, "copilot", ts));

        var aggregates = service.queryAggregates(null, null);
        assertEquals(2, aggregates.size());
    }

    @Test
    void disposeClosesConnection() throws SQLException {
        assertFalse(connection.isClosed());
        service.dispose();
        assertTrue(connection.isClosed());
    }

    @Test
    void querySummaryWithFilters() {
        Instant ts = Instant.parse("2026-01-20T00:00:00Z");
        service.recordCall(new ToolCallRecord("tool", "CAT", 100, 200, 50, true, null, "copilot", ts));
        service.recordCall(new ToolCallRecord("tool", "CAT", 300, 400, 100, false, "Error: test", "opencode", ts));

        Map<String, Long> summary = service.querySummary(null, "copilot");
        assertEquals(1L, summary.get("totalCalls"));
        assertEquals(0L, summary.get("totalErrors"));
        assertEquals(100L, summary.get("totalInputBytes"));
    }

    @Test
    void highVolumeRecordAndQuery() {
        Instant base = Instant.parse("2026-01-15T00:00:00Z");
        for (int i = 0; i < 100; i++) {
            boolean success = i % 10 != 0;
            service.recordCall(new ToolCallRecord(
                "tool_" + (i % 5), "CAT", i * 10, i * 20, i, success,
                success ? null : "Error: iteration " + i,
                "client_" + (i % 3), base.plusSeconds(i)));
        }

        var aggregates = service.queryAggregates(null, null);
        assertFalse(aggregates.isEmpty());

        long totalCalls = aggregates.stream().mapToLong(ToolCallStatisticsService.ToolAggregate::callCount).sum();
        assertEquals(100, totalCalls);
    }

    @Test
    void queryRecentErrors() {
        Instant base = Instant.parse("2026-01-15T10:00:00Z");
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 0, 10, false,
            "Error: File not found", "copilot", base));
        service.recordCall(new ToolCallRecord("write_file", "FILE", 200, 0, 20, false,
            "Error: Permission denied", "copilot", base.plusSeconds(60)));
        service.recordCall(new ToolCallRecord("search_text", "NAV", 50, 1000, 5, true,
            null, "copilot", base.plusSeconds(30)));

        var errors = service.queryRecentErrors(null, null, 10);
        assertEquals(2, errors.size());
        // Most recent first
        assertEquals("write_file", errors.get(0).toolName());
        assertEquals("Error: Permission denied", errors.get(0).errorMessage());
        assertEquals("read_file", errors.get(1).toolName());
        assertEquals("Error: File not found", errors.get(1).errorMessage());
    }

    @Test
    void queryRecentErrorsFilterByClient() {
        Instant ts = Instant.parse("2026-01-15T10:00:00Z");
        service.recordCall(new ToolCallRecord("tool_a", "CAT", 100, 0, 10, false,
            "Error: A failed", "copilot", ts));
        service.recordCall(new ToolCallRecord("tool_b", "CAT", 100, 0, 10, false,
            "Error: B failed", "opencode", ts));

        var errors = service.queryRecentErrors(null, "opencode", 10);
        assertEquals(1, errors.size());
        var first = errors.getFirst();
        assertEquals("tool_b", first.toolName());
        assertEquals("opencode", first.clientId());
    }

    @Test
    void errorMessageNullOnSuccess() {
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true,
            null, "copilot", Instant.now()));

        var errors = service.queryRecentErrors(null, null, 10);
        assertTrue(errors.isEmpty());
    }

    @Test
    void schemaMigrationIdempotent() throws Exception {
        // Calling initializeWithConnection again should not fail (migration re-runs safely)
        service.dispose();
        Path dbPath2 = tempDir.resolve("tool-stats-2.db");
        Connection conn2 = DriverManager.getConnection("jdbc:sqlite:" + dbPath2);
        var service2 = new ToolCallStatisticsService();
        service2.initializeWithConnection(conn2);
        // Second init on same connection — migration should be idempotent
        service2.initializeWithConnection(conn2);
        // Verify DB is still functional after double-init
        service2.recordCall(new ToolCallRecord("test", null, 0, 0, 0, true, null, "copilot", Instant.now()));
        assertEquals(1, service2.getRecordCount());
        service2.dispose();
    }

    @Test
    void hasRecordAtFindsExistingRecord() {
        Instant ts = Instant.parse("2026-01-15T10:30:00Z");
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, null, "copilot", ts));

        assertTrue(service.hasRecordAt(ts, "read_file"));
    }

    @Test
    void hasRecordAtReturnsFalseForNonExistent() {
        Instant ts = Instant.parse("2026-01-15T10:30:00Z");
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, null, "copilot", ts));

        assertFalse(service.hasRecordAt(ts, "write_file"));
        assertFalse(service.hasRecordAt(Instant.parse("2026-01-16T10:30:00Z"), "read_file"));
    }

    @Test
    void getRecordCountReturnsCorrectCount() {
        assertEquals(0, service.getRecordCount());

        Instant ts = Instant.now();
        service.recordCall(new ToolCallRecord("a", null, 0, 0, 0, true, null, "copilot", ts));
        assertEquals(1, service.getRecordCount());

        service.recordCall(new ToolCallRecord("b", null, 0, 0, 0, true, null, "copilot", ts.plusSeconds(1)));
        assertEquals(2, service.getRecordCount());
    }

    @Test
    void recordCallWithNullConnectionDoesNotThrow() {
        // Create a service without initializing a connection
        var uninitService = new ToolCallStatisticsService();
        // Should not throw — just silently drops the call
        uninitService.recordCall(new ToolCallRecord("test", null, 0, 0, 0, true, null, "copilot", Instant.now()));
        // Verify getRecordCount also handles null connection
        assertEquals(0, uninitService.getRecordCount());
    }

    @Test
    void hasRecordAtWithNullConnectionReturnsFalse() {
        var uninitService = new ToolCallStatisticsService();
        assertFalse(uninitService.hasRecordAt(Instant.now(), "anything"));
    }

    @Test
    void getRecordCountWithNullConnectionReturnsZero() {
        var uninitService = new ToolCallStatisticsService();
        assertEquals(0, uninitService.getRecordCount());
    }

    @Test
    void queryMethodsWithNullConnectionReturnEmpty() {
        var uninitService = new ToolCallStatisticsService();
        assertTrue(uninitService.queryAggregates(null, null).isEmpty());
        assertTrue(uninitService.querySummary(null, null).isEmpty());
        assertTrue(uninitService.getDistinctClients().isEmpty());
        assertTrue(uninitService.queryRecentErrors(null, null, 10).isEmpty());
    }

    @Test
    void isDbMovedDetectsSqliteReadonlyDbmoved() {
        assertTrue(ToolCallStatisticsService.isDbMoved(
            new SQLException("[SQLITE_READONLY_DBMOVED] database file moved or deleted")));
        assertFalse(ToolCallStatisticsService.isDbMoved(
            new SQLException("some other error")));
        assertFalse(ToolCallStatisticsService.isDbMoved(
            new SQLException((String) null)));
    }

    @Test
    void initializeThrowsWhenBasePathIsNull() {
        Project mockProject = Mockito.mock(Project.class);
        Mockito.when(mockProject.getBasePath()).thenReturn(null);

        ToolCallStatisticsService svc = new ToolCallStatisticsService(mockProject);
        assertThrows(IllegalStateException.class, svc::initialize);
    }

    // --- Turn stats tests ---

    private static ToolCallStatisticsService.TurnStatsRecord turnRecord(
        String sessionId, String agentId, String date,
        long inputTokens, long outputTokens, int toolCalls,
        long durationMs, int linesAdded, int linesRemoved,
        double premiumRequests, String timestamp) {
        return new ToolCallStatisticsService.TurnStatsRecord(
            sessionId, agentId, date, inputTokens, outputTokens, toolCalls,
            durationMs, linesAdded, linesRemoved, premiumRequests, timestamp, null, null);
    }

    @Test
    @DisplayName("recordTurnStats inserts and getTurnStatsCount returns correct count")
    void recordTurnStatsInsertsCorrectly() {
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z"));

        assertEquals(1, service.getTurnStatsCount());
    }

    @Test
    @DisplayName("hasTurnStatsAt detects existing record by timestamp")
    void hasTurnStatsAtFindsExisting() {
        String ts = "2025-01-15T10:00:00Z";
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, ts));

        assertTrue(service.hasTurnStatsAt(ts));
        assertFalse(service.hasTurnStatsAt("2025-01-15T11:00:00Z"));
    }

    @Test
    @DisplayName("queryDailyTurnStats aggregates multiple turns for same date/agent")
    void queryDailyTurnStatsAggregates() {
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z"));
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            150, 300, 5, 7000, 20, 5, 0.5, "2025-01-15T10:05:00Z"));

        var results = service.queryDailyTurnStats("2025-01-15", "2025-01-15");
        assertEquals(1, results.size());

        var agg = results.getFirst();
        assertEquals(LocalDate.of(2025, 1, 15), agg.date());
        assertEquals("copilot", agg.agentId());
        assertEquals(2, agg.turns());
        assertEquals(250, agg.inputTokens());
        assertEquals(500, agg.outputTokens());
        assertEquals(8, agg.toolCalls());
        assertEquals(12000, agg.durationMs());
        assertEquals(30, agg.linesAdded());
        assertEquals(7, agg.linesRemoved());
        assertEquals(1.5, agg.premiumRequests(), 0.001);
    }

    @Test
    @DisplayName("queryDailyTurnStats separates different agents on same day")
    void queryDailyTurnStatsSeparatesAgents() {
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z"));
        service.recordTurnStats(turnRecord("s2", "claude-cli", "2025-01-15",
            50, 100, 1, 3000, 5, 1, 0.5, "2025-01-15T10:01:00Z"));

        var results = service.queryDailyTurnStats("2025-01-15", "2025-01-15");
        assertEquals(2, results.size());
        assertEquals("claude-cli", results.get(0).agentId());
        assertEquals("copilot", results.get(1).agentId());
    }

    @Test
    @DisplayName("queryDailyTurnStats filters by date range")
    void queryDailyTurnStatsDateFilter() {
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-14",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-14T10:00:00Z"));
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            50, 100, 1, 3000, 5, 1, 1.0, "2025-01-15T10:00:00Z"));
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-16",
            75, 150, 2, 4000, 8, 3, 1.0, "2025-01-16T10:00:00Z"));

        var results = service.queryDailyTurnStats("2025-01-15", "2025-01-16");
        assertEquals(2, results.size());
        assertEquals(LocalDate.of(2025, 1, 15), results.get(0).date());
        assertEquals(LocalDate.of(2025, 1, 16), results.get(1).date());
    }

    @Test
    @DisplayName("getDistinctTurnAgents returns all unique agent IDs")
    void getDistinctTurnAgentsReturnsAll() {
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z"));
        service.recordTurnStats(turnRecord("s2", "claude-cli", "2025-01-15",
            50, 100, 1, 3000, 5, 1, 1.0, "2025-01-15T10:01:00Z"));
        service.recordTurnStats(turnRecord("s3", "copilot", "2025-01-16",
            75, 150, 2, 4000, 8, 3, 1.0, "2025-01-16T10:00:00Z"));

        var agents = service.getDistinctTurnAgents();
        assertEquals(2, agents.size());
        assertTrue(agents.contains("copilot"));
        assertTrue(agents.contains("claude-cli"));
    }

    @Test
    @DisplayName("getEarliestTurnDate returns the earliest date")
    void getEarliestTurnDateReturnsMin() {
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-16",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-16T10:00:00Z"));
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-14",
            50, 100, 1, 3000, 5, 1, 1.0, "2025-01-14T10:00:00Z"));

        assertEquals(LocalDate.of(2025, 1, 14), service.getEarliestTurnDate());
    }

    @Test
    @DisplayName("getEarliestTurnDate returns null when table is empty")
    void getEarliestTurnDateNullWhenEmpty() {
        assertNull(service.getEarliestTurnDate());
    }

    @Test
    @DisplayName("queryDailyTurnStats returns empty for no matching range")
    void queryDailyTurnStatsEmptyRange() {
        service.recordTurnStats(turnRecord("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z"));

        var results = service.queryDailyTurnStats("2025-02-01", "2025-02-28");
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("getTurnStatsCount with null connection returns 0")
    void getTurnStatsCountNullConnectionReturnsZero() {
        ToolCallStatisticsService nullService = new ToolCallStatisticsService();
        assertEquals(0, nullService.getTurnStatsCount());
    }

    @Test
    @DisplayName("hasTurnStatsAt with null connection returns false")
    void hasTurnStatsAtNullConnectionReturnsFalse() {
        ToolCallStatisticsService nullService = new ToolCallStatisticsService();
        assertFalse(nullService.hasTurnStatsAt("2025-01-15T10:00:00Z"));
    }

    @Test
    @DisplayName("recordTurnStats with null connection does not throw")
    void recordTurnStatsNullConnectionDoesNotThrow() {
        ToolCallStatisticsService nullService = new ToolCallStatisticsService();
        assertDoesNotThrow(() -> nullService.recordTurnStats(
            turnRecord("s1", "copilot", "2025-01-15",
                100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z")));
    }

    // --- Per-branch query tests ---

    private static ToolCallStatisticsService.TurnStatsRecord turnRecordWithBranch(
        String sessionId, String agentId, String date,
        long inputTokens, long outputTokens, int toolCalls,
        long durationMs, int linesAdded, int linesRemoved,
        double premiumRequests, String timestamp, String branch) {
        return new ToolCallStatisticsService.TurnStatsRecord(
            sessionId, agentId, date, inputTokens, outputTokens, toolCalls,
            durationMs, linesAdded, linesRemoved, premiumRequests, timestamp, null, branch);
    }

    @Test
    @DisplayName("queryBranchTotals aggregates per branch and orders by premium DESC")
    void queryBranchTotalsAggregatesAndSorts() {
        service.recordTurnStats(turnRecordWithBranch("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 0.5, "2025-01-15T10:00:00Z", "feat/a"));
        service.recordTurnStats(turnRecordWithBranch("s2", "copilot", "2025-01-16",
            100, 200, 3, 5000, 10, 2, 0.5, "2025-01-16T10:00:00Z", "feat/a"));
        service.recordTurnStats(turnRecordWithBranch("s3", "copilot", "2025-01-15",
            50, 100, 1, 1000, 1, 0, 0.1, "2025-01-15T11:00:00Z", "feat/b"));
        // Cross-agent — should still be merged by branch (collapses agent dimension)
        service.recordTurnStats(turnRecordWithBranch("s4", "claude-cli", "2025-01-15",
            10, 20, 1, 500, 0, 0, 5.0, "2025-01-15T12:00:00Z", "feat/b"));

        var results = service.queryBranchTotals("2025-01-01", "2025-01-31");

        assertEquals(2, results.size());
        // feat/b has higher premium total (5.1) than feat/a (1.0) → first
        assertEquals("feat/b", results.get(0).branch());
        assertEquals(5.1, results.get(0).premiumRequests(), 0.0001);
        assertEquals(2, results.get(0).turns());
        assertEquals("feat/a", results.get(1).branch());
        assertEquals(1.0, results.get(1).premiumRequests(), 0.0001);
        assertEquals(2, results.get(1).turns());
    }

    @Test
    @DisplayName("queryBranchTotals excludes rows with null/empty git_branch")
    void queryBranchTotalsExcludesUnattributed() {
        service.recordTurnStats(turnRecordWithBranch("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z", "feat/a"));
        service.recordTurnStats(turnRecordWithBranch("s2", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T11:00:00Z", null));
        service.recordTurnStats(turnRecordWithBranch("s3", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T12:00:00Z", ""));

        var results = service.queryBranchTotals("2025-01-01", "2025-01-31");

        assertEquals(1, results.size());
        assertEquals("feat/a", results.get(0).branch());
    }

    @Test
    @DisplayName("countUnattributedTurns counts only null/empty branches in range")
    void countUnattributedTurnsCountsCorrectly() {
        service.recordTurnStats(turnRecordWithBranch("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z", "feat/a"));
        service.recordTurnStats(turnRecordWithBranch("s2", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T11:00:00Z", null));
        service.recordTurnStats(turnRecordWithBranch("s3", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T12:00:00Z", ""));
        // Outside the date range — should not be counted
        service.recordTurnStats(turnRecordWithBranch("s4", "copilot", "2024-12-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2024-12-15T10:00:00Z", null));

        assertEquals(2, service.countUnattributedTurns("2025-01-01", "2025-01-31"));
    }

    @Test
    @DisplayName("recordTurnStats persists git_branch round-trip")
    void recordTurnStatsPersistsBranch() {
        service.recordTurnStats(turnRecordWithBranch("s1", "copilot", "2025-01-15",
            100, 200, 3, 5000, 10, 2, 1.0, "2025-01-15T10:00:00Z", "feat/round-trip"));

        var results = service.queryBranchTotals("2025-01-01", "2025-01-31");
        assertEquals(1, results.size());
        assertEquals("feat/round-trip", results.get(0).branch());
    }
}
