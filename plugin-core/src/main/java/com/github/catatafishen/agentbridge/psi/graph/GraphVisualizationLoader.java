package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.COL_DEPENDENT_COUNT;
import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.COL_FILE_PATH;
import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.COL_TIMESTAMP;
import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.COMMIT_PREFIX;
import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.FILE_PREFIX;
import static com.github.catatafishen.agentbridge.psi.graph.GraphColumns.TURN_PREFIX;
import static com.github.catatafishen.agentbridge.psi.graph.GraphSqlSupport.inPlaceholders;
import static com.github.catatafishen.agentbridge.psi.graph.GraphSqlSupport.withQueryOnly;

/**
 * Loads graph visualization data centered on prompts and commits, with files included only when
 * reached via commit/prompt origin or bounded dependency traversal.
 *
 * <p>Files are second-class: a file appears only if it was touched by a loaded commit/prompt or
 * is within {@code fileDepth} hops of one such file via the {@code uses} graph. This avoids the
 * dense self-connected file blob that a top-N-by-imports approach produces.
 *
 * <p>Package-private — invoked by {@link CodeGraphStore}.
 *
 * <p><b>SQL safety note:</b> several queries below concatenate
 * {@link GraphSqlSupport#inPlaceholders(int)} into the SQL string. That helper returns a constant
 * {@code "?,?,?"} sequence built from collection size — no user data ever reaches the SQL string.
 * All actual values are bound via {@link PreparedStatement#setString} / {@code setInt}. The
 * connection is also wrapped with {@code PRAGMA query_only = ON} so any write operation is
 * rejected at the SQLite engine level. Hence
 * {@link SuppressWarnings @SuppressWarnings}{@code ("java:S2077")} at class scope.
 */
@SuppressWarnings("java:S2077")
final class GraphVisualizationLoader {

    private static final Logger LOG = Logger.getInstance(GraphVisualizationLoader.class);

    private static final String LABEL_PROMPT = "prompt";

    /**
     * Cap on file nodes during BFS expansion — keeps the visualization readable
     * and bounds query cost on dense dependency graphs.
     */
    private static final int FILE_NODE_CAP = 500;

    private GraphVisualizationLoader() {
    }

    /**
     * @param commitLimit number of most-recent commits to include (0 = none)
     * @param promptLimit number of most-recent prompts/turns to include (0 = none)
     * @param fileDepth   how many hops of file→file dependency edges to traverse from
     *                    files touched by the loaded commits/prompts (0 = only directly
     *                    touched files; 1 = +direct dependencies/dependents; 2 = two hops, etc.)
     */
    @NotNull
    static CodeGraphStore.GraphData load(@NotNull Project project,
                                         int commitLimit, int promptLimit, int fileDepth) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        try {
            return Objects.requireNonNull(db.withConnection(conn ->
                withQueryOnly(conn, () -> buildGraphData(conn, commitLimit, promptLimit, fileDepth))));
        } catch (SQLException e) {
            LOG.warn("Failed to query graph visualization data", e);
            return new CodeGraphStore.GraphData(List.of(), List.of());
        }
    }

    @NotNull
    private static CodeGraphStore.GraphData buildGraphData(@NotNull Connection conn,
                                                           int commitLimit,
                                                           int promptLimit,
                                                           int fileDepth) throws SQLException {
        List<CodeGraphStore.GraphDataNode> nodes = new ArrayList<>();
        List<CodeGraphStore.GraphDataEdge> edges = new ArrayList<>();

        Set<String> commitHashes = new LinkedHashSet<>();
        Set<String> turnIds = new LinkedHashSet<>();
        Set<String> seedFiles = new LinkedHashSet<>();
        // session_id → list of [turn_id, started_at] for the selected turns.
        Map<String, List<String[]>> turnsBySession = new HashMap<>();

        if (commitLimit > 0) {
            loadCommits(conn, commitLimit, nodes, commitHashes);
            loadFilesTouchedByCommits(conn, commitHashes, seedFiles);
        }
        if (promptLimit > 0) {
            loadPrompts(conn, promptLimit, nodes, turnIds, turnsBySession);
            loadFilesTouchedByPrompts(conn, turnIds, seedFiles);
        }

        Set<String> expandedFiles = expandFilesByDependency(conn, seedFiles, fileDepth);
        addFileNodes(conn, expandedFiles, nodes);

        addCommitFileEdges(conn, commitHashes, expandedFiles, edges);
        addPromptFileEdges(conn, turnIds, expandedFiles, edges);
        addFileToFileEdges(conn, expandedFiles, edges);
        addPrevPromptEdges(turnsBySession, edges);

        removeOrphanPrompts(nodes, edges);
        return new CodeGraphStore.GraphData(nodes, edges);
    }

    private static void loadCommits(@NotNull Connection conn, int limit,
                                    @NotNull List<CodeGraphStore.GraphDataNode> outNodes,
                                    @NotNull Set<String> outHashes) throws SQLException {
        String sql = "SELECT hash, short_hash, message, author, timestamp" +
            " FROM graph_commits ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String hash = rs.getString("hash");
                    outHashes.add(hash);
                    String label = rs.getString("short_hash") + " " +
                        graphTruncate(rs.getString("message"), 30);
                    outNodes.add(new CodeGraphStore.GraphDataNode(
                        COMMIT_PREFIX + hash, "commit", label, null, 0, 0,
                        hash, rs.getString("author"), rs.getString(COL_TIMESTAMP), null));
                }
            }
        }
    }

    private static void loadFilesTouchedByCommits(@NotNull Connection conn,
                                                  @NotNull Set<String> commitHashes,
                                                  @NotNull Set<String> outFiles) throws SQLException {
        if (commitHashes.isEmpty()) return;
        // Safe SQL: only inPlaceholders(N) — a constant '?,?,?' string — is concatenated.
        // All values are bound via setString below.
        String sql = "SELECT DISTINCT file_path FROM graph_commit_files" +
            " WHERE commit_hash IN (" + inPlaceholders(commitHashes.size()) + ")" +
            " AND file_path IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String h : commitHashes) ps.setString(idx++, h);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) outFiles.add(rs.getString(COL_FILE_PATH));
            }
        }
    }

    private static void loadPrompts(@NotNull Connection conn, int limit,
                                    @NotNull List<CodeGraphStore.GraphDataNode> outNodes,
                                    @NotNull Set<String> outTurnIds,
                                    @NotNull Map<String, List<String[]>> outBySession) throws SQLException {
        String sql = "SELECT id, session_id, started_at, SUBSTR(prompt_text, 1, 150) AS preview" +
            " FROM turns ORDER BY started_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String sessionId = rs.getString("session_id");
                    String startedAt = rs.getString("started_at");
                    outTurnIds.add(id);
                    if (sessionId != null && startedAt != null) {
                        outBySession.computeIfAbsent(sessionId, k -> new ArrayList<>())
                            .add(new String[]{id, startedAt});
                    }
                    String preview = rs.getString("preview");
                    outNodes.add(new CodeGraphStore.GraphDataNode(
                        TURN_PREFIX + id, LABEL_PROMPT,
                        graphTruncate(preview != null ? preview : id, 32),
                        null, 0, 0, null, null, startedAt, preview));
                }
            }
        }
    }

    private static void loadFilesTouchedByPrompts(@NotNull Connection conn,
                                                  @NotNull Set<String> turnIds,
                                                  @NotNull Set<String> outFiles) throws SQLException {
        if (turnIds.isEmpty()) return;
        // Safe SQL: only inPlaceholders(N) is concatenated. All values are bound below.
        String sql = "SELECT DISTINCT tce.file_path FROM tool_call_events tce" +
            " JOIN events e ON e.id = tce.event_id" +
            " WHERE e.turn_id IN (" + inPlaceholders(turnIds.size()) + ")" +
            " AND tce.file_path IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String t : turnIds) ps.setString(idx++, t);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) outFiles.add(rs.getString(COL_FILE_PATH));
            }
        }
    }

    @NotNull
    private static Set<String> expandFilesByDependency(@NotNull Connection conn,
                                                       @NotNull Set<String> seedFiles,
                                                       int fileDepth) throws SQLException {
        Set<String> expanded = new LinkedHashSet<>(seedFiles);
        Set<String> frontier = new LinkedHashSet<>(seedFiles);
        for (int hop = 0;
             hop < fileDepth && !frontier.isEmpty() && expanded.size() < FILE_NODE_CAP;
             hop++) {
            frontier = bfsOneHop(conn, frontier, expanded);
        }
        return expanded;
    }

    @NotNull
    private static Set<String> bfsOneHop(@NotNull Connection conn,
                                         @NotNull Set<String> frontier,
                                         @NotNull Set<String> expanded) throws SQLException {
        Set<String> nextFrontier = new LinkedHashSet<>();
        String placeholders = inPlaceholders(frontier.size());
        // Safe SQL: only inPlaceholders(N) is concatenated. All values are bound below.
        String sql =
            "SELECT REPLACE(target_id,'file:','') AS dst FROM graph_edges" +
                " WHERE relation='uses' AND source_id IN (" + placeholders + ")" +
                " UNION " +
                "SELECT REPLACE(source_id,'file:','') AS dst FROM graph_edges" +
                " WHERE relation='uses' AND target_id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String p : frontier) ps.setString(idx++, FILE_PREFIX + p);
            for (String p : frontier) ps.setString(idx++, FILE_PREFIX + p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && expanded.size() < FILE_NODE_CAP) {
                    String fp = rs.getString("dst");
                    if (fp != null && expanded.add(fp)) {
                        nextFrontier.add(fp);
                    }
                }
            }
        }
        return nextFrontier;
    }

    private static void addFileNodes(@NotNull Connection conn, @NotNull Set<String> files,
                                     @NotNull List<CodeGraphStore.GraphDataNode> outNodes) throws SQLException {
        if (files.isEmpty()) return;
        Map<String, int[]> counts = loadFileCounts(conn, files);
        for (String fp : files) {
            int[] c = counts.getOrDefault(fp, new int[]{0, 0});
            outNodes.add(new CodeGraphStore.GraphDataNode(
                FILE_PREFIX + fp, "file", graphFileName(fp), fp,
                c[0], c[1], null, null, null, null));
        }
    }

    @NotNull
    private static Map<String, int[]> loadFileCounts(@NotNull Connection conn,
                                                     @NotNull Set<String> files) throws SQLException {
        Map<String, int[]> counts = new HashMap<>();
        // Safe SQL: only inPlaceholders(N) is concatenated. All values are bound below.
        String sql =
            "SELECT fi.path," +
                " COALESCE(deps.cnt, 0) AS dep_count," +
                " COALESCE(depnts.cnt, 0) AS dependent_count" +
                " FROM graph_file_index fi" +
                " LEFT JOIN (SELECT source_id, COUNT(DISTINCT target_id) AS cnt" +
                "   FROM graph_edges WHERE relation='uses' GROUP BY source_id) deps" +
                "   ON deps.source_id = 'file:' || fi.path" +
                " LEFT JOIN (SELECT target_id, COUNT(DISTINCT source_id) AS cnt" +
                "   FROM graph_edges WHERE relation='uses' GROUP BY target_id) depnts" +
                "   ON depnts.target_id = 'file:' || fi.path" +
                " WHERE fi.path IN (" + inPlaceholders(files.size()) + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String p : files) ps.setString(idx++, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("path"),
                        new int[]{rs.getInt("dep_count"), rs.getInt(COL_DEPENDENT_COUNT)});
                }
            }
        }
        return counts;
    }

    private static void addCommitFileEdges(@NotNull Connection conn,
                                           @NotNull Set<String> commitHashes,
                                           @NotNull Set<String> files,
                                           @NotNull List<CodeGraphStore.GraphDataEdge> outEdges) throws SQLException {
        if (commitHashes.isEmpty() || files.isEmpty()) return;
        // Safe SQL: only inPlaceholders(N) is concatenated. All values are bound below.
        String sql = "SELECT commit_hash, file_path FROM graph_commit_files" +
            " WHERE commit_hash IN (" + inPlaceholders(commitHashes.size()) + ")" +
            " AND file_path IN (" + inPlaceholders(files.size()) + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String h : commitHashes) ps.setString(idx++, h);
            for (String f : files) ps.setString(idx++, f);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    outEdges.add(new CodeGraphStore.GraphDataEdge(
                        COMMIT_PREFIX + rs.getString("commit_hash"),
                        FILE_PREFIX + rs.getString(COL_FILE_PATH),
                        "changed"));
                }
            }
        }
    }

    private static void addPromptFileEdges(@NotNull Connection conn,
                                           @NotNull Set<String> turnIds,
                                           @NotNull Set<String> files,
                                           @NotNull List<CodeGraphStore.GraphDataEdge> outEdges) throws SQLException {
        if (turnIds.isEmpty() || files.isEmpty()) return;
        // Safe SQL: only inPlaceholders(N) is concatenated. All values are bound below.
        String sql = "SELECT DISTINCT e.turn_id, tce.file_path" +
            " FROM tool_call_events tce" +
            " JOIN events e ON e.id = tce.event_id" +
            " WHERE e.turn_id IN (" + inPlaceholders(turnIds.size()) + ")" +
            " AND tce.file_path IN (" + inPlaceholders(files.size()) + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String t : turnIds) ps.setString(idx++, t);
            for (String f : files) ps.setString(idx++, f);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    outEdges.add(new CodeGraphStore.GraphDataEdge(
                        TURN_PREFIX + rs.getString("turn_id"),
                        FILE_PREFIX + rs.getString(COL_FILE_PATH),
                        "touched"));
                }
            }
        }
    }

    private static void addFileToFileEdges(@NotNull Connection conn,
                                           @NotNull Set<String> files,
                                           @NotNull List<CodeGraphStore.GraphDataEdge> outEdges) throws SQLException {
        if (files.size() <= 1) return;
        String placeholders = inPlaceholders(files.size());
        // Safe SQL: only inPlaceholders(N) is concatenated. All values are bound below.
        String sql =
            "SELECT REPLACE(source_id,'file:','') AS src," +
                " REPLACE(target_id,'file:','') AS tgt" +
                " FROM graph_edges WHERE relation='uses'" +
                " AND source_id IN (" + placeholders + ")" +
                " AND target_id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String p : files) ps.setString(idx++, FILE_PREFIX + p);
            for (String p : files) ps.setString(idx++, FILE_PREFIX + p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    outEdges.add(new CodeGraphStore.GraphDataEdge(
                        FILE_PREFIX + rs.getString("src"),
                        FILE_PREFIX + rs.getString("tgt"),
                        "uses"));
                }
            }
        }
    }

    private static void addPrevPromptEdges(@NotNull Map<String, List<String[]>> turnsBySession,
                                           @NotNull List<CodeGraphStore.GraphDataEdge> outEdges) {
        for (List<String[]> sessionTurns : turnsBySession.values()) {
            sessionTurns.sort(Comparator.comparing(a -> a[1]));
            for (int i = 1; i < sessionTurns.size(); i++) {
                outEdges.add(new CodeGraphStore.GraphDataEdge(
                    TURN_PREFIX + sessionTurns.get(i - 1)[0],
                    TURN_PREFIX + sessionTurns.get(i)[0],
                    "prev"));
            }
        }
    }

    private static void removeOrphanPrompts(@NotNull List<CodeGraphStore.GraphDataNode> nodes,
                                            @NotNull List<CodeGraphStore.GraphDataEdge> edges) {
        if (nodes.isEmpty() || edges.isEmpty()) return;
        Set<String> connected = new HashSet<>();
        for (CodeGraphStore.GraphDataEdge e : edges) {
            connected.add(e.source());
            connected.add(e.target());
        }
        nodes.removeIf(n -> LABEL_PROMPT.equals(n.type()) && !connected.contains(n.id()));
    }

    @NotNull
    private static String graphFileName(@NotNull String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @NotNull
    private static String graphTruncate(@Nullable String s, int max) {
        if (s == null) return "";
        String cleaned = s.replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 1) + "…";
    }
}
