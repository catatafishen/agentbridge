package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link ConversationQuery} — structured query engine for conversation history.
 *
 * <p>Pure JDBC against in-memory SQLite — no IntelliJ platform needed.
 * Uses {@link ConversationWriter} to insert test data and then exercises the query API.
 */
class ConversationQueryTest {

    private Connection conn;
    private ConversationDatabase database;
    private ConversationWriter writer;
    private ConversationQuery query;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        database = new ConversationDatabase();
        database.initializeWithConnection(conn);
        writer = new ConversationWriter(database);
        query = new ConversationQuery(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    // ── Basic query behavior ──────────────────────────────────────────────────

    @Nested
    class BasicQuery {

        @Test
        void emptyDatabaseReturnsEmptyList() {
            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(5));
            assertTrue(results.isEmpty());
        }

        @Test
        void returnsTurnsOrderedByTimestampDescending() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Second", "2026-01-01T11:00:00Z", null, "t2", "t2")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Third", "2026-01-01T12:00:00Z", null, "t3", "t3")));

            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(10));
            assertEquals(3, results.size());
            assertEquals("Third", results.get(0).userMessage());
            assertEquals("Second", results.get(1).userMessage());
            assertEquals("First", results.get(2).userMessage());
        }

        @Test
        void lastNLimitsResults() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Second", "2026-01-01T11:00:00Z", null, "t2", "t2")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Third", "2026-01-01T12:00:00Z", null, "t3", "t3")));

            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(2));
            assertEquals(2, results.size());
            assertEquals("Third", results.get(0).userMessage());
            assertEquals("Second", results.get(1).userMessage());
        }

        @Test
        void offsetSkipsMostRecent() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Second", "2026-01-01T11:00:00Z", null, "t2", "t2")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Third", "2026-01-01T12:00:00Z", null, "t3", "t3")));

            var params = new ConversationQuery.QueryParams(
                null, null, 2, 1, null, null, null, null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(2, results.size());
            assertEquals("Second", results.get(0).userMessage());
            assertEquals("First", results.get(1).userMessage());
        }

        @Test
        void byTurnIdReturnsSingleTurn() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Second", "2026-01-01T11:00:00Z", null, "t2", "t2")));

            List<ConversationQuery.TurnSummary> results = query.query(
                ConversationQuery.QueryParams.byTurnId("t1"));
            assertEquals(1, results.size());
            assertEquals("First", results.get(0).userMessage());
            assertEquals("t1", results.get(0).turnId());
        }
    }

    // ── Session filtering ─────────────────────────────────────────────────────

    @Nested
    class SessionFilter {

        @Test
        void filterBySessionId() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Session 1", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-2", "Claude", "opencode", List.of(
                new EntryData.Prompt("Session 2", "2026-01-02T10:00:00Z", null, "t2", "t2")));

            var params = new ConversationQuery.QueryParams(
                null, "sess-1", null, null, null, null, null, null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Session 1", results.get(0).userMessage());
            assertEquals("sess-1", results.get(0).sessionId());
        }
    }

    // ── Text-based filters ────────────────────────────────────────────────────

    @Nested
    class TextFilters {

        @Test
        void filterByUserMessage() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Fix the bug in auth", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Add a test", "2026-01-01T11:00:00Z", null, "t2", "t2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, "auth", null, null, null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Fix the bug in auth", results.get(0).userMessage());
        }

        @Test
        void filterByUserMessageIsCaseInsensitive() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Fix the BUG", "2026-01-01T10:00:00Z", null, "t1", "t1")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, "bug", null, null, null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
        }

        @Test
        void filterByAssistantText() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.Text("Here's the solution using HashMap", "2026-01-01T10:00:01Z",
                    "assistant", "gpt-4", "e1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Next", "2026-01-01T11:00:00Z", null, "t2", "t2"),
                new EntryData.Text("Use a TreeMap instead", "2026-01-01T11:00:01Z",
                    "assistant", "gpt-4", "e2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, "HashMap", null, null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Help", results.get(0).userMessage());
        }

        @Test
        void filterByToolName() {
            EntryData.ToolCall tc = new EntryData.ToolCall("read_file", null, "call", "content",
                "completed", null, "/src/Main.java", false, null, null, "2026-01-01T10:00:01Z");
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Read it", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                tc));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("No tools", "2026-01-01T11:00:00Z", null, "t2", "t2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, "read_file", null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Read it", results.get(0).userMessage());
        }

        @Test
        void filterByFilePath() {
            EntryData.ToolCall tc = new EntryData.ToolCall("write_file", null, "call", "done",
                "completed", null, "/src/auth/Login.java", false, null, null, "2026-01-01T10:00:01Z");
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Write", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                tc));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Other", "2026-01-01T11:00:00Z", null, "t2", "t2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, "auth/Login", null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Write", results.get(0).userMessage());
        }
    }

    // ── Temporal and metadata filters ─────────────────────────────────────────

    @Nested
    class MetadataFilters {

        @Test
        void filterBySinceAndUntil() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Early", "2026-01-01T08:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Middle", "2026-01-01T12:00:00Z", null, "t2", "t2")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Late", "2026-01-01T18:00:00Z", null, "t3", "t3")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T15:00:00Z"),
                false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Middle", results.get(0).userMessage());
        }

        @Test
        void filterByAgentName() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("From Copilot", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-2", "Claude", "opencode", List.of(
                new EntryData.Prompt("From Claude", "2026-01-02T10:00:00Z", null, "t2", "t2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, "claude",
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("From Claude", results.get(0).userMessage());
        }

        @Test
        void filterByBranchPrefix() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("On feature", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.TurnStats("t1", 100, 0, 0, 0.0, 0, 0, 0, "gpt-4", "",
                    0, 0, 0, 0.0, 0, 0, 0,
                    "2026-01-01T10:00:01Z", "ts1", List.of(), "feature/auth", null)));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("On main", "2026-01-02T10:00:00Z", null, "t2", "t2"),
                new EntryData.TurnStats("t2", 100, 0, 0, 0.0, 0, 0, 0, "gpt-4", "",
                    0, 0, 0, 0.0, 0, 0, 0,
                    "2026-01-02T10:00:01Z", "ts2", List.of(), "main", null)));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, "feature", null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("On feature", results.get(0).userMessage());
        }
    }

    // ── Content inclusion ─────────────────────────────────────────────────────

    @Nested
    class ContentInclusion {

        @Test
        void includeToolCallsReturnsToolDetails() {
            EntryData.ToolCall tc = new EntryData.ToolCall("search_text", "{\"query\": \"hello\"}",
                "call", "found 3 matches", "completed", null, null, false, null, null,
                "2026-01-01T10:00:01Z");
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Search", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                tc));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, false, true, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertFalse(results.get(0).toolCalls().isEmpty());
            assertEquals("search_text", results.get(0).toolCalls().get(0).toolName());
        }

        @Test
        void includeThinkingReturnsThinkingBlocks() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Think", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.Thinking("Let me analyze...", "2026-01-01T10:00:01Z",
                    "assistant", "claude", "e1")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, true, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertFalse(results.get(0).thinkingBlocks().isEmpty());
            assertEquals("Let me analyze...", results.get(0).thinkingBlocks().get(0));
        }

        @Test
        void withoutIncludeToolCallsReturnsEmptyList() {
            EntryData.ToolCall tc = new EntryData.ToolCall("read_file", null, "call", "content",
                "completed", null, null, false, null, null, "2026-01-01T10:00:01Z");
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Read", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                tc));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertTrue(results.get(0).toolCalls().isEmpty());
        }

        @Test
        void assistantTextConcatenatesMultipleTextEvents() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.Text("Part 1", "2026-01-01T10:00:01Z", "assistant", "gpt-4", "e1"),
                new EntryData.Text("Part 2", "2026-01-01T10:00:02Z", "assistant", "gpt-4", "e2")));

            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(5));
            assertEquals(1, results.size());
            assertEquals("Part 1\nPart 2", results.get(0).assistantText());
        }
    }

    // ── Combined text search ──────────────────────────────────────────────────

    @Nested
    class CombinedTextSearch {

        @Test
        void combinedTextSearchesUserPromptByDefault() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Fix authentication bug", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Add logging", "2026-01-01T11:00:00Z", null, "t2", "t2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, false, false, 8000, "authentication", null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Fix authentication bug", results.get(0).userMessage());
        }

        @Test
        void combinedTextSearchesTextEventsByDefault() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.Text("Use ConcurrentHashMap for thread safety",
                    "2026-01-01T10:00:01Z", "assistant", "gpt-4", "e1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Next", "2026-01-01T11:00:00Z", null, "t2", "t2"),
                new EntryData.Text("Simple ArrayList works here",
                    "2026-01-01T11:00:01Z", "assistant", "gpt-4", "e2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, false, false, 8000, "ConcurrentHashMap",
                ConversationQuery.SearchScope.defaultScope());
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Help", results.get(0).userMessage());
        }

        @Test
        void combinedTextSearchesThinkingWhenScopeIncludes() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.Thinking("I need to refactor the singleton pattern",
                    "2026-01-01T10:00:01Z", "assistant", "claude", "e1")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, false, false, 8000, "singleton",
                EnumSet.of(ConversationQuery.SearchScope.THINKING));
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
        }

        @Test
        void combinedTextSearchesToolCallsWhenScopeIncludes() {
            EntryData.ToolCall tc = new EntryData.ToolCall("write_file",
                "{\"path\": \"/src/AuthService.java\"}", "call", "file written", "completed",
                null, null, false, null, null, "2026-01-01T10:00:01Z");
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Write", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                tc));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, false, false, 8000, "AuthService",
                EnumSet.of(ConversationQuery.SearchScope.TOOL_CALLS));
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
        }

        @Test
        void emptyCombinedScopesReturnsNoResults() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Everywhere", "2026-01-01T10:00:00Z", null, "t1", "t1")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, null, null, null, null, null, null,
                null, null, false, false, 8000, "Everywhere",
                EnumSet.noneOf(ConversationQuery.SearchScope.class));
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertTrue(results.isEmpty());
        }
    }

    // ── Turn metadata ─────────────────────────────────────────────────────────

    @Nested
    class TurnMetadata {

        @Test
        void prevTurnIdLinksToOlderTurn() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Second", "2026-01-01T11:00:00Z", null, "t2", "t2")));

            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(10));
            assertEquals(2, results.size());
            // Most recent turn (Second) should link back to First
            assertEquals("t1", results.get(0).prevTurnId());
            // Oldest turn (First) has no previous
            assertNull(results.get(1).prevTurnId());
        }

        @Test
        void agentNameAndDisplayNamePopulated() {
            writer.recordEntries("sess-1", "GitHub Copilot", "copilot", List.of(
                new EntryData.Prompt("Hello", "2026-01-01T10:00:00Z", null, "t1", "t1")));

            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(5));
            assertEquals(1, results.size());
            assertEquals("GitHub Copilot", results.get(0).agentName());
        }

        @Test
        void toolCallCountReflectsFinalisedStats() {
            EntryData.ToolCall tc1 = new EntryData.ToolCall("read_file", null, "call", "ok",
                "completed", null, null, false, null, null, "2026-01-01T10:00:01Z");
            EntryData.ToolCall tc2 = new EntryData.ToolCall("write_file", null, "call", "ok",
                "completed", null, null, false, null, null, "2026-01-01T10:00:02Z");
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Do things", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                tc1, tc2,
                new EntryData.TurnStats("t1", 100, 0, 0, 0.0, 2, 0, 0, "gpt-4", "",
                    0, 0, 0, 0.0, 0, 0, 0, "2026-01-01T10:00:05Z")));

            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(5));
            assertEquals(1, results.size());
            assertEquals(2, results.get(0).toolCallCount());
        }
    }

    // ── Human nudges ──────────────────────────────────────────────────────────

    @Nested
    class HumanNudges {

        @Test
        void humanNudgesIncludedInResults() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Start", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.Nudge("Keep going", "n1", true, "2026-01-01T10:01:00Z", "n1")));

            List<ConversationQuery.TurnSummary> results = query.query(ConversationQuery.QueryParams.lastN(5));
            assertEquals(1, results.size());
            assertEquals(1, results.get(0).humanNudges().size());
            assertEquals("Keep going", results.get(0).humanNudges().get(0));
        }

        @Test
        void userMessageFilterMatchesNudgeText() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Start", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.Nudge("Fix the NPE instead", "n1", true, "2026-01-01T10:01:00Z", "n1")));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Other turn", "2026-01-01T11:00:00Z", null, "t2", "t2")));

            var params = new ConversationQuery.QueryParams(
                null, null, 10, null, "NPE", null, null, null, null, null,
                null, null, false, false, 8000, null, null);
            List<ConversationQuery.TurnSummary> results = query.query(params);
            assertEquals(1, results.size());
            assertEquals("Start", results.get(0).userMessage());
        }
    }

    // ── List distinct branches/agents ─────────────────────────────────────────

    @Nested
    class DistinctLists {

        @Test
        void listDistinctBranchesReturnsEmpty() {
            assertTrue(query.listDistinctBranches().isEmpty());
        }

        @Test
        void listDistinctBranchesReturnsBranches() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("On feat", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                new EntryData.TurnStats("t1", 100, 0, 0, 0.0, 0, 0, 0, "", "",
                    0, 0, 0, 0.0, 0, 0, 0,
                    "2026-01-01T10:00:01Z", "ts1", List.of(), "feature/login", null)));
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("On main", "2026-01-02T10:00:00Z", null, "t2", "t2"),
                new EntryData.TurnStats("t2", 100, 0, 0, 0.0, 0, 0, 0, "", "",
                    0, 0, 0, 0.0, 0, 0, 0,
                    "2026-01-02T10:00:01Z", "ts2", List.of(), "main", null)));

            List<String> branches = query.listDistinctBranches();
            assertEquals(2, branches.size());
            // Most recently used first
            assertEquals("main", branches.get(0));
            assertEquals("feature/login", branches.get(1));
        }

        @Test
        void listDistinctAgentsReturnsEmpty() {
            assertTrue(query.listDistinctAgents().isEmpty());
        }

        @Test
        void listDistinctAgentsReturnsAgents() {
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Hello", "2026-01-01T10:00:00Z", null, "t1", "t1")));
            writer.recordEntries("sess-2", "Claude", "opencode", List.of(
                new EntryData.Prompt("World", "2026-01-02T10:00:00Z", null, "t2", "t2")));

            List<String> agents = query.listDistinctAgents();
            assertEquals(2, agents.size());
            assertTrue(agents.contains("Copilot"));
            assertTrue(agents.contains("Claude"));
        }
    }

    // ── Tool call history ─────────────────────────────────────────────────────

    @Nested
    class ToolCallHistory {

        @Test
        void loadToolCallHistoryReturnsEmptyWhenNoData() {
            List<ConversationQuery.ToolCallHistoryEntry> history = query.loadToolCallHistory(10, null);
            assertTrue(history.isEmpty());
        }

        @Test
        void loadToolCallHistoryReturnsMcpToolCalls() {
            EntryData.ToolCall tc = new EntryData.ToolCall("agentbridge-read_file",
                "{\"path\": \"Main.java\"}", "call", "file content", "completed",
                null, null, false, null, "read_file", "2026-01-01T10:00:01Z");
            writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                new EntryData.Prompt("Read", "2026-01-01T10:00:00Z", null, "t1", "t1"),
                tc));
            writer.markToolCallMcp(tc.getEntryId());

            List<ConversationQuery.ToolCallHistoryEntry> history = query.loadToolCallHistory(10, null);
            assertEquals(1, history.size());
            assertNotNull(history.get(0).eventId());
            assertEquals("read_file", history.get(0).toolName());
        }

        @Test
        void loadToolCallHistoryLimitsResults() {
            for (int i = 0; i < 5; i++) {
                EntryData.ToolCall tc = new EntryData.ToolCall("agentbridge-tool_" + i, null,
                    "call", "ok", "completed", null, null, false, null, "tool_" + i,
                    "2026-01-01T1" + i + ":00:01Z");
                writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
                    new EntryData.Prompt("Turn " + i, "2026-01-01T1" + i + ":00:00Z", null,
                        "t" + i, "t" + i),
                    tc));
                writer.markToolCallMcp(tc.getEntryId());
            }

            List<ConversationQuery.ToolCallHistoryEntry> history = query.loadToolCallHistory(3, null);
            assertEquals(3, history.size());
        }
    }

    // ── SearchScope enum ──────────────────────────────────────────────────────

    @Nested
    class SearchScopeTest {

        @Test
        void defaultScopeContainsUserPromptAndTextEvents() {
            Set<ConversationQuery.SearchScope> defaults = ConversationQuery.SearchScope.defaultScope();
            assertTrue(defaults.contains(ConversationQuery.SearchScope.USER_PROMPT));
            assertTrue(defaults.contains(ConversationQuery.SearchScope.TEXT_EVENTS));
            assertFalse(defaults.contains(ConversationQuery.SearchScope.THINKING));
            assertFalse(defaults.contains(ConversationQuery.SearchScope.TOOL_CALLS));
        }
    }
}
