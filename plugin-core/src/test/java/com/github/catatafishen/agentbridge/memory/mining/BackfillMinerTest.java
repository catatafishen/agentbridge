package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BackfillMiner} — backfill iteration logic and result aggregation.
 */
class BackfillMinerTest {

    // --- BackfillResult record ---

    @Test
    void backfillResultRecordFields() {
        BackfillMiner.BackfillResult result = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        assertEquals(5, result.sessions());
        assertEquals(20, result.stored());
        assertEquals(3, result.filtered());
        assertEquals(2, result.duplicates());
        assertEquals(30, result.exchanges());
    }

    @Test
    void backfillResultEquality() {
        BackfillMiner.BackfillResult a = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        BackfillMiner.BackfillResult b = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void backfillResultInequality() {
        BackfillMiner.BackfillResult a = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        BackfillMiner.BackfillResult b = new BackfillMiner.BackfillResult(5, 21, 3, 2, 30);
        assertNotEquals(a, b);
    }

    @Test
    void emptyBackfillResult() {
        BackfillMiner.BackfillResult empty = new BackfillMiner.BackfillResult(0, 0, 0, 0, 0);
        assertEquals(0, empty.sessions());
        assertEquals(0, empty.stored());
        assertEquals(0, empty.filtered());
        assertEquals(0, empty.duplicates());
        assertEquals(0, empty.exchanges());
    }

    @Test
    void backfillResultToStringContainsFields() {
        BackfillMiner.BackfillResult result = new BackfillMiner.BackfillResult(3, 10, 2, 1, 15);
        String str = result.toString();
        assertTrue(str.contains("3"));
        assertTrue(str.contains("10"));
        assertTrue(str.contains("15"));
    }

    // --- executeBackfill ---

    @Test
    void executeBackfillSingleSession() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Fix auth bug")
        );

        List<String> progress = new ArrayList<>();
        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(2, 1, 0, 3);
        BackfillMiner.EntryLoader loader = sessionId -> List.of(
            prompt("How to fix the auth bug?"),
            response("You should check the token validation logic and ensure proper error handling throughout the auth flow.")
        );

        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(sessions, loader, miner, progress::add);

        assertEquals(1, result.sessions());
        assertEquals(2, result.stored());
        assertEquals(1, result.filtered());
        assertEquals(0, result.duplicates());
        assertEquals(3, result.exchanges());

        assertTrue(progress.get(0).contains("Found 1 sessions"));
        assertTrue(progress.get(1).contains("Mining session 1 of 1: Fix auth bug"));
        assertTrue(progress.get(2).contains("Backfill complete"));
    }

    @Test
    void executeBackfillMultipleSessions() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Fix auth"),
            session("s2", "claude", "Add tests"),
            session("s3", "copilot", "Refactor DB")
        );

        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(1, 0, 0, 1);
        BackfillMiner.EntryLoader loader = sessionId -> List.of(
            prompt("Some prompt text about the task at hand"),
            response("Some response text with implementation details and analysis of the problem.")
        );

        List<String> progress = new ArrayList<>();
        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(sessions, loader, miner, progress::add);

        assertEquals(3, result.sessions());
        assertEquals(3, result.stored());
        assertEquals(0, result.filtered());
        assertEquals(0, result.duplicates());
        assertEquals(3, result.exchanges());
    }

    @Test
    void executeBackfillSkipsEmptyEntries() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Has entries"),
            session("s2", "copilot", "Empty session")
        );

        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(1, 0, 0, 1);
        BackfillMiner.EntryLoader loader = sessionId ->
            "s1".equals(sessionId) ? List.of(
                prompt("Question about architecture design"),
                response("Here is a detailed response about architecture patterns and design principles.")
            ) : Collections.emptyList();

        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(
            sessions, loader, miner, msg -> {
            });

        assertEquals(2, result.sessions());
        assertEquals(1, result.stored());
    }

    @Test
    void executeBackfillSkipsNullEntries() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Null entries")
        );

        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(1, 0, 0, 1);
        BackfillMiner.EntryLoader loader = sessionId -> null;

        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(
            sessions, loader, miner, msg -> {
            });

        assertEquals(1, result.sessions());
        assertEquals(0, result.stored());
    }

    @Test
    void executeBackfillHandlesExceptionPerSession() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Good session"),
            session("s2", "copilot", "Bad session"),
            session("s3", "copilot", "Good session 2")
        );

        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) -> {
            if ("s2".equals(sessionId)) throw new RuntimeException("Mining failed");
            return new TurnMiner.MineResult(1, 0, 0, 1);
        };
        BackfillMiner.EntryLoader loader = sessionId -> List.of(
            prompt("Question about the code"), response("Detailed code analysis and recommendations.")
        );

        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(
            sessions, loader, miner, msg -> {
            });

        // Session 2 failed, but sessions 1 and 3 succeeded
        assertEquals(3, result.sessions());
        assertEquals(2, result.stored());
    }

    @Test
    void executeBackfillAggregatesResults() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Session 1"),
            session("s2", "copilot", "Session 2")
        );

        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) -> {
            if ("s1".equals(sessionId)) return new TurnMiner.MineResult(3, 2, 1, 6);
            return new TurnMiner.MineResult(2, 1, 0, 3);
        };
        BackfillMiner.EntryLoader loader = sessionId -> List.of(
            prompt("Question"), response("Answer with lots of detail and analysis.")
        );

        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(
            sessions, loader, miner, msg -> {
            });

        assertEquals(2, result.sessions());
        assertEquals(5, result.stored());
        assertEquals(3, result.filtered());
        assertEquals(1, result.duplicates());
        assertEquals(9, result.exchanges());
    }

    @Test
    void executeBackfillUsesSessionNameForLabel() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("abcdef1234567890", "copilot", "")
        );

        List<String> progress = new ArrayList<>();
        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(0, 0, 0, 0);
        BackfillMiner.EntryLoader loader = sessionId -> Collections.emptyList();

        BackfillMiner backfillMiner = new BackfillMiner();
        backfillMiner.executeBackfill(sessions, loader, miner, progress::add);

        // Empty name → uses first 8 chars of session ID
        assertTrue(progress.get(1).contains("abcdef12"));
    }

    @Test
    void executeBackfillSummaryMessage() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Session 1")
        );

        List<String> progress = new ArrayList<>();
        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(5, 2, 1, 8);
        BackfillMiner.EntryLoader loader = sessionId -> List.of(
            prompt("Question"), response("Answer with sufficient length for the test.")
        );

        BackfillMiner backfillMiner = new BackfillMiner();
        backfillMiner.executeBackfill(sessions, loader, miner, progress::add);

        String summary = progress.getLast();
        assertTrue(summary.contains("Backfill complete"));
        assertTrue(summary.contains("5 memories stored"));
        assertTrue(summary.contains("1 duplicates"));
        assertTrue(summary.contains("2 filtered"));
    }

    @Test
    void executeBackfillHandlesLoaderException() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Session 1"),
            session("s2", "copilot", "Session 2")
        );

        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(1, 0, 0, 1);
        BackfillMiner.EntryLoader loader = sessionId -> {
            if ("s1".equals(sessionId)) throw new RuntimeException("IO error");
            return List.of(
                prompt("Question"), response("Answer with enough detail for testing.")
            );
        };

        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(
            sessions, loader, miner, msg -> {
            });

        // Session 1 loader failed, session 2 succeeded
        assertEquals(2, result.sessions());
        assertEquals(1, result.stored());
    }

    @Test
    void executeBackfillShortSessionIdTruncation() {
        // Session ID shorter than 8 chars, empty name → should truncate correctly
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("ab", "copilot", "")
        );

        List<String> progress = new ArrayList<>();
        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) ->
            new TurnMiner.MineResult(0, 0, 0, 0);
        BackfillMiner.EntryLoader loader = sessionId -> Collections.emptyList();

        BackfillMiner backfillMiner = new BackfillMiner();
        backfillMiner.executeBackfill(sessions, loader, miner, progress::add);

        // Math.min(8, 2) = 2, so "ab" is the label
        assertTrue(progress.get(1).contains("ab"));
    }

    @Test
    void executeBackfillRespectsCancel() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Session 1"),
            session("s2", "copilot", "Session 2"),
            session("s3", "copilot", "Session 3")
        );

        int[] callCount = {0};
        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) -> {
            callCount[0]++;
            return new TurnMiner.MineResult(1, 0, 0, 1);
        };
        BackfillMiner.EntryLoader loader = sessionId -> List.of(
            prompt("Question"), response("Answer with enough detail for testing purposes.")
        );

        // Cancel after first session is processed
        boolean[] cancelled = {false};
        List<String> progress = new ArrayList<>();
        List<Double> fractions = new ArrayList<>();
        BackfillMiner backfillMiner = new BackfillMiner();
        BackfillMiner.BackfillResult result = backfillMiner.executeBackfill(
            sessions, loader,
            (entries, sessionId, agent, exchangeProgress) -> {
                TurnMiner.MineResult r = miner.mine(entries, sessionId, agent, exchangeProgress);
                cancelled[0] = true; // cancel after first mine
                return r;
            },
            progress::add, fractions::add, () -> cancelled[0]);

        // Only 1 session was mined before cancellation stopped the loop
        assertEquals(1, callCount[0]);
        assertEquals(1, result.stored());
        assertTrue(progress.stream().anyMatch(p -> p.contains("cancelled")));
    }

    @Test
    void executeBackfillReportsExchangeProgress() {
        List<SessionStoreV2.SessionRecord> sessions = List.of(
            session("s1", "copilot", "Session 1")
        );

        List<String> progress = new ArrayList<>();
        List<Double> fractions = new ArrayList<>();
        BackfillMiner.MineFunction miner = (entries, sessionId, agent, exchangeProgress) -> {
            // Simulate 3 exchanges with progress callbacks
            if (exchangeProgress != null) {
                exchangeProgress.onExchange(1, 3);
                exchangeProgress.onExchange(2, 3);
                exchangeProgress.onExchange(3, 3);
            }
            return new TurnMiner.MineResult(3, 0, 0, 3);
        };
        BackfillMiner.EntryLoader loader = sessionId -> List.of(
            prompt("Question"), response("Answer with enough detail for testing purposes.")
        );

        BackfillMiner backfillMiner = new BackfillMiner();
        backfillMiner.executeBackfill(sessions, loader, miner,
            progress::add, fractions::add, () -> false);

        // Should contain exchange-level progress messages
        assertTrue(progress.stream().anyMatch(p -> p.contains("exchange 1 of 3")));
        assertTrue(progress.stream().anyMatch(p -> p.contains("exchange 3 of 3")));
        // Fraction should advance within session
        assertFalse(fractions.isEmpty());
        assertEquals(1.0, fractions.getLast());
    }

    // --- Helpers ---

    private static SessionStoreV2.SessionRecord session(String id, String agent, String name) {
        return new SessionStoreV2.SessionRecord(id, agent, name, 0L, 0L, 0);
    }

    private static EntryData prompt(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData response(String text) {
        EntryData.Text t = new EntryData.Text();
        t.setRaw(text);
        return t;
    }
}
