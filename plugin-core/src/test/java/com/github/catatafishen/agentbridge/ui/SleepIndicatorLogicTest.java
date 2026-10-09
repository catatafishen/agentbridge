package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleepIndicatorLogicTest {

    private static final long START = 1_000_000L;

    @Test
    void trackerEndsTheActiveRequestOnce() {
        SleepRequestTracker tracker = new SleepRequestTracker();
        tracker.begin("a");

        assertTrue(tracker.end("a"));
        assertFalse(tracker.end("a"), "already ended: a duplicate end must be a no-op");
    }

    @Test
    void trackerIgnoresAnEndForAReplacedRequest() {
        SleepRequestTracker tracker = new SleepRequestTracker();
        tracker.begin("old");
        tracker.begin("new");

        assertFalse(tracker.end("old"), "a stale end must not tear down the newer sleep's countdown");
        assertTrue(tracker.end("new"));
    }

    @Test
    void trackerIgnoresAnEndForAnUnknownRequest() {
        SleepRequestTracker tracker = new SleepRequestTracker();

        assertFalse(tracker.end("never-started"));
    }

    @Test
    void trackerForgetsTheRequestWhenCleared() {
        SleepRequestTracker tracker = new SleepRequestTracker();
        tracker.begin("a");

        tracker.clear();

        assertFalse(tracker.end("a"), "the indicator was torn down: a late end must be ignored");
    }

    @Test
    void labelShowsElapsedOutOfTotalSeconds() {
        String label = WaitCountdown.label("Sleeping", START, START + 60_000L, START + 12_400L);

        assertEquals("Sleeping… 12s / 60s", label);
    }

    @Test
    void labelUsesTheGivenPrefix() {
        String label = WaitCountdown.label("Waiting", START, START + 30_000L, START);

        assertEquals("Waiting… 0s / 30s", label);
    }

    @Test
    void labelTotalGrowsWhenTheDeadlineIsExtended() {
        String before = WaitCountdown.label("Sleeping", START, START + 60_000L, START + 5_000L);
        String after = WaitCountdown.label("Sleeping", START, START + 90_000L, START + 5_000L);

        assertEquals("Sleeping… 5s / 60s", before);
        assertEquals("Sleeping… 5s / 90s", after);
    }

    @Test
    void canExtendWhileBelowTheCap() {
        assertTrue(WaitCountdown.canExtend(START + 60_000L, START + 170_000L));
    }

    @Test
    void cannotExtendOnceTheCapIsReached() {
        assertFalse(WaitCountdown.canExtend(START + 170_000L, START + 170_000L));
    }
}
