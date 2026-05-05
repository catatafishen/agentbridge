package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Project-level service that owns the unified conversation history SQLite
 * database. Replaces JSONL session files plus the separate {@code tool-stats.db}
 * (see {@link com.github.catatafishen.agentbridge.services.ToolCallStatisticsService}).
 *
 * <p>Schema follows the ER diagram in {@code scratches/conversation-db-er.md}:
 * sessions → turns (1 turn = 1 prompt) → events → typed event subtables, with
 * standalone events (no turn) for tool calls outside any ACP turn.
 *
 * <p>Database location: {@code {storageRoot}/conversation.db} where
 * {@code storageRoot} comes from {@link AgentBridgeStorageSettings}. Migration
 * from JSONL is handled by
 * {@link com.github.catatafishen.agentbridge.session.db.JsonlToSqliteMigrator}
 * — run once on first start after upgrade.
 */
@Service(Service.Level.PROJECT)
public final class ConversationDatabase implements Disposable {

    private static final Logger LOG = Logger.getInstance(ConversationDatabase.class);

    private static final String DB_FILENAME = "conversation.db";

    /**
     * Bumped whenever {@link ConversationSchema} changes. Stored in {@code schema_version} table.
     */
    static final int SCHEMA_VERSION = 2;

    private final Project project;
    // All access is inside synchronized(database) blocks in ConversationReader/Writer.
    // volatile alone would not be sufficient (S3077) and is redundant here.
    private Connection connection;

    private volatile boolean initAttempted;

    @SuppressWarnings("unused") // instantiated by IntelliJ service container
    public ConversationDatabase(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Test-only — use {@link #initializeWithConnection(Connection)} after construction.
     */
    public ConversationDatabase() {
        this.project = null;
    }

    @NotNull
    public static ConversationDatabase getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ConversationDatabase.class);
    }

    public synchronized void initialize() {
        if (initAttempted) return;
        if (project == null || project.getBasePath() == null) {
            throw new IllegalStateException(
                "Cannot initialize ConversationDatabase: project has no base path");
        }
        Path dbDir = AgentBridgeStorageSettings.getInstance().getProjectStorageDir(project);
        Path dbPath = dbDir.resolve(DB_FILENAME);
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not found on classpath", e);
        }
        try {
            Files.createDirectories(dbDir);
            initializeWithConnection(DriverManager.getConnection("jdbc:sqlite:" + dbPath));
            LOG.info("ConversationDatabase initialized at " + dbPath);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to initialize ConversationDatabase", e);
        }
        initAttempted = true;
        // Migrate legacy JSONL data on first initialization.
        JsonlToSqliteMigrator.migrateIfNeeded(project);
    }

    public void initializeWithConnection(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        ConversationSchema.createOrMigrate(conn);
        this.connection = conn;
    }

    /**
     * Returns the live JDBC connection, or {@code null} when the database has
     * not been initialised yet (e.g. during early startup, or when storage is
     * temporarily unavailable). Callers must check for null and treat the
     * absence of a connection as "DB unavailable, fall back gracefully".
     *
     * <p>Callers must <b>not</b> close the returned connection — its lifecycle
     * is owned by the service.
     */
    @Nullable
    public Connection getConnection() {
        return connection;
    }

    /**
     * Returns {@code true} if the connection is open and ready for use.
     */
    public boolean isReady() {
        return connection != null;
    }

    @Override
    public synchronized void dispose() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("Error closing ConversationDatabase connection", e);
            }
            connection = null;
        }
    }

    /**
     * Returns the resolved database path for the current project (for tests / diagnostics).
     */
    @Nullable
    public Path getDatabasePath() {
        if (project == null) return null;
        return AgentBridgeStorageSettings.getInstance()
            .getProjectStorageDir(project)
            .resolve(DB_FILENAME);
    }
}
