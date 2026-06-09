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
            "hook_executions",
            "graph_nodes",
            "graph_edges",
            "graph_file_index",
            "graph_commits",
            "graph_commit_files"
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

    @Test
    void v3MigrationPreservesToolCallData() throws Exception {
        // Simulate a V2 database: create the V1+V2 tables manually, then call createOrMigrate.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            stmt.execute("INSERT INTO schema_version (version, applied_at) VALUES (2, '2026-01-01')");

            stmt.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, agent_name TEXT NOT NULL, "
                + "client_id TEXT NOT NULL, display_name TEXT, started_at TEXT NOT NULL, ended_at TEXT)");
            stmt.execute("CREATE TABLE turns (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, "
                + "prompt_text TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT, model TEXT, "
                + "token_multiplier REAL, input_tokens INTEGER, output_tokens INTEGER, cost_usd REAL, "
                + "duration_ms INTEGER, tool_call_count INTEGER, lines_added INTEGER, lines_removed INTEGER, "
                + "git_branch_at_start TEXT, git_branch_at_end TEXT, git_commit_at_start TEXT, git_commit_at_end TEXT)");
            stmt.execute("CREATE TABLE events (id TEXT PRIMARY KEY, turn_id TEXT REFERENCES turns(id) ON DELETE CASCADE, "
                + "sequence_num INTEGER NOT NULL, event_type TEXT NOT NULL, agent_name TEXT, model TEXT, timestamp TEXT NOT NULL)");
            stmt.execute("CREATE TABLE tool_call_events (event_id TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE, "
                + "tool_name TEXT NOT NULL, tool_kind TEXT, category TEXT, client_id TEXT, display_name TEXT, "
                + "arguments TEXT, result TEXT, input_size_bytes INTEGER NOT NULL DEFAULT 0, "
                + "output_size_bytes INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, "
                + "success INTEGER NOT NULL DEFAULT 1, error_message TEXT, status TEXT, file_path TEXT, "
                + "auto_denied INTEGER NOT NULL DEFAULT 0, denial_reason TEXT, is_mcp INTEGER)");
            stmt.execute("CREATE TABLE nudge_events (event_id TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE, "
                + "text TEXT NOT NULL, nudge_id TEXT)");
            // Other required tables for FK constraints
            stmt.execute("CREATE TABLE text_events (event_id TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE, content TEXT NOT NULL)");
            stmt.execute("CREATE TABLE thinking_events (event_id TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE, content TEXT NOT NULL)");
            stmt.execute("CREATE TABLE sub_agent_events (event_id TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE, "
                + "agent_type TEXT NOT NULL, description TEXT, prompt_text TEXT, result_text TEXT, status TEXT, "
                + "call_id TEXT, auto_denied INTEGER NOT NULL DEFAULT 0, denial_reason TEXT)");
            stmt.execute("CREATE TABLE turn_context_files (id INTEGER PRIMARY KEY AUTOINCREMENT, turn_id TEXT NOT NULL, "
                + "file_name TEXT NOT NULL, file_path TEXT NOT NULL, file_line INTEGER NOT NULL DEFAULT 0)");
            stmt.execute("CREATE TABLE commits (id INTEGER PRIMARY KEY AUTOINCREMENT, turn_id TEXT NOT NULL, "
                + "commit_hash TEXT NOT NULL, UNIQUE(turn_id, commit_hash))");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_commits_unique ON commits(turn_id, commit_hash)");
            stmt.execute("CREATE TABLE hook_executions (id INTEGER PRIMARY KEY AUTOINCREMENT, tool_event_id TEXT NOT NULL, "
                + "trigger_kind TEXT NOT NULL, entry_id TEXT NOT NULL, command TEXT, exit_code INTEGER, "
                + "duration_ms INTEGER NOT NULL DEFAULT 0, input_payload TEXT, output_payload TEXT, "
                + "outcome TEXT NOT NULL, outcome_reason TEXT, timestamp TEXT NOT NULL)");

            // Insert test data with is_mcp set
            stmt.execute("INSERT INTO sessions VALUES ('s1','Copilot','copilot','Test','2026-01-01T00:00:00Z',NULL)");
            stmt.execute("INSERT INTO turns VALUES ('t1','s1','Hi','2026-01-01T00:00:00Z',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
            stmt.execute("INSERT INTO events VALUES ('e1','t1',0,'tool_call','Copilot',NULL,'2026-01-01T00:00:00Z')");
            stmt.execute("INSERT INTO tool_call_events (event_id, tool_name, is_mcp) VALUES ('e1', 'read_file', 1)");
        }

        // Run migration — should apply V3 (rebuild tool_call_events) and V4
        ConversationSchema.createOrMigrate(conn);

        // Verify data survived the V3 table rebuild
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT tool_name, is_mcp FROM tool_call_events WHERE event_id = 'e1'")) {
            assertTrue(rs.next(), "Tool call data must survive V3 migration");
            assertEquals("read_file", rs.getString("tool_name"));
            assertEquals(1, rs.getInt("is_mcp"));
        }
        // Verify schema version is now current
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
            assertTrue(rs.next());
            assertEquals(ConversationDatabase.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    @Test
    void v4MigrationAddsSourceColumnAndReclassifiesReprimands() throws Exception {
        // Simulate a V3 database
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            stmt.execute("INSERT INTO schema_version (version, applied_at) VALUES (3, '2026-01-01')");

            stmt.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, agent_name TEXT NOT NULL, "
                + "client_id TEXT NOT NULL, display_name TEXT, started_at TEXT NOT NULL, ended_at TEXT)");
            stmt.execute("CREATE TABLE turns (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
                + "prompt_text TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT, model TEXT, "
                + "token_multiplier REAL, input_tokens INTEGER, output_tokens INTEGER, cost_usd REAL, "
                + "duration_ms INTEGER, tool_call_count INTEGER, lines_added INTEGER, lines_removed INTEGER, "
                + "git_branch_at_start TEXT, git_branch_at_end TEXT, git_commit_at_start TEXT, git_commit_at_end TEXT)");
            stmt.execute("CREATE TABLE events (id TEXT PRIMARY KEY, turn_id TEXT, "
                + "sequence_num INTEGER NOT NULL, event_type TEXT NOT NULL, agent_name TEXT, model TEXT, timestamp TEXT NOT NULL)");
            stmt.execute("CREATE TABLE nudge_events (event_id TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE, "
                + "text TEXT NOT NULL, nudge_id TEXT)");
            // Other tables needed for FK/constraints (minimal)
            stmt.execute("CREATE TABLE text_events (event_id TEXT PRIMARY KEY, content TEXT NOT NULL)");
            stmt.execute("CREATE TABLE thinking_events (event_id TEXT PRIMARY KEY, content TEXT NOT NULL)");
            stmt.execute("CREATE TABLE tool_call_events (event_id TEXT PRIMARY KEY, tool_name TEXT NOT NULL, "
                + "tool_kind TEXT, category TEXT, client_id TEXT, display_name TEXT, arguments TEXT, result TEXT, "
                + "input_size_bytes INTEGER NOT NULL DEFAULT 0, output_size_bytes INTEGER NOT NULL DEFAULT 0, "
                + "duration_ms INTEGER NOT NULL DEFAULT 0, success INTEGER NOT NULL DEFAULT 1, error_message TEXT, "
                + "status TEXT, file_path TEXT, auto_denied INTEGER NOT NULL DEFAULT 0, denial_reason TEXT, is_mcp INTEGER)");
            stmt.execute("CREATE TABLE sub_agent_events (event_id TEXT PRIMARY KEY, agent_type TEXT NOT NULL, "
                + "description TEXT, prompt_text TEXT, result_text TEXT, status TEXT, call_id TEXT, "
                + "auto_denied INTEGER NOT NULL DEFAULT 0, denial_reason TEXT)");
            stmt.execute("CREATE TABLE turn_context_files (id INTEGER PRIMARY KEY AUTOINCREMENT, turn_id TEXT NOT NULL, "
                + "file_name TEXT NOT NULL, file_path TEXT NOT NULL, file_line INTEGER NOT NULL DEFAULT 0)");
            stmt.execute("CREATE TABLE commits (id INTEGER PRIMARY KEY AUTOINCREMENT, turn_id TEXT NOT NULL, "
                + "commit_hash TEXT NOT NULL, UNIQUE(turn_id, commit_hash))");
            stmt.execute("CREATE TABLE hook_executions (id INTEGER PRIMARY KEY AUTOINCREMENT, tool_event_id TEXT NOT NULL, "
                + "trigger_kind TEXT NOT NULL, entry_id TEXT NOT NULL, command TEXT, exit_code INTEGER, "
                + "duration_ms INTEGER NOT NULL DEFAULT 0, input_payload TEXT, output_payload TEXT, "
                + "outcome TEXT NOT NULL, outcome_reason TEXT, timestamp TEXT NOT NULL)");

            // Insert nudge data with old-style nudge IDs
            stmt.execute("INSERT INTO sessions VALUES ('s1','Copilot','copilot','Test','2026-01-01T00:00:00Z',NULL)");
            stmt.execute("INSERT INTO turns VALUES ('t1','s1','Hi','2026-01-01T00:00:00Z',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
            stmt.execute("INSERT INTO events VALUES ('e1','t1',0,'nudge','Copilot',NULL,'2026-01-01T00:00:00Z')");
            stmt.execute("INSERT INTO events VALUES ('e2','t1',1,'nudge','Copilot',NULL,'2026-01-01T00:00:01Z')");
            stmt.execute("INSERT INTO nudge_events (event_id, text, nudge_id) VALUES ('e1', 'Use MCP tool', 'reprimand-42')");
            stmt.execute("INSERT INTO nudge_events (event_id, text, nudge_id) VALUES ('e2', 'Hello', 'user-msg-1')");
        }

        // Run migration — should apply V4 only
        ConversationSchema.createOrMigrate(conn);

        // Reprimand nudge should have source = 'native_tool_reprimand'
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT source FROM nudge_events WHERE event_id = 'e1'")) {
            assertTrue(rs.next());
            assertEquals("native_tool_reprimand", rs.getString("source"));
        }
        // Normal nudge should have default source = 'human'
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT source FROM nudge_events WHERE event_id = 'e2'")) {
            assertTrue(rs.next());
            assertEquals("human", rs.getString("source"));
        }
    }

    @Test
    void v2MigrationCreatesUniqueCommitIndex() throws Exception {
        // Simulate a V1 database without the unique index on commits
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            stmt.execute("INSERT INTO schema_version (version, applied_at) VALUES (1, '2026-01-01')");

            stmt.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, agent_name TEXT NOT NULL, "
                + "client_id TEXT NOT NULL, display_name TEXT, started_at TEXT NOT NULL, ended_at TEXT)");
            stmt.execute("CREATE TABLE turns (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
                + "prompt_text TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT, model TEXT, "
                + "token_multiplier REAL, input_tokens INTEGER, output_tokens INTEGER, cost_usd REAL, "
                + "duration_ms INTEGER, tool_call_count INTEGER, lines_added INTEGER, lines_removed INTEGER, "
                + "git_branch_at_start TEXT, git_branch_at_end TEXT, git_commit_at_start TEXT, git_commit_at_end TEXT)");
            stmt.execute("CREATE TABLE events (id TEXT PRIMARY KEY, turn_id TEXT, "
                + "sequence_num INTEGER NOT NULL, event_type TEXT NOT NULL, agent_name TEXT, model TEXT, timestamp TEXT NOT NULL)");
            stmt.execute("CREATE TABLE text_events (event_id TEXT PRIMARY KEY, content TEXT NOT NULL)");
            stmt.execute("CREATE TABLE thinking_events (event_id TEXT PRIMARY KEY, content TEXT NOT NULL)");
            stmt.execute("CREATE TABLE tool_call_events (event_id TEXT PRIMARY KEY, tool_name TEXT NOT NULL, "
                + "tool_kind TEXT, category TEXT, client_id TEXT, display_name TEXT, arguments TEXT, result TEXT, "
                + "input_size_bytes INTEGER NOT NULL DEFAULT 0, output_size_bytes INTEGER NOT NULL DEFAULT 0, "
                + "duration_ms INTEGER NOT NULL DEFAULT 0, success INTEGER NOT NULL DEFAULT 1, error_message TEXT, "
                + "status TEXT, file_path TEXT, auto_denied INTEGER NOT NULL DEFAULT 0, denial_reason TEXT, is_mcp INTEGER)");
            stmt.execute("CREATE TABLE sub_agent_events (event_id TEXT PRIMARY KEY, agent_type TEXT NOT NULL, "
                + "description TEXT, prompt_text TEXT, result_text TEXT, status TEXT, call_id TEXT, "
                + "auto_denied INTEGER NOT NULL DEFAULT 0, denial_reason TEXT)");
            stmt.execute("CREATE TABLE nudge_events (event_id TEXT PRIMARY KEY, text TEXT NOT NULL, nudge_id TEXT)");
            stmt.execute("CREATE TABLE turn_context_files (id INTEGER PRIMARY KEY AUTOINCREMENT, turn_id TEXT NOT NULL, "
                + "file_name TEXT NOT NULL, file_path TEXT NOT NULL, file_line INTEGER NOT NULL DEFAULT 0)");
            stmt.execute("CREATE TABLE commits (id INTEGER PRIMARY KEY AUTOINCREMENT, turn_id TEXT NOT NULL, commit_hash TEXT NOT NULL)");
            stmt.execute("CREATE TABLE hook_executions (id INTEGER PRIMARY KEY AUTOINCREMENT, tool_event_id TEXT NOT NULL, "
                + "trigger_kind TEXT NOT NULL, entry_id TEXT NOT NULL, command TEXT, exit_code INTEGER, "
                + "duration_ms INTEGER NOT NULL DEFAULT 0, input_payload TEXT, output_payload TEXT, "
                + "outcome TEXT NOT NULL, outcome_reason TEXT, timestamp TEXT NOT NULL)");

            // Insert test commit data
            stmt.execute("INSERT INTO sessions VALUES ('s1','Copilot','copilot','Test','2026-01-01T00:00:00Z',NULL)");
            stmt.execute("INSERT INTO turns VALUES ('t1','s1','Hi','2026-01-01T00:00:00Z',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
            stmt.execute("INSERT INTO commits (turn_id, commit_hash) VALUES ('t1', 'abc123')");
        }

        // Run migration — should apply V2, V3, V4
        ConversationSchema.createOrMigrate(conn);

        // Verify unique index prevents duplicate commits
        try (Statement stmt = conn.createStatement()) {
            boolean threw = false;
            try {
                stmt.execute("INSERT INTO commits (turn_id, commit_hash) VALUES ('t1', 'abc123')");
            } catch (Exception e) {
                threw = true;
                assertTrue(e.getMessage().contains("UNIQUE"), "Expected UNIQUE constraint violation");
            }
            assertTrue(threw, "Duplicate commit insert should have thrown");
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
