package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD wrapper over the three code-graph tables in {@code conversation.db}.
 * All writes are batched for performance. Reads return plain {@link NodeData}
 * / {@link EdgeData} lists or raw {@link ResultSet} for the SQL escape hatch.
 *
 * <p>The connection is owned by {@link ConversationDatabase}; this class
 * never closes it.
 */
@Service(Service.Level.PROJECT)
public final class CodeGraphStore {

    private static final Logger LOG = Logger.getInstance(CodeGraphStore.class);

    private final Project project;

    @SuppressWarnings("unused") // IntelliJ service container
    public CodeGraphStore(@NotNull Project project) {
        this.project = project;
    }

    public static CodeGraphStore getInstance(@NotNull Project project) {
        return project.getService(CodeGraphStore.class);
    }

    // ── Batch upsert ─────────────────────────────────────────────────────────

    /**
     * Insert-or-replace all nodes. Runs inside a single transaction.
     * Uses {@link ConversationDatabase#withConnection} for thread-safe access.
     */
    public void upsertNodes(@NotNull List<NodeData> nodes) {
        if (nodes.isEmpty()) return;
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                String sql = """
                    INSERT OR REPLACE INTO graph_nodes
                        (id, label, kind, fqn, source_file, source_line, language, indexed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    long now = System.currentTimeMillis();
                    for (NodeData n : nodes) {
                        ps.setString(1, n.id);
                        ps.setString(2, n.label);
                        ps.setString(3, n.kind);
                        ps.setString(4, n.fqn);
                        ps.setString(5, n.sourceFile);
                        if (n.sourceLine > 0) ps.setInt(6, n.sourceLine);
                        else ps.setNull(6, java.sql.Types.INTEGER);
                        ps.setString(7, n.language);
                        ps.setLong(8, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    rollbackQuietly(conn);
                    throw e;
                } finally {
                    setAutoCommitQuietly(conn, true);
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.error("Failed to upsert graph nodes", e);
        }
    }

    /**
     * Insert edges. Existing edges for the same source file are deleted first by {@link #deleteByFile}.
     * Uses {@link ConversationDatabase#withConnection} for thread-safe access and
     * disables FK constraints within the synchronized block so cross-file edges
     * (referencing nodes not yet indexed) don't fail.
     */
    public void insertEdges(@NotNull List<EdgeData> edges) {
        if (edges.isEmpty()) return;
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                String sql = """
                    INSERT OR IGNORE INTO graph_edges (source_id, target_id, relation, source_file, source_line)
                    VALUES (?, ?, ?, ?, ?)
                    """;
                try {
                    // Disable FK enforcement — cross-file edges may reference nodes not yet indexed.
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA foreign_keys = OFF");
                    }
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        for (EdgeData e : edges) {
                            ps.setString(1, e.sourceId);
                            ps.setString(2, e.targetId);
                            ps.setString(3, e.relation);
                            ps.setString(4, e.sourceFile);
                            if (e.sourceLine > 0) ps.setInt(5, e.sourceLine);
                            else ps.setNull(5, java.sql.Types.INTEGER);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    rollbackQuietly(conn);
                    LOG.error("Failed to insert graph edges", e);
                } finally {
                    setAutoCommitQuietly(conn, true);
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA foreign_keys = ON");
                    } catch (SQLException ex) {
                        LOG.warn("Failed to re-enable foreign keys", ex);
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.error("Failed to acquire connection for edge insert", e);
        }
    }

    // ── Delete by file ────────────────────────────────────────────────────────

    /**
     * Deletes all graph data (nodes, edges, file index). Used before a full rebuild
     * to ensure deleted files don't leave stale entries.
     * Synchronized via {@link ConversationDatabase#withConnection} to prevent
     * interference with other DB writers.
     */
    public void clearAll() throws SQLException {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM graph_edges");
                st.execute("DELETE FROM graph_nodes");
                st.execute("DELETE FROM graph_file_index");
            }
            return null;
        });
    }

    /**
     * Remove all nodes (and their CASCADE-deleted edges) belonging to {@code relativePath},
     * then delete the file's entry from {@code graph_file_index}.
     * Call before re-indexing a file to avoid stale nodes.
     * Uses {@link ConversationDatabase#withConnection} for thread-safe access.
     */
    public void deleteByFile(@NotNull String relativePath) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM graph_nodes WHERE source_file = ?")) {
                        ps.setString(1, relativePath);
                        ps.executeUpdate();
                    }
                    // Also delete edges whose source_file matches (covers cross-file edges)
                    try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM graph_edges WHERE source_file = ?")) {
                        ps.setString(1, relativePath);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM graph_file_index WHERE path = ?")) {
                        ps.setString(1, relativePath);
                        ps.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    rollbackQuietly(conn);
                    throw e;
                } finally {
                    setAutoCommitQuietly(conn, true);
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.error("Failed to delete graph data for file: " + relativePath, e);
        }
    }

    // ── File index ────────────────────────────────────────────────────────────

    @Nullable
    public String getFileHash(@NotNull String relativePath) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT content_hash FROM graph_file_index WHERE path = ?")) {
                    ps.setString(1, relativePath);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return rs.getString(1);
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.warn("Failed to read file hash for: " + relativePath, e);
            return null;
        }
    }

    public void setFileIndex(@NotNull String relativePath, @NotNull String hash, int nodes, int edges) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR REPLACE INTO graph_file_index (path, content_hash, indexed_at, node_count, edge_count)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                    ps.setString(1, relativePath);
                    ps.setString(2, hash);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setInt(4, nodes);
                    ps.setInt(5, edges);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.error("Failed to update file index for: " + relativePath, e);
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public record GraphStats(long nodeCount, long edgeCount, long fileCount, long commitCount, long lastIndexedAt) {
        public boolean isEmpty() {
            return nodeCount == 0 && commitCount == 0;
        }
    }

    @NotNull
    public GraphStats getStats() {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> {
                long nodes = 0, edges = 0, files = 0, commits = 0, lastAt = 0;
                try (Statement st = conn.createStatement()) {
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM graph_nodes")) {
                        if (rs.next()) nodes = rs.getLong(1);
                    }
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM graph_edges")) {
                        if (rs.next()) edges = rs.getLong(1);
                    }
                    try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*), MAX(indexed_at) FROM graph_file_index")) {
                        if (rs.next()) {
                            files = rs.getLong(1);
                            lastAt = rs.getLong(2);
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM graph_commits")) {
                        if (rs.next()) commits = rs.getLong(1);
                    }
                }
                return new GraphStats(nodes, edges, files, commits, lastAt);
            });
        } catch (SQLException e) {
            LOG.warn("Failed to read graph stats", e);
            return new GraphStats(0, 0, 0, 0, 0);
        }
    }

    // ── Raw query ─────────────────────────────────────────────────────────────

    /**
     * Execute a read-only SQL query and return results as a list of string-keyed maps.
     * Uses {@link ConversationDatabase#withConnection} for thread-safe access.
     * Any statement not starting with SELECT/WITH is rejected before execution.
     */
    @NotNull
    public List<java.util.Map<String, Object>> queryRaw(@NotNull String sql) throws SQLException {
        rejectWriteSql(sql);
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        return db.withConnection(conn -> {
            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }

    /**
     * Same as {@link #queryRaw(String)} but with positional parameters.
     * Uses {@link ConversationDatabase#withConnection} for thread-safe access.
     */
    @NotNull
    public List<java.util.Map<String, Object>> queryRaw(@NotNull String sql, Object... params) throws SQLException {
        rejectWriteSql(sql);
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        return db.withConnection(conn -> {
            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static void rollbackQuietly(@Nullable Connection conn) {
        if (conn == null) return;
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void setAutoCommitQuietly(@Nullable Connection conn, boolean v) {
        if (conn == null) return;
        try {
            conn.setAutoCommit(v);
        } catch (SQLException ignored) {
        }
    }

    private static void rejectWriteSql(@NotNull String sql) throws SQLException {
        // Whitelist approach: only pure SELECT statements (optionally preceded by CTEs) are allowed.
        // Strip leading SQL comments before checking.
        String trimmed = sql.stripLeading().replaceAll("(?s)/\\*.*?\\*/", "").stripLeading();
        while (trimmed.startsWith("--")) {
            int nl = trimmed.indexOf('\n');
            if (nl < 0) break;
            trimmed = trimmed.substring(nl + 1).stripLeading();
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new SQLException(
                "Only SELECT / WITH statements are allowed in query_knowledge_graph.");
        }
        // WITH ... INSERT/UPDATE/DELETE is valid in SQLite — reject DML/DDL keywords
        // even when the statement starts with WITH.
        if (upper.startsWith("WITH")) {
            java.util.regex.Pattern dmlAfterCte = java.util.regex.Pattern.compile(
                "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|REPLACE|ATTACH|DETACH|PRAGMA)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
            // Strip string literals and CTEs' SELECT bodies are fine — just scan for DML keywords
            // after removing single-quoted strings to avoid false positives.
            String stripped = trimmed.replaceAll("'[^']*'", "''");
            if (dmlAfterCte.matcher(stripped).find()) {
                throw new SQLException(
                    "WITH ... DML/DDL is not allowed in query_knowledge_graph. Only WITH ... SELECT is permitted.");
            }
        }
    }
}
