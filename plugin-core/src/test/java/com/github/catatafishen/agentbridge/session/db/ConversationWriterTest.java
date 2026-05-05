package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.FileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationWriterTest {

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

    @Test
    void writesPromptAsTurnAndSession() throws Exception {
        EntryData.Prompt prompt = new EntryData.Prompt(
            "Fix the bug", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1");

        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(prompt));

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, agent_name, client_id FROM sessions")) {
            assertTrue(rs.next());
            assertEquals("sess-1", rs.getString(1));
            assertEquals("Copilot", rs.getString(2));
            assertEquals("copilot", rs.getString(3));
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT id, session_id, prompt_text FROM turns")) {
            assertTrue(rs.next());
            assertEquals("turn-1", rs.getString(1));
            assertEquals("sess-1", rs.getString(2));
            assertEquals("Fix the bug", rs.getString(3));
        }
    }

    @Test
    void writesEventsLinkedToCurrentTurnWithSequenceNumbers() throws Exception {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Hi", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1"),
            new EntryData.Text("hello", "2026-01-01T10:00:01Z", "Copilot", "gpt-5", "ev-text"),
            new EntryData.Thinking("hmm", "2026-01-01T10:00:02Z", "Copilot", "gpt-5", "ev-think")
        ));

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT id, turn_id, sequence_num, event_type FROM events ORDER BY sequence_num")) {
            assertTrue(rs.next());
            assertEquals("ev-text", rs.getString(1));
            assertEquals("turn-1", rs.getString(2));
            assertEquals(0, rs.getInt(3));
            assertEquals("text", rs.getString(4));

            assertTrue(rs.next());
            assertEquals("ev-think", rs.getString(1));
            assertEquals(1, rs.getInt(3));
            assertEquals("thinking", rs.getString(4));
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT content FROM text_events")) {
            assertTrue(rs.next());
            assertEquals("hello", rs.getString(1));
        }
    }

    @Test
    void writesToolCallWithIsMcpDefaultZero() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall(
            "read_file", "{\"path\":\"a.txt\"}", "read", "ok", "completed",
            null, "a.txt", false, null, "agentbridge",
            "2026-01-01T10:00:01Z", "Copilot", "gpt-5", "ev-tc");

        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Hi", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1"),
            tc
        ));

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT tool_name, arguments, status, file_path, is_mcp, client_id, display_name "
                     + "FROM tool_call_events WHERE event_id = 'ev-tc'")) {
            assertTrue(rs.next());
            assertEquals("read_file", rs.getString(1));
            assertEquals("{\"path\":\"a.txt\"}", rs.getString(2));
            assertEquals("completed", rs.getString(3));
            assertEquals("a.txt", rs.getString(4));
            assertEquals(0, rs.getInt(5));
            assertEquals("copilot", rs.getString(6));
            assertEquals("agentbridge", rs.getString(7));
        }

        writer.markToolCallMcp("ev-tc");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT is_mcp FROM tool_call_events WHERE event_id = 'ev-tc'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void finaliseTurnFillsTotalsAndCommits() throws Exception {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Build feature", "2026-01-01T10:00:00Z", null,
                "turn-1", "turn-1"),
            new EntryData.TurnStats("turn-1", 5000L, 100L, 200L, 0.05, 3,
                12, 4, "gpt-5", "1.0", 0L, 0L, 0L, 0.0, 0, 0, 0,
                "2026-01-01T10:00:05Z", "stats-1",
                List.of("abc123", "def456"))
        ));

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT ended_at, model, input_tokens, output_tokens, duration_ms, "
                     + "tool_call_count, lines_added, lines_removed FROM turns")) {
            assertTrue(rs.next());
            assertEquals("2026-01-01T10:00:05Z", rs.getString(1));
            assertEquals("gpt-5", rs.getString(2));
            assertEquals(100L, rs.getLong(3));
            assertEquals(200L, rs.getLong(4));
            assertEquals(5000L, rs.getLong(5));
            assertEquals(3, rs.getInt(6));
            assertEquals(12, rs.getInt(7));
            assertEquals(4, rs.getInt(8));
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT commit_hash FROM commits ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("abc123", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("def456", rs.getString(1));
        }
    }

    @Test
    void writesContextFilesForCurrentTurn() throws Exception {
        EntryData.Prompt prompt = new EntryData.Prompt(
            "Look at this", "2026-01-01T10:00:00Z",
            List.of(new ContextFileRef("Foo.kt", "src/Foo.kt", 42)),
            "turn-1", "turn-1");

        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            prompt,
            new EntryData.ContextFiles(
                List.of(new FileRef("Bar.kt", "src/Bar.kt")), "ctx-1")
        ));

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT file_name, file_path, file_line FROM turn_context_files "
                     + "WHERE turn_id = 'turn-1' ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("Foo.kt", rs.getString(1));
            assertEquals("src/Foo.kt", rs.getString(2));
            assertEquals(42, rs.getInt(3));
            assertTrue(rs.next());
            assertEquals("Bar.kt", rs.getString(1));
            assertEquals(0, rs.getInt(3));
        }
    }

    @Test
    void recordsHookExecution() throws Exception {
        // Standalone tool call — no turn.
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.ToolCall(
                "delete_file", "{}", "fs", null, "pending",
                null, null, false, null, null,
                "2026-01-01T10:00:01Z", "", "", "ev-tc")
        ));
        // First entry was a tool call without prior prompt → cursor.turnId is null.
        // This proves standalone events work.

        writer.recordHookExecution(
            "ev-tc", "permission", "block-deletes",
            "/usr/bin/policy --check", 0, 25L,
            "{\"tool\":\"delete_file\"}", "{\"allow\":false}",
            "deny", "delete blocked by policy");

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT trigger_kind, entry_id, exit_code, duration_ms, outcome, outcome_reason "
                     + "FROM hook_executions")) {
            assertTrue(rs.next());
            assertEquals("permission", rs.getString(1));
            assertEquals("block-deletes", rs.getString(2));
            assertEquals(0, rs.getInt(3));
            assertEquals(25L, rs.getLong(4));
            assertEquals("deny", rs.getString(5));
            assertEquals("delete blocked by policy", rs.getString(6));
        }

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT turn_id FROM events WHERE id = 'ev-tc'")) {
            assertTrue(rs.next());
            // Standalone event: turn_id is NULL.
            rs.getString(1);
            assertTrue(rs.wasNull(), "Standalone tool call should have NULL turn_id");
        }
    }

    @Test
    void skipsUnsentNudges() throws Exception {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Hi", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1"),
            new EntryData.Nudge("don't forget X", "n-1", false,
                "2026-01-01T10:00:01Z", "ev-nudge-pending"),
            new EntryData.Nudge("send help", "n-2", true,
                "2026-01-01T10:00:02Z", "ev-nudge-sent")
        ));

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT event_id, text FROM nudge_events")) {
            assertTrue(rs.next());
            assertEquals("ev-nudge-sent", rs.getString(1));
            assertEquals("send help", rs.getString(2));
            org.junit.jupiter.api.Assertions.assertFalse(rs.next(),
                "Pending nudges must not be persisted");
        }
    }

    @Test
    void restoresCursorFromExistingRows() throws Exception {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Hi", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1"),
            new EntryData.Text("a", "", "", "", "ev-1"),
            new EntryData.Text("b", "", "", "", "ev-2")
        ));

        // Fresh writer (simulates restart): cursor empty.
        ConversationWriter fresh = new ConversationWriter(database);
        fresh.restoreCursor("sess-1");
        fresh.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Text("c", "", "", "", "ev-3")
        ));

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT sequence_num, turn_id FROM events WHERE id = 'ev-3'")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "Cursor should resume sequence at 2");
            assertEquals("turn-1", rs.getString(2));
        }
    }

    @Test
    void ignoresStatusAndSessionSeparator() throws Exception {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Hi", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1"),
            new EntryData.Status("⚠", "stuck", "ev-status"),
            new EntryData.SessionSeparator("2026-01-01T10:00:00Z", "Copilot", "ev-sep")
        ));
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM events")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Status and SessionSeparator should not be persisted");
        }
    }

    @Test
    void mcpFlagSurvivesReReads() throws Exception {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Hi", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1"),
            new EntryData.ToolCall("write_file", null, "fs", null, null, null, null,
                false, null, null, "", "", "", "ev-tc-mcp")
        ));
        writer.markToolCallMcp("ev-tc-mcp");

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM tool_call_events WHERE is_mcp = 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        // Sanity: marking unknown id is a no-op, not an error.
        writer.markToolCallMcp("does-not-exist");
        assertNotNull(database.getConnection());
        assertNull(null);
    }
}
