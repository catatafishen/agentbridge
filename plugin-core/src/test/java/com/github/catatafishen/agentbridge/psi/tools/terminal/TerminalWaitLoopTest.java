package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalWaitLoopTest {

    private static final long POLL = 200;

    /**
     * A fake terminal: returns the scripted texts in order, then repeats the last one. The fake clock advances only
     * when the loop sleeps, so no real time passes.
     */
    private static final class Fake {
        final AtomicLong now = new AtomicLong();
        final Deque<String> texts = new ArrayDeque<>();
        final List<Long> sleeps = new ArrayList<>();
        String last = "";

        Fake(String... scripted) {
            Collections.addAll(texts, scripted);
        }

        String read() {
            if (!texts.isEmpty()) last = texts.poll();
            return last;
        }

        void sleep(long ms) {
            sleeps.add(ms);
            now.addAndGet(ms);
        }
    }

    private static TerminalWaitLoop.Outcome run(Fake f, Pattern pattern, long timeoutMs) {
        return TerminalWaitLoop.run(f::read, pattern, timeoutMs, POLL, f.now::get, f::sleep);
    }

    @Test
    void stopsAsSoonAsThePatternMatchesWithoutSleepingLonger() {
        var f = new Fake("$ make", "$ make", "building", "done\nAB_DONE_x_0");
        var outcome = run(f, Pattern.compile("AB_DONE_x_\\d+"), 60_000);

        assertEquals(TerminalWaitTracker.Reason.MATCHED, outcome.reason());
        assertTrue(outcome.text().contains("AB_DONE_x_0"));
        assertEquals(2, f.sleeps.size(), "slept once per poll that did not match, then stopped");
    }

    @Test
    void timeoutEndsTheWaitWhenNothingMatches() {
        var f = new Fake("$ ");
        var outcome = run(f, Pattern.compile("NEVER"), 1_000);

        assertEquals(TerminalWaitTracker.Reason.TIMEOUT, outcome.reason());
        assertTrue(outcome.waitedMs() >= 1_000);
    }

    @Test
    void withoutPatternOutputThatSettlesEndsAsIdle() {
        var f = new Fake("$ ", "$ out");
        var outcome = run(f, null, 60_000);

        assertEquals(TerminalWaitTracker.Reason.IDLE, outcome.reason());
        assertTrue(outcome.waitedMs() < 60_000);
    }

    @Test
    void nullTextFromTheTerminalIsTreatedAsEmpty() {
        var now = new AtomicLong();
        var outcome = TerminalWaitLoop.run(() -> null, Pattern.compile("x"), 400, POLL, now::get, now::addAndGet);

        assertEquals(TerminalWaitTracker.Reason.TIMEOUT, outcome.reason());
        assertEquals("", outcome.text());
    }

    @Test
    void interruptedSleepStopsTheWaitAndRestoresTheInterruptFlag() {
        var now = new AtomicLong();
        try {
            var outcome = TerminalWaitLoop.run(() -> "x", Pattern.compile("never"), 60_000, POLL, now::get, ms -> {
                throw new InterruptedException();
            });

            assertEquals(TerminalWaitTracker.Reason.INTERRUPTED, outcome.reason());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Clear the flag so it does not leak into other tests on this thread.
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void reportsTheTextThePatternMatched() {
        var f = new Fake("$ make", "out\nAB_DONE_a1b2c3d4_3\n$ ");
        var outcome = run(f, Pattern.compile("AB_DONE_[0-9a-f]+_\\d+"), 60_000);

        assertEquals("AB_DONE_a1b2c3d4_3", outcome.matched());
    }

    @Test
    void noMatchedTextWhenTheWaitEndsWithoutAMatch() {
        var f = new Fake("$ ");
        var outcome = run(f, Pattern.compile("NEVER"), 400);

        assertEquals(TerminalWaitTracker.Reason.TIMEOUT, outcome.reason());
        assertNull(outcome.matched());
    }

    @Test
    void pollsAtTheConfiguredInterval() {
        var f = new Fake("a");
        run(f, Pattern.compile("NEVER"), 600);

        assertTrue(f.sleeps.stream().allMatch(s -> s == POLL));
    }
}
