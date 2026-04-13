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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolCallStatisticsBackfill} — exercises backfill from
 * session JSONL files into the SQLite database.
 */
class ToolCallStatisticsBackfillTest {

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
    @DisplayName("backfills tool entries from JSONL session files")
    void backfillsToolEntries() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            toolEntry("read_file", "completed", "2025-01-15T10:00:00Z",
                "{\"path\":\"test.java\"}", "file contents here"),
            toolEntry("search_text", "completed", "2025-01-15T10:01:00Z",
                "{\"query\":\"foo\"}", "3 matches found"));

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(2, result.inserted());
        assertEquals(0, result.skipped());
        assertEquals(0, result.errors());
        assertEquals(2, service.getRecordCount());
    }

    @Test
    @DisplayName("skips duplicate entries on re-run (idempotent)")
    void idempotentBackfill() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            toolEntry("read_file", "completed", "2025-01-15T10:00:00Z",
                "{}", "ok"));

        ToolCallStatisticsBackfill.backfill(service, basePath);
        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(1, result.skipped());
        assertEquals(1, service.getRecordCount());
    }

    @Test
    @DisplayName("maps session agent to correct clientId")
    void mapsAgentToClientId() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "Claude Code");
        createSessionJsonl(basePath, "session-1",
            toolEntry("edit_file", "completed", "2025-01-15T10:00:00Z",
                "{}", "done"));

        ToolCallStatisticsBackfill.backfill(service, basePath);

        var clients = service.getDistinctClients();
        assertEquals(1, clients.size());
        assertEquals("claude-cli", clients.getFirst());
    }

    @Test
    @DisplayName("handles failed tool entries with error message")
    void handlesFailedEntries() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            toolEntry("write_file", "error", "2025-01-15T10:00:00Z",
                "{}", "Error: file not found"));

        ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(1, service.getRecordCount());
        var errors = service.queryRecentErrors(null, null, 10);
        assertEquals(1, errors.size());
        assertEquals("Error: file not found", errors.getFirst().errorMessage());
    }

    @Test
    @DisplayName("skips non-tool entries in JSONL files")
    void skipsNonToolEntries() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        String textEntry = """
            {"type":"text","content":"hello","timestamp":"2025-01-15T10:00:00Z"}""";
        String thinkingEntry = """
            {"type":"thinking","content":"hmm","timestamp":"2025-01-15T10:00:01Z"}""";
        createSessionJsonl(basePath, "session-1",
            textEntry, thinkingEntry,
            toolEntry("read_file", "completed", "2025-01-15T10:00:02Z", "{}", "ok"));

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(1, result.inserted());
        assertEquals(0, result.errors());
    }

    @Test
    @DisplayName("returns empty result when no sessions exist")
    void noSessions() {
        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, tempDir.toString());

        assertEquals(0, result.inserted());
        assertEquals(0, result.skipped());
        assertEquals(0, result.errors());
    }

    @Test
    @DisplayName("hasRecordAt finds existing records")
    void hasRecordAt() {
        Instant ts = Instant.parse("2025-01-15T10:00:00Z");
        service.recordCall(new ToolCallRecord(
            "read_file", null, 10, 100, 50, true, null, "copilot", ts));

        assertTrue(service.hasRecordAt(ts, "read_file"));
        assertFalse(service.hasRecordAt(ts, "write_file"));
        assertFalse(service.hasRecordAt(Instant.parse("2025-01-15T11:00:00Z"), "read_file"));
    }

    @Test
    @DisplayName("getRecordCount returns correct count")
    void getRecordCount() {
        assertEquals(0, service.getRecordCount());
        service.recordCall(new ToolCallRecord(
            "tool1", null, 0, 0, 0, true, null, "copilot", Instant.now()));
        service.recordCall(new ToolCallRecord(
            "tool2", null, 0, 0, 0, true, null, "copilot", Instant.now()));
        assertEquals(2, service.getRecordCount());
    }

    @Test
    @DisplayName("backfills multiple sessions with different agents")
    void multipleSessions() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath,
            new String[]{"session-1", "GitHub Copilot"},
            new String[]{"session-2", "Claude Code"});
        createSessionJsonl(basePath, "session-1",
            toolEntry("read_file", "completed", "2025-01-15T10:00:00Z", "{}", "ok"));
        createSessionJsonl(basePath, "session-2",
            toolEntry("edit_file", "completed", "2025-01-15T11:00:00Z", "{}", "done"));

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(2, result.inserted());
        var clients = service.getDistinctClients();
        assertTrue(clients.contains("copilot"));
        assertTrue(clients.contains("claude-cli"));
    }

    @Test
    @DisplayName("invalid timestamp in tool entry is counted as error")
    void invalidTimestampCountedAsError() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        // Entry has a non-empty but unparseable timestamp → Instant.parse() throws
        createSessionJsonl(basePath, "session-1",
            toolEntry("read_file", "completed", "NOT-AN-ISO-DATE", "{}", "ok"));

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(1, result.errors(), "Invalid timestamp should increment the error count");
    }

    @Test
    @DisplayName("entry with empty tool name is ignored (not inserted and not an error)")
    void emptyToolNameIsIgnored() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            toolEntry("", "completed", "2025-01-15T10:00:00Z", "{}", "ok"));

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(0, result.errors(), "Empty title is an ignored entry, not an error");
        assertEquals(0, service.getRecordCount());
    }

    @Test
    @DisplayName("entry with empty timestamp is ignored (not inserted and not an error)")
    void emptyTimestampIsIgnored() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            toolEntry("read_file", "completed", "", "{}", "ok"));

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(0, result.errors(), "Empty timestamp is an ignored entry, not an error");
        assertEquals(0, service.getRecordCount());
    }

    @Test
    @DisplayName("kind field is stored as category on the inserted record")
    void kindFieldStoredAsCategory() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        String entryWithKind = "{\"type\":\"tool\",\"title\":\"read_file\","
            + "\"kind\":\"FILE\","
            + "\"timestamp\":\"2025-01-15T10:00:00Z\",\"status\":\"completed\","
            + "\"arguments\":\"{}\",\"result\":\"file contents\"}";
        createSessionJsonl(basePath, "session-1", entryWithKind);

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(1, result.inserted(), "Entry with kind field should be inserted");
        assertEquals(0, result.errors());
    }

    @Test
    @DisplayName("error message longer than 500 chars is truncated before storing")
    void longErrorMessageIsTruncated() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-1", "GitHub Copilot");
        createSessionJsonl(basePath, "session-1",
            toolEntry("write_file", "error", "2025-01-15T10:00:00Z", "{}", "E".repeat(600)));

        ToolCallStatisticsBackfill.backfill(service, basePath);

        var errors = service.queryRecentErrors(null, null, 10);
        assertEquals(1, errors.size());
        assertEquals(500, errors.getFirst().errorMessage().length(),
            "Error message should be truncated to 500 characters");
    }

    @Test
    @DisplayName("session in index with no corresponding JSONL file is silently skipped")
    void missingJsonlFileIsSkipped() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "session-missing", "GitHub Copilot");
        // Intentionally NOT calling createSessionJsonl — the file does not exist

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(0, result.errors(), "Missing JSONL file should be silently skipped");
        assertEquals(0, service.getRecordCount());
    }

    private static String toolEntry(String title, String status, String timestamp,
                                    String arguments, String result) {
        return "{\"type\":\"tool\",\"title\":\"" + title
            + "\",\"status\":\"" + status
            + "\",\"timestamp\":\"" + timestamp
            + "\",\"arguments\":\"" + arguments.replace("\"", "\\\"")
            + "\",\"result\":\"" + result.replace("\"", "\\\"")
            + "\"}";
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

    @Test
    @DisplayName("BackfillResult.toString() includes inserted, skipped, and errors counts")
    void backfillResultToStringIncludesAllFields() {
        ToolCallStatisticsBackfill.BackfillResult result =
            new ToolCallStatisticsBackfill.BackfillResult(5, 3, 1);
        assertEquals("BackfillResult{inserted=5, skipped=3, errors=1}", result.toString());
    }

    @Test
    @DisplayName("JSONL path that is a directory triggers IOException path (logs warning, returns zeros)")
    void backfillSessionJsonlIsDirectoryIsHandledGracefully() throws IOException {
        String basePath = tempDir.toString();
        createSessionIndex(basePath, "dir-session", "GitHub Copilot");

        // Create a directory at the JSONL path — Files.newBufferedReader will throw IOException
        Path sessionsDir = Path.of(basePath, ".agent-work", "sessions");
        Files.createDirectories(sessionsDir.resolve("dir-session.jsonl"));

        ToolCallStatisticsBackfill.BackfillResult result =
            ToolCallStatisticsBackfill.backfill(service, basePath);

        assertEquals(0, result.inserted());
        assertEquals(0, result.errors());
        assertEquals(0, service.getRecordCount());
    }

    @Test
    @DisplayName("private constructor enforces utility-class pattern")
    void constructorThrowsUtilityClassException() throws Exception {
        var constructor = ToolCallStatisticsBackfill.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }
}
