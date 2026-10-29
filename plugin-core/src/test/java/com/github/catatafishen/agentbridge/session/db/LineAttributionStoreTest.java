package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineAttributionStoreTest {

    private Connection conn;
    private LineAttributionStore store;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        ConversationDatabase database = new ConversationDatabase();
        database.initializeWithConnection(conn);
        new ConversationWriter(database).recordEntries("s1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Implement login", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Prompt("Fix the bug", "2026-01-01T11:00:00Z", null, "t2", "t2")));
        store = new LineAttributionStore(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    private static LineAttributionStore.RunRow run(String turn, int from, int to) {
        return new LineAttributionStore.RunRow(turn, 1_000L, "edit", from, to, "h" + from, false);
    }

    @Test
    void storesAndLoadsRunsWithFileHash() {
        int stored = store.replaceFile("src/A.java", "filehash", List.of(run("t1", 0, 4), run("t2", 5, 9)));

        assertEquals(2, stored);
        LineAttributionStore.Stored loaded = store.load("src/A.java");
        assertNotNull(loaded);
        assertEquals("filehash", loaded.fileHash());
        assertEquals(2, loaded.runs().size());
        assertEquals("t2", loaded.runs().get(1).turnId());
        assertEquals(9, loaded.runs().get(1).endLine());
    }

    @Test
    void replacingAFileReplacesAllItsRunsAndLeavesOtherFilesAlone() {
        store.replaceFile("A.java", "x", List.of(run("t1", 0, 4)));
        store.replaceFile("B.java", "y", List.of(run("t1", 0, 1)));

        store.replaceFile("A.java", "x2", List.of(run("t2", 3, 3)));

        assertEquals(1, store.load("A.java").runs().size());
        assertEquals("t2", store.load("A.java").runs().getFirst().turnId());
        assertNotNull(store.load("B.java"));
        assertEquals(Set.of("A.java", "B.java"), store.pathsWithAttribution());
    }

    @Test
    void runsOfUnknownTurnsAreSkippedInsteadOfFailing() {
        int stored = store.replaceFile("A.java", "x", List.of(run("t1", 0, 1), run("not-yet-saved", 2, 3)));

        assertEquals(1, stored);
        assertEquals(1, store.load("A.java").runs().size());
    }

    @Test
    void loadReturnsNullForUnknownFile() {
        assertNull(store.load("nope.java"));
    }

    @Test
    void filesForTurnSumsLines() {
        store.replaceFile("A.java", "x", List.of(run("t1", 0, 4), run("t2", 5, 6)));
        store.replaceFile("B.java", "y", List.of(run("t1", 0, 1)));

        assertEquals(Map.of("A.java", 5, "B.java", 2), store.filesForTurn("t1"));
        assertEquals(Map.of("A.java", 2), store.filesForTurn("t2"));
    }

    @Test
    void turnInfoJoinsPromptAndSession() {
        Map<String, LineAttributionStore.TurnInfo> info = store.turnInfo(List.of("t1", "t2", "missing"));

        assertEquals(2, info.size());
        assertEquals("Implement login", info.get("t1").prompt());
        assertEquals("s1", info.get("t1").sessionId());
        assertEquals("Copilot", info.get("t2").agentName());
        assertTrue(info.get("t1").startedAtMs() > 0);
    }

    @Test
    void editsForFileReturnsSuccessfulEditCallsOldestFirstForBothPathSpellings() throws Exception {
        ConversationDatabase database = new ConversationDatabase();
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        database.initializeWithConnection(c);
        new ConversationWriter(database).recordEntries("s1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Edit it", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            toolCall("ev-2", "2026-01-01T10:02:00Z", "edit_text", "{\"new_str\":\"B\"}", "src/A.java", "completed"),
            toolCall("ev-1", "2026-01-01T10:01:00Z", "write_file", "{\"content\":\"A\"}", "/abs/src/A.java", "completed"),
            toolCall("ev-3", "2026-01-01T10:03:00Z", "read_file", "{}", "src/A.java", "completed"),
            toolCall("ev-4", "2026-01-01T10:04:00Z", "edit_text", "{\"new_str\":\"C\"}", "src/Other.java", "completed")));

        List<LineAttributionStore.ToolEditRow> rows =
            new LineAttributionStore(database).editsForFile("src/A.java", "/abs/src/A.java");

        assertEquals(2, rows.size());
        assertEquals("write_file", rows.get(0).toolName());
        assertEquals("edit_text", rows.get(1).toolName());
        assertEquals("t1", rows.get(0).turnId());
        c.close();
    }

    private static EntryData.ToolCall toolCall(String id, String ts, String tool, String args, String path, String status) {
        return new EntryData.ToolCall(tool, args, "edit", "ok", status, null, path, false, null, null, ts, "Copilot", "m", id);
    }

    @Test
    void deletingATurnCascadesToItsAttribution() throws Exception {
        store.replaceFile("A.java", "x", List.of(run("t1", 0, 4), run("t2", 5, 6)));

        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM turns WHERE id = 't1'");
        }

        assertEquals(1, store.load("A.java").runs().size());
    }
}
