package com.github.catatafishen.agentbridge.session.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationSchemaTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void createsAllExpectedTables() throws Exception {
        ConversationSchema.createOrMigrate(conn);

        Set<String> tables = listTables();
        Set<String> expected = Set.of(
            "schema_version",
            "sessions",
            "turns",
            "turn_context_files",
            "events",
            "text_events",
            "thinking_events",
            "tool_call_events",
            "sub_agent_events",
            "nudge_events",
            "commits",
            "hook_executions"
        );
        for (String name : expected) {
            assertTrue(tables.contains(name), "Missing table: " + name + " (have " + tables + ")");
        }
    }

    @Test
    void recordsSchemaVersion() throws Exception {
        ConversationSchema.createOrMigrate(conn);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
            assertTrue(rs.next());
            assertEquals(ConversationDatabase.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        ConversationSchema.createOrMigrate(conn);
        ConversationSchema.createOrMigrate(conn); // second call must not fail

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_version")) {
            assertTrue(rs.next());
            // Only one version row should exist after re-running.
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void enforcesForeignKeyOnDeleteCascade() throws Exception {
        ConversationSchema.createOrMigrate(conn);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");

            stmt.executeUpdate(
                "INSERT INTO sessions (id, agent_name, client_id, started_at) "
                    + "VALUES ('s1', 'Copilot', 'copilot', '2026-01-01T00:00:00Z')");
            stmt.executeUpdate(
                "INSERT INTO turns (id, session_id, prompt_text, started_at) "
                    + "VALUES ('t1', 's1', 'hi', '2026-01-01T00:00:00Z')");
            stmt.executeUpdate(
                "INSERT INTO events (id, turn_id, sequence_num, event_type, timestamp) "
                    + "VALUES ('e1', 't1', 0, 'text', '2026-01-01T00:00:00Z')");

            stmt.executeUpdate("DELETE FROM sessions WHERE id = 's1'");

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM turns")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Turn should cascade-delete with session");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM events")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Event should cascade-delete with turn");
            }
        }
    }

    @Test
    void allowsStandaloneEventsWithNullTurnId() throws Exception {
        ConversationSchema.createOrMigrate(conn);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.executeUpdate(
                "INSERT INTO events (id, turn_id, sequence_num, event_type, timestamp) "
                    + "VALUES ('e-standalone', NULL, 0, 'tool_call', '2026-01-01T00:00:00Z')");
            stmt.executeUpdate(
                "INSERT INTO tool_call_events (event_id, tool_name, is_mcp) "
                    + "VALUES ('e-standalone', 'read_file', 1)");

            try (ResultSet rs = stmt.executeQuery(
                "SELECT is_mcp FROM tool_call_events WHERE event_id = 'e-standalone'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    private Set<String> listTables() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }
}
