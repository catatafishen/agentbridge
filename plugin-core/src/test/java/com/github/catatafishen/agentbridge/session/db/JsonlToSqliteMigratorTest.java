package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.ui.EntryData;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void skipsLegacyFormatLines() throws Exception {
        String index = "[{\"id\": \"sess-1\", \"agent\": \"Copilot\"}]";
        Files.writeString(sessionsDir.resolve("sessions-index.json"), index);

        // Legacy format has "role" key, not "type"
        String jsonl = """
            {"type":"prompt","text":"New format","timestamp":"2026-01-01T10:00:00Z","entryId":"t1"}
            {"role":"assistant","parts":[{"type":"text","content":"legacy response"}]}
            """;
        Files.writeString(sessionsDir.resolve("sess-1.jsonl"), jsonl);

        int count = JsonlToSqliteMigrator.migrate(sessionsDir, writer);
        assertEquals(1, count);

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(1, entries.size()); // Only the new-format prompt
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
        Files.writeString(sessionsDir.resolve("session-abc.part-001.jsonl"), ""); // Should be excluded

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
}
