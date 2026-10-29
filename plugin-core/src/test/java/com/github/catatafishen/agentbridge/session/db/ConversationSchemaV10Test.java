package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema v10: git-commit capture, prompt FTS index and the line attribution table.
 */
class ConversationSchemaV10Test {

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

    private String scalar(String sql) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void createsLineAttributionTableAndInferredColumn() throws Exception {
        assertEquals("0", scalar("SELECT COUNT(*) FROM line_attribution"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM turns WHERE git_info_inferred = 1"));
    }

    @Test
    void promptSearchIndexFindsTurnsAndFollowsInserts() throws Exception {
        writer.recordEntries("s1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Refactor the payment gateway", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Prompt("Fix flaky login test", "2026-01-01T10:05:00Z", null, "t2", "t2")));

        assertEquals("t1", scalar(
            "SELECT t.id FROM turns_fts f JOIN turns t ON t.rowid = f.rowid WHERE turns_fts MATCH 'payment'"));
        assertEquals("t2", scalar(
            "SELECT t.id FROM turns_fts f JOIN turns t ON t.rowid = f.rowid WHERE turns_fts MATCH 'flaky'"));
    }

    @Test
    void turnStatsPersistCommitAtStartAndEnd() throws Exception {
        writer.recordEntries("s1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Do it", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.TurnStats("t1", 10, 1, 1, 0.0, 0, 0, 0, "m", "",
                0, 0, 0, 0.0, 0, 0, 0, "2026-01-01T10:01:00Z", "t1-stats",
                List.of("a".repeat(40)), "feature/x", "feature/x", null, null,
                "b".repeat(40), "c".repeat(40))));

        assertEquals("b".repeat(40), scalar("SELECT git_commit_at_start FROM turns WHERE id = 't1'"));
        assertEquals("c".repeat(40), scalar("SELECT git_commit_at_end FROM turns WHERE id = 't1'"));
        assertEquals("feature/x", scalar("SELECT git_branch_at_start FROM turns WHERE id = 't1'"));
        assertTrue(Integer.parseInt(scalar("SELECT COUNT(*) FROM commits WHERE turn_id = 't1'")) == 1);
    }
}
