package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolCallStatisticsService} — exercises the actual service code
 * (recordCall, queryAggregates, querySummary, getDistinctClients) against a
 * test-owned in-memory SQLite database via {@code initializeWithConnection()}.
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

        // Use the package-private initializeWithConnection to inject a test DB
        service = new ToolCallStatisticsService(null);
        service.initializeWithConnection(connection);
    }

    @AfterEach
    void tearDown() {
        service.dispose();
    }

    @Test
    void recordAndQuerySingleCall() {
        service.recordCall(new ToolCallRecord(
            "read_file", "FILE", 256, 4096, 42, true, "copilot",
            Instant.parse("2026-01-15T10:30:00Z")));

        var aggregates = service.queryAggregates(null, null);
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
    void aggregatesMultipleCallsSameTool() {
        Instant base = Instant.parse("2026-01-15T10:00:00Z");
        service.recordCall(new ToolCallRecord("search_text", "NAV", 100, 2000, 50, true, "copilot", base));
        service.recordCall(new ToolCallRecord("search_text", "NAV", 200, 3000, 150, true, "copilot", base.plusSeconds(60)));
        service.recordCall(new ToolCallRecord("search_text", "NAV", 150, 1000, 100, false, "copilot", base.plusSeconds(120)));

        var aggregates = service.queryAggregates(null, null);
        assertEquals(1, aggregates.size());

        var agg = aggregates.getFirst();
        assertEquals(3, agg.callCount());
        assertEquals(100, agg.avgDurationMs()); // (50+150+100)/3
        assertEquals(450, agg.totalInputBytes());
        assertEquals(6000, agg.totalOutputBytes());
        assertEquals(1, agg.errorCount());
    }

    @Test
    void filterByTimestamp() {
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "copilot",
            Instant.parse("2026-01-10T00:00:00Z")));
        service.recordCall(new ToolCallRecord("read_file", "FILE", 300, 400, 20, true, "copilot",
            Instant.parse("2026-01-20T00:00:00Z")));

        var filtered = service.queryAggregates("2026-01-15T00:00:00Z", null);
        assertEquals(1, filtered.size());
        assertEquals(300, filtered.getFirst().totalInputBytes());
    }

    @Test
    void filterByClient() {
        Instant ts = Instant.parse("2026-01-15T10:00:00Z");
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "copilot", ts));
        service.recordCall(new ToolCallRecord("read_file", "FILE", 300, 400, 20, true, "opencode", ts));

        var filtered = service.queryAggregates(null, "opencode");
        assertEquals(1, filtered.size());
        assertEquals("opencode", filtered.getFirst().clientId());
        assertEquals(300, filtered.getFirst().totalInputBytes());
    }

    @Test
    void filterByBothTimestampAndClient() {
        service.recordCall(new ToolCallRecord("tool_a", "CAT", 100, 200, 10, true, "copilot",
            Instant.parse("2026-01-10T00:00:00Z")));
        service.recordCall(new ToolCallRecord("tool_a", "CAT", 200, 300, 20, true, "copilot",
            Instant.parse("2026-01-20T00:00:00Z")));
        service.recordCall(new ToolCallRecord("tool_a", "CAT", 400, 500, 30, true, "opencode",
            Instant.parse("2026-01-20T00:00:00Z")));

        var filtered = service.queryAggregates("2026-01-15T00:00:00Z", "copilot");
        assertEquals(1, filtered.size());
        assertEquals(200, filtered.getFirst().totalInputBytes());
    }

    @Test
    void distinctClients() {
        Instant ts = Instant.now();
        service.recordCall(new ToolCallRecord("a", null, 0, 0, 0, true, "copilot", ts));
        service.recordCall(new ToolCallRecord("b", null, 0, 0, 0, true, "opencode", ts));
        service.recordCall(new ToolCallRecord("c", null, 0, 0, 0, true, "copilot", ts));

        List<String> clients = service.getDistinctClients();
        assertEquals(2, clients.size());
        assertTrue(clients.contains("copilot"));
        assertTrue(clients.contains("opencode"));
    }

    @Test
    void querySummary() {
        Instant ts = Instant.now();
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 50, true, "copilot", ts));
        service.recordCall(new ToolCallRecord("write_file", "FILE", 300, 400, 150, false, "copilot", ts));

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
        service.recordCall(new ToolCallRecord("custom_tool", null, 50, 100, 30, true, "copilot", Instant.now()));

        var aggregates = service.queryAggregates(null, null);
        assertEquals(1, aggregates.size());
        assertNull(aggregates.getFirst().category());
    }

    @Test
    void groupsByToolClientCombination() {
        Instant ts = Instant.now();
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "copilot", ts));
        service.recordCall(new ToolCallRecord("read_file", "FILE", 100, 200, 10, true, "opencode", ts));
        service.recordCall(new ToolCallRecord("write_file", "FILE", 100, 200, 10, true, "copilot", ts));

        var aggregates = service.queryAggregates(null, null);
        assertEquals(3, aggregates.size());
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
        service.recordCall(new ToolCallRecord("tool", "CAT", 100, 200, 50, true, "copilot", ts));
        service.recordCall(new ToolCallRecord("tool", "CAT", 300, 400, 100, false, "opencode", ts));

        Map<String, Long> summary = service.querySummary(null, "copilot");
        assertEquals(1L, summary.get("totalCalls"));
        assertEquals(0L, summary.get("totalErrors"));
        assertEquals(100L, summary.get("totalInputBytes"));
    }

    @Test
    void highVolumeRecordAndQuery() {
        Instant base = Instant.parse("2026-01-15T00:00:00Z");
        for (int i = 0; i < 100; i++) {
            service.recordCall(new ToolCallRecord(
                "tool_" + (i % 5), "CAT", i * 10, i * 20, i, i % 10 != 0,
                "client_" + (i % 3), base.plusSeconds(i)));
        }

        var aggregates = service.queryAggregates(null, null);
        assertFalse(aggregates.isEmpty());

        long totalCalls = aggregates.stream().mapToLong(ToolCallStatisticsService.ToolAggregate::callCount).sum();
        assertEquals(100, totalCalls);
    }
}
