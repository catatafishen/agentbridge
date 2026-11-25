package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The conversation is saved once the agent has sent nothing for the delay, so what it sends after its turn
 * is not left unsaved.
 */
class QuietSaveSchedulerTest {

    private static final long DELAY = 2_000;

    private long now;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private int saves;
    private QuietSaveScheduler scheduler;

    @BeforeEach
    void setUp() {
        now = 10_000;
        scheduler = new QuietSaveScheduler(DELAY, () -> now, (task, delay) -> {
            scheduled.add(task);
            delays.add(delay);
            return kotlin.Unit.INSTANCE;
        }, () -> saves++);
    }

    /** Runs the earliest scheduled task, as the timer would once its delay has passed. */
    private void runNext() {
        scheduled.removeFirst().run();
    }

    @Test
    void savesOnceTheAgentHasBeenQuietForTheDelay() {
        scheduler.onActivity();
        now += DELAY;

        runNext();

        assertEquals(1, saves);
        assertEquals(List.of(DELAY), delays);
    }

    @Test
    void aStreamOfChunksSchedulesOneCheck() {
        for (int i = 0; i < 100; i++) {
            scheduler.onActivity();
            now += 5;
        }

        assertEquals(1, scheduled.size(), "every chunk must not reschedule a timer");
    }

    @Test
    void activityDuringTheWaitPostponesTheSave() {
        scheduler.onActivity();
        now += 1_500;
        scheduler.onActivity();
        now += 500;

        runNext();

        assertEquals(0, saves, "the agent sent something 500 ms ago");
        assertEquals(List.of(DELAY, 1_500L), delays, "the check waits for the rest of the quiet period");
        now += 1_500;
        runNext();
        assertEquals(1, saves);
    }

    @Test
    void activityAfterASaveSchedulesTheNextOne() {
        scheduler.onActivity();
        now += DELAY;
        runNext();

        scheduler.onActivity();
        now += DELAY;
        runNext();

        assertEquals(2, saves);
    }

    @Test
    void nothingIsSavedWithoutActivity() {
        assertEquals(0, scheduled.size());
        assertEquals(0, saves);
    }
}
