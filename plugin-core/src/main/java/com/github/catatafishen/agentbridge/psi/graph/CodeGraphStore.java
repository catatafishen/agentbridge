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

    // ── SQL constants (S6203: text blocks must be outside lambda bodies) ──────

    private static final String SQL_GRAPH_TOP_FILES = """
        SELECT fi.path,
               COALESCE(deps.cnt, 0)    AS dep_count,
               COALESCE(depnts.cnt, 0)  AS dependent_count
        FROM graph_file_index fi
        LEFT JOIN (
            SELECT source_id, COUNT(DISTINCT target_id) AS cnt
            FROM graph_edges WHERE relation = 'uses' GROUP BY source_id
        ) deps   ON deps.source_id   = 'file:' || fi.path
        LEFT JOIN (
            SELECT target_id, COUNT(DISTINCT source_id) AS cnt
            FROM graph_edges WHERE relation = 'uses' GROUP BY target_id
        ) depnts ON depnts.target_id = 'file:' || fi.path
        ORDER BY (COALESCE(deps.cnt, 0) + COALESCE(depnts.cnt, 0)) DESC
        LIMIT ?
        """;

    private static final String SQL_HOTSPOTS = """
        SELECT e.target_id AS file_id, n.source_file AS path,
               COUNT(DISTINCT e.source_id) AS dependent_count
        FROM graph_edges e
        JOIN graph_nodes n ON n.id = e.target_id
        WHERE e.relation = 'uses'
          AND n.kind = 'file'
        GROUP BY e.target_id
        ORDER BY dependent_count DESC
        LIMIT ?
        """;

    private static final String SQL_EXPLORER_ROWS = """
        SELECT fi.path,
               COALESCE(deps.cnt, 0) AS dep_count,
               COALESCE(depnts.cnt, 0) AS dependent_count,
               COALESCE(commits.cnt, 0) AS commit_count
        FROM graph_file_index fi
        LEFT JOIN (
            SELECT source_id, COUNT(DISTINCT target_id) AS cnt
            FROM graph_edges WHERE relation = 'uses'
            GROUP BY source_id
        ) deps ON deps.source_id = 'file:' || fi.path
        LEFT JOIN (
            SELECT target_id, COUNT(DISTINCT source_id) AS cnt
            FROM graph_edges WHERE relation = 'uses'
            GROUP BY target_id
        ) depnts ON depnts.target_id = 'file:' || fi.path
        LEFT JOIN (
            SELECT file_path, COUNT(*) AS cnt
            FROM graph_commit_files
            GROUP BY file_path
        ) commits ON commits.file_path = fi.path
        ORDER BY dependent_count DESC
        LIMIT ?
        """;

    private static final String SQL_FILE_DEPENDENCIES =
        "SELECT DISTINCT REPLACE(target_id, 'file:', '') AS dep " +
            "FROM graph_edges WHERE source_id = ? AND relation = 'uses' ORDER BY 1";

    private static final String SQL_FILE_DEPENDENTS =
        "SELECT DISTINCT REPLACE(source_id, 'file:', '') AS dep " +
            "FROM graph_edges WHERE target_id = ? AND relation = 'uses' ORDER BY 1";

    private static final String SQL_FILE_COMMITS = """
        SELECT gc.short_hash, gc.message, gc.author, gc.timestamp
        FROM graph_commits gc
        JOIN graph_commit_files gcf ON gcf.commit_hash = gc.hash
        WHERE gcf.file_path = ?
        ORDER BY gc.timestamp DESC
        LIMIT 10
        """;

    private static final String SQL_RECENT_ACTIVITY = """
        SELECT type, summary, timestamp FROM (
            SELECT 'commit' AS type,
                   short_hash || ' ' || message AS summary,
                   timestamp
            FROM graph_commits
            UNION ALL
            SELECT 'agent_' || CASE
                WHEN tce.tool_name IN ('write_file', 'edit_text', 'replace_symbol_body',
                                       'insert_after_symbol', 'insert_before_symbol') THEN 'edit'
                ELSE 'read'
                END AS type,
                tce.tool_name || ' ' || COALESCE(tce.file_path, '') AS summary,
                e.timestamp
            FROM tool_call_events tce
            JOIN events e ON e.id = tce.event_id
            WHERE tce.file_path IS NOT NULL
        )
        ORDER BY timestamp DESC
        LIMIT ?
        """;

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
                    ps.setLong(8, now);
                    for (NodeData n : nodes) {
                        ps.setString(1, n.id);
                        ps.setString(2, n.label);
                        ps.setString(3, n.kind);
                        ps.setString(4, n.fqn);
                        ps.setString(5, n.sourceFile);
                        ps.setObject(6, n.sourceLine > 0 ? n.sourceLine : null, java.sql.Types.INTEGER);
                        ps.setString(7, n.language);
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
                            ps.setObject(5, e.sourceLine > 0 ? e.sourceLine : null, java.sql.Types.INTEGER);
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

    public void setFileIndex(@NotNull String relativePath, @NotNull String hash,
                             int nodes, int edges, @NotNull String rootType) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            db.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR REPLACE INTO graph_file_index (path, content_hash, indexed_at, node_count, edge_count, root_type)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
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

    // ── Stats ─────────────────────────────────────────────────────────────────

    public record GraphStats(long nodeCount, long edgeCount, long fileCount, long commitCount,
                             long promptCount, long toolCallCount, long lastIndexedAt) {
        public boolean isEmpty() {
            return nodeCount == 0 && commitCount == 0;
        }
    }

    @NotNull
    public GraphStats getStats() {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> {
                long nodes = 0;
                long edges = 0;
                long files = 0;
                long commits = 0;
                long prompts = 0;
                long toolCalls = 0;
                long lastAt = 0;
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
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM turns")) {
                        if (rs.next()) prompts = rs.getLong(1);
                    }
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tool_call_events")) {
                        if (rs.next()) toolCalls = rs.getLong(1);
                    }
                }
                return new GraphStats(nodes, edges, files, commits, prompts, toolCalls, lastAt);
            });
        } catch (SQLException e) {
            LOG.warn("Failed to read graph stats", e);
            return new GraphStats(0, 0, 0, 0, 0, 0, 0);
        }
    }

    // ── Raw query ─────────────────────────────────────────────────────────────

    // ── Dashboard & Explorer queries ──────────────────────────────────────────

    /**
     * Top files by incoming edge count (most depended-upon).
     * Returns rows with: path, dependentCount.
     */
    @NotNull
    public List<HotspotEntry> getHotspots(int limit) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> {
                List<HotspotEntry> result = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(SQL_HOTSPOTS)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new HotspotEntry(
                                rs.getString("path"),
                                rs.getInt("dependent_count")
                            ));
                        }
                    }
                }
                return result;
            });
        } catch (SQLException e) {
            LOG.warn("Failed to query hotspots", e);
            return List.of();
        }
    }

    /**
     * File-level data for the Explorer table: path, dependency count,
     * dependent count, and commit count.
     */
    @NotNull
    public List<ExplorerRow> getExplorerRows(int limit) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> {
                List<ExplorerRow> result = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(SQL_EXPLORER_ROWS)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new ExplorerRow(
                                rs.getString("path"),
                                rs.getInt("dep_count"),
                                rs.getInt("dependent_count"),
                                rs.getInt("commit_count")
                            ));
                        }
                    }
                }
                return result;
            });
        } catch (SQLException e) {
            LOG.warn("Failed to query explorer data", e);
            return List.of();
        }
    }

    /**
     * Dependencies and dependents for a specific file.
     */
    @NotNull
    public FileDetail getFileDetail(@NotNull String path) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> {
                String fileId = "file:" + path;
                List<String> dependencies = new ArrayList<>();
                List<String> dependents = new ArrayList<>();
                List<CommitSummary> commits = new ArrayList<>();

                try (PreparedStatement ps = conn.prepareStatement(SQL_FILE_DEPENDENCIES)) {
                    ps.setString(1, fileId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) dependencies.add(rs.getString("dep"));
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(SQL_FILE_DEPENDENTS)) {
                    ps.setString(1, fileId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) dependents.add(rs.getString("dep"));
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(SQL_FILE_COMMITS)) {
                    ps.setString(1, path);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            commits.add(new CommitSummary(
                                rs.getString("short_hash"),
                                rs.getString("message"),
                                rs.getString("author"),
                                rs.getString("timestamp")
                            ));
                        }
                    }
                }
                return new FileDetail(path, dependencies, dependents, commits);
            });
        } catch (SQLException e) {
            LOG.warn("Failed to query file detail for " + path, e);
            return new FileDetail(path, List.of(), List.of(), List.of());
        }
    }

    public record HotspotEntry(@NotNull String path, int dependentCount) {
    }

    public record ExplorerRow(@NotNull String path, int depCount, int dependentCount, int commitCount) {
    }

    public record CommitSummary(@NotNull String shortHash, @NotNull String message,
                                @NotNull String author, @NotNull String timestamp) {
    }

    public record FileDetail(@NotNull String path, @NotNull List<String> dependencies,
                             @NotNull List<String> dependents, @NotNull List<CommitSummary> commits) {
    }

    /**
     * Recent activity feed combining git commits and agent tool calls.
     * Returns entries ordered by time descending, limited to the requested count.
     */
    @NotNull
    public List<ActivityEntry> getRecentActivity(int limit) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> {
                List<ActivityEntry> result = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(SQL_RECENT_ACTIVITY)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new ActivityEntry(
                                rs.getString("type"),
                                rs.getString("summary"),
                                rs.getString("timestamp")
                            ));
                        }
                    }
                }
                return result;
            });
        } catch (SQLException e) {
            LOG.warn("Failed to query recent activity", e);
            return List.of();
        }
    }

    public record ActivityEntry(@NotNull String type, @NotNull String summary,
                                @NotNull String timestamp) {
    }

    @NotNull
    public List<java.util.Map<String, Object>> queryRaw(@NotNull String sql) throws SQLException {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        return db.withConnection(conn -> withQueryOnly(conn, () -> {
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
        }));
    }

    @NotNull
    public List<java.util.Map<String, Object>> queryRaw(@NotNull String sql, Object... params) throws SQLException {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        return db.withConnection(conn -> withQueryOnly(conn, () -> {
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
        }));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static void rollbackQuietly(@Nullable Connection conn) {
        if (conn == null) return;
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Rollback failure during error handling is non-actionable — the connection is already in a bad state
        }
    }

    private static void setAutoCommitQuietly(@Nullable Connection conn, boolean v) {
        if (conn == null) return;
        try {
            conn.setAutoCommit(v);
        } catch (SQLException ignored) {
            // AutoCommit reset failure is non-actionable — occurs on broken/closed connections
        }
    }

    /**
     * Executes a callback with SQLite's native {@code PRAGMA query_only = ON}, which makes the
     * engine reject any write operation (INSERT, UPDATE, DELETE, DDL, ATTACH, etc.) at the driver
     * level. This is safer than SQL parsing — no regex bypass risk. The pragma is always reset
     * in the finally block since {@code withConnection} is synchronized, so no concurrent writer
     * can observe the read-only state.
     */
    private static <T> T withQueryOnly(@NotNull Connection conn, @NotNull SqlCallable<T> callable) throws SQLException {
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

    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws SQLException;
    }

    // ── Graph visualization data ──────────────────────────────────────────────

    public record GraphDataNode(
        @NotNull String id,
        @NotNull String type,
        @NotNull String label,
        @Nullable String path,
        int depCount,
        int dependentCount,
        @Nullable String hash,
        @Nullable String author,
        @Nullable String timestamp,
        @Nullable String preview
    ) {
    }

    public record GraphDataEdge(@NotNull String source, @NotNull String target, @NotNull String type) {
    }

    public record GraphData(@NotNull List<GraphDataNode> nodes, @NotNull List<GraphDataEdge> edges) {
    }

    /**
     * Returns a mixed graph of files, commits, and prompts with their connections.
     * Files are the top N by total connection count. Commits and prompts are the most recent.
     * Edges are filtered in Java to only those between the selected sets, avoiding dynamic IN
     * clauses while keeping the query simple.
     */
    @NotNull
    @SuppressWarnings({"DataFlowIssue", "java:S3776"})
    // DataFlowIssue: withConnection returns non-null; S3776: sequential query steps, no simpler decomposition
    public GraphData getGraphData(int fileLimit, int commitLimit, int promptLimit) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return db.withConnection(conn -> withQueryOnly(conn, () -> {
                List<GraphDataNode> nodes = new ArrayList<>();
                List<GraphDataEdge> edges = new ArrayList<>();
                java.util.Set<String> filePaths = new java.util.LinkedHashSet<>();

                // ── Files ─────────────────────────────────────────────────────
                try (PreparedStatement ps = conn.prepareStatement(SQL_GRAPH_TOP_FILES)) {
                    ps.setInt(1, fileLimit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String path = rs.getString("path");
                            filePaths.add(path);
                            nodes.add(new GraphDataNode(
                                "file:" + path, "file", graphFileName(path), path,
                                rs.getInt("dep_count"), rs.getInt("dependent_count"),
                                null, null, null, null));
                        }
                    }
                }

                // ── File→File edges ───────────────────────────────────────────
                if (!filePaths.isEmpty()) {
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery(
                             "SELECT REPLACE(source_id,'file:','') AS src," +
                                 " REPLACE(target_id,'file:','') AS tgt" +
                                 " FROM graph_edges WHERE relation='uses'")) {
                        while (rs.next()) {
                            String src = rs.getString("src");
                            String tgt = rs.getString("tgt");
                            if (filePaths.contains(src) && filePaths.contains(tgt)) {
                                edges.add(new GraphDataEdge("file:" + src, "file:" + tgt, "uses"));
                            }
                        }
                    }
                }

                // ── Commits ───────────────────────────────────────────────────
                java.util.Set<String> commitHashes = new java.util.LinkedHashSet<>();
                if (commitLimit > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT hash, short_hash, message, author, timestamp" +
                            " FROM graph_commits ORDER BY timestamp DESC LIMIT ?")) {
                        ps.setInt(1, commitLimit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String hash = rs.getString("hash");
                                commitHashes.add(hash);
                                String label = rs.getString("short_hash") + " " +
                                    graphTruncate(rs.getString("message"), 30);
                                nodes.add(new GraphDataNode(
                                    "commit:" + hash, "commit", label, null, 0, 0,
                                    hash, rs.getString("author"), rs.getString("timestamp"), null));
                            }
                        }
                    }
                    // Commit→file edges
                    if (!commitHashes.isEmpty() && !filePaths.isEmpty()) {
                        try (Statement st = conn.createStatement();
                             ResultSet rs = st.executeQuery(
                                 "SELECT commit_hash, file_path FROM graph_commit_files LIMIT 20000")) {
                            while (rs.next()) {
                                String hash = rs.getString("commit_hash");
                                String fp = rs.getString("file_path");
                                if (commitHashes.contains(hash) && filePaths.contains(fp)) {
                                    edges.add(new GraphDataEdge("commit:" + hash, "file:" + fp, "changed"));
                                }
                            }
                        }
                    }
                }

                // ── Prompts ───────────────────────────────────────────────────
                java.util.Set<String> turnIds = new java.util.LinkedHashSet<>();
                if (promptLimit > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, started_at, SUBSTR(prompt_text, 1, 150) AS preview" +
                            " FROM turns ORDER BY started_at DESC LIMIT ?")) {
                        ps.setInt(1, promptLimit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String id = rs.getString("id");
                                turnIds.add(id);
                                String preview = rs.getString("preview");
                                nodes.add(new GraphDataNode(
                                    "turn:" + id, "prompt",
                                    graphTruncate(preview != null ? preview : id, 32),
                                    null, 0, 0, null, null, rs.getString("started_at"), preview));
                            }
                        }
                    }
                    // Prompt→file edges (join events to get turn_id)
                    if (!turnIds.isEmpty() && !filePaths.isEmpty()) {
                        try (Statement st = conn.createStatement();
                             ResultSet rs = st.executeQuery(
                                 "SELECT DISTINCT e.turn_id, tce.file_path" +
                                     " FROM tool_call_events tce" +
                                     " JOIN events e ON e.id = tce.event_id" +
                                     " WHERE e.turn_id IS NOT NULL AND tce.file_path IS NOT NULL" +
                                     " ORDER BY e.timestamp DESC LIMIT 5000")) {
                            while (rs.next()) {
                                String turnId = rs.getString("turn_id");
                                String fp = rs.getString("file_path");
                                if (turnIds.contains(turnId) && filePaths.contains(fp)) {
                                    edges.add(new GraphDataEdge("turn:" + turnId, "file:" + fp, "touched"));
                                }
                            }
                        }
                    }
                }

                return new GraphData(nodes, edges);
            }));
        } catch (SQLException e) {
            LOG.warn("Failed to query graph visualization data", e);
            return new GraphData(List.of(), List.of());
        }
    }

    private static String graphFileName(@NotNull String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String graphTruncate(@Nullable String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }
}
