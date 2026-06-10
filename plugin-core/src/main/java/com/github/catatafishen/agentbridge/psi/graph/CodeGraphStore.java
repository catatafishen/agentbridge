package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Project-scoped facade over the code-graph tables in {@code conversation.db}. Provides
 * write operations ({@link #upsertNodes}, {@link #insertEdges}, …), dashboard queries
 * ({@link #getStats}, {@link #getHotspots}, …), graph-visualization loading
 * ({@link #getGraphData}), and a read-only SQL escape hatch ({@link #queryRaw}).
 *
 * <p>The class is intentionally a thin facade — the implementations live in
 * focused package-private helpers under the same package:
 * <ul>
 *   <li>{@link GraphCrud} — write operations</li>
 *   <li>{@link GraphDashboardQueries} — dashboard / explorer reads</li>
 *   <li>{@link GraphVisualizationLoader} — graph data for the diagram panel</li>
 *   <li>{@link GraphRawQuery} — arbitrary read-only SQL</li>
 *   <li>{@link GraphSqlSupport}, {@link GraphColumns} — shared helpers and constants</li>
 * </ul>
 *
 * <p>The DB connection is owned by
 * {@link com.github.catatafishen.agentbridge.session.db.ConversationDatabase};
 * this class never closes it.
 */
@Service(Service.Level.PROJECT)
public final class CodeGraphStore {

    private final Project project;

    @SuppressWarnings("unused") // IntelliJ service container
    public CodeGraphStore(@NotNull Project project) {
        this.project = project;
    }

    @NotNull
    public static CodeGraphStore getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, CodeGraphStore.class);
    }

    // ── Write operations ─────────────────────────────────────────────────────

    /** Insert-or-replace all nodes in a single transaction. */
    public void upsertNodes(@NotNull List<NodeData> nodes) {
        GraphCrud.upsertNodes(project, nodes);
    }

    /** Insert edges, skipping duplicates. Foreign-key checks are temporarily disabled
     *  to allow cross-file edges that reference nodes not yet indexed. */
    public void insertEdges(@NotNull List<EdgeData> edges) {
        GraphCrud.insertEdges(project, edges);
    }

    /** Wipe all rows in {@code graph_nodes}, {@code graph_edges}, and {@code graph_file_index}. */
    public void clearAll() throws SQLException {
        GraphCrud.clearAll(project);
    }

    /** Delete all graph rows belonging to one source file. */
    public void deleteByFile(@NotNull String relativePath) {
        GraphCrud.deleteByFile(project, relativePath);
    }

    /** @return the stored content hash for a file, or {@code null} if not indexed. */
    @Nullable
    public String getFileHash(@NotNull String relativePath) {
        return GraphCrud.getFileHash(project, relativePath);
    }

    /** Insert-or-replace the file-index row for one source file. */
    public void setFileIndex(@NotNull String relativePath, @NotNull String hash,
                             int nodes, int edges, @NotNull String rootType) {
        GraphCrud.setFileIndex(project, relativePath, hash, nodes, edges, rootType);
    }

    // ── Dashboard / explorer queries ─────────────────────────────────────────

    @NotNull
    public GraphStats getStats() {
        return GraphDashboardQueries.getStats(project);
    }

    /** Top files by incoming edge count (most depended-upon). */
    @NotNull
    public List<HotspotEntry> getHotspots(int limit) {
        return GraphDashboardQueries.getHotspots(project, limit);
    }

    /** File-level rows for the Explorer table. */
    @NotNull
    public List<ExplorerRow> getExplorerRows(int limit) {
        return GraphDashboardQueries.getExplorerRows(project, limit);
    }

    /** Dependencies, dependents, and recent commits for one file. */
    @NotNull
    public FileDetail getFileDetail(@NotNull String path) {
        return GraphDashboardQueries.getFileDetail(project, path);
    }

    /** Recent activity feed combining git commits and agent tool calls. */
    @NotNull
    public List<ActivityEntry> getRecentActivity(int limit) {
        return GraphDashboardQueries.getRecentActivity(project, limit);
    }

    // ── Graph visualization ──────────────────────────────────────────────────

    /**
     * Loads graph data centred on prompts and commits, with files included only when
     * reached via commit/prompt origin or bounded dependency traversal.
     *
     * @param commitLimit number of most-recent commits to include (0 = none)
     * @param promptLimit number of most-recent prompts/turns to include (0 = none)
     * @param fileDepth   how many hops of file→file dependency edges to traverse from
     *                    files touched by the loaded commits/prompts (0 = only directly
     *                    touched files; 1 = +direct dependencies/dependents; 2 = two hops, etc.)
     */
    @NotNull
    public GraphData getGraphData(int commitLimit, int promptLimit, int fileDepth) {
        return GraphVisualizationLoader.load(project, commitLimit, promptLimit, fileDepth);
    }

    // ── Raw query (read-only escape hatch) ───────────────────────────────────

    /**
     * Run an arbitrary read-only SQL query and return rows as column-name → value maps.
     * Executed under {@code PRAGMA query_only = ON} so any write operation is rejected
     * by SQLite at the engine level.
     */
    @NotNull
    public List<Map<String, Object>> queryRaw(@NotNull String sql, @NotNull Object... params)
        throws SQLException {
        return GraphRawQuery.queryRaw(project, sql, params);
    }

    // ── Public DTOs (preserved for caller compatibility) ─────────────────────

    public record GraphStats(long nodeCount, long edgeCount, long fileCount, long commitCount,
                             long promptCount, long toolCallCount, long lastIndexedAt) {
        public boolean isEmpty() {
            return nodeCount == 0 && commitCount == 0;
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

    public record ActivityEntry(@NotNull String type, @NotNull String summary,
                                @NotNull String timestamp) {
    }

    /**
     * @param sizeMetric raw value used for per-type relative node sizing in the diagram:
     *                   <ul>
     *                     <li><b>file</b> — {@code graph_file_index.node_count} (PSI symbol count;
     *                         a complexity proxy, since LOC is not stored)</li>
     *                     <li><b>commit</b> — number of files changed in the commit
     *                         (proxy for diff size, since per-commit lines added/removed are not stored)</li>
     *                     <li><b>prompt</b> — {@code input_tokens + output_tokens} when present;
     *                         otherwise {@code duration_ms / 1000} as a fallback for agents that
     *                         do not report tokens (e.g., Copilot CLI)</li>
     *                   </ul>
     *                   Always {@code >= 0}. The renderer maps each type's [min, max] across the
     *                   loaded graph to a fixed pixel-radius range.
     */
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
        @Nullable String preview,
        long sizeMetric
    ) {
    }

    public record GraphDataEdge(@NotNull String source, @NotNull String target, @NotNull String type) {
    }

    public record GraphData(@NotNull List<GraphDataNode> nodes, @NotNull List<GraphDataEdge> edges) {
    }
}
