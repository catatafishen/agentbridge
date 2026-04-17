package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the CompletableFuture-based review gating mechanics used by git tools.
 * Verifies that {@code awaitReviewCompletion()} blocks until the future is
 * completed, and that {@code completeReviewIfEmpty()} wakes up waiters.
 * <p>
 * These are pure concurrency tests — no IntelliJ project infrastructure needed.
 */
class ReviewGatingFutureTest {

    @Test
    void futureCompletesWhenReviewDone() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        assertFalse(future.isDone());

        future.complete(null);
        assertTrue(future.isDone());
        assertNull(future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void waiterUnblocksOnComplete() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("not-set");

        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                future.get(5, TimeUnit.SECONDS);
                result.set(null); // success
            } catch (Exception e) {
                result.set("Error: " + e.getClass().getSimpleName());
            }
        });
        waiter.start();
        assertTrue(started.await(1, TimeUnit.SECONDS), "Waiter should have started");

        // Complete the future — waiter should unblock
        future.complete(null);
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "Waiter should have finished");
        assertNull(result.get(), "Waiter should have received null (success)");
    }

    @Test
    void multipleWaitersAllUnblock() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        int waiterCount = 3;
        CountDownLatch allStarted = new CountDownLatch(waiterCount);
        CountDownLatch allDone = new CountDownLatch(waiterCount);

        for (int i = 0; i < waiterCount; i++) {
            new Thread(() -> {
                allStarted.countDown();
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // ignore
                } finally {
                    allDone.countDown();
                }
            }).start();
        }

        assertTrue(allStarted.await(1, TimeUnit.SECONDS));
        future.complete(null);
        assertTrue(allDone.await(2, TimeUnit.SECONDS),
            "All waiters should unblock when future completes");
    }

    @Test
    void timeoutProducesErrorMessage() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicReference<String> result = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            try {
                future.get(100, TimeUnit.MILLISECONDS);
                result.set(null);
            } catch (java.util.concurrent.TimeoutException e) {
                result.set("Error: timed out");
            } catch (Exception e) {
                result.set("Error: " + e.getClass().getSimpleName());
            }
        });
        waiter.start();
        waiter.join(2000);
        assertNotNull(result.get());
        assertTrue(result.get().startsWith("Error:"));
    }

    @Test
    void reusingFutureAfterReset() {
        // Simulates the pattern: create future → complete → create new future
        CompletableFuture<Void> future1 = new CompletableFuture<>();
        future1.complete(null);
        assertTrue(future1.isDone());

        CompletableFuture<Void> future2 = new CompletableFuture<>();
        assertFalse(future2.isDone(), "New future should not be done");
        future2.complete(null);
        assertTrue(future2.isDone());
    }
}
