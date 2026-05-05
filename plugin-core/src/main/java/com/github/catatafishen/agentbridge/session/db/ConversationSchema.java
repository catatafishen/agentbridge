package com.github.catatafishen.agentbridge.session.db;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DDL for the unified conversation history database.
 *
 * <p>Implements the schema documented in {@code scratches/conversation-db-er.md}
 * with one addition: the {@code hook_executions} table tracks each hook fire
 * for tool calls, capturing trigger, command, exit code, and input/output
 * payloads.
 *
 * <p>The {@code schema_version} table records the current
 * {@link ConversationDatabase#SCHEMA_VERSION} so future migrations can be applied
 * incrementally. On a fresh database all CREATE statements run; on an existing
 * one only migrations whose version is higher than the stored value run.
 */
final class ConversationSchema {

    private static final Logger LOG = Logger.getInstance(ConversationSchema.class);

    private ConversationSchema() {
    }

    /**
     * Creates the schema if missing, or applies any pending migrations on an
     * existing database. Idempotent.
     */
    static void createOrMigrate(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Schema version tracking — must come first.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version  INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL
                )
                """);

            int currentVersion = readSchemaVersion(conn);
            if (currentVersion >= ConversationDatabase.SCHEMA_VERSION) {
                LOG.debug("ConversationDatabase schema is up to date at v" + currentVersion);
                return;
            }

            if (currentVersion < 1) applyV1(stmt);
            if (currentVersion < 2) applyV2(stmt);

            stmt.executeUpdate(
                "INSERT INTO schema_version (version, applied_at) VALUES ("
                    + ConversationDatabase.SCHEMA_VERSION
                    + ", datetime('now'))");
            LOG.info("ConversationDatabase migrated to schema v" + ConversationDatabase.SCHEMA_VERSION);
        }
    }

    private static int readSchemaVersion(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
            if (rs.next()) {
                int v = rs.getInt(1);
                if (!rs.wasNull()) return v;
            }
            return 0;
        }
    }

    /**
     * Initial schema. Creates all tables and indexes.
     */
    private static void applyV1(@NotNull Statement stmt) throws SQLException {
        // ── Sessions ──────────────────────────────────────────────────────────
        stmt.execute("""
            CREATE TABLE sessions (
                id            TEXT PRIMARY KEY,
                agent_name    TEXT NOT NULL,
                client_id     TEXT NOT NULL,
                display_name  TEXT,
                started_at    TEXT NOT NULL,
                ended_at      TEXT
            )
            """);
        stmt.execute("CREATE INDEX idx_sessions_started ON sessions(started_at DESC)");

        // ── Turns (= prompts, 1 turn = 1 prompt) ─────────────────────────────
        stmt.execute("""
            CREATE TABLE turns (
                id                   TEXT PRIMARY KEY,
                session_id           TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                prompt_text          TEXT NOT NULL,
                started_at           TEXT NOT NULL,
                ended_at             TEXT,
                model                TEXT,
                token_multiplier     REAL,
                input_tokens         INTEGER,
                output_tokens        INTEGER,
                cost_usd             REAL,
                duration_ms          INTEGER,
                tool_call_count      INTEGER,
                lines_added          INTEGER,
                lines_removed        INTEGER,
                git_branch_at_start  TEXT,
                git_branch_at_end    TEXT,
                git_commit_at_start  TEXT,
                git_commit_at_end    TEXT
            )
            """);
        stmt.execute("CREATE INDEX idx_turns_session_time ON turns(session_id, started_at DESC)");
        stmt.execute("CREATE INDEX idx_turns_started_at ON turns(started_at)");
        stmt.execute("CREATE INDEX idx_turns_branch ON turns(git_branch_at_start)");

        // ── Turn context files (files attached to the prompt) ────────────────
        stmt.execute("""
            CREATE TABLE turn_context_files (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                turn_id     TEXT NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
                file_name   TEXT NOT NULL,
                file_path   TEXT NOT NULL,
                file_line   INTEGER NOT NULL DEFAULT 0
            )
            """);
        stmt.execute("CREATE INDEX idx_tcf_turn ON turn_context_files(turn_id)");

        // ── Events (parent table for all event subtypes) ─────────────────────
        // turn_id is nullable: standalone tool calls (outside any ACP turn)
        // have no turn — and consequently no session.
        stmt.execute("""
            CREATE TABLE events (
                id            TEXT PRIMARY KEY,
                turn_id       TEXT REFERENCES turns(id) ON DELETE CASCADE,
                sequence_num  INTEGER NOT NULL,
                event_type    TEXT NOT NULL,
                agent_name    TEXT,
                model         TEXT,
                timestamp     TEXT NOT NULL
            )
            """);
        stmt.execute("CREATE INDEX idx_events_turn_seq ON events(turn_id, sequence_num)");
        stmt.execute("CREATE INDEX idx_events_type_time ON events(event_type, timestamp)");
        stmt.execute(
            "CREATE INDEX idx_events_standalone ON events(event_type) WHERE turn_id IS NULL");

        // ── Event subtype tables ─────────────────────────────────────────────
        stmt.execute("""
            CREATE TABLE text_events (
                event_id  TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
                content   TEXT NOT NULL
            )
            """);

        stmt.execute("""
            CREATE TABLE thinking_events (
                event_id  TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
                content   TEXT NOT NULL
            )
            """);

        stmt.execute("""
            CREATE TABLE tool_call_events (
                event_id          TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
                tool_name         TEXT NOT NULL,
                tool_kind         TEXT,
                category          TEXT,
                client_id         TEXT,
                display_name      TEXT,
                arguments         TEXT,
                result            TEXT,
                input_size_bytes  INTEGER NOT NULL DEFAULT 0,
                output_size_bytes INTEGER NOT NULL DEFAULT 0,
                duration_ms       INTEGER NOT NULL DEFAULT 0,
                success           INTEGER NOT NULL DEFAULT 1,
                error_message     TEXT,
                status            TEXT,
                file_path         TEXT,
                auto_denied       INTEGER NOT NULL DEFAULT 0,
                denial_reason     TEXT,
                is_mcp            INTEGER NOT NULL DEFAULT 0
            )
            """);
        stmt.execute("CREATE INDEX idx_tc_tool_name ON tool_call_events(tool_name)");
        stmt.execute("CREATE INDEX idx_tc_client ON tool_call_events(client_id)");
        stmt.execute("CREATE INDEX idx_tc_is_mcp ON tool_call_events(is_mcp)");

        stmt.execute("""
            CREATE TABLE sub_agent_events (
                event_id      TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
                agent_type    TEXT NOT NULL,
                description   TEXT,
                prompt_text   TEXT,
                result_text   TEXT,
                status        TEXT,
                call_id       TEXT,
                auto_denied   INTEGER NOT NULL DEFAULT 0,
                denial_reason TEXT
            )
            """);

        stmt.execute("""
            CREATE TABLE nudge_events (
                event_id  TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
                text      TEXT NOT NULL,
                nudge_id  TEXT
            )
            """);

        // ── Commits produced during a turn ───────────────────────────────────
        stmt.execute("""
            CREATE TABLE commits (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                turn_id     TEXT NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
                commit_hash TEXT NOT NULL,
                UNIQUE(turn_id, commit_hash)
            )
            """);
        stmt.execute("CREATE INDEX idx_commits_turn ON commits(turn_id)");

        // ── Hook executions (one row per hook fire for a tool call) ──────────
        stmt.execute("""
            CREATE TABLE hook_executions (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                tool_event_id   TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                trigger_kind    TEXT NOT NULL,
                entry_id        TEXT NOT NULL,
                command         TEXT,
                exit_code       INTEGER,
                duration_ms     INTEGER NOT NULL DEFAULT 0,
                input_payload   TEXT,
                output_payload  TEXT,
                outcome         TEXT NOT NULL,
                outcome_reason  TEXT,
                timestamp       TEXT NOT NULL
            )
            """);
        stmt.execute("CREATE INDEX idx_hook_tool ON hook_executions(tool_event_id)");
        stmt.execute("CREATE INDEX idx_hook_time ON hook_executions(timestamp)");
    }

    /**
     * V2: add UNIQUE constraint to commits table (idempotent for existing databases).
     */
    private static void applyV2(@NotNull Statement stmt) throws SQLException {
        stmt.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_commits_unique ON commits(turn_id, commit_hash)");
    }
}
