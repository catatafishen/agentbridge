package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.COL_DEPENDENT_COUNT;
import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.COL_TIMESTAMP;
import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.FILE_PREFIX;

/**
 * Read-only queries that power the Knowledge Graph dashboard and explorer panels:
 * stats, hotspots, explorer rows, file detail, and recent activity.
 * Package-private — invoked by {@link CodeGraphStore} which exposes the public API.
 */
final class GraphDashboardQueries {

    private static final Logger LOG = Logger.getInstance(GraphDashboardQueries.class);

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

    private static final String SQL_FILE_DEPENDENCIES = """
        SELECT DISTINCT REPLACE(target_id, 'file:', '') AS dep
        FROM graph_edges WHERE source_id = ? AND relation = 'uses' ORDER BY 1
        """;

    private static final String SQL_FILE_DEPENDENTS = """
        SELECT DISTINCT REPLACE(source_id, 'file:', '') AS dep
        FROM graph_edges WHERE target_id = ? AND relation = 'uses' ORDER BY 1
        """;

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

    private GraphDashboardQueries() {
    }

    @NotNull
    static CodeGraphStore.GraphStats getStats(@NotNull Project project) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return Objects.requireNonNull(db.withConnection(conn -> {
                long nodes = countOf(conn, "SELECT COUNT(*) FROM graph_nodes");
                long edges = countOf(conn, "SELECT COUNT(*) FROM graph_edges");
                long files;
                long lastAt;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*), MAX(indexed_at) FROM graph_file_index")) {
                    if (rs.next()) {
                        files = rs.getLong(1);
                        lastAt = rs.getLong(2);
                    } else {
                        files = 0L;
                        lastAt = 0L;
                    }
                }
                long commits = countOf(conn, "SELECT COUNT(*) FROM graph_commits");
                long prompts = countOf(conn, "SELECT COUNT(*) FROM turns");
                long toolCalls = countOf(conn, "SELECT COUNT(*) FROM tool_call_events");
                return new CodeGraphStore.GraphStats(
                    nodes, edges, files, commits, prompts, toolCalls, lastAt);
            }));
        } catch (SQLException e) {
            LOG.warn("Failed to read graph stats", e);
            return new CodeGraphStore.GraphStats(0, 0, 0, 0, 0, 0, 0);
        }
    }

    private static long countOf(@NotNull java.sql.Connection conn, @NotNull String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    @NotNull
    static List<CodeGraphStore.HotspotEntry> getHotspots(@NotNull Project project, int limit) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return Objects.requireNonNull(db.withConnection(conn -> {
                List<CodeGraphStore.HotspotEntry> result = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(SQL_HOTSPOTS)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new CodeGraphStore.HotspotEntry(
                                rs.getString("path"),
                                rs.getInt(COL_DEPENDENT_COUNT)));
                        }
                    }
                }
                return result;
            }));
        } catch (SQLException e) {
            LOG.warn("Failed to query hotspots", e);
            return List.of();
        }
    }

    @NotNull
    static List<CodeGraphStore.ExplorerRow> getExplorerRows(@NotNull Project project, int limit) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return Objects.requireNonNull(db.withConnection(conn -> {
                List<CodeGraphStore.ExplorerRow> result = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(SQL_EXPLORER_ROWS)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new CodeGraphStore.ExplorerRow(
                                rs.getString("path"),
                                rs.getInt("dep_count"),
                                rs.getInt(COL_DEPENDENT_COUNT),
                                rs.getInt("commit_count")));
                        }
                    }
                }
                return result;
            }));
        } catch (SQLException e) {
            LOG.warn("Failed to query explorer data", e);
            return List.of();
        }
    }

    @NotNull
    static CodeGraphStore.FileDetail getFileDetail(@NotNull Project project, @NotNull String path) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return Objects.requireNonNull(db.withConnection(conn -> {
                String fileId = FILE_PREFIX + path;
                List<String> dependencies = collectFileDeps(conn, SQL_FILE_DEPENDENCIES, fileId);
                List<String> dependents = collectFileDeps(conn, SQL_FILE_DEPENDENTS, fileId);
                List<CodeGraphStore.CommitSummary> commits = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(SQL_FILE_COMMITS)) {
                    ps.setString(1, path);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            commits.add(new CodeGraphStore.CommitSummary(
                                rs.getString("short_hash"),
                                rs.getString("message"),
                                rs.getString("author"),
                                rs.getString(COL_TIMESTAMP)));
                        }
                    }
                }
                return new CodeGraphStore.FileDetail(path, dependencies, dependents, commits);
            }));
        } catch (SQLException e) {
            LOG.warn("Failed to query file detail for " + path, e);
            return new CodeGraphStore.FileDetail(path, List.of(), List.of(), List.of());
        }
    }

    private static List<String> collectFileDeps(@NotNull java.sql.Connection conn,
                                                @NotNull String sql,
                                                @NotNull String fileId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("dep"));
            }
        }
        return result;
    }

    @NotNull
    static List<CodeGraphStore.ActivityEntry> getRecentActivity(@NotNull Project project, int limit) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return Objects.requireNonNull(db.withConnection(conn -> {
                List<CodeGraphStore.ActivityEntry> result = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(SQL_RECENT_ACTIVITY)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new CodeGraphStore.ActivityEntry(
                                rs.getString("type"),
                                rs.getString("summary"),
                                rs.getString(COL_TIMESTAMP)));
                        }
                    }
                }
                return result;
            }));
        } catch (SQLException e) {
            LOG.warn("Failed to query recent activity", e);
            return List.of();
        }
    }
}
