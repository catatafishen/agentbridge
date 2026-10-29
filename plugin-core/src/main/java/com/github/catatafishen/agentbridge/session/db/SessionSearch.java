package com.github.catatafishen.agentbridge.session.db;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session-level search and aggregation used by the session picker.
 *
 * <p>A <em>session</em> spans many turns, and therefore many branches and commits. Git facts are
 * never read from a single column: they are the union over all turns of the session, combining the
 * branches captured at turn time with the branches of the commits linked to those turns
 * ({@code commits} joined to {@code graph_commits}). Branches that only come from the latter, or
 * were backfilled ({@code turns.git_info_inferred}), are reported as <em>inferred</em>.
 *
 * <p>Pure JDBC with no platform dependencies, so it can be tested against in-memory SQLite.
 */
public final class SessionSearch {

    private static final Logger LOG = Logger.getInstance(SessionSearch.class);

    private static final int MAX_COMMITS_PER_SESSION = 200;
    private static final int PROMPT_SNIPPET_LENGTH = 140;

    private final ConversationDatabase database;

    public SessionSearch(@NotNull ConversationDatabase database) {
        this.database = database;
    }

    /**
     * Search filters. Every field is optional; null/blank means "no constraint".
     *
     * @param text            free text: matches session name, any prompt in the session, branch and commit
     * @param agent           exact agent name
     * @param branch          sessions that touched this branch (captured or via linked commits)
     * @param commitPrefix    sessions that produced a commit starting with this hash prefix
     * @param sinceEpochMs    only sessions with activity at or after this time (0 = no limit)
     * @param onlyWithCommits only sessions that produced at least one commit
     * @param limit           page size (clamped to 1..500)
     * @param offset          rows to skip
     */
    public record Params(
        @Nullable String text,
        @Nullable String agent,
        @Nullable String branch,
        @Nullable String commitPrefix,
        long sinceEpochMs,
        boolean onlyWithCommits,
        int limit,
        int offset
    ) {
        public static Params all(int limit) {
            return new Params(null, null, null, null, 0, false, limit, 0);
        }
    }

    public record CommitRef(@NotNull String hash, @Nullable String message) {
    }

    /**
     * One row of the picker.
     *
     * @param branches         every branch the session touched, alphabetical
     * @param inferredBranches subset of {@code branches} that was inferred rather than captured
     * @param commits          commits produced by any turn of the session
     * @param matchedPrompt    the first prompt matching the search text, or null when not searching
     */
    public record SessionSummary(
        @NotNull String id,
        @NotNull String agentName,
        @NotNull String displayName,
        @NotNull String firstPrompt,
        @Nullable String matchedPrompt,
        long startedAtMs,
        long lastActivityMs,
        int turnCount,
        int linesAdded,
        int linesRemoved,
        @NotNull List<String> branches,
        @NotNull Set<String> inferredBranches,
        @NotNull List<CommitRef> commits
    ) {
        /**
         * Best human label: explicit name, else first prompt, else agent.
         */
        public @NotNull String label() {
            if (!displayName.isBlank()) return displayName;
            if (!firstPrompt.isBlank()) return firstPrompt;
            return agentName;
        }
    }

    /**
     * One turn of a session, for the preview pane.
     */
    public record TurnPreview(
        @NotNull String turnId,
        @NotNull String prompt,
        long startedAtMs,
        @Nullable String branch,
        @NotNull List<CommitRef> commits,
        int linesAdded,
        int linesRemoved
    ) {
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @NotNull
    public List<SessionSummary> search(@NotNull Params params) {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try {
                return searchInternal(conn, params);
            } catch (SQLException e) {
                LOG.warn("SessionSearch: search failed", e);
                return List.of();
            }
        }
    }

    private List<SessionSummary> searchInternal(Connection conn, Params p) throws SQLException {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        String text = p.text() == null ? "" : p.text().trim();
        if (!text.isEmpty()) {
            appendTextFilter(conn, text, where, args);
        }
        if (notBlank(p.agent())) {
            where.add("s.agent_name = ?");
            args.add(p.agent());
        }
        if (notBlank(p.branch())) {
            where.add(branchExists());
            args.add(p.branch());
            args.add(p.branch());
            args.add(p.branch());
        }
        if (notBlank(p.commitPrefix())) {
            where.add("""
                EXISTS (SELECT 1 FROM commits c JOIN turns t ON t.id = c.turn_id
                        WHERE t.session_id = s.id AND c.commit_hash LIKE ? ESCAPE '\\')""");
            args.add(escapeLike(p.commitPrefix().trim().toLowerCase()) + "%");
        }
        if (p.onlyWithCommits()) {
            where.add("""
                EXISTS (SELECT 1 FROM commits c JOIN turns t ON t.id = c.turn_id
                        WHERE t.session_id = s.id)""");
        }
        if (p.sinceEpochMs() > 0) {
            where.add("COALESCE(s.ended_at, s.started_at) >= ?");
            args.add(Instant.ofEpochMilli(p.sinceEpochMs()).toString());
        }

        String sql = """
            SELECT s.id, s.agent_name, COALESCE(s.display_name, ''), s.started_at,
                   COALESCE(s.ended_at, s.started_at) AS last_activity,
                   (SELECT COUNT(*) FROM turns WHERE session_id = s.id) AS turn_count,
                   COALESCE((SELECT SUM(lines_added) FROM turns WHERE session_id = s.id), 0),
                   COALESCE((SELECT SUM(lines_removed) FROM turns WHERE session_id = s.id), 0),
                   COALESCE((SELECT prompt_text FROM turns WHERE session_id = s.id
                             ORDER BY started_at LIMIT 1), '')
            FROM sessions s
            """
            + (where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where) + "\n")
            + "ORDER BY last_activity DESC LIMIT ? OFFSET ?";

        List<SessionSummary> result = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (Object a : args) ps.setObject(i++, a);
            ps.setInt(i++, Math.max(1, Math.min(500, p.limit())));
            ps.setInt(i, Math.max(0, p.offset()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9)});
                }
            }
        }
        // One batched query per fact instead of several per session.
        Map<String, GitFacts> facts = loadGitFactsBatch(conn,
            rows.stream().map(r -> (String) r[0]).toList());
        for (Object[] r : rows) {
            String id = (String) r[0];
            GitFacts git = facts.getOrDefault(id, new GitFacts(List.of(), Set.of(), List.of()));
            result.add(new SessionSummary(
                id, (String) r[1], (String) r[2], snippet((String) r[8]),
                text.isEmpty() ? null : findMatchedPrompt(conn, id, text),
                parseMs((String) r[3]), parseMs((String) r[4]),
                (Integer) r[5], (Integer) r[6], (Integer) r[7],
                git.branches, git.inferred, git.commits));
        }
        return result;
    }

    private static String branchExists() {
        return """
            (EXISTS (SELECT 1 FROM turns t WHERE t.session_id = s.id
                       AND (t.git_branch_at_start = ? OR t.git_branch_at_end = ?))
             OR EXISTS (SELECT 1 FROM commits c
                          JOIN turns t ON t.id = c.turn_id
                          JOIN graph_commits gc ON gc.hash = c.commit_hash
                        WHERE t.session_id = s.id AND gc.branch = ?))""";
    }

    /**
     * Free-text filter: session name, branch names, commit message/hash, or any prompt of the
     * session (FTS5 when available, otherwise LIKE).
     */
    private void appendTextFilter(Connection conn, String text, List<String> where, List<Object> args)
        throws SQLException {
        String like = "%" + escapeLike(text) + "%";
        List<String> any = new ArrayList<>();

        any.add("s.display_name LIKE ? ESCAPE '\\'");
        args.add(like);

        String fts = ftsQuery(text);
        if (hasFts(conn) && !fts.isEmpty()) {
            any.add("""
                s.id IN (SELECT t.session_id FROM turns_fts f JOIN turns t ON t.id = f.turn_id
                         WHERE turns_fts MATCH ?)""");
            args.add(fts);
        } else {
            any.add("EXISTS (SELECT 1 FROM turns t WHERE t.session_id = s.id AND t.prompt_text LIKE ? ESCAPE '\\')");
            args.add(like);
        }

        any.add("""
            EXISTS (SELECT 1 FROM turns t WHERE t.session_id = s.id
                      AND (t.git_branch_at_start LIKE ? ESCAPE '\\' OR t.git_branch_at_end LIKE ? ESCAPE '\\'))""");
        args.add(like);
        args.add(like);

        any.add("""
            EXISTS (SELECT 1 FROM commits c
                      JOIN turns t ON t.id = c.turn_id
                      LEFT JOIN graph_commits gc ON gc.hash = c.commit_hash
                    WHERE t.session_id = s.id
                      AND (c.commit_hash LIKE ? ESCAPE '\\' OR gc.message LIKE ? ESCAPE '\\'
                           OR gc.branch LIKE ? ESCAPE '\\'))""");
        args.add(escapeLike(text.toLowerCase()) + "%");
        args.add(like);
        args.add(like);

        where.add("(" + String.join(" OR ", any) + ")");
    }

    @Nullable
    private String findMatchedPrompt(Connection conn, String sessionId, String text) throws SQLException {
        String fts = ftsQuery(text);
        if (!fts.isEmpty() && hasFts(conn)) {
            // Same matcher as the filter, so a session found by FTS always shows why.
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT t.prompt_text FROM turns_fts f JOIN turns t ON t.id = f.turn_id
                WHERE t.session_id = ? AND turns_fts MATCH ?
                ORDER BY t.started_at LIMIT 1""")) {
                ps.setString(1, sessionId);
                ps.setString(2, fts);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return snippet(rs.getString(1));
                }
            }
        }
        String like = "%" + escapeLike(text) + "%";
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT prompt_text FROM turns
            WHERE session_id = ? AND prompt_text LIKE ? ESCAPE '\\'
            ORDER BY started_at LIMIT 1""")) {
            ps.setString(1, sessionId);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return snippet(rs.getString(1));
            }
        }
        return null;
    }

    // ── Git facts (session level) ─────────────────────────────────────────────

    private record GitFacts(List<String> branches, Set<String> inferred, List<CommitRef> commits) {
    }

    private Map<String, GitFacts> loadGitFactsBatch(Connection conn, List<String> sessionIds) throws SQLException {
        Map<String, GitFacts> out = new HashMap<>();
        if (sessionIds.isEmpty()) return out;
        String in = String.join(",", Collections.nCopies(sessionIds.size(), "?"));

        Map<String, Set<String>> captured = new HashMap<>();
        Map<String, Set<String>> all = new HashMap<>();
        Map<String, List<CommitRef>> commits = new HashMap<>();

        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT sid, b, MAX(inferred) FROM (
                SELECT session_id AS sid, git_branch_at_start AS b, git_info_inferred AS inferred
                  FROM turns WHERE session_id IN (""" + in + """
            )
                UNION ALL
                SELECT session_id, git_branch_at_end, git_info_inferred
                  FROM turns WHERE session_id IN (""" + in + """
            )
            ) WHERE b IS NOT NULL AND b != '' GROUP BY sid, b""")) {
            int i = 1;
            for (String id : sessionIds) ps.setString(i++, id);
            for (String id : sessionIds) ps.setString(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
                    if (rs.getInt(3) == 0) {
                        captured.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
                    }
                }
            }
        }

        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT t.session_id, c.commit_hash, gc.message, gc.branch, MIN(t.started_at)
            FROM commits c
              JOIN turns t ON t.id = c.turn_id
              LEFT JOIN graph_commits gc ON gc.hash = c.commit_hash
            WHERE t.session_id IN (""" + in + """
            )
            GROUP BY t.session_id, c.commit_hash
            ORDER BY MIN(t.started_at) DESC""")) {
            int i = 1;
            for (String id : sessionIds) ps.setString(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString(1);
                    List<CommitRef> list = commits.computeIfAbsent(sid, k -> new ArrayList<>());
                    if (list.size() < MAX_COMMITS_PER_SESSION) {
                        list.add(new CommitRef(rs.getString(2), rs.getString(3)));
                    }
                    String b = rs.getString(4);
                    if (b != null && !b.isBlank()) all.computeIfAbsent(sid, k -> new LinkedHashSet<>()).add(b);
                }
            }
        }

        for (String id : sessionIds) {
            List<String> sorted = new ArrayList<>(all.getOrDefault(id, Set.of()));
            sorted.sort(String::compareTo);
            Set<String> inferred = new LinkedHashSet<>(sorted);
            inferred.removeAll(captured.getOrDefault(id, Set.of()));
            out.put(id, new GitFacts(sorted, inferred, commits.getOrDefault(id, List.of())));
        }
        return out;
    }

    // ── Preview + filter value lists ──────────────────────────────────────────

    /**
     * All turns of a session, oldest first, with the commits each turn produced.
     */
    @NotNull
    public List<TurnPreview> listTurns(@NotNull String sessionId) {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try {
                return listTurnsInternal(conn, sessionId);
            } catch (SQLException e) {
                LOG.warn("SessionSearch: failed to list turns for " + sessionId, e);
                return List.of();
            }
        }
    }

    private List<TurnPreview> listTurnsInternal(Connection conn, String sessionId) throws SQLException {
        List<TurnPreview> turns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT id, prompt_text, started_at, COALESCE(git_branch_at_start, git_branch_at_end),
                   COALESCE(lines_added, 0), COALESCE(lines_removed, 0)
            FROM turns WHERE session_id = ? ORDER BY started_at""")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    turns.add(new TurnPreview(rs.getString(1), rs.getString(2), parseMs(rs.getString(3)),
                        rs.getString(4), new ArrayList<>(), rs.getInt(5), rs.getInt(6)));
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT c.turn_id, c.commit_hash, gc.message
            FROM commits c
              JOIN turns t ON t.id = c.turn_id
              LEFT JOIN graph_commits gc ON gc.hash = c.commit_hash
            WHERE t.session_id = ?""")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String turnId = rs.getString(1);
                    for (TurnPreview t : turns) {
                        if (t.turnId().equals(turnId)) {
                            t.commits().add(new CommitRef(rs.getString(2), rs.getString(3)));
                            break;
                        }
                    }
                }
            }
        }
        return turns;
    }

    /**
     * A turn that produced a commit, for the "show prompts for this commit" action.
     */
    public record CommitTurn(
        @NotNull String sessionId,
        @NotNull String sessionName,
        @NotNull String agentName,
        @NotNull String turnId,
        @NotNull String prompt,
        long startedAtMs
    ) {
    }

    /**
     * Turns whose work produced a commit starting with {@code hashPrefix} (min 4 characters), oldest first.
     * A commit can be linked to several turns when a turn's commit range overlapped another's.
     */
    @NotNull
    public List<CommitTurn> turnsForCommit(@NotNull String hashPrefix) {
        String prefix = hashPrefix.trim().toLowerCase();
        if (prefix.length() < 4) return List.of();
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT s.id, COALESCE(s.display_name, ''), s.agent_name, t.id, t.prompt_text, t.started_at
                FROM commits c
                  JOIN turns t ON t.id = c.turn_id
                  JOIN sessions s ON s.id = t.session_id
                WHERE c.commit_hash LIKE ? ESCAPE '\\'
                ORDER BY t.started_at""")) {
                ps.setString(1, escapeLike(prefix) + "%");
                List<CommitTurn> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new CommitTurn(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), parseMs(rs.getString(6))));
                    }
                }
                return result;
            } catch (SQLException e) {
                LOG.warn("SessionSearch: failed to look up turns for commit " + hashPrefix, e);
                return List.of();
            }
        }
    }

    /**
     * Distinct branches across all sessions (captured and via commits), most recently used first.
     */
    @NotNull
    public List<String> listBranches() {
        synchronized (database) {
            Connection conn = database.getConnection();
            if (conn == null) return List.of();
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT b FROM (
                    SELECT git_branch_at_start AS b, started_at AS ts FROM turns
                    UNION ALL
                    SELECT git_branch_at_end, started_at FROM turns
                    UNION ALL
                    SELECT gc.branch, t.started_at FROM commits c
                      JOIN turns t ON t.id = c.turn_id
                      JOIN graph_commits gc ON gc.hash = c.commit_hash
                ) WHERE b IS NOT NULL AND b != ''
                GROUP BY b ORDER BY MAX(ts) DESC""");
                 ResultSet rs = ps.executeQuery()) {
                List<String> branches = new ArrayList<>();
                while (rs.next()) branches.add(rs.getString(1));
                return branches;
            } catch (SQLException e) {
                LOG.warn("SessionSearch: failed to list branches", e);
                return List.of();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * True only when the table and all three sync triggers exist, so a partial index is never used.
     */
    private static boolean hasFts(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_master WHERE name IN "
                + "('turns_fts', 'turns_fts_ai', 'turns_fts_ad', 'turns_fts_au')");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) == 4;
        }
    }

    /**
     * Turns user text into a safe FTS5 query: each word quoted (so punctuation cannot break the
     * syntax) with a prefix star, all words required.
     */
    static String ftsQuery(@NotNull String text) {
        List<String> terms = new ArrayList<>();
        for (String raw : text.split("\\s+")) {
            String word = raw.replaceAll("[^\\p{L}\\p{N}_]", "");
            if (!word.isEmpty()) terms.add("\"" + word + "\"*");
        }
        return String.join(" ", terms);
    }

    static String escapeLike(@NotNull String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    private static String snippet(@Nullable String prompt) {
        if (prompt == null) return "";
        String flat = prompt.replaceAll("\\s+", " ").trim();
        return flat.length() > PROMPT_SNIPPET_LENGTH ? flat.substring(0, PROMPT_SNIPPET_LENGTH) + "…" : flat;
    }

    private static long parseMs(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            LOG.debug("SessionSearch: unparseable timestamp '" + iso + "'", e);
            return 0;
        }
    }
}
