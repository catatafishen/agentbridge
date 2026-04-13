package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationReplayerTest {

    private ConversationReplayer replayer;

    @BeforeEach
    void setUp() {
        replayer = new ConversationReplayer();
    }

    // ── 1. Empty input ────────────────────────────────────────────────────────

    @Test
    void emptyEntries_allCountsZero() {
        replayer.loadAndSplit(Collections.emptyList(), 5);

        assertTrue(replayer.recentEntries().isEmpty());
        assertTrue(replayer.deferredEntries().isEmpty());
        assertEquals(0, replayer.deferredCount());
        assertEquals(0, replayer.remainingPromptCount());
        assertEquals(0, replayer.totalPromptCount());
        assertEquals(0, replayer.totalLoadedCount());
    }

    // ── 2. Single turn fits entirely in recent ────────────────────────────────

    @Test
    void singleTurn_recentContainsAll_deferredEmpty() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        List<EntryData> entries = Arrays.asList(p1, t1);

        replayer.loadAndSplit(entries, 5);

        assertEquals(2, replayer.recentEntries().size());
        assertSame(p1, replayer.recentEntries().get(0));
        assertSame(t1, replayer.recentEntries().get(1));
        assertTrue(replayer.deferredEntries().isEmpty());
        assertEquals(0, replayer.deferredCount());
    }

    // ── 3. Two turns, recentTurns=1 → first turn deferred ────────────────────

    @Test
    void twoTurns_recentTurns1_defersFirst() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        List<EntryData> entries = Arrays.asList(p1, t1, p2, t2);

        replayer.loadAndSplit(entries, 1);

        // Recent: last turn [p2, t2]
        assertEquals(2, replayer.recentEntries().size());
        assertSame(p2, replayer.recentEntries().get(0));
        assertSame(t2, replayer.recentEntries().get(1));

        // Deferred: first turn [p1, t1]
        assertEquals(2, replayer.deferredEntries().size());
        assertSame(p1, replayer.deferredEntries().get(0));
        assertSame(t1, replayer.deferredEntries().get(1));
    }

    // ── 4. Five turns, recentTurns=2 → three turns deferred ──────────────────

    @Test
    void fiveTurns_recentTurns2_defersThree() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        EntryData.Prompt p4 = new EntryData.Prompt("q4");
        EntryData.Text t4 = new EntryData.Text("a4");
        EntryData.Prompt p5 = new EntryData.Prompt("q5");
        EntryData.Text t5 = new EntryData.Text("a5");
        List<EntryData> entries = Arrays.asList(p1, t1, p2, t2, p3, t3, p4, t4, p5, t5);

        replayer.loadAndSplit(entries, 2);

        // Recent: last 2 prompt turns → [p4, t4, p5, t5]
        assertEquals(4, replayer.recentEntries().size());
        assertSame(p4, replayer.recentEntries().get(0));
        assertSame(t4, replayer.recentEntries().get(1));
        assertSame(p5, replayer.recentEntries().get(2));
        assertSame(t5, replayer.recentEntries().get(3));

        // Deferred: first 3 turns → 6 entries, 3 prompts
        assertEquals(6, replayer.deferredEntries().size());
        assertEquals(6, replayer.deferredCount());
        assertEquals(3, replayer.remainingPromptCount());
    }

    // ── 5. loadNextBatch pops the newest deferred turn ───────────────────────

    @Test
    void loadNextBatch_popsFromDeferred() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        replayer.loadAndSplit(Arrays.asList(p1, t1, p2, t2, p3, t3), 1);
        // recent=[p3,t3], deferred=[p1,t1,p2,t2]

        List<EntryData> batch = replayer.loadNextBatch(1);

        // Newest deferred turn popped: [p2, t2]
        assertEquals(2, batch.size());
        assertSame(p2, batch.get(0));
        assertSame(t2, batch.get(1));

        // Remaining deferred: [p1, t1]
        assertEquals(2, replayer.deferredCount());
    }

    // ── 6. loadNextBatch requesting more turns than available drains fully ────

    @Test
    void loadNextBatch_moreThanAvailable_drainsFully() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        replayer.loadAndSplit(Arrays.asList(p1, t1, p2, t2, p3, t3), 1);
        // deferred=[p1,t1,p2,t2]

        List<EntryData> batch = replayer.loadNextBatch(10);

        // All deferred entries returned in chronological order
        assertEquals(4, batch.size());
        assertSame(p1, batch.get(0));
        assertSame(t1, batch.get(1));
        assertSame(p2, batch.get(2));
        assertSame(t2, batch.get(3));
        assertEquals(0, replayer.deferredCount());
    }

    // ── 7. remainingPromptCount decreases with each batch ────────────────────

    @Test
    void remainingPromptCount_tracksCorrectly() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        EntryData.Prompt p4 = new EntryData.Prompt("q4");
        EntryData.Text t4 = new EntryData.Text("a4");
        replayer.loadAndSplit(Arrays.asList(p1, t1, p2, t2, p3, t3, p4, t4), 1);
        // deferred has 3 prompts: p1, p2, p3

        assertEquals(3, replayer.remainingPromptCount());

        replayer.loadNextBatch(1); // pops [p3, t3]
        assertEquals(2, replayer.remainingPromptCount());

        replayer.loadNextBatch(1); // pops [p2, t2]
        assertEquals(1, replayer.remainingPromptCount());
    }

    // ── 8. totalPromptCount spans both deferred and recent ───────────────────

    @Test
    void totalPromptCount_includesBothRecentAndDeferred() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        replayer.loadAndSplit(Arrays.asList(p1, t1, p2, t2, p3, t3), 1);
        // 3 total prompts: 2 deferred + 1 recent

        assertEquals(3, replayer.totalPromptCount());
    }

    // ── 9. totalLoadedCount includes every entry type ────────────────────────

    @Test
    void totalLoadedCount_includesAll() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.ToolCall tc1 = new EntryData.ToolCall("read_file");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        replayer.loadAndSplit(Arrays.asList(p1, t1, tc1, p2, t2), 1);

        assertEquals(5, replayer.totalLoadedCount());
    }

    // ── 10. deferredCount decreases after a batch load ───────────────────────

    @Test
    void deferredCount_decreasesAfterBatch() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        replayer.loadAndSplit(Arrays.asList(p1, t1, p2, t2, p3, t3), 1);
        // deferred=[p1,t1,p2,t2] → 4 entries

        assertEquals(4, replayer.deferredCount());

        replayer.loadNextBatch(1); // pops [p2, t2]
        assertEquals(2, replayer.deferredCount());
    }

    // ── 11. No prompts → everything is recent ────────────────────────────────

    @Test
    void loadAndSplit_entriesWithoutPrompts_allRecent() {
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.ToolCall tc1 = new EntryData.ToolCall("read_file");
        EntryData.Text t2 = new EntryData.Text("a2");
        List<EntryData> entries = Arrays.asList(t1, tc1, t2);

        replayer.loadAndSplit(entries, 3);

        assertEquals(3, replayer.recentEntries().size());
        assertTrue(replayer.deferredEntries().isEmpty());
        assertEquals(0, replayer.totalPromptCount());
    }

    // ── 12. recentTurns exactly matches total turns → no deferred ────────────

    @Test
    void loadAndSplit_exactlyMatchingTurns_noDeferred() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        List<EntryData> entries = Arrays.asList(p1, t1, p2, t2, p3, t3);

        replayer.loadAndSplit(entries, 3);

        assertEquals(6, replayer.recentEntries().size());
        assertTrue(replayer.deferredEntries().isEmpty());
        assertEquals(0, replayer.deferredCount());
    }

    // ── 13. recentTurns larger than total → all recent ───────────────────────

    @Test
    void recentTurnsLargerThanTotal_allRecent() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        List<EntryData> entries = Arrays.asList(p1, t1, p2, t2);

        replayer.loadAndSplit(entries, 10);

        assertEquals(4, replayer.recentEntries().size());
        assertTrue(replayer.deferredEntries().isEmpty());
    }

    // ── 14. Repeated batch loads eventually drain deferred completely ─────────

    @Test
    void multipleBatchLoads_eventuallyDrainsDeferred() {
        EntryData.Prompt p1 = new EntryData.Prompt("q1");
        EntryData.Text t1 = new EntryData.Text("a1");
        EntryData.Prompt p2 = new EntryData.Prompt("q2");
        EntryData.Text t2 = new EntryData.Text("a2");
        EntryData.Prompt p3 = new EntryData.Prompt("q3");
        EntryData.Text t3 = new EntryData.Text("a3");
        EntryData.Prompt p4 = new EntryData.Prompt("q4");
        EntryData.Text t4 = new EntryData.Text("a4");
        replayer.loadAndSplit(Arrays.asList(p1, t1, p2, t2, p3, t3, p4, t4), 1);
        // recent=[p4,t4], deferred=[p1,t1,p2,t2,p3,t3]

        assertFalse(replayer.deferredEntries().isEmpty());

        replayer.loadNextBatch(1); // pops [p3, t3]
        replayer.loadNextBatch(1); // pops [p2, t2]
        replayer.loadNextBatch(1); // pops [p1, t1]

        assertTrue(replayer.deferredEntries().isEmpty());
        assertEquals(0, replayer.deferredCount());
        assertEquals(0, replayer.remainingPromptCount());

        // Additional call on empty deferred returns empty list
        List<EntryData> empty = replayer.loadNextBatch(1);
        assertTrue(empty.isEmpty());
    }
}
