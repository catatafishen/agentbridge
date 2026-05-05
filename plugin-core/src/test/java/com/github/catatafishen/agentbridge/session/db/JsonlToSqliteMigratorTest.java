package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link JsonlToSqliteMigrator} — the JSONL → SQLite migration path.
 * Uses real JSONL files in a temp directory and an in-memory SQLite database.
 * No IntelliJ platform dependencies.
 */
class JsonlToSqliteMigratorTest {

    @TempDir
    Path sessionsDir;

    private Connection conn;
    private ConversationDatabase database;
    private ConversationWriter writer;
    private ConversationReader reader;

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
        reader = new ConversationReader(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void migratesEmptyDirectoryReturnsZero() {
        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(0, count);
    }

    @Test
    void migratesSingleSessionFromJsonl() throws Exception {
        // Write a sessions-index.json
        String index = """
            [{"id": "sess-1", "agent": "Copilot"}]
            """;
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        // Write a JSONL file for sess-1
        String jsonl = """
            {"type":"prompt","text":"Fix the bug","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
            {"type":"text","raw":"Here is the fix","timestamp":"2026-01-01T10:00:01Z","agent":"assistant","model":"gpt-4","entryId":"e1"}
            {"type":"turnStats","turnId":"t1","durationMs":5000,"inputTokens":100,"outputTokens":200,"costUsd":0.01,"toolCallCount":1,"linesAdded":5,"linesRemoved":2,"model":"gpt-4","timestamp":"2026-01-01T10:05:00Z","entryId":"s1"}
            """;
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), jsonl);

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count);

        // Verify data was written to SQLite
        assertTrue(reader.sessionExists("sess-1"));
        List<EntryData> entries = reader.loadEntries("sess-1");
        assertFalse(entries.isEmpty());
        // Should have: prompt + text + turnStats
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Prompt));
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Text));
    }

    @Test
    void migratesMultipleSessions() throws Exception {
        String index = """
            [
              {"id": "sess-1", "agent": "Copilot"},
              {"id": "sess-2", "agent": "Claude"}
            ]
            """;
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        Files.writeString(sessionsDir.resolve("sess-1.jsonl"),
            "{\"type\":\"prompt\",\"text\":\"First\",\"timestamp\":\"2026-01-01T10:00:00Z\",\"entryId\":\"t1\"}\n");
        Files.writeString(sessionsDir.resolve("sess-2.jsonl"),
            "{\"type\":\"prompt\",\"text\":\"Second\",\"timestamp\":\"2026-01-02T10:00:00Z\",\"entryId\":\"t2\"}\n");

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(2, count);

        assertTrue(reader.sessionExists("sess-1"));
        assertTrue(reader.sessionExists("sess-2"));
    }

    @Test
    void migratesToolCallEntries() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        String jsonl = """
            {"type":"prompt","text":"Help","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
            {"type":"tool","title":"agentbridge-read_file","arguments":"{\\"path\\":\\"/src/Main.java\\"}","kind":"file","result":"contents","status":"success","timestamp":"2026-01-01T10:00:01Z","agent":"assistant","model":"gpt-4","entryId":"e1"}
            """;
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), jsonl);

        JsonlToSqliteMigrator.migrate(sessionsDir, writer);

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.ToolCall));
        EntryData.ToolCall tc = entries.stream()
            .filter(e -> e instanceof EntryData.ToolCall)
            .map(e -> (EntryData.ToolCall) e)
            .findFirst().orElseThrow();
        assertEquals("agentbridge-read_file", tc.getTitle());
    }

    @Test
    void skipsMalformedLines() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        String jsonl = """
            {"type":"prompt","text":"Good line","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
            this is not valid json
            {"type":"text","raw":"Also good","timestamp":"2026-01-01T10:00:01Z","agent":"a","model":"m","entryId":"e1"}
            """;
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), jsonl);

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count);

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(2, entries.size()); // prompt + text (malformed line skipped)
    }

    @Test
    void migratesLegacyFormatLines() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        // Legacy format has "role" key instead of "type"; entries inside "parts" use "type" for
        // the part kind — this is the exact pattern that caused isEntryFormat() to return true
        // incorrectly (false positive from nested "type":) yet produce null from deserialize().
        String jsonl = """
                {"type":"prompt","text":"New format","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
                {"id":"msg-1","role":"assistant","parts":[{"type":"text","text":"legacy response"}],"createdAt":1735689601000,"agent":"Copilot"}
                """;
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), jsonl);

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count);

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(2, entries.size()); // new-format prompt + legacy assistant text
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Prompt));
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Text));
    }

    @Test
    void discoversSessionsWithoutIndex() throws Exception {
        // No sessions-index.json — should scan for .jsonl files
        Files.writeString(sessionsDir.resolve("abc-123.jsonl"),
            "{\"type\":\"prompt\",\"text\":\"Found\",\"timestamp\":\"2026-01-01T10:00:00Z\",\"entryId\":\"t1\"}\n");

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count);
        assertTrue(reader.sessionExists("abc-123"));
    }

    @Test
    void migratesPartFiles() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        // Part file (older entries)
        Files.writeString(sessionsDir.resolve("sess-1.part-001.jsonl"),
            "{\"type\":\"prompt\",\"text\":\"First prompt\",\"timestamp\":\"2026-01-01T09:00:00Z\",\"entryId\":\"t0\"}\n");
        // Active file (newer entries)
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"),
            "{\"type\":\"prompt\",\"text\":\"Second prompt\",\"timestamp\":\"2026-01-01T10:00:00Z\",\"entryId\":\"t1\"}\n");

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count);

        List<EntryData> entries = reader.loadEntries("sess-1");
        // Both prompts should be migrated
        long promptCount = entries.stream().filter(e -> e instanceof EntryData.Prompt).count();
        assertEquals(2, promptCount);
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"),
            "{\"type\":\"prompt\",\"text\":\"Hello\",\"timestamp\":\"2026-01-01T10:00:00Z\",\"entryId\":\"t1\"}\n");

        int firstCount = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, firstCount);

        // JSONL files are moved to backup on first migration — second run finds nothing
        int secondCount = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(0, secondCount);

        List<ConversationReader.SessionRecord> sessions = reader.listSessions();
        assertEquals(1, sessions.size()); // No duplicates in SQLite
    }

    @Test
    void parseJsonlFileDirectly() throws Exception {
        Path file = sessionsDir.resolve("test.jsonl");
        Files.writeString(file, """
            {"type":"prompt","text":"Test","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
            {"type":"thinking","raw":"hmm","timestamp":"2026-01-01T10:00:01Z","agent":"a","model":"m","entryId":"e1"}
            """);

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseJsonlFile(file, entries);
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.get(0));
        assertInstanceOf(EntryData.Thinking.class, entries.get(1));
    }

    @Test
    void discoverSessionsReadsIndex() throws Exception {
        Files.writeString(sessionsDir.resolve("sessions-index.json"),
            "[{\"id\":\"s1\",\"agent\":\"A\"},{\"id\":\"s2\",\"agent\":\"B\"}]");

        List<JsonlToSqliteMigrator.SessionInfo> sessions =
            JsonlToSqliteMigrator.discoverSessions(sessionsDir);
        assertEquals(2, sessions.size());
        assertEquals("s1", sessions.getFirst().id());
        assertEquals("A", sessions.getFirst().agent());
    }

    @Test
    void discoverSessionsMergesIndexWithFileScan() throws Exception {
        // Index has s1; s2 is a JSONL file not listed in the index.
        Files.writeString(sessionsDir.resolve("sessions-index.json"),
            "[{\"id\":\"s1\",\"agent\":\"A\"}]");
        Files.writeString(sessionsDir.resolve("s1.jsonl"), "");
        Files.writeString(sessionsDir.resolve("s2.jsonl"), "");

        List<JsonlToSqliteMigrator.SessionInfo> sessions =
            JsonlToSqliteMigrator.discoverSessions(sessionsDir);

        assertEquals(2, sessions.size());
        // Index entry retains its agent name
        var s1 = sessions.stream().filter(s -> s.id().equals("s1")).findFirst().orElseThrow();
        assertEquals("A", s1.agent());
        // Scanned entry gets Unknown agent
        var s2 = sessions.stream().filter(s -> s.id().equals("s2")).findFirst().orElseThrow();
        assertEquals("Unknown", s2.agent());
    }

    @Test
    void discoverSessionsFallsBackToFileScan() throws Exception {
        Files.writeString(sessionsDir.resolve("session-abc.jsonl"), "");
        Files.writeString(sessionsDir.resolve("session-def.jsonl"), "");
        // Part file for session-abc — contributes the same session ID, deduplicated.
        Files.writeString(sessionsDir.resolve("session-abc.part-001.jsonl"), "");

        List<JsonlToSqliteMigrator.SessionInfo> sessions =
            JsonlToSqliteMigrator.discoverSessions(sessionsDir);
        assertEquals(2, sessions.size());
    }

    @Test
    void migratesSubAgentEntries() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        String jsonl = """
            {"type":"prompt","text":"Help","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
            {"type":"subagent","agentType":"explore","description":"Find auth module","timestamp":"2026-01-01T10:00:01Z","agent":"assistant","model":"claude","entryId":"e1"}
            """;
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), jsonl);

        JsonlToSqliteMigrator.migrate(sessionsDir, writer);

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.SubAgent));
    }

    @Test
    void migratesNudgeEntries() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        String jsonl = """
            {"type":"prompt","text":"Help","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
            {"type":"nudge","text":"Use read_file","id":"n1","sent":true,"timestamp":"2026-01-01T10:00:01Z","entryId":"e1"}
            """;
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), jsonl);

        JsonlToSqliteMigrator.migrate(sessionsDir, writer);

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Nudge));
    }

    @Test
    void handlesEmptyJsonlFile() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), "");

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(0, count); // Empty file = no entries = not migrated
    }

    // ── Regression tests for the bugs fixed in feat/sqlite-only-storage ───────

    /**
     * sessions.started_at must equal the timestamp from the first JSONL entry,
     * never the migration wall-clock time.
     */
    @Test
    void sessionStartedAtMatchesFirstEntryTimestamp() throws Exception {
        Files.writeString(sessionsDir.resolve("sessions-index.json"),
            "[{\"id\": \"sess-ts\", \"agent\": \"Copilot\"}]");
        Files.writeString(sessionsDir.resolve("sess-ts.jsonl"), """
            {"type":"prompt","text":"Hi","timestamp":"2025-03-10T08:30:00Z","entryId":"t1"}
            {"type":"turnStats","turnId":"t1","durationMs":1000,"inputTokens":10,"outputTokens":20,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"gpt-4","timestamp":"2025-03-10T08:30:01Z","entryId":"s1"}
            """);

        JsonlToSqliteMigrator.migrate(sessionsDir, writer);

        List<ConversationReader.SessionRecord> sessions = reader.listSessions();
        assertEquals(1, sessions.size());
        String startedAt = sessions.getFirst().startedAt();
        assertEquals("2025-03-10T08:30:00Z", startedAt,
            "started_at must come from the JSONL entry, not Instant.now()");
    }

    /**
     * Multiple sessions must each be migrated as their own distinct session row with
     * the correct number of turns.
     */
    @Test
    void multipleSessionsAllMigratedWithCorrectTurnCounts() throws Exception {
        String index = """
            [
              {"id": "session-a", "agent": "Copilot"},
              {"id": "session-b", "agent": "Claude"},
              {"id": "session-c", "agent": "Gemini"}
            ]
            """;
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        // session-a: 3 turns
        Files.writeString(sessionsDir.resolve("session-a.jsonl"), """
            {"type":"prompt","text":"P1","timestamp":"2025-01-01T10:00:00Z","entryId":"a-t1"}
            {"type":"turnStats","turnId":"a-t1","durationMs":1000,"inputTokens":5,"outputTokens":10,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-01-01T10:01:00Z","entryId":"a-s1"}
            {"type":"prompt","text":"P2","timestamp":"2025-01-01T10:02:00Z","entryId":"a-t2"}
            {"type":"turnStats","turnId":"a-t2","durationMs":500,"inputTokens":3,"outputTokens":6,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-01-01T10:03:00Z","entryId":"a-s2"}
            {"type":"prompt","text":"P3","timestamp":"2025-01-01T10:04:00Z","entryId":"a-t3"}
            {"type":"turnStats","turnId":"a-t3","durationMs":200,"inputTokens":2,"outputTokens":4,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-01-01T10:05:00Z","entryId":"a-s3"}
            """);

        // session-b: 1 turn
        Files.writeString(sessionsDir.resolve("session-b.jsonl"), """
            {"type":"prompt","text":"Q1","timestamp":"2025-01-02T09:00:00Z","entryId":"b-t1"}
            {"type":"turnStats","turnId":"b-t1","durationMs":800,"inputTokens":8,"outputTokens":16,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-01-02T09:01:00Z","entryId":"b-s1"}
            """);

        // session-c: 2 turns
        Files.writeString(sessionsDir.resolve("session-c.jsonl"), """
            {"type":"prompt","text":"R1","timestamp":"2025-01-03T11:00:00Z","entryId":"c-t1"}
            {"type":"turnStats","turnId":"c-t1","durationMs":300,"inputTokens":1,"outputTokens":2,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-01-03T11:01:00Z","entryId":"c-s1"}
            {"type":"prompt","text":"R2","timestamp":"2025-01-03T11:02:00Z","entryId":"c-t2"}
            {"type":"turnStats","turnId":"c-t2","durationMs":400,"inputTokens":2,"outputTokens":4,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-01-03T11:03:00Z","entryId":"c-s2"}
            """);

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(3, count);

        List<ConversationReader.SessionRecord> sessions = reader.listSessions();
        assertEquals(3, sessions.size(), "All three sessions must appear in SQLite");

        Map<String, Integer> turnCounts = new HashMap<>();
        for (ConversationReader.SessionRecord s : sessions) {
            turnCounts.put(s.id(), s.turnCount());
        }
        assertEquals(3, turnCounts.get("session-a"), "session-a must have 3 turns");
        assertEquals(1, turnCounts.get("session-b"), "session-b must have 1 turn");
        assertEquals(2, turnCounts.get("session-c"), "session-c must have 2 turns");
    }

    /**
     * A session that has only .part-NNN.jsonl files (no active .jsonl) must be
     * discovered and migrated.  This is the root cause of "single session with all turns".
     */
    @Test
    void sessionWithOnlyPartFilesIsDiscoveredAndMigrated() throws Exception {
        // No sessions-index.json — rely solely on directory scan.
        // The session has been fully rotated: only part files remain.
        Files.writeString(sessionsDir.resolve("rotated-sess.part-001.jsonl"), """
            {"type":"prompt","text":"OldPrompt","timestamp":"2024-12-01T08:00:00Z","entryId":"rp1"}
            {"type":"turnStats","turnId":"rp1","durationMs":1000,"inputTokens":10,"outputTokens":20,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"gpt-4","timestamp":"2024-12-01T08:01:00Z","entryId":"rs1"}
            """);
        Files.writeString(sessionsDir.resolve("rotated-sess.part-002.jsonl"), """
            {"type":"prompt","text":"NewerPrompt","timestamp":"2024-12-02T09:00:00Z","entryId":"rp2"}
            {"type":"turnStats","turnId":"rp2","durationMs":500,"inputTokens":5,"outputTokens":10,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"gpt-4","timestamp":"2024-12-02T09:01:00Z","entryId":"rs2"}
            """);
        // No "rotated-sess.jsonl" base file exists.

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count, "Part-only session must be migrated");

        assertTrue(reader.sessionExists("rotated-sess"),
            "rotated-sess must exist in SQLite");
        List<ConversationReader.SessionRecord> sessions = reader.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(2, sessions.getFirst().turnCount(),
            "Both turns from both part files must be migrated");
        assertEquals("2024-12-01T08:00:00Z", sessions.getFirst().startedAt(),
            "started_at must come from the oldest part file entry");
    }

    /**
     * Each session must receive only its own turns — entries from session A must never
     * appear under session B.
     */
    @Test
    void eachSessionReceivesOnlyItsOwnEntries() throws Exception {
        String index = """
            [
              {"id": "alpha", "agent": "Copilot"},
              {"id": "beta",  "agent": "Claude"}
            ]
            """;
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        Files.writeString(sessionsDir.resolve("alpha.jsonl"), """
            {"type":"prompt","text":"Alpha question","timestamp":"2025-06-01T10:00:00Z","entryId":"alpha-t1"}
            {"type":"text","raw":"Alpha answer","timestamp":"2025-06-01T10:00:01Z","agent":"assistant","model":"m","entryId":"alpha-e1"}
            {"type":"turnStats","turnId":"alpha-t1","durationMs":100,"inputTokens":1,"outputTokens":2,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-06-01T10:00:02Z","entryId":"alpha-s1"}
            """);

        Files.writeString(sessionsDir.resolve("beta.jsonl"), """
            {"type":"prompt","text":"Beta question","timestamp":"2025-06-02T10:00:00Z","entryId":"beta-t1"}
            {"type":"text","raw":"Beta answer","timestamp":"2025-06-02T10:00:01Z","agent":"assistant","model":"m","entryId":"beta-e1"}
            {"type":"turnStats","turnId":"beta-t1","durationMs":100,"inputTokens":1,"outputTokens":2,"costUsd":0.0,"toolCallCount":0,"linesAdded":0,"linesRemoved":0,"model":"m","timestamp":"2025-06-02T10:00:02Z","entryId":"beta-s1"}
            """);

        JsonlToSqliteMigrator.migrate(sessionsDir, writer);

        List<EntryData> alphaEntries = reader.loadEntries("alpha");
        List<EntryData> betaEntries = reader.loadEntries("beta");

        assertTrue(alphaEntries.stream().anyMatch(e ->
            e instanceof EntryData.Prompt p && "Alpha question".equals(p.getText())));
        assertTrue(betaEntries.stream().anyMatch(e ->
            e instanceof EntryData.Prompt p && "Beta question".equals(p.getText())));

        // Cross-contamination check: alpha must not contain beta's entries and vice versa.
        assertFalse(alphaEntries.stream().anyMatch(e ->
                e instanceof EntryData.Prompt p && "Beta question".equals(p.getText())),
            "Alpha session must not contain Beta's prompt");
        assertFalse(betaEntries.stream().anyMatch(e ->
                e instanceof EntryData.Prompt p && "Alpha question".equals(p.getText())),
            "Beta session must not contain Alpha's prompt");
    }

    /**
     * extractSessionIdFromFilename must correctly strip both the active extension
     * and part-file extensions.
     */
    @Test
    void extractSessionIdFromFilenameHandlesBothFileTypes() {
        assertEquals("my-session",
            JsonlToSqliteMigrator.extractSessionIdFromFilename("my-session.jsonl"));
        assertEquals("my-session",
            JsonlToSqliteMigrator.extractSessionIdFromFilename("my-session.part-001.jsonl"));
        assertEquals("my-session",
            JsonlToSqliteMigrator.extractSessionIdFromFilename("my-session.part-099.jsonl"));
        assertNull(
            JsonlToSqliteMigrator.extractSessionIdFromFilename("README.txt"),
            "Non-JSONL file should return null");
    }

    // ── Legacy role/parts format tests ────────────────────────────────────────

    /**
     * Regression test for the root bug: a JSONL file whose lines are entirely in the legacy
     * role/parts format was never migrated because isEntryFormat() had a false positive
     * (matched the "type" string nested inside "parts") while deserialize() returned null
     * (no top-level "type" key), leaving entries empty and skipping the session entirely.
     */
    @Test
    void migratesFileWithOnlyLegacyEntries() throws Exception {
        // No sessions-index.json — session discovered via file scan
        String jsonl = """
                {"id":"user-1","role":"user","parts":[{"type":"text","text":"Fix the bug"}],"createdAt":1735689601000}
                {"id":"asst-1","role":"assistant","parts":[{"type":"reasoning","text":"Let me look..."},{"type":"text","text":"Here is the fix"}],"createdAt":1735689602000,"agent":"Copilot"}
                """;
        Files.writeString(sessionsDir.resolve("abc-session.jsonl"), jsonl);

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count); // session must be migrated, not skipped

        assertTrue(reader.sessionExists("abc-session"));
        List<EntryData> entries = reader.loadEntries("abc-session");
        // user message → Prompt; assistant reasoning → Thinking; assistant text → Text
        assertEquals(3, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Prompt));
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Thinking));
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Text));
    }

    @Test
    void parsesLegacyUserMessageAsPrompt() {
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"Hello\"}],\"createdAt\":1735689601000}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.getFirst());
        assertEquals("Hello", ((EntryData.Prompt) entries.getFirst()).getText());
        assertEquals("2025-01-01T00:00:01Z", entries.getFirst().getTimestamp());
    }

    @Test
    void parsesLegacyAssistantTextAsText() {
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"text\":\"response\"}],\"createdAt\":1735689601000,\"agent\":\"Copilot\"}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.Text.class, entries.getFirst());
        EntryData.Text text = (EntryData.Text) entries.getFirst();
        assertEquals("response", text.getRaw());
        assertEquals("Copilot", text.getAgent());
    }

    @Test
    void parsesLegacyReasoningAsThinking() {
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"assistant\",\"parts\":[{\"type\":\"reasoning\",\"text\":\"hmm...\"}],\"createdAt\":1735689601000,\"agent\":\"Copilot\"}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.Thinking.class, entries.getFirst());
        assertEquals("hmm...", ((EntryData.Thinking) entries.getFirst()).getRaw());
    }

    @Test
    void parsesLegacyToolInvocationAsToolCall() {
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"assistant\",\"parts\":[{\"type\":\"tool-invocation\",\"toolInvocation\":{\"toolName\":\"read_file\",\"args\":\"{}\",\"result\":\"contents\",\"state\":\"result\"}}],\"createdAt\":1735689601000,\"agent\":\"Copilot\"}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.ToolCall.class, entries.getFirst());
        EntryData.ToolCall tc = (EntryData.ToolCall) entries.getFirst();
        assertEquals("read_file", tc.getTitle());
        assertEquals("{}", tc.getArguments());
        assertEquals("contents", tc.getResult());
        assertEquals("result", tc.getStatus());
    }

    @Test
    void parsesLegacySubagentAsSubAgent() {
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"assistant\",\"parts\":[{\"type\":\"subagent\",\"agentType\":\"intellij-explore\",\"description\":\"Find auth\",\"prompt\":\"Find the auth module\",\"result\":\"Found it\",\"status\":\"completed\",\"colorIndex\":2}],\"createdAt\":1735689601000,\"agent\":\"Copilot\"}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.SubAgent.class, entries.getFirst());
        EntryData.SubAgent sa = (EntryData.SubAgent) entries.getFirst();
        assertEquals("intellij-explore", sa.getAgentType());
        assertEquals("Find auth", sa.getDescription());
        assertEquals("Find the auth module", sa.getPrompt());
        assertEquals("Found it", sa.getResult());
        assertEquals("completed", sa.getStatus());
        assertEquals(2, sa.getColorIndex());
    }

    @Test
    void parsesLegacyMultiPartMessageGeneratesMultipleEntries() {
        // A single assistant message with reasoning + text produces two separate entries,
        // each with a unique entryId derived from the message id.
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-abc\",\"role\":\"assistant\",\"parts\":[{\"type\":\"reasoning\",\"text\":\"thinking\"},{\"type\":\"text\",\"text\":\"answer\"}],\"createdAt\":1735689601000,\"agent\":\"Copilot\"}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.Thinking.class, entries.get(0));
        assertInstanceOf(EntryData.Text.class, entries.get(1));
        // Each part gets a distinct entryId
        assertNotEquals(entries.get(0).getEntryId(), entries.get(1).getEntryId());
    }

    @Test
    void parsesLegacyTimestampFromCreatedAt() {
        // createdAt in milliseconds should be converted to ISO-8601 timestamp
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}],\"createdAt\":1735689601000}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(1, entries.size());
        assertEquals("2025-01-01T00:00:01Z", entries.getFirst().getTimestamp());
    }

    @Test
    void parsesLegacyMissingCreatedAtUsesEmptyTimestamp() {
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}]}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(1, entries.size());
        assertEquals("", entries.getFirst().getTimestamp());
    }

    @Test
    void parsesLegacyUnknownPartTypeSkipped() {
        // Parts with types we don't know about should not cause failures or produce entries.
        JsonObject obj = JsonParser.parseString(
            "{\"id\":\"msg-1\",\"role\":\"assistant\",\"parts\":[{\"type\":\"unknown-future-type\",\"data\":\"x\"}],\"createdAt\":1735689601000}"
        ).getAsJsonObject();

        List<EntryData> entries = new ArrayList<>();
        JsonlToSqliteMigrator.parseLegacyMessage(obj, entries);

        assertEquals(0, entries.size());
    }
}
