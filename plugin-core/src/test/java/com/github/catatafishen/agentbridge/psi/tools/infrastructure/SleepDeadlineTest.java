package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleepDeadlineTest {

    private static final long START = 1_000_000L;

    @Test
    void deadlineStartsAtRequestedLength() {
        SleepDeadline deadline = new SleepDeadline(START, 60, 170);

        assertEquals(START + 60_000L, deadline.deadlineMs());
        assertEquals(60_000L, deadline.remainingMs(START));
    }

    @Test
    void remainingCountsDownAndNeverGoesNegative() {
        SleepDeadline deadline = new SleepDeadline(START, 10, 170);

        assertEquals(4_000L, deadline.remainingMs(START + 6_000L));
        assertEquals(0L, deadline.remainingMs(START + 10_000L));
        assertEquals(0L, deadline.remainingMs(START + 99_000L));
    }

    @Test
    void extendMovesTheDeadlineLater() {
        SleepDeadline deadline = new SleepDeadline(START, 60, 170);

        assertEquals(START + 70_000L, deadline.extend(10));
        assertEquals(START + 100_000L, deadline.extend(30));
        assertEquals(START + 100_000L, deadline.deadlineMs());
    }

    @Test
    void extendIsCappedAtTheMaximumTotalSleep() {
        SleepDeadline deadline = new SleepDeadline(START, 160, 170);

        assertEquals(START + 170_000L, deadline.extend(30));
        assertEquals(START + 170_000L, deadline.extend(10), "already at the cap: nothing more to give");
        assertEquals(deadline.maxDeadlineMs(), deadline.deadlineMs());
    }

    @Test
    void initialLengthAboveTheCapIsClamped() {
        SleepDeadline deadline = new SleepDeadline(START, 500, 170);

        assertEquals(START + 170_000L, deadline.deadlineMs());
    }

    @Test
    void maximumIsMeasuredFromTheStartOfTheSleep() {
        SleepDeadline deadline = new SleepDeadline(START, 5, 170);

        assertEquals(START + 170_000L, deadline.maxDeadlineMs());
    }
}
