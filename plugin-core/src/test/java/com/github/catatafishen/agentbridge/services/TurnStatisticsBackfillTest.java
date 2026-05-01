package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TurnStatisticsBackfill} — verifies that turn stats entries
 * are correctly extracted from session JSONL files and inserted into the SQLite database.
 */
class TurnStatisticsBackfillTest {

    @TempDir
    Path tempDir;

    private ToolCallStatisticsService service;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbPath = tempDir.resolve("tool-stats.db");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        service = new ToolCallStatisticsService();
        service.initializeWithConnection(connection);
    }

    @AfterEach
    void tearDown() {
        service.dispose();
    }

    @Test
    @DisplayName("backfills turnStats entries from JSONL session files")
    void backfillsTurnStatsEntries() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            turnStatsEntry("2025-01-15T10:00:00Z", 100, 200, 3, 5000, 10, 2, "1x"),
            turnStatsEntry("2025-01-15T10:05:00Z", 150, 300, 5, 7000, 20, 5, "0.5x"));

        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, basePath);

        assertEquals(2, result.inserted());
        assertEquals(0, result.skipped());
        assertEquals(0, result.errors());
        assertEquals(2, service.getTurnStatsCount());
    }

    @Test
    @DisplayName("skips duplicate entries on re-run (idempotent)")
    void idempotentBackfill() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            turnStatsEntry("2025-01-15T10:00:00Z", 100, 200, 3, 5000, 10, 2, "1x"));

        TurnStatisticsBackfill.backfill(service, basePath);
        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(1, result.skipped());
        assertEquals(1, service.getTurnStatsCount());
    }

    @Test
    @DisplayName("maps session agent to correct agentId via AgentIdMapper")
    void mapsAgentToAgentId() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "Claude Code");
        createSessionJsonl(basePath, "session-1",
            turnStatsEntry("2025-01-15T10:00:00Z", 100, 200, 3, 5000, 10, 2, "1x"));

        TurnStatisticsBackfill.backfill(service, basePath);

        var agents = service.getDistinctTurnAgents();
        assertEquals(1, agents.size());
        assertTrue(agents.contains("claude-cli"));
    }

    @Test
    @DisplayName("skips non-turnStats entries in JSONL")
    void skipsNonTurnStatsEntries() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            "{\"type\":\"tool\",\"title\":\"read_file\",\"status\":\"completed\",\"timestamp\":\"2025-01-15T10:00:00Z\"}",
            turnStatsEntry("2025-01-15T10:01:00Z", 100, 200, 3, 5000, 10, 2, "1x"),
            "{\"type\":\"assistant\",\"text\":\"Hello\",\"timestamp\":\"2025-01-15T10:02:00Z\"}");

        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, basePath);

        assertEquals(1, result.inserted());
        assertEquals(1, service.getTurnStatsCount());
    }

    @Test
    @DisplayName("handles empty multiplier as 1.0 premium request")
    void emptyMultiplierDefaultsToOne() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            turnStatsEntry("2025-01-15T10:00:00Z", 100, 200, 3, 5000, 10, 2, ""));

        TurnStatisticsBackfill.backfill(service, basePath);

        var results = service.queryDailyTurnStats("2025-01-15", "2025-01-15");
        assertEquals(1, results.size());
        assertEquals(1.0, results.getFirst().premiumRequests(), 0.001);
    }

    @Test
    @DisplayName("parses fractional premium multiplier (0.5x)")
    void parsesFractionalMultiplier() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            turnStatsEntry("2025-01-15T10:00:00Z", 100, 200, 3, 5000, 10, 2, "0.5x"));

        TurnStatisticsBackfill.backfill(service, basePath);

        var results = service.queryDailyTurnStats("2025-01-15", "2025-01-15");
        assertEquals(0.5, results.getFirst().premiumRequests(), 0.001);
    }

    @Test
    @DisplayName("handles multiple sessions with different agents")
    void multipleSessions() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath,
            new String[]{"session-1", "GitHub Copilot"},
            new String[]{"session-2", "OpenCode"});
        createSessionJsonl(basePath, "session-1",
            turnStatsEntry("2025-01-15T10:00:00Z", 100, 200, 3, 5000, 10, 2, "1x"));
        createSessionJsonl(basePath, "session-2",
            turnStatsEntry("2025-01-15T11:00:00Z", 50, 100, 1, 3000, 5, 1, "1x"));

        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, basePath);

        assertEquals(2, result.inserted());
        var agents = service.getDistinctTurnAgents();
        assertEquals(2, agents.size());
        assertTrue(agents.contains("copilot"));
        assertTrue(agents.contains("opencode"));
    }

    @Test
    @DisplayName("no sessions returns empty result")
    void noSessions() {
        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, tempDir.toString());

        assertEquals(0, result.inserted());
        assertEquals(0, result.skipped());
        assertEquals(0, result.errors());
    }

    @Test
    @DisplayName("missing JSONL file is silently skipped")
    void missingJsonlFileIsSkipped() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-missing", "GitHub Copilot");

        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(0, result.errors());
    }

    @Test
    @DisplayName("entry without timestamp uses fallback from previous entry")
    void fallbackTimestamp() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            "{\"type\":\"assistant\",\"text\":\"Hello\",\"timestamp\":\"2025-01-15T10:00:00Z\"}",
            turnStatsEntryNoTimestamp());

        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, basePath);

        assertEquals(1, result.inserted());
        assertTrue(service.hasTurnStatsAt("2025-01-15T10:00:00Z"));
    }

    @Test
    @DisplayName("backfills git branch start/end and applies attribution rules")
    void backfillsGitBranchStartEnd() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1", turnStatsEntryWithBranches());

        TurnStatisticsBackfill.BackfillResult result =
            TurnStatisticsBackfill.backfill(service, basePath);

        assertEquals(1, result.inserted());
        var branches = service.queryBranchTotals("2025-01-01", "2025-01-31");
        assertEquals(1, branches.size());
        assertEquals("feat/end", branches.getFirst().branch());
    }

    @Test
    @DisplayName("BackfillResult.toString() includes all field counts")
    void backfillResultToString() {
        TurnStatisticsBackfill.BackfillResult result =
            new TurnStatisticsBackfill.BackfillResult(5, 3, 1);
        assertEquals("BackfillResult{inserted=5, skipped=3, errors=1}", result.toString());
    }

    @Test
    @DisplayName("private constructor enforces utility-class pattern")
    void constructorThrowsUtilityClassException() throws Exception {
        var constructor = TurnStatisticsBackfill.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    // --- Helper methods ---

    private static String turnStatsEntry(String timestamp, long inputTokens, long outputTokens,
                                         int toolCalls, long durationMs,
                                         int linesAdded, int linesRemoved, String multiplier) {
        return "{\"type\":\"turnStats\""
            + ",\"timestamp\":\"" + timestamp + "\""
            + ",\"inputTokens\":" + inputTokens
            + ",\"outputTokens\":" + outputTokens
            + ",\"toolCallCount\":" + toolCalls
            + ",\"durationMs\":" + durationMs
            + ",\"linesAdded\":" + linesAdded
            + ",\"linesRemoved\":" + linesRemoved
            + ",\"multiplier\":\"" + multiplier + "\""
            + "}";
    }

    private static String turnStatsEntryWithBranches() {
        return "{\"type\":\"turnStats\""
            + ",\"timestamp\":\"2025-01-15T10:00:00Z\""
            + ",\"inputTokens\":100"
            + ",\"outputTokens\":200"
            + ",\"toolCallCount\":3"
            + ",\"durationMs\":5000"
            + ",\"linesAdded\":10"
            + ",\"linesRemoved\":2"
            + ",\"multiplier\":\"1x\""
            + ",\"gitBranchStart\":\"master\""
            + ",\"gitBranchEnd\":\"feat/end\""
            + "}";
    }

    private static String turnStatsEntryNoTimestamp() {
        return "{\"type\":\"turnStats\""
            + ",\"inputTokens\":100"
            + ",\"outputTokens\":200"
            + ",\"toolCallCount\":3"
            + ",\"durationMs\":5000"
            + ",\"linesAdded\":10"
            + ",\"linesRemoved\":2"
            + ",\"multiplier\":\"1x\""
            + "}";
    }

    private static void createSessionIndex(String basePath, String sessionId,
                                           String agent) throws IOException {
        createSessionIndex(basePath, new String[]{sessionId, agent});
    }

    private static void createSessionIndex(String basePath,
                                           String[]... sessions) throws IOException {
        Path indexDir = Path.of(basePath, ".agent-work", "sessions");
        Files.createDirectories(indexDir);

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sessions.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(sessions[i][0]).append("\"")
                .append(",\"agent\":\"").append(sessions[i][1]).append("\"")
                .append(",\"name\":\"Test Session\"")
                .append(",\"createdAt\":1736935200")
                .append(",\"updatedAt\":1736942400")
                .append(",\"turnCount\":5")
                .append("}");
        }
        sb.append("]");
        Files.writeString(indexDir.resolve("sessions-index.json"), sb.toString());
    }

    private static void createSessionJsonl(String basePath, String sessionId,
                                           String... entries) throws IOException {
        Path sessionsDir = Path.of(basePath, ".agent-work", "sessions");
        Files.createDirectories(sessionsDir);
        Files.writeString(sessionsDir.resolve(sessionId + ".jsonl"),
            String.join("\n", entries) + "\n");
    }
}
