package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WriteBatchCoordinator} — the mechanism that defers auto-highlight
 * collection until all pending write tool calls in a batch have completed.
 *
 * <h3>Why this matters</h3>
 * When an agent sends multiple edits in one turn (e.g., "add method" then "call method"),
 * they queue on the write semaphore. Without draining, highlights collected after the first
 * edit would show false positives like "unused method" — because the second edit (which uses
 * the method) hasn't executed yet. The coordinator fixes this by letting earlier writes wait
 * for later ones before collecting highlights.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class WriteBatchCoordinatorTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Basic counter semantics
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("register/unregister increments and decrements the pending count")
    void registerAndUnregister() {
        var coord = new WriteBatchCoordinator(new Semaphore(1));
        assertEquals(0, coord.getPendingCount());

        coord.registerWrite();
        assertEquals(1, coord.getPendingCount());
        assertTrue(coord.hasPendingWrites());

        coord.unregisterWrite();
        assertEquals(0, coord.getPendingCount());
        assertFalse(coord.hasPendingWrites());
    }

    @Test
    @DisplayName("multiple registers stack correctly")
    void multipleRegisters() {
        var coord = new WriteBatchCoordinator(new Semaphore(1));

        coord.registerWrite();
        coord.registerWrite();
        coord.registerWrite();
        assertEquals(3, coord.getPendingCount());

        coord.unregisterWrite();
        assertEquals(2, coord.getPendingCount());
        assertTrue(coord.hasPendingWrites());

        coord.unregisterWrite();
        coord.unregisterWrite();
        assertEquals(0, coord.getPendingCount());
        assertFalse(coord.hasPendingWrites());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Single write — no draining needed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("single write: hasPendingWrites returns false after unregister")
    void singleWriteNoDrain() {
        var semaphore = new Semaphore(1);
        var coord = new WriteBatchCoordinator(semaphore);

        coord.registerWrite();
        // Simulate: acquire semaphore, execute write, unregister
        coord.unregisterWrite();

        assertFalse(coord.hasPendingWrites(),
            "After a single write completes, no pending writes should remain");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Two concurrent writes — first drains before highlights
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two concurrent writes: first write waits for second to complete before proceeding")
    void twoConcurrentWritesDrain() throws Exception {
        var semaphore = new Semaphore(1);
        var coord = new WriteBatchCoordinator(semaphore);

        // Latches to synchronize the test threads
        var threadAHoldsSemaphore = new CountDownLatch(1);
        var threadBRegistered = new CountDownLatch(1);
        var threadADrained = new CountDownLatch(1);

        // Track the order of events
        var threadBWriteCompleted = new AtomicBoolean(false);
        var threadACheckedAfterDrain = new AtomicBoolean(false);

        // Thread A: first write
        Thread threadA = new Thread(() -> {
            try {
                coord.registerWrite();
                semaphore.acquire();
                threadAHoldsSemaphore.countDown();

                // Wait for Thread B to register (simulating a queued write)
                threadBRegistered.await();

                // Simulate: write completes
                coord.unregisterWrite();

                // At this point, hasPendingWrites should be true (Thread B is pending)
                assertTrue(coord.hasPendingWrites(),
                    "Thread B should be pending after Thread A's write completes");

                // Drain pending writes (releases semaphore internally)
                coord.drainPendingWrites(5000);
                threadADrained.countDown();

                // After drain, Thread B's write should have completed
                threadACheckedAfterDrain.set(threadBWriteCompleted.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "write-A");

        // Thread B: second write
        Thread threadB = new Thread(() -> {
            try {
                // Wait for Thread A to hold the semaphore first
                threadAHoldsSemaphore.await();

                coord.registerWrite();
                threadBRegistered.countDown();

                // This blocks until Thread A releases the semaphore (via drain)
                semaphore.acquire();

                // Simulate: write completes
                threadBWriteCompleted.set(true);
                coord.unregisterWrite();

                semaphore.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "write-B");

        threadA.start();
        threadB.start();

        threadA.join(8000);
        threadB.join(8000);

        assertTrue(threadACheckedAfterDrain.get(),
            "Thread A should see Thread B's write completed after draining");
        assertEquals(0, coord.getPendingCount(),
            "All writes should be complete");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Three concurrent writes — all drain before highlights
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("three concurrent writes: all complete before any starts collecting highlights")
    void threeConcurrentWritesDrain() throws Exception {
        var semaphore = new Semaphore(1);
        var coord = new WriteBatchCoordinator(semaphore);

        var allWritesComplete = new AtomicInteger(0);
        var writeOrder = new java.util.concurrent.CopyOnWriteArrayList<String>();

        // All three threads register, execute sequentially via semaphore, drain
        int threadCount = 3;
        var allRegistered = new CountDownLatch(threadCount);
        var startSignal = new CountDownLatch(1);
        var allDone = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    coord.registerWrite();
                    allRegistered.countDown();
                    startSignal.await();

                    semaphore.acquire();
                    // Simulate write execution
                    writeOrder.add("write-" + threadId);
                    pauseBriefly();
                    coord.unregisterWrite();

                    if (coord.hasPendingWrites()) {
                        coord.drainPendingWrites(5000);
                    }

                    // By the time we get here, all writes should be done
                    allWritesComplete.incrementAndGet();
                    semaphore.release();
                    allDone.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "write-" + i).start();
        }

        // Wait for all threads to register, then start them
        allRegistered.await();
        startSignal.countDown();

        assertTrue(allDone.await(8, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(3, allWritesComplete.get(), "All 3 writes should have completed");
        assertEquals(3, writeOrder.size(), "All 3 writes should have executed");
        assertEquals(0, coord.getPendingCount(), "No pending writes should remain");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Drain timeout
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("drain times out if pending writes never complete")
    void drainTimesOut() throws Exception {
        var semaphore = new Semaphore(1);
        var coord = new WriteBatchCoordinator(semaphore);

        // Register a write but never unregister it — simulates a stuck write
        coord.registerWrite();
        // Acquire semaphore so drainPendingWrites can release it
        semaphore.acquire();

        long start = System.currentTimeMillis();
        coord.drainPendingWrites(200); // short timeout
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 180, "Should have waited at least near the timeout");
        assertTrue(elapsed < 2000, "Should not wait much longer than timeout");
        assertEquals(1, coord.getPendingCount(), "Pending count unchanged after timeout");

        // Clean up
        coord.unregisterWrite();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // drainPendingWrites releases the semaphore
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("drainPendingWrites releases the semaphore so pending writes can proceed")
    void drainReleasesSemaphore() throws Exception {
        var semaphore = new Semaphore(1);
        var coord = new WriteBatchCoordinator(semaphore);
        var semaphoreAcquiredByOther = new AtomicBoolean(false);

        // Hold the semaphore
        semaphore.acquire();

        // Register a "pending" write
        coord.registerWrite();

        // Start a thread that tries to acquire the semaphore
        var acquired = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            try {
                semaphore.acquire();
                semaphoreAcquiredByOther.set(true);
                coord.unregisterWrite();
                acquired.countDown();
                semaphore.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pending-writer");
        other.start();

        // Wait for the thread to block on the semaphore
        waitUntil(semaphore::hasQueuedThreads, 1_000);
        assertFalse(semaphoreAcquiredByOther.get(), "Should be blocked on semaphore");

        // Drain releases the semaphore, allowing the other thread to proceed
        coord.drainPendingWrites(5000);

        assertTrue(acquired.await(3, TimeUnit.SECONDS), "Other thread should have acquired semaphore");
        assertTrue(semaphoreAcquiredByOther.get(), "Other thread should have run");
        assertEquals(0, coord.getPendingCount());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Thread interruption during drain
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("drain respects thread interruption")
    void drainRespectsInterruption() throws Exception {
        var semaphore = new Semaphore(1);
        var coord = new WriteBatchCoordinator(semaphore);

        coord.registerWrite();
        semaphore.acquire();

        var drainStarted = new CountDownLatch(1);
        var wasInterrupted = new AtomicBoolean(false);

        Thread drainer = new Thread(() -> {
            try {
                drainStarted.countDown();
                coord.drainPendingWrites(30_000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }, "drainer");
        drainer.start();

        drainStarted.await();
        waitUntil(() -> drainer.getState() == Thread.State.WAITING
            || drainer.getState() == Thread.State.TIMED_WAITING, 1_000);
        drainer.interrupt();
        drainer.join(3000);

        assertTrue(wasInterrupted.get(), "Thread should have been interrupted during drain");

        // Clean up
        coord.unregisterWrite();
    }

    private static void pauseBriefly() throws InterruptedException {
        new CountDownLatch(1).await(50, TimeUnit.MILLISECONDS);
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Coordinator tracks only write ops (sanity)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reads do not register with the coordinator")
    void readToolsDoNotRegister() {
        // This test documents that the coordinator is only used for write tools.
        // In PsiBridgeService.callTool(), isWriteOp is only true for isWriteToolName() tools.
        // Read tools skip registerWrite() entirely — so the coordinator stays at 0.
        var coord = new WriteBatchCoordinator(new Semaphore(1));
        assertEquals(0, coord.getPendingCount());
        assertFalse(coord.hasPendingWrites());
        // No registerWrite() call — simulating a read tool
        assertEquals(0, coord.getPendingCount());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Realistic scenario: batch of two writes, verify drain ordering
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("realistic: two edits where second resolves first's false positive")
    void realisticBatchScenario() throws Exception {
        var semaphore = new Semaphore(1);
        var coord = new WriteBatchCoordinator(semaphore);

        // Simulates: Edit 1 adds method, Edit 2 uses method
        // After Edit 1, "unused method" would be a false positive
        // After Edit 2, the method is used — no false positive

        var edit1Done = new CountDownLatch(1);
        var edit2Done = new CountDownLatch(1);
        var highlightsCollected = new AtomicReference<String>();

        // Register both edits before either starts (simulating concurrent arrival)
        coord.registerWrite();
        coord.registerWrite();

        // Edit 1
        Thread edit1 = new Thread(() -> {
            try {
                semaphore.acquire();
                // Simulate: write "add method"
                coord.unregisterWrite();
                edit1Done.countDown();

                // Check for pending writes
                if (coord.hasPendingWrites()) {
                    // Release semaphore, let Edit 2 proceed
                    coord.drainPendingWrites(5000);
                }

                // Collect highlights — by now Edit 2 has also written
                highlightsCollected.set("highlights-after-all-writes");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "edit-1");

        // Edit 2
        Thread edit2 = new Thread(() -> {
            try {
                edit1Done.await(); // wait for edit1 to release via drain
                semaphore.acquire();
                // Simulate: write "call method"
                coord.unregisterWrite();
                semaphore.release();
                edit2Done.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "edit-2");

        edit1.start();
        edit2.start();

        edit1.join(8000);
        edit2.join(8000);

        assertEquals("highlights-after-all-writes", highlightsCollected.get(),
            "Highlights should be collected after all writes complete");
        assertEquals(0, coord.getPendingCount());
    }
}
