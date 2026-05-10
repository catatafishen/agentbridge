package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates batches of concurrent write tool calls so that highlight collection
 * is deferred until all pending writes in the current batch have completed.
 *
 * <h3>Problem</h3>
 * When multiple write tool calls are queued (e.g., the agent sends 3 edits in one turn),
 * they execute sequentially via the write semaphore. After each write, the system collects
 * code highlights (warnings/errors) to report back to the agent. If highlight collection
 * happens after each individual write, intermediate highlights may contain false positives —
 * e.g., "unused method" after edit 1 adds a method, even though edit 2 will call it.
 *
 * <h3>Solution</h3>
 * <ol>
 *   <li>Each write tool call <em>registers</em> itself (incrementing a counter) before
 *       acquiring the write semaphore.</li>
 *   <li>After executing its write, it <em>unregisters</em> (decrements the counter) and
 *       checks whether other writes are still pending.</li>
 *   <li>If pending writes exist, it releases the semaphore and waits for all of them to
 *       drain. Only then does it collect highlights — which now reflect the final state
 *       after all writes.</li>
 *   <li>If no pending writes exist, it collects highlights immediately (the common
 *       single-write case, no extra overhead).</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * The counter uses {@link AtomicInteger} for lock-free updates. The drain loop
 * polls with 50 ms sleeps (bounded by a configurable timeout) — this is acceptable
 * because write tool calls are already serialized and the drain is a rare event
 * (only when the agent sends multiple writes in rapid succession).
 */
final class WriteBatchCoordinator {

    private static final Logger LOG = Logger.getInstance(WriteBatchCoordinator.class);
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 60_000L;

    /**
     * Number of write tool calls that are waiting to acquire the semaphore OR have
     * acquired it but have not yet completed their file write.
     * <p>
     * After a write completes and decrements this counter, {@code pendingWrites > 0}
     * means other write calls are still queued — the current call should drain before
     * collecting highlights.
     */
    private final AtomicInteger pendingWrites = new AtomicInteger(0);

    private final Semaphore writeSemaphore;

    WriteBatchCoordinator(Semaphore writeSemaphore) {
        this.writeSemaphore = writeSemaphore;
    }

    /**
     * Register a write tool call before acquiring the write semaphore.
     * Must be paired with exactly one {@link #unregisterWrite()} call — either
     * after the write executes or on any early-exit path (permission denied, error, etc.).
     */
    void registerWrite() {
        pendingWrites.incrementAndGet();
    }

    /**
     * Unregister after the write has executed, or on any early-exit path where no
     * write occurred. Always call exactly once per {@link #registerWrite()}.
     */
    void unregisterWrite() {
        pendingWrites.decrementAndGet();
    }

    /**
     * Returns true if other write calls are still pending (waiting for the semaphore
     * or in-progress). Used after a write completes to decide whether to drain.
     */
    boolean hasPendingWrites() {
        return pendingWrites.get() > 0;
    }

    /**
     * Releases the write semaphore and blocks until all pending write calls have
     * completed their file writes (i.e., {@code pendingWrites} reaches 0).
     * <p>
     * Call this when the current write tool sees other pending writes — it allows
     * them to acquire the semaphore and execute, ensuring highlights collected
     * afterward reflect the final document state.
     *
     * @param timeoutMs maximum time to wait for pending writes to drain
     * @throws InterruptedException if the waiting thread is interrupted
     */
    void drainPendingWrites(long timeoutMs) throws InterruptedException {
        LOG.info("Write batch drain: releasing semaphore, waiting for "
            + pendingWrites.get() + " pending write(s)");
        writeSemaphore.release();

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (pendingWrites.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                LOG.warn("Write batch drain: timed out after " + timeoutMs
                    + "ms with " + pendingWrites.get() + " write(s) still pending");
                break;
            }
            //noinspection BusyWait — polling is acceptable here; drain is rare and short-lived.
            // Antipattern (DESIGN-PRINCIPLES.md): Thread.sleep blocks a thread. Kept because there is no
            // IntelliJ callback for "all pending WriteActions completed" — short polling is the only option.
            Thread.sleep(50);
        }
        LOG.info("Write batch drain: complete");
    }

    /**
     * Convenience overload using the default 60-second timeout.
     */
    void drainPendingWrites() throws InterruptedException {
        drainPendingWrites(DEFAULT_DRAIN_TIMEOUT_MS);
    }

    /**
     * Package-private accessor for testing.
     */
    int getPendingCount() {
        return pendingWrites.get();
    }
}
