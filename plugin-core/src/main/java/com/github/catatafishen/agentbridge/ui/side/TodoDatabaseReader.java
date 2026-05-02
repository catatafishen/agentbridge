package com.github.catatafishen.agentbridge.ui.side;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads task data from a Copilot session's SQLite database.
 * Pure data access — no UI or IntelliJ dependencies, fully testable.
 */
public final class TodoDatabaseReader {

    private TodoDatabaseReader() {
    }

    /**
     * A single task item from the database.
     */
    public record TodoItem(
        @NotNull String id,
        @NotNull String title,
        @Nullable String description,
        @NotNull String status,
        @Nullable String createdAt,
        @Nullable String updatedAt
    ) {

        public boolean isDone() {
            return "done".equals(status);
        }

        public boolean isBlocked() {
            return "blocked".equals(status);
        }

        public boolean isInProgress() {
            return "in_progress".equals(status);
        }
    }

    /**
     * Reads all todos from the given SQLite database file, ordered by status priority
     * (in_progress first, then pending, blocked, done).
     *
     * @param dbFile path to the session.db file
     * @return list of todos, or empty list if file doesn't exist or has no todos table
     */
    public static @NotNull List<TodoItem> readTodos(@NotNull File dbFile) {
        if (!dbFile.exists() || !dbFile.canRead()) {
            return Collections.emptyList();
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            if (!hasTodosTable(stmt)) {
                return Collections.emptyList();
            }

            return queryTodos(stmt);
        } catch (SQLException e) {
            return Collections.emptyList();
        }
    }

    private static boolean hasTodosTable(@NotNull Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='todos'")) {
            return rs.next();
        }
    }

    private static @NotNull List<TodoItem> queryTodos(@NotNull Statement stmt) throws SQLException {
        String sql = "SELECT id, title, description, status, created_at, updated_at FROM todos "
            + "ORDER BY CASE status "
            + "WHEN 'in_progress' THEN 1 "
            + "WHEN 'pending' THEN 2 "
            + "WHEN 'blocked' THEN 3 "
            + "WHEN 'done' THEN 4 "
            + "ELSE 5 END, updated_at DESC";

        List<TodoItem> items = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                items.add(new TodoItem(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("status"),
                    rs.getString("created_at"),
                    rs.getString("updated_at")
                ));
            }
        }
        return items;
    }
}
