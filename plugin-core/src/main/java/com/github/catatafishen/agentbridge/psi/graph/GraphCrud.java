package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import static com.github.catatafishen.agentbridge.psi.graph.GraphSqlSupport.restoreAutoCommitQuietly;
import static com.github.catatafishen.agentbridge.psi.graph.GraphSqlSupport.rollbackQuietly;

/**
 * Write-side operations on the {@code graph_nodes}, {@code graph_edges}, and
 * {@code graph_file_index} tables. Package-private — invoked by
 * {@link CodeGraphStore} which exposes the public API.
 */
final class GraphCrud {

    private static final Logger LOG = Logger.getInstance(GraphCrud.class);

    private static final String SQL_INSERT_NODE = """
        INSERT OR REPLACE INTO graph_nodes
            (id, label, kind, fqn, source_file, source_line, language, indexed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String SQL_INSERT_EDGE = """
        INSERT OR IGNORE INTO graph_edges (source_id, target_id, relation, source_file, source_line)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String SQL_FILE_INDEX_UPSERT = """
        INSERT OR REPLACE INTO graph_file_index (path, content_hash, indexed_at, node_count, edge_count, root_type)
        VALUES (?, ?, ?, ?, ?, ?)
        """;

    private GraphCrud() {
    }

    static void upsertNodes(@NotNull Project project, @NotNull List<NodeData> nodes) {
        if (nodes.isEmpty()) return;
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_NODE)) {
                    long now = System.currentTimeMillis();
                    ps.setLong(8, now);
                    for (NodeData n : nodes) {
                        ps.setString(1, n.id);
                        ps.setString(2, n.label);
                        ps.setString(3, n.kind);
                        ps.setString(4, n.fqn);
                        ps.setString(5, n.sourceFile);
                        ps.setObject(6, n.sourceLine > 0 ? n.sourceLine : null, Types.INTEGER);
                        ps.setString(7, n.language);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    rollbackQuietly(conn);
                    throw e;
                } finally {
                    restoreAutoCommitQuietly(conn);
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.error("Failed to upsert graph nodes", e);
        }
    }

    static void insertEdges(@NotNull Project project, @NotNull List<EdgeData> edges) {
        if (edges.isEmpty()) return;
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                try {
                    // Disable FK enforcement — cross-file edges may reference nodes not yet indexed.
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA foreign_keys = OFF");
                    }
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_EDGE)) {
                        for (EdgeData e : edges) {
                            ps.setString(1, e.sourceId);
                            ps.setString(2, e.targetId);
                            ps.setString(3, e.relation);
                            ps.setString(4, e.sourceFile);
                            ps.setObject(5, e.sourceLine > 0 ? e.sourceLine : null, Types.INTEGER);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    rollbackQuietly(conn);
                    LOG.error("Failed to insert graph edges", e);
                } finally {
                    restoreAutoCommitQuietly(conn);
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

    static void clearAll(@NotNull Project project) throws SQLException {
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

    static void deleteByFile(@NotNull Project project, @NotNull String relativePath) {
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
                    // Also, delete edges whose source_file matches (covers cross-file edges).
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
                    restoreAutoCommitQuietly(conn);
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.error("Failed to delete graph data for file: " + relativePath, e);
        }
    }

    @Nullable
    static String getFileHash(@NotNull Project project, @NotNull String relativePath) {
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

    static void setFileIndex(@NotNull Project project, @NotNull String relativePath, @NotNull String hash,
                             int nodes, int edges, @NotNull String rootType) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(SQL_FILE_INDEX_UPSERT)) {
                    ps.setString(1, relativePath);
                    ps.setString(2, hash);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setInt(4, nodes);
                    ps.setInt(5, edges);
                    ps.setString(6, rootType);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.error("Failed to update file index for: " + relativePath, e);
        }
    }
}
