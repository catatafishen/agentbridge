package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSearchTest {

    private static final String H1 = "1".repeat(40);
    private static final String H2 = "2".repeat(40);
    private static final String H3 = "3".repeat(40);

    private Connection conn;
    private ConversationWriter writer;
    private SessionSearch search;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        ConversationDatabase database = new ConversationDatabase();
        database.initializeWithConnection(conn);
        writer = new ConversationWriter(database);
        search = new SessionSearch(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    private void graphCommit(String hash, String message, String branch) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO graph_commits (hash, short_hash, message, author, timestamp, branch, indexed_at) "
                + "VALUES (?, ?, ?, 'a', '2026-01-01T00:00:00Z', ?, 0)")) {
            ps.setString(1, hash);
            ps.setString(2, hash.substring(0, 7));
            ps.setString(3, message);
            ps.setString(4, branch);
            ps.executeUpdate();
        }
    }

    private static EntryData.TurnStats stats(String turnId, String time, String branch, List<String> commits) {
        return new EntryData.TurnStats(turnId, 10, 1, 1, 0.0, 0, 3, 1, "m", "",
            0, 0, 0, 0.0, 0, 0, 0, time, turnId + "-stats", commits, branch, branch);
    }

    /**
     * s1 spans two branches and two commits; s2 is on one branch with no commits.
     */
    private void seed() throws Exception {
        graphCommit(H1, "Add payment gateway", "feature/pay");
        graphCommit(H2, "Fix login", "bugfix/login");
        writer.recordEntries("s1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Implement the payment gateway", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            stats("t1", "2026-01-01T10:01:00Z", "feature/pay", List.of(H1)),
            new EntryData.Prompt("Now fix the login bug", "2026-01-01T11:00:00Z", null, "t2", "t2"),
            stats("t2", "2026-01-01T11:01:00Z", "bugfix/login", List.of(H2))));
        writer.recordEntries("s2", "Claude", "claude", List.of(
            new EntryData.Prompt("Explain the build setup", "2026-01-02T10:00:00Z", null, "t3", "t3"),
            stats("t3", "2026-01-02T10:01:00Z", "master", List.of())));
    }

    @Test
    void sessionAggregatesAllBranchesAndCommitsOfItsTurns() throws Exception {
        seed();

        SessionSearch.SessionSummary s1 = search.search(SessionSearch.Params.all(10)).stream()
            .filter(s -> s.id().equals("s1")).findFirst().orElseThrow();

        assertEquals(List.of("bugfix/login", "feature/pay"), s1.branches());
        assertEquals(2, s1.commits().size());
        assertEquals(2, s1.turnCount());
        assertEquals(6, s1.linesAdded());
        assertEquals("Implement the payment gateway", s1.firstPrompt());
        assertTrue(s1.inferredBranches().isEmpty());
    }

    @Test
    void branchOnlyKnownThroughCommitsIsReportedAsInferred() throws Exception {
        graphCommit(H3, "Docs", "docs/readme");
        writer.recordEntries("s3", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Write docs", "2026-01-03T10:00:00Z", null, "t4", "t4"),
            stats("t4", "2026-01-03T10:01:00Z", null, List.of(H3))));

        SessionSearch.SessionSummary s = search.search(SessionSearch.Params.all(10)).getFirst();

        assertEquals(List.of("docs/readme"), s.branches());
        assertEquals(java.util.Set.of("docs/readme"), s.inferredBranches());
    }

    @Test
    void filtersByBranchUsingCapturedAndCommitBranches() throws Exception {
        seed();

        List<SessionSearch.SessionSummary> pay = search.search(
            new SessionSearch.Params(null, null, "feature/pay", null, 0, false, 10, 0));
        assertEquals(List.of("s1"), pay.stream().map(SessionSearch.SessionSummary::id).toList());

        List<SessionSearch.SessionSummary> master = search.search(
            new SessionSearch.Params(null, null, "master", null, 0, false, 10, 0));
        assertEquals(List.of("s2"), master.stream().map(SessionSearch.SessionSummary::id).toList());
    }

    @Test
    void filtersByCommitPrefixAcrossAnyTurn() throws Exception {
        seed();

        List<SessionSearch.SessionSummary> hits = search.search(
            new SessionSearch.Params(null, null, null, "2222", 0, false, 10, 0));

        assertEquals(List.of("s1"), hits.stream().map(SessionSearch.SessionSummary::id).toList());
    }

    @Test
    void freeTextMatchesPromptsInsideSessionAndReturnsTheMatchedPrompt() throws Exception {
        seed();

        List<SessionSearch.SessionSummary> hits = search.search(
            new SessionSearch.Params("login", null, null, null, 0, false, 10, 0));

        assertEquals(1, hits.size());
        assertEquals("s1", hits.getFirst().id());
        assertEquals("Now fix the login bug", hits.getFirst().matchedPrompt());
    }

    @Test
    void freeTextMatchesCommitMessageAndPrefixOfWord() throws Exception {
        seed();

        assertEquals(List.of("s1"), search.search(
                new SessionSearch.Params("payment", null, null, null, 0, false, 10, 0))
            .stream().map(SessionSearch.SessionSummary::id).toList());
        assertEquals(List.of("s2"), search.search(
                new SessionSearch.Params("expl", null, null, null, 0, false, 10, 0))
            .stream().map(SessionSearch.SessionSummary::id).toList());
    }

    @Test
    void specialCharactersInSearchTextDoNotBreakTheQuery() throws Exception {
        seed();

        assertTrue(search.search(new SessionSearch.Params("\"foo* AND (bar", null, null, null, 0, false, 10, 0))
            .isEmpty());
        assertTrue(search.search(new SessionSearch.Params("100%_", null, null, null, 0, false, 10, 0)).isEmpty());
    }

    @Test
    void onlyWithCommitsAndAgentFilters() throws Exception {
        seed();

        assertEquals(List.of("s1"), search.search(
                new SessionSearch.Params(null, null, null, null, 0, true, 10, 0))
            .stream().map(SessionSearch.SessionSummary::id).toList());
        assertEquals(List.of("s2"), search.search(
                new SessionSearch.Params(null, "Claude", null, null, 0, false, 10, 0))
            .stream().map(SessionSearch.SessionSummary::id).toList());
    }

    @Test
    void emptySearchReturnsMostRecentFirstAndHasNoMatchedPrompt() throws Exception {
        seed();

        List<SessionSearch.SessionSummary> all = search.search(SessionSearch.Params.all(10));

        assertEquals(List.of("s2", "s1"), all.stream().map(SessionSearch.SessionSummary::id).toList());
        assertNull(all.getFirst().matchedPrompt());
    }

    @Test
    void listTurnsAttachesCommitsToTheTurnThatMadeThem() throws Exception {
        seed();

        List<SessionSearch.TurnPreview> turns = search.listTurns("s1");

        assertEquals(2, turns.size());
        assertEquals("Implement the payment gateway", turns.get(0).prompt());
        assertEquals(H1, turns.get(0).commits().getFirst().hash());
        assertEquals("Fix login", turns.get(1).commits().getFirst().message());
    }

    @Test
    void listBranchesIncludesEveryKnownBranch() throws Exception {
        seed();

        List<String> branches = search.listBranches();

        assertTrue(branches.containsAll(List.of("feature/pay", "bugfix/login", "master")));
        assertFalse(branches.contains(""));
    }

    @Test
    void turnsForCommitFindsTheTurnThatProducedItByPrefix() throws Exception {
        seed();

        List<SessionSearch.CommitTurn> turns = search.turnsForCommit("1111");

        assertEquals(1, turns.size());
        assertEquals("s1", turns.getFirst().sessionId());
        assertEquals("t1", turns.getFirst().turnId());
        assertEquals("Implement the payment gateway", turns.getFirst().prompt());
    }

    @Test
    void turnsForCommitIgnoresTooShortOrUnknownPrefixes() throws Exception {
        seed();

        assertTrue(search.turnsForCommit("111").isEmpty());
        assertTrue(search.turnsForCommit("ffff").isEmpty());
        assertTrue(search.turnsForCommit("%%%%").isEmpty());
    }

    @Test
    void ftsQuerySanitisesInput() {
        assertEquals("\"foo\"* \"bar\"*", SessionSearch.ftsQuery("foo* (bar"));
        assertEquals("", SessionSearch.ftsQuery("*** ()"));
    }
}
