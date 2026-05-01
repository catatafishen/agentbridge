package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CodeChangeTracker} — counter logic, listener notification, and countLines.
 * Does not test {@code diffLines} as it requires IntelliJ's {@code Diff} class.
 *
 * <p><b>Isolation note:</b> {@link CodeChangeTracker} holds JVM-wide static state. Other tests
 * (e.g. file tool tests) call {@code recordChange} via PSI listeners and can leak counts into
 * this class when sharing a JVM fork. We mitigate this by:
 * <ul>
 *   <li>Resetting all counters in {@code @BeforeEach}.</li>
 *   <li>Serialising execution against the shared {@code "code-change-tracker"} resource lock
 *       so no other tests holding the same lock run concurrently.</li>
 *   <li>Using delta-based assertions for tests that observe absolute counter values.</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "code-change-tracker", mode = ResourceAccessMode.READ_WRITE)
class CodeChangeTrackerTest {

    @BeforeEach
    void resetBefore() {
        CodeChangeTracker.getAndClear();
        CodeChangeTracker.clearSession();
    }

    @AfterEach
    void resetCounters() {
        resetBefore();
    }

    // ── countLines ──────────────────────────────────────────

    @Test
    void countLines_nullReturnsZero() {
        assertEquals(0, CodeChangeTracker.countLines(null));
    }

    @Test
    void countLines_emptyReturnsZero() {
        assertEquals(0, CodeChangeTracker.countLines(""));
    }

    @Test
    void countLines_singleLineNoNewline() {
        assertEquals(1, CodeChangeTracker.countLines("hello"));
    }

    @Test
    void countLines_trailingNewlineCountsExtraLine() {
        assertEquals(2, CodeChangeTracker.countLines("hello\n"));
    }

    @Test
    void countLines_multipleLines() {
        assertEquals(3, CodeChangeTracker.countLines("a\nb\nc"));
    }

    @Test
    void countLines_onlyNewlines() {
        assertEquals(4, CodeChangeTracker.countLines("\n\n\n"));
    }

    // ── recordChange + get ──────────────────────────────────

    @Test
    void recordChange_accumulatesAdded() {
        CodeChangeTracker.recordChange(5, 0);
        CodeChangeTracker.recordChange(3, 0);

        int[] counts = CodeChangeTracker.get();
        assertEquals(8, counts[0], "added");
        assertEquals(0, counts[1], "removed");
    }

    @Test
    void recordChange_accumulatesRemoved() {
        CodeChangeTracker.recordChange(0, 7);
        int[] counts = CodeChangeTracker.get();
        assertEquals(0, counts[0]);
        assertEquals(7, counts[1]);
    }

    @Test
    void recordChange_ignoresNegativeValues() {
        CodeChangeTracker.recordChange(-5, -3);
        int[] counts = CodeChangeTracker.get();
        assertEquals(0, counts[0]);
        assertEquals(0, counts[1]);
    }

    // ── getAndClear ─────────────────────────────────────────

    @Test
    void getAndClear_returnsAndResetsPerTurn() {
        CodeChangeTracker.recordChange(10, 4);

        int[] first = CodeChangeTracker.getAndClear();
        assertEquals(10, first[0]);
        assertEquals(4, first[1]);

        int[] second = CodeChangeTracker.get();
        assertEquals(0, second[0], "Should be cleared after getAndClear");
        assertEquals(0, second[1]);
    }

    // ── session totals ──────────────────────────────────────

    @Test
    void sessionTotals_accumulateAcrossTurns() {
        CodeChangeTracker.recordChange(10, 5);
        CodeChangeTracker.getAndClear(); // simulate turn end

        CodeChangeTracker.recordChange(7, 3);

        int[] session = CodeChangeTracker.getSessionTotal();
        assertEquals(17, session[0], "Session should accumulate across turns");
        assertEquals(8, session[1]);

        // Per-turn should only show the second turn
        int[] perTurn = CodeChangeTracker.get();
        assertEquals(7, perTurn[0]);
        assertEquals(3, perTurn[1]);
    }

    @Test
    void clearSession_resetsBothCounters() {
        CodeChangeTracker.recordChange(5, 3);
        CodeChangeTracker.clearSession();

        int[] session = CodeChangeTracker.getSessionTotal();
        assertEquals(0, session[0]);
        assertEquals(0, session[1]);
    }

    // ── listeners ───────────────────────────────────────────

    @Test
    void listener_notifiedOnRecordChange() {
        AtomicInteger callCount = new AtomicInteger();
        Runnable listener = callCount::incrementAndGet;

        CodeChangeTracker.addListener(listener);
        try {
            CodeChangeTracker.recordChange(1, 0);
            CodeChangeTracker.recordChange(0, 1);
            assertEquals(2, callCount.get(), "Listener should be called for each recordChange");
        } finally {
            CodeChangeTracker.removeListener(listener);
        }
    }

    @Test
    void removeListener_stopsNotifications() {
        AtomicInteger callCount = new AtomicInteger();
        Runnable listener = callCount::incrementAndGet;

        CodeChangeTracker.addListener(listener);
        CodeChangeTracker.recordChange(1, 0);
        CodeChangeTracker.removeListener(listener);
        CodeChangeTracker.recordChange(1, 0);

        assertEquals(1, callCount.get(), "Should not be called after removal");
    }

    // ── clear ───────────────────────────────────────────────

    @Test
    void clear_resetsPerTurnWithoutReading() {
        CodeChangeTracker.recordChange(10, 5);
        CodeChangeTracker.clear();

        int[] counts = CodeChangeTracker.get();
        assertEquals(0, counts[0]);
        assertEquals(0, counts[1]);
    }
}
