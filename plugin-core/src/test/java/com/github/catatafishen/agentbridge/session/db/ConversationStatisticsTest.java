package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStatisticsTest {

    private Connection conn;
    private ConversationDatabase database;
    private ConversationWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        database = new ConversationDatabase();
        database.initializeWithConnection(conn);
        writer = new ConversationWriter(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    private void insertTurnWithStats(String sessionId, String agentName, String clientId,
                                     String timestamp, String turnId, int inputTokens,
                                     int outputTokens, int toolCalls, long durationMs,
                                     String branch) {
        writer.recordEntries(sessionId, agentName, clientId, List.of(
            new EntryData.Prompt("prompt", timestamp, null, turnId, turnId),
            new EntryData.TurnStats(turnId, durationMs, inputTokens, outputTokens, 1.0,
                toolCalls, 10, 5, "gpt-4", "",
                0, 0, 0, 0.0, 0, 0, 0, timestamp,
                turnId, List.of(), branch, branch)));
    }

    private void insertMcpToolCall(String sessionId, String turnId, String toolName,
                                   String timestamp, long durationMs, int inputSize,
                                   int outputSize, boolean success, String clientId) throws Exception {
        // Insert a prompt first to establish the turn
        writer.recordEntries(sessionId, "Copilot", clientId, List.of(
            new EntryData.Prompt("call", timestamp, null, turnId, turnId)));

        // Insert tool call event directly
        String eventId = java.util.UUID.randomUUID().toString();
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO events (id, turn_id, sequence_num, event_type, timestamp) VALUES (?, ?, 1, 'tool_call', ?)")) {
            ps.setString(1, eventId);
            ps.setString(2, turnId);
            ps.setString(3, timestamp);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO tool_call_events (event_id, tool_name, display_name, category, is_mcp, " +
                "duration_ms, input_size_bytes, output_size_bytes, success, client_id) " +
                "VALUES (?, ?, ?, 'file', 1, ?, ?, ?, ?, ?)")) {
            ps.setString(1, eventId);
            ps.setString(2, toolName);
            ps.setString(3, toolName);
            ps.setLong(4, durationMs);
            ps.setInt(5, inputSize);
            ps.setInt(6, outputSize);
            ps.setInt(7, success ? 1 : 0);
            ps.setString(8, clientId);
            ps.executeUpdate();
        }
    }

    // ── Turn count ───────────────────────────────────────────────────────────

    @Nested
    class TurnCount {

        @Test
        void returnsZeroForEmptyDatabase() {
            assertEquals(0, ConversationStatistics.getTurnCount(database));
        }

        @Test
        void returnsTotalTurnCount() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-01T10:00:00Z", "t1",
                100, 200, 1, 500, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-02T10:00:00Z", "t2",
                150, 250, 2, 600, "main");
            insertTurnWithStats("s2", "Claude", "opencode", "2026-01-03T10:00:00Z", "t3",
                200, 300, 0, 700, "feat/auth");

            assertEquals(3, ConversationStatistics.getTurnCount(database));
        }
    }

    // ── Earliest turn date ───────────────────────────────────────────────────

    @Nested
    class EarliestTurnDate {

        @Test
        void returnsNullForEmptyDatabase() {
            assertNull(ConversationStatistics.getEarliestTurnDate(database));
        }

        @Test
        void returnsEarliestDate() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-03-15T10:00:00Z", "t1",
                100, 200, 1, 500, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-10T10:00:00Z", "t2",
                150, 250, 2, 600, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-02-20T10:00:00Z", "t3",
                200, 300, 0, 700, "main");

            assertEquals(LocalDate.of(2026, 1, 10),
                ConversationStatistics.getEarliestTurnDate(database));
        }
    }

    // ── Distinct agents ──────────────────────────────────────────────────────

    @Nested
    class DistinctAgents {

        @Test
        void returnsEmptyForNoSessions() {
            assertTrue(ConversationStatistics.getDistinctAgents(database).isEmpty());
        }

        @Test
        void returnsDistinctAgentNames() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-01T10:00:00Z", "t1",
                100, 200, 1, 500, "main");
            insertTurnWithStats("s2", "Claude", "opencode", "2026-01-02T10:00:00Z", "t2",
                150, 250, 2, 600, "main");
            insertTurnWithStats("s3", "Copilot", "copilot", "2026-01-03T10:00:00Z", "t3",
                200, 300, 0, 700, "feat/auth");

            Set<String> agents = ConversationStatistics.getDistinctAgents(database);
            assertEquals(2, agents.size());
            assertTrue(agents.contains("Copilot"));
            assertTrue(agents.contains("Claude"));
        }
    }

    // ── Daily turn stats ─────────────────────────────────────────────────────

    @Nested
    class DailyTurnStats {

        @Test
        void returnsEmptyForNoData() {
            List<ConversationStatistics.DailyTurnAggregate> result =
                ConversationStatistics.queryDailyTurnStats(database, null, null);
            assertTrue(result.isEmpty());
        }

        @Test
        void aggregatesByDateAndAgent() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T10:00:00Z", "t1",
                100, 200, 2, 500, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T11:00:00Z", "t2",
                150, 250, 3, 600, "main");
            insertTurnWithStats("s2", "Claude", "opencode", "2026-01-15T12:00:00Z", "t3",
                200, 300, 1, 700, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-16T10:00:00Z", "t4",
                50, 100, 0, 300, "main");

            List<ConversationStatistics.DailyTurnAggregate> result =
                ConversationStatistics.queryDailyTurnStats(database, null, null);

            assertEquals(3, result.size());

            // First entry: 2026-01-15, Claude (alphabetical agent sort)
            ConversationStatistics.DailyTurnAggregate day15Claude = result.getFirst();
            assertEquals("2026-01-15", day15Claude.date());
            assertEquals("Claude", day15Claude.agentId());
            assertEquals(1, day15Claude.turns());
            assertEquals(200, day15Claude.inputTokens());

            // Second entry: 2026-01-15, Copilot (2 turns aggregated)
            ConversationStatistics.DailyTurnAggregate day15Copilot = result.get(1);
            assertEquals("2026-01-15", day15Copilot.date());
            assertEquals("Copilot", day15Copilot.agentId());
            assertEquals(2, day15Copilot.turns());
            assertEquals(250, day15Copilot.inputTokens());
            assertEquals(450, day15Copilot.outputTokens());
            assertEquals(5, day15Copilot.toolCalls());
            assertEquals(1100, day15Copilot.durationMs());
        }

        @Test
        void respectsDateRange() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-10T10:00:00Z", "t1",
                100, 200, 1, 500, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T10:00:00Z", "t2",
                150, 250, 2, 600, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-20T10:00:00Z", "t3",
                200, 300, 3, 700, "main");

            List<ConversationStatistics.DailyTurnAggregate> result =
                ConversationStatistics.queryDailyTurnStats(database, "2026-01-12", "2026-01-18");

            assertEquals(1, result.size());
            assertEquals("2026-01-15", result.getFirst().date());
        }
    }

    // ── Branch totals ────────────────────────────────────────────────────────

    @Nested
    class BranchTotals {

        @Test
        void returnsEmptyForNoData() {
            assertTrue(ConversationStatistics.queryBranchTotals(database, null, null).isEmpty());
        }

        @Test
        void aggregatesByBranch() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T10:00:00Z", "t1",
                100, 200, 2, 500, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T11:00:00Z", "t2",
                150, 250, 3, 600, "main");
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-16T10:00:00Z", "t3",
                200, 300, 1, 700, "feat/auth");

            List<ConversationStatistics.BranchAggregate> result =
                ConversationStatistics.queryBranchTotals(database, null, null);

            assertEquals(2, result.size());
            // Ordered by turns DESC: main (2), feat/auth (1)
            assertEquals("main", result.getFirst().branch());
            assertEquals(2, result.getFirst().turns());
            assertEquals(250, result.getFirst().inputTokens());
            assertEquals("feat/auth", result.get(1).branch());
            assertEquals(1, result.get(1).turns());
        }

        @Test
        void excludesTurnsWithoutBranch() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T10:00:00Z", "t1",
                100, 200, 2, 500, "main");
            // Turn without branch — insert manually with null branch
            writer.recordEntries("s1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("no branch", "2026-01-16T10:00:00Z", null, "t2", "t2")));

            List<ConversationStatistics.BranchAggregate> result =
                ConversationStatistics.queryBranchTotals(database, null, null);

            assertEquals(1, result.size());
            assertEquals("main", result.getFirst().branch());
        }
    }

    // ── Unattributed turns ───────────────────────────────────────────────────

    @Nested
    class UnattributedTurns {

        @Test
        void returnsZeroWhenAllHaveBranch() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T10:00:00Z", "t1",
                100, 200, 2, 500, "main");

            assertEquals(0, ConversationStatistics.countUnattributedTurns(database, null, null));
        }

        @Test
        void countsTurnsWithNullBranch() {
            insertTurnWithStats("s1", "Copilot", "copilot", "2026-01-15T10:00:00Z", "t1",
                100, 200, 2, 500, "main");
            // Turn without branch
            writer.recordEntries("s1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("no branch", "2026-01-16T10:00:00Z", null, "t2", "t2")));

            assertEquals(1, ConversationStatistics.countUnattributedTurns(database, null, null));
        }

        @Test
        void respectsDateRange() {
            writer.recordEntries("s1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("early", "2026-01-10T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("s1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("in range", "2026-01-15T10:00:00Z", null, "t2", "t2")));
            writer.recordEntries("s1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("late", "2026-01-20T10:00:00Z", null, "t3", "t3")));

            assertEquals(1, ConversationStatistics.countUnattributedTurns(
                database, "2026-01-12", "2026-01-18"));
        }
    }

    // ── Tool aggregates ──────────────────────────────────────────────────────

    @Nested
    class ToolAggregates {

        @Test
        void returnsEmptyForNoToolCalls() {
            assertTrue(ConversationStatistics.queryToolAggregates(database, null, null).isEmpty());
        }

        @Test
        void aggregatesToolUsage() throws Exception {
            insertMcpToolCall("s1", "t1", "read_file", "2026-01-15T10:00:00Z",
                50, 100, 500, true, "copilot");
            insertMcpToolCall("s1", "t2", "read_file", "2026-01-15T10:01:00Z",
                30, 80, 400, true, "copilot");
            insertMcpToolCall("s1", "t3", "write_file", "2026-01-15T10:02:00Z",
                100, 200, 50, false, "copilot");

            List<ConversationStatistics.ToolAggregate> result =
                ConversationStatistics.queryToolAggregates(database, null, null);

            assertEquals(2, result.size());
            // Ordered by call_count DESC: read_file (2), write_file (1)
            assertEquals("read_file", result.getFirst().toolName());
            assertEquals(2, result.getFirst().callCount());
            assertEquals(40, result.getFirst().avgDurationMs());
            assertEquals(180, result.getFirst().totalInputBytes());
            assertEquals(900, result.getFirst().totalOutputBytes());
            assertEquals(0, result.getFirst().errorCount());

            assertEquals("write_file", result.get(1).toolName());
            assertEquals(1, result.get(1).callCount());
            assertEquals(1, result.get(1).errorCount());
        }

        @Test
        void filtersByClientId() throws Exception {
            insertMcpToolCall("s1", "t1", "read_file", "2026-01-15T10:00:00Z",
                50, 100, 500, true, "copilot");
            insertMcpToolCall("s2", "t2", "write_file", "2026-01-15T10:01:00Z",
                30, 80, 400, true, "opencode");

            List<ConversationStatistics.ToolAggregate> result =
                ConversationStatistics.queryToolAggregates(database, null, "copilot");

            assertEquals(1, result.size());
            assertEquals("read_file", result.getFirst().toolName());
        }
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    @Nested
    class Summary {

        @Test
        void returnsZerosForEmptyDatabase() {
            Map<String, Long> summary = ConversationStatistics.querySummary(database, null, null);
            assertNotNull(summary);
        }

        @Test
        void aggregatesTotalMetrics() throws Exception {
            insertMcpToolCall("s1", "t1", "read_file", "2026-01-15T10:00:00Z",
                50, 100, 500, true, "copilot");
            insertMcpToolCall("s1", "t2", "write_file", "2026-01-15T10:01:00Z",
                100, 200, 300, false, "copilot");

            Map<String, Long> summary = ConversationStatistics.querySummary(database, null, null);
            assertEquals(2L, summary.get("totalCalls"));
            assertEquals(150L, summary.get("totalDurationMs"));
            assertEquals(300L, summary.get("totalInputBytes"));
            assertEquals(800L, summary.get("totalOutputBytes"));
            assertEquals(1L, summary.get("totalErrors"));
        }
    }

    // ── Distinct clients ─────────────────────────────────────────────────────

    @Nested
    class DistinctClients {

        @Test
        void returnsEmptyWhenNoToolCalls() {
            assertTrue(ConversationStatistics.getDistinctClients(database).isEmpty());
        }

        @Test
        void returnsDistinctClientIds() throws Exception {
            insertMcpToolCall("s1", "t1", "read_file", "2026-01-15T10:00:00Z",
                50, 100, 500, true, "copilot");
            insertMcpToolCall("s2", "t2", "write_file", "2026-01-15T10:01:00Z",
                30, 80, 400, true, "opencode");
            insertMcpToolCall("s3", "t3", "search", "2026-01-15T10:02:00Z",
                20, 60, 300, true, "copilot");

            List<String> clients = ConversationStatistics.getDistinctClients(database);
            assertEquals(2, clients.size());
            assertTrue(clients.contains("copilot"));
            assertTrue(clients.contains("opencode"));
        }
    }
}
