package com.github.catatafishen.agentbridge.psi.tools.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings;
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP tool for querying the project knowledge graph (PSI + git + agent activity).
 */
public final class QueryCodeGraphTool extends Tool {

    private static final Logger LOG = Logger.getInstance(QueryCodeGraphTool.class);

    private static final String PARAM_QUERY_TYPE = "query_type";
    private static final String PARAM_TARGET = "target";
    private static final String PARAM_PATH = "path";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_DEPTH = "depth";
    private static final String PARAM_SQL = "sql";
    private static final String PARAM_LIMIT = "limit";

    public QueryCodeGraphTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "query_knowledge_graph";
    }

    @Override
    public @NotNull String displayName() {
        return "Query Knowledge Graph";
    }

    @Override
    public @NotNull String description() {
        return "Query the project knowledge graph: PSI dependencies, git commit history, "
            + "and agent activity joined in one store. Use for impact analysis, "
            + "finding affected tests, and understanding file/commit history. "
            + "query_type: dependents_of, dependencies_of, recent_changes_impact, "
            + "file_history, commit_history, hotspots, affected_tests, sql.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.SEARCH;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.SEARCH;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_QUERY_TYPE, TYPE_STRING,
                "dependents_of | dependencies_of | recent_changes_impact | file_history | commit_history | hotspots | affected_tests | sql"),
            Param.optional(PARAM_TARGET, TYPE_STRING,
                "File path (project-relative) or fully-qualified name. Required for dependents_of, dependencies_of, file_history. Optional for commit_history (filters to that file)."),
            Param.optional(PARAM_PATH, TYPE_STRING,
                "Subtree filter for hotspots. Omit for whole-project scope."),
            Param.optional(PARAM_SINCE, TYPE_STRING,
                "Time window for activity queries. Accepts '2h', '30m', '1d', a date '2026-06-08', or ISO 8601."),
            Param.optional(PARAM_DEPTH, TYPE_INTEGER,
                "Traversal depth for dependents_of / dependencies_of. Default 1, max 5."),
            Param.optional(PARAM_SQL, TYPE_STRING,
                "Raw read-only SQL. Required when query_type=sql. Tables: graph_nodes, graph_edges, graph_file_index, tool_call_events, graph_commits, graph_commit_files."),
            Param.optional(PARAM_LIMIT, TYPE_INTEGER,
                "Max rows. Default 50, max 500.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!CodeGraphSettings.getInstance(project).isEnabled()) {
            return "Knowledge Graph is disabled. Enable it in the AgentBridge → Knowledge Graph tool window.";
        }
        CodeGraphStore.GraphStats stats = CodeGraphStore.getInstance(project).getStats();
        if (stats.isEmpty()) {
            return "Knowledge Graph is empty. Open the AgentBridge → Knowledge Graph tool window and click 'Rebuild'.";
        }

        String type = optString(args, PARAM_QUERY_TYPE, "");
        int limit = clamp(optInt(args, PARAM_LIMIT, 50), 1, 500);
        try {
            String body = switch (type.toLowerCase(Locale.ROOT)) {
                case "dependents_of" -> dependentsOf(args, limit);
                case "dependencies_of" -> dependenciesOf(args, limit);
                case "recent_changes_impact" -> recentChangesImpact(args, limit);
                case "file_history" -> fileHistory(args, limit);
                case "commit_history" -> commitHistory(args, limit);
                case "hotspots" -> hotspots(args, limit);
                case "affected_tests" -> affectedTests(args, limit);
                case "sql" -> rawSql(args, limit);
                default -> "Error: unknown query_type '" + type + "'. "
                    + "Use one of: dependents_of, dependencies_of, recent_changes_impact, "
                    + "file_history, commit_history, hotspots, affected_tests, sql.";
            };
            return body + "\n\n" + formatStats(stats);
        } catch (SQLException e) {
            LOG.warn("query_knowledge_graph SQL error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ── Query implementations ───────────────────────────────────────────────

    private @NotNull String dependentsOf(@NotNull JsonObject args, int limit) throws SQLException {
        String target = requireTarget(args);
        int depth = clamp(optInt(args, PARAM_DEPTH, 1), 1, 5);
        String sql = """
            WITH RECURSIVE walk(file, hop) AS (
              SELECT ?, 0
              UNION ALL
              SELECT e.source_file, w.hop + 1
                FROM graph_edges e
                JOIN graph_nodes n ON n.id = e.target_id AND n.kind = 'file'
                JOIN walk w ON n.source_file = w.file
               WHERE w.hop < ?
            )
            SELECT DISTINCT file AS dependent, hop FROM walk WHERE hop > 0
            ORDER BY hop, file
            LIMIT ?
            """;
        return formatRows(CodeGraphStore.getInstance(project).queryRaw(sql, target, depth, limit));
    }

    private @NotNull String dependenciesOf(@NotNull JsonObject args, int limit) throws SQLException {
        String target = requireTarget(args);
        int depth = clamp(optInt(args, PARAM_DEPTH, 1), 1, 5);
        String sql = """
            WITH RECURSIVE walk(file, hop) AS (
              SELECT ?, 0
              UNION ALL
              SELECT n.source_file, w.hop + 1
                FROM graph_edges e
                JOIN graph_nodes n ON n.id = e.target_id AND n.kind = 'file'
                JOIN walk w ON e.source_file = w.file
               WHERE w.hop < ?
            )
            SELECT DISTINCT file AS dependency, hop FROM walk WHERE hop > 0
            ORDER BY hop, file
            LIMIT ?
            """;
        return formatRows(CodeGraphStore.getInstance(project).queryRaw(sql, target, depth, limit));
    }

    private @NotNull String recentChangesImpact(@NotNull JsonObject args, int limit) throws SQLException {
        String sinceIso = parseSinceIso(optString(args, PARAM_SINCE, "24h"));
        String sql = """
            SELECT t.file_path AS file,
                   COUNT(DISTINCT t.event_id) AS edit_count,
                   COUNT(DISTINCT e.id) AS dependents_count,
                   MAX(ev.timestamp) AS last_edited_at
              FROM tool_call_events t
              JOIN events ev ON ev.id = t.event_id
              LEFT JOIN graph_nodes n ON n.source_file = t.file_path AND n.kind = 'file'
              LEFT JOIN graph_edges e ON e.target_id = n.id
             WHERE ev.timestamp >= ?
               AND t.file_path IS NOT NULL
               AND t.tool_kind IN ('edit','write','move','delete')
             GROUP BY t.file_path
             ORDER BY (COUNT(DISTINCT e.id) + 1) * COUNT(DISTINCT t.event_id) DESC
             LIMIT ?
            """;
        return formatRows(CodeGraphStore.getInstance(project).queryRaw(sql, sinceIso, limit));
    }

    private @NotNull String fileHistory(@NotNull JsonObject args, int limit) throws SQLException {
        String input = optString(args, PARAM_TARGET, "");
        if (input.isEmpty()) {
            throw new IllegalArgumentException("'target' parameter is required for this query_type");
        }
        CodeGraphStore store = CodeGraphStore.getInstance(project);

        // Agent tool call history
        List<Map<String, Object>> toolRows = store.queryRaw("""
            SELECT ev.timestamp AS at, t.tool_name, t.tool_kind, t.success
              FROM tool_call_events t
              JOIN events ev ON ev.id = t.event_id
             WHERE t.file_path = ?
             ORDER BY ev.timestamp DESC
             LIMIT ?
            """, input, limit);
        if (toolRows.isEmpty()) {
            String filename = input.contains("/") ? input.substring(input.lastIndexOf('/') + 1) : input;
            toolRows = store.queryRaw("""
                SELECT ev.timestamp AS at, t.tool_name, t.tool_kind, t.success
                  FROM tool_call_events t
                  JOIN events ev ON ev.id = t.event_id
                 WHERE t.file_path LIKE ?
                 ORDER BY ev.timestamp DESC
                 LIMIT ?
                """, "%" + filename, limit);
        }

        // Git commit history for this file
        String resolved = resolveCommitFilePath(store, input);
        List<Map<String, Object>> commitRows = store.queryRaw("""
            SELECT c.short_hash, c.message, c.author, c.timestamp, f.change_type
              FROM graph_commits c
              JOIN graph_commit_files f ON f.commit_hash = c.hash
             WHERE f.file_path = ?
             ORDER BY c.timestamp DESC
             LIMIT ?
            """, resolved, limit);

        StringBuilder sb = new StringBuilder();
        if (!commitRows.isEmpty()) {
            sb.append("=== Git Commits ===\n");
            sb.append(formatRows(commitRows));
        }
        if (!toolRows.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("=== Agent Tool Calls ===\n");
            sb.append(formatRows(toolRows));
        }
        if (sb.isEmpty()) return "No history found for: " + input;
        return sb.toString();
    }

    private @NotNull String commitHistory(@NotNull JsonObject args, int limit) throws SQLException {
        String target = optString(args, PARAM_TARGET, "");
        String since = optString(args, PARAM_SINCE, "");
        CodeGraphStore store = CodeGraphStore.getInstance(project);

        if (target.isEmpty() && since.isEmpty()) {
            // No filter — show most recent commits
            return formatRows(store.queryRaw("""
                SELECT c.short_hash, c.message, c.author, c.timestamp, c.branch,
                       COUNT(f.id) AS files_changed
                  FROM graph_commits c
                  LEFT JOIN graph_commit_files f ON f.commit_hash = c.hash
                 GROUP BY c.hash
                 ORDER BY c.timestamp DESC
                 LIMIT ?
                """, limit));
        }

        if (!target.isEmpty()) {
            // Show commits that touched a specific file
            String resolved = resolveCommitFilePath(store, target);
            return formatRows(store.queryRaw("""
                SELECT c.short_hash, c.message, c.author, c.timestamp, f.change_type
                  FROM graph_commits c
                  JOIN graph_commit_files f ON f.commit_hash = c.hash
                 WHERE f.file_path = ?
                 ORDER BY c.timestamp DESC
                 LIMIT ?
                """, resolved, limit));
        }

        // Filter by time
        String sinceIso = parseSinceIso(since);
        return formatRows(store.queryRaw("""
            SELECT c.short_hash, c.message, c.author, c.timestamp,
                   COUNT(f.id) AS files_changed
              FROM graph_commits c
              LEFT JOIN graph_commit_files f ON f.commit_hash = c.hash
             WHERE c.timestamp >= ?
             GROUP BY c.hash
             ORDER BY c.timestamp DESC
             LIMIT ?
            """, sinceIso, limit));
    }

    /**
     * Resolves a target path for commit file lookups.
     * Tries exact match in graph_commit_files, then falls back to filename suffix matching.
     */
    private @NotNull String resolveCommitFilePath(@NotNull CodeGraphStore store, @NotNull String input) throws SQLException {
        List<Map<String, Object>> exact = store.queryRaw(
            "SELECT DISTINCT file_path FROM graph_commit_files WHERE file_path = ? LIMIT 1", input);
        if (!exact.isEmpty()) return input;

        String filename = input.contains("/") ? input.substring(input.lastIndexOf('/') + 1) : input;
        List<Map<String, Object>> byName = store.queryRaw(
            "SELECT DISTINCT file_path FROM graph_commit_files WHERE file_path LIKE ? LIMIT 10",
            "%" + filename);
        if (byName.isEmpty()) return input;
        if (byName.size() == 1) return (String) byName.getFirst().get("file_path");

        for (Map<String, Object> row : byName) {
            String candidate = (String) row.get("file_path");
            if (candidate.endsWith(input) || input.endsWith(candidate)) return candidate;
        }
        return byName.stream()
            .map(r -> (String) r.get("file_path"))
            .min(java.util.Comparator.comparingInt(String::length))
            .orElse(input);
    }

    private @NotNull String hotspots(@NotNull JsonObject args, int limit) throws SQLException {
        String path = optString(args, PARAM_PATH, "");
        String like = path.isEmpty() ? "%" : (path.endsWith("/") ? path + "%" : path + "/%");
        String sql = """
            SELECT n.source_file AS file,
                   COUNT(DISTINCT e.id) AS dependents_count,
                   COALESCE((SELECT COUNT(*) FROM tool_call_events t
                              WHERE t.file_path = n.source_file
                                AND t.tool_kind IN ('edit','write')), 0) AS agent_edit_count
              FROM graph_nodes n
              JOIN graph_edges e ON e.target_id = n.id
             WHERE n.source_file LIKE ?
               AND n.kind = 'file'
             GROUP BY n.source_file
             ORDER BY dependents_count DESC
             LIMIT ?
            """;
        return formatRows(CodeGraphStore.getInstance(project).queryRaw(sql, like, limit));
    }

    private @NotNull String affectedTests(@NotNull JsonObject args, int limit) throws SQLException {
        String sinceIso = parseSinceIso(optString(args, PARAM_SINCE, "1h"));
        String sql = """
            SELECT DISTINCT n.source_file AS test_file
              FROM tool_call_events t
              JOIN events ev ON ev.id = t.event_id
              JOIN graph_nodes src_n ON src_n.source_file = t.file_path AND src_n.kind = 'file'
              JOIN graph_edges e ON e.target_id = src_n.id
              JOIN graph_nodes n ON n.id = e.source_id
             WHERE ev.timestamp >= ?
               AND t.tool_kind IN ('edit','write')
               AND (n.source_file LIKE '%/test/%'
                 OR n.source_file LIKE '%/tests/%'
                 OR n.source_file LIKE '%Test.%'
                 OR n.source_file LIKE '%_test.%')
             LIMIT ?
            """;
        return formatRows(CodeGraphStore.getInstance(project).queryRaw(sql, sinceIso, limit));
    }

    private @NotNull String rawSql(@NotNull JsonObject args, int limit) throws SQLException {
        String sql = optString(args, PARAM_SQL, "");
        if (sql.isEmpty()) return "Error: 'sql' parameter is required for query_type=sql.";
        // Inject LIMIT if missing — keeps unbounded SELECTs safe
        if (!sql.toLowerCase(Locale.ROOT).contains("limit")) {
            sql = sql.trim().replaceAll(";+$", "") + " LIMIT " + limit;
        }
        return formatRows(CodeGraphStore.getInstance(project).queryRaw(sql));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private @NotNull String requireTarget(@NotNull JsonObject args) throws SQLException {
        String t = optString(args, PARAM_TARGET, "");
        if (t.isEmpty()) {
            throw new IllegalArgumentException(
                "'target' parameter is required for this query_type");
        }
        return resolveTarget(t);
    }

    /**
     * Resolves a target file path against the graph index.
     * If the exact path isn't found, falls back to matching by filename suffix.
     * This makes the tool forgiving when agents provide incorrect directory prefixes.
     */
    private @NotNull String resolveTarget(@NotNull String input) throws SQLException {
        CodeGraphStore store = CodeGraphStore.getInstance(project);
        // Try exact match first
        List<Map<String, Object>> exact = store.queryRaw(
            "SELECT source_file FROM graph_nodes WHERE kind = 'file' AND source_file = ? LIMIT 1",
            input);
        if (!exact.isEmpty()) return input;

        // Fallback: match by filename (last path component)
        String filename = input.contains("/") ? input.substring(input.lastIndexOf('/') + 1) : input;
        List<Map<String, Object>> byName = store.queryRaw(
            "SELECT DISTINCT source_file FROM graph_nodes WHERE kind = 'file' AND source_file LIKE ? LIMIT 10",
            "%" + filename);
        if (byName.isEmpty()) return input; // no match at all — return as-is, query will be empty
        if (byName.size() == 1) {
            return (String) byName.getFirst().get("source_file");
        }
        // Multiple matches — try to pick the one whose path suffix best matches the input
        for (Map<String, Object> row : byName) {
            String candidate = (String) row.get("source_file");
            if (candidate.endsWith(input) || input.endsWith(candidate)) return candidate;
        }
        // Last resort: pick the shortest match (likely the most specific project file)
        return byName.stream()
            .map(r -> (String) r.get("source_file"))
            .min(java.util.Comparator.comparingInt(String::length))
            .orElse(input);
    }

    private static @NotNull String optString(@NotNull JsonObject args, @NotNull String key, @NotNull String dflt) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : dflt;
    }

    private static int optInt(@NotNull JsonObject args, @NotNull String key, int dflt) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : dflt;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Convert a duration string ('2h', '30m', '1d') or ISO date to an ISO-8601 instant string.
     */
    static @NotNull String parseSinceIso(@Nullable String s) {
        long ms = parseSinceMs(s);
        return java.time.Instant.ofEpochMilli(ms).toString();
    }

    /**
     * Convert a duration string or ISO date to an epoch-ms cutoff.
     */
    static long parseSinceMs(@Nullable String s) {
        if (s == null || s.isBlank()) return 0L;
        s = s.trim();
        try {
            if (s.matches("(?i)\\d+[smhd]")) {
                char unit = Character.toLowerCase(s.charAt(s.length() - 1));
                long n = Long.parseLong(s.substring(0, s.length() - 1));
                long ms = switch (unit) {
                    case 's' -> n * 1_000L;
                    case 'm' -> n * 60_000L;
                    case 'h' -> n * 3_600_000L;
                    case 'd' -> n * 86_400_000L;
                    default -> 0L;
                };
                return System.currentTimeMillis() - ms;
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return java.time.LocalDate.parse(s)
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant().toEpochMilli();
            }
            return java.time.Instant.parse(s).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static @NotNull String formatRows(@NotNull List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "No results.";
        // Compact table output: header + rows
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("  |  ", cols)).append('\n');
        sb.append("-".repeat(Math.min(120, cols.size() * 20))).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String c : cols) {
                Object v = row.get(c);
                values.add(v == null ? "" : v.toString());
            }
            sb.append(String.join("  |  ", values)).append('\n');
        }
        sb.append('\n').append(rows.size()).append(" row(s)");
        return sb.toString();
    }

    private static @NotNull String formatStats(@NotNull CodeGraphStore.GraphStats stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", stats.nodeCount());
        m.put("edges", stats.edgeCount());
        m.put("files_indexed", stats.fileCount());
        m.put("commits_indexed", stats.commitCount());
        m.put("last_indexed_at_ms", stats.lastIndexedAt());
        return "graph_stats: " + m;
    }
}
