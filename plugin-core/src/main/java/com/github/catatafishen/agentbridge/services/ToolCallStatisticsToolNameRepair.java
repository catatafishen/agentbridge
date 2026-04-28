package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-shot repair that fixes {@code tool_calls} rows whose {@code tool_name} was
 * polluted by the legacy backfill (which stored the agent-supplied chip {@code title}
 * — e.g. "Tail full log", "Run summary" — instead of the canonical MCP tool id).
 *
 * <p>For each row whose {@code tool_name} is not a known canonical id from
 * {@link ToolRegistry}, the repair tries to recover the canonical id via:</p>
 * <ol>
 *   <li>Stripping {@code agentbridge-} / {@code agentbridge_} / {@code @agentbridge/}
 *       prefixes that some agents add.</li>
 *   <li>Matching the polluted value against {@link ToolRegistry#findByDisplayName(String)}.</li>
 * </ol>
 *
 * <p>Rows that match are updated: the original {@code tool_name} is moved to
 * {@code display_name} and the canonical id replaces it. Rows that don't match
 * any known tool are deleted — they have no aggregation value (each was a unique
 * non-deterministic title).</p>
 *
 * <p>The repair is idempotent and runs at most once per database (tracked via a
 * {@code meta} key/value table). Subsequent invocations are no-ops.</p>
 */
public final class ToolCallStatisticsToolNameRepair {

    private static final Logger LOG = Logger.getInstance(ToolCallStatisticsToolNameRepair.class);
    private static final String REPAIR_MARKER_KEY = "tool_name_repair_v1_done";

    private ToolCallStatisticsToolNameRepair() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Result of a repair operation.
     *
     * @param scanned    total rows examined
     * @param repaired   rows whose {@code tool_name} was successfully recovered to a canonical id
     * @param deleted    rows whose {@code tool_name} clearly looked like a display title
     *                   (contained whitespace) and could not be recovered — removed
     * @param skipped    rows already matching a canonical id (left untouched)
     * @param kept       rows whose {@code tool_name} looked like a tool id but isn't in the
     *                   current registry. These are <b>not</b> deleted (they may come from an
     *                   older plugin version where the tool has since been renamed/removed),
     *                   and their presence prevents the run-once marker from being written so
     *                   a future plugin version with the missing registration can repair them.
     * @param alreadyRun true if the repair had already run on this DB and was skipped entirely
     */
    public record RepairResult(int scanned, int repaired, int deleted, int skipped, int kept, boolean alreadyRun) {
        @NotNull
        @Override
        public String toString() {
            if (alreadyRun) {
                return "RepairResult{alreadyRun=true}";
            }
            return "RepairResult{scanned=" + scanned + ", repaired=" + repaired
                + ", deleted=" + deleted + ", skipped=" + skipped + ", kept=" + kept + "}";
        }
    }

    /**
     * Run the repair on the given connection. Safe to call repeatedly — the second
     * call returns {@link RepairResult#alreadyRun() alreadyRun=true} immediately.
     *
     * @param connection live JDBC connection (auto-commit may be on or off)
     * @param registry   the project's tool registry (used to look up canonical ids)
     * @return a result describing what changed
     */
    public static RepairResult repair(@NotNull Connection connection, @NotNull ToolRegistry registry) {
        return repair(connection, collectKnownIds(registry), registry::findByDisplayName);
    }

    /**
     * Lower-level overload that accepts the canonical-id set and display-name lookup
     * directly. Package-private to keep the production API small while letting tests
     * exercise the repair without instantiating a full {@link ToolRegistry}
     * (which has an instrumented {@code @NotNull Project} constructor parameter).
     */
    static RepairResult repair(@NotNull Connection connection,
                               @NotNull Set<String> knownIds,
                               @NotNull java.util.function.Function<String, ToolDefinition> displayNameLookup) {
        try {
            ensureMetaTable(connection);
            if (isMarkerSet(connection)) {
                return new RepairResult(0, 0, 0, 0, 0, true);
            }

            if (knownIds.isEmpty()) {
                LOG.warn("Tool name repair: registry is empty — skipping (will retry on next IDE start)");
                return new RepairResult(0, 0, 0, 0, 0, false);
            }

            List<Row> rows = loadAllRows(connection);
            int repaired = 0;
            int deleted = 0;
            int skipped = 0;
            int kept = 0;

            for (Row row : rows) {
                if (knownIds.contains(row.toolName)) {
                    skipped++;
                    continue;
                }
                String canonical = resolveCanonical(row.toolName, knownIds, displayNameLookup);
                if (canonical != null) {
                    updateToolName(connection, row.id, canonical, row.toolName);
                    repaired++;
                } else if (looksLikeDisplayTitle(row.toolName)) {
                    // Free-form chip title with no canonical match — safe to delete.
                    deleteRow(connection, row.id);
                    deleted++;
                } else {
                    // Looks like a tool id but isn't in the current registry. Likely a
                    // tool that's been renamed/removed in this plugin version. Keep the
                    // row (preserves historical aggregates) and don't set the marker, so
                    // a future plugin version with the registration restored can repair it.
                    kept++;
                }
            }

            // Only mark repair as done when nothing remains unresolved. Re-running on
            // every IDE start is cheap (one SELECT + N hash lookups) and idempotent for
            // canonical/display-title rows.
            if (kept == 0) {
                setMarker(connection);
            }
            LOG.info("Tool name repair complete: scanned=" + rows.size()
                + " repaired=" + repaired + " deleted=" + deleted
                + " skipped=" + skipped + " kept=" + kept);
            return new RepairResult(rows.size(), repaired, deleted, skipped, kept, false);
        } catch (SQLException e) {
            LOG.warn("Tool name repair failed — leaving data untouched", e);
            return new RepairResult(0, 0, 0, 0, 0, false);
        }
    }

    private static String resolveCanonical(@NotNull String pollutedName,
                                           @NotNull Set<String> knownIds,
                                           @NotNull java.util.function.Function<String, ToolDefinition> displayNameLookup) {
        // 1. Strip agent-added MCP prefixes ("agentbridge-read_file" → "read_file")
        String stripped = ToolCallStatisticsBackfill.stripMcpPrefix(pollutedName);
        if (!stripped.equals(pollutedName) && knownIds.contains(stripped)) {
            return stripped;
        }
        // 2. Match against the human-readable display name ("Read File" → "read_file").
        //    Trim leading/trailing whitespace first — agent display titles occasionally
        //    have stray spaces that would otherwise prevent the lookup from matching.
        //    Verify the resolved id is actually in knownIds before accepting it: the
        //    lookup may return a tool that's been removed from the live registry.
        String normalized = pollutedName.trim();
        ToolDefinition def = displayNameLookup.apply(normalized);
        if (def != null && knownIds.contains(def.id())) {
            return def.id();
        }
        return null;
    }

    /**
     * Heuristic: does {@code name} look like a free-form display title (vs. a canonical
     * tool id)? Canonical ids are lowercase tokens with {@code _}, {@code -}, {@code /},
     * or {@code @} separators (e.g. {@code read_file}, {@code @agentbridge/git_status}).
     * Anything containing whitespace is unambiguously a display title, never a tool id —
     * those rows are safe to delete because each one is a unique non-deterministic chip
     * label with no aggregation value.
     */
    static boolean looksLikeDisplayTitle(@NotNull String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) return true;
        }
        return false;
    }

    private static void ensureMetaTable(@NotNull Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS meta (
                    key   TEXT PRIMARY KEY,
                    value TEXT
                )
                """);
        }
    }

    private static boolean isMarkerSet(@NotNull Connection connection) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM meta WHERE key = ?")) {
            stmt.setString(1, REPAIR_MARKER_KEY);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void setMarker(@NotNull Connection connection) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            stmt.setString(1, REPAIR_MARKER_KEY);
            stmt.setString(2, java.time.Instant.now().toString());
            stmt.executeUpdate();
        }
    }

    @NotNull
    private static Set<String> collectKnownIds(@NotNull ToolRegistry registry) {
        Set<String> ids = new HashSet<>();
        for (ToolDefinition def : registry.getAllTools()) {
            ids.add(def.id());
        }
        return ids;
    }

    @NotNull
    private static List<Row> loadAllRows(@NotNull Connection connection) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, tool_name FROM tool_calls")) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong("id"), rs.getString("tool_name")));
            }
        }
        return rows;
    }

    private static void updateToolName(@NotNull Connection connection,
                                       long rowId,
                                       @NotNull String canonical,
                                       @NotNull String previous) throws SQLException {
        // Preserve the original polluted value in display_name (only if it differs
        // and there's no existing display_name to overwrite).
        try (PreparedStatement stmt = connection.prepareStatement("""
            UPDATE tool_calls
               SET tool_name = ?,
                   display_name = COALESCE(display_name, ?)
             WHERE id = ?
            """)) {
            stmt.setString(1, canonical);
            stmt.setString(2, canonical.equals(previous) ? null : previous);
            stmt.setLong(3, rowId);
            stmt.executeUpdate();
        }
    }

    private static void deleteRow(@NotNull Connection connection, long rowId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "DELETE FROM tool_calls WHERE id = ?")) {
            stmt.setLong(1, rowId);
            stmt.executeUpdate();
        }
    }

    private record Row(long id, String toolName) {
    }
}
