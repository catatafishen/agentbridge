package com.github.catatafishen.agentbridge.ui.side;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Writes task data to a Copilot session's SQLite database.
 * Pure data access — no UI or IntelliJ dependencies, fully testable.
 *
 * <p>Each method opens its own connection so callers don't need to manage connection lifecycle.
 */
public final class TodoDatabaseWriter {

    private TodoDatabaseWriter() {
    }

    /**
     * Creates a new todo item with the given fields.
     *
     * @throws SQLException if the database cannot be opened or the write fails
     */
    public static void createTodo(@NotNull File dbFile,
                                  @NotNull String id,
                                  @NotNull String title,
                                  @Nullable String description) throws SQLException {
        withConnection(dbFile, conn -> {
            ensureTableExists(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO todos (id, title, description, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'pending', strftime('%Y-%m-%dT%H:%M:%SZ','now'), strftime('%Y-%m-%dT%H:%M:%SZ','now'))")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, description);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Partially updates an existing todo item. Only non-null parameters are written.
     * {@code updated_at} is always refreshed.
     *
     * @throws SQLException if the database cannot be opened or the write fails
     */
    public static void updateTodo(@NotNull File dbFile,
                                  @NotNull String id,
                                  @Nullable String title,
                                  @Nullable String description,
                                  @Nullable String status) throws SQLException {
        if (title == null && description == null && status == null) return;

        StringBuilder sql = new StringBuilder("UPDATE todos SET updated_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')");
        if (title != null) sql.append(", title = ?");
        if (description != null) sql.append(", description = ?");
        if (status != null) sql.append(", status = ?");
        sql.append(" WHERE id = ?");

        withConnection(dbFile, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                if (title != null) ps.setString(idx++, title);
                if (description != null) ps.setString(idx++, description);
                if (status != null) ps.setString(idx++, status);
                ps.setString(idx, id);
                ps.executeUpdate();
            }
        });
    }

    /**
     * Deletes the todo item with the given id.
     *
     * @throws SQLException if the database cannot be opened or the write fails
     */
    public static void deleteTodo(@NotNull File dbFile, @NotNull String id) throws SQLException {
        withConnection(dbFile, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM todos WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        });
    }

    private static void ensureTableExists(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS todos ("
                    + "id TEXT PRIMARY KEY, "
                    + "title TEXT NOT NULL, "
                    + "description TEXT, "
                    + "status TEXT DEFAULT 'pending', "
                    + "created_at TEXT, "
                    + "updated_at TEXT"
                    + ")");
        }
    }

    @FunctionalInterface
    private interface DbOperation {
        void run(@NotNull Connection conn) throws SQLException;
    }

    private static void withConnection(@NotNull File dbFile, @NotNull DbOperation op) throws SQLException {
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            op.run(conn);
        }
    }
}
