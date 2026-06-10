package com.github.catatafishen.agentbridge.psi.graph;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared SQL helpers used by the read-side graph query classes
 * ({@link GraphDashboardQueries}, {@link GraphVisualizationLoader},
 * {@link GraphRawQuery}). Package-private — not part of the public
 * {@link CodeGraphStore} API.
 */
final class GraphSqlSupport {

    private GraphSqlSupport() {
    }

    /**
     * Executes a callback with SQLite's native {@code PRAGMA query_only = ON}, which makes the
     * engine reject any write operation (INSERT, UPDATE, DELETE, DDL, ATTACH, etc.) at the driver
     * level. This is safer than SQL parsing — no regex bypass risk. The pragma is always reset
     * in the finally block since the calling {@code withConnection} is synchronized, so no
     * concurrent writer can observe the read-only state.
     */
    static <T> T withQueryOnly(@NotNull Connection conn, @NotNull SqlCallable<T> callable) throws SQLException {
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA query_only = ON");
        }
        try {
            return callable.call();
        } finally {
            try (Statement pragma = conn.createStatement()) {
                pragma.execute("PRAGMA query_only = OFF");
            }
        }
    }

    /**
     * Builds a comma-separated list of {@code n} {@code ?} placeholders for use inside an
     * {@code IN (...)} clause. The result contains <b>only</b> punctuation and {@code ?}
     * characters — no caller data — so concatenating it into a SQL string is safe; the
     * actual values are bound via {@link java.sql.PreparedStatement#setString}.
     */
    @NotNull
    static String inPlaceholders(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * Rolls back a transaction without throwing — a rollback failure during error
     * handling is non-actionable since the connection is already in a bad state.
     */
    static void rollbackQuietly(@Nullable Connection conn) {
        if (conn == null) return;
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Rollback failure during error handling is non-actionable
        }
    }

    /**
     * Restores auto-commit on a connection without throwing — a failure here typically
     * means the connection is broken or already closed, both non-actionable.
     */
    static void restoreAutoCommitQuietly(@Nullable Connection conn) {
        if (conn == null) return;
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // AutoCommit reset failure is non-actionable
        }
    }

    @FunctionalInterface
    interface SqlCallable<T> {
        T call() throws SQLException;
    }
}
