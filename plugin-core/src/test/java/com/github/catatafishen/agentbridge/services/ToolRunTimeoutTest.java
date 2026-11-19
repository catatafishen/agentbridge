package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolRunTimeout}: time a tool call spends waiting for the user (an open or
 * queued approval card) must not count towards the "tool is still running" timeout.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ToolRunTimeoutTest {

    private static final long WINDOW = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long POLL = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long SCHEDULING_SLACK = TimeUnit.MILLISECONDS.toNanos(50);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("returns the result of a call that finishes within the window")
    void returnsResultWithinWindow() throws Exception {
        CompletableFuture<String> future = CompletableFuture.completedFuture("done");

        assertEquals("done", ToolRunTimeout.await(future, WINDOW, () -> false, POLL));
    }

    @Test
    @DisplayName("times out a call that runs longer than the window")
    void timesOutLongRunningCall() {
        CompletableFuture<String> future = new CompletableFuture<>();

        assertThrows(TimeoutException.class, () -> ToolRunTimeout.await(future, WINDOW, () -> false, POLL));
    }

    @Test
    @DisplayName("waiting for the user longer than the window does not time out")
    void waitingForUserDoesNotCount() throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicBoolean waitingForUser = new AtomicBoolean(true);
        // The user answers after three windows; the tool then finishes immediately.
        scheduler.schedule(() -> {
            waitingForUser.set(false);
            future.complete("approved");
        }, TimeUnit.NANOSECONDS.toMillis(WINDOW * 3), TimeUnit.MILLISECONDS);

        assertEquals("approved", ToolRunTimeout.await(future, WINDOW, waitingForUser::get, POLL));
    }

    @Test
    @DisplayName("the window restarts once the user has answered")
    void windowRestartsAfterApproval() {
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicBoolean waitingForUser = new AtomicBoolean(true);
        scheduler.schedule(() -> waitingForUser.set(false),
            TimeUnit.NANOSECONDS.toMillis(WINDOW * 2), TimeUnit.MILLISECONDS);

        long start = System.nanoTime();
        assertThrows(TimeoutException.class, () -> ToolRunTimeout.await(future, WINDOW, waitingForUser::get, POLL));
        long elapsed = System.nanoTime() - start;

        // Two windows waiting for the user, then a full run window before timing out. The last
        // restart happens at the last poll before the flag flips, so allow for poll interval and
        // timer granularity (about 15 ms on Windows). Without the pause the call would time out after
        // one window (150 ms).
        assertTrue(elapsed >= WINDOW * 3 - SCHEDULING_SLACK, "timed out after " + elapsed + "ns");
    }

    @Test
    @DisplayName("a failed call surfaces its exception")
    void failedCallSurfacesException() {
        CompletableFuture<String> future = CompletableFuture.failedFuture(new IllegalStateException("boom"));

        ExecutionException e = assertThrows(ExecutionException.class,
            () -> ToolRunTimeout.await(future, WINDOW, () -> false, POLL));
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }
}
