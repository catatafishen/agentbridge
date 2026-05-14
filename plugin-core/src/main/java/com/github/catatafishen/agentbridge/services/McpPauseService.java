package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Project-level service that controls whether incoming MCP tool calls are deferred
 * (paused) until the user explicitly resumes execution.
 *
 * <p>Pause/resume is purely a deferral mechanism — the tool call is already accepted from
 * the agent's point of view; it just waits here before being dispatched. The agent transport
 * sees no error; it simply receives the result later than usual.
 *
 * <p>Three observable states:
 * <ul>
 *   <li>{@link PauseState#RUNNING} — no pause active, tool calls proceed normally
 *   <li>{@link PauseState#PENDING} — user requested pause but no tool call has arrived yet
 *   <li>{@link PauseState#PAUSED}  — at least one tool call is currently blocked waiting
 * </ul>
 *
 * <p>Threading: {@link #awaitResumeIfPaused} is designed to be called on the MCP worker
 * thread. It blocks using a {@link Condition} and wakes up as soon as
 * {@link #setPaused(boolean)} is called with {@code false}.
 */
@Service(Service.Level.PROJECT)
public final class McpPauseService {

    /**
     * The three observable pause states exposed to UI and callers.
     */
    public enum PauseState {
        /** No pause is active — tool calls proceed immediately. */
        RUNNING,
        /** User requested pause; waiting for the agent to make its next tool call. */
        PENDING,
        /** A tool call is currently blocked and waiting for the user to click Resume. */
        PAUSED
    }

    public interface PauseListener {
        void onPauseStateChanged(PauseState state);
    }

    private volatile boolean pauseRequested = false;
    private final AtomicInteger blockedCallCount = new AtomicInteger(0);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition resumed = lock.newCondition();
    private final List<PauseListener> listeners = new CopyOnWriteArrayList<>();

    public static McpPauseService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, McpPauseService.class);
    }

    /** Returns true when a pause has been requested (PENDING or PAUSED state). */
    public boolean isPaused() {
        return pauseRequested;
    }

    /**
     * Returns the current three-way pause state.
     *
     * <ul>
     *   <li>{@link PauseState#RUNNING} — not paused
     *   <li>{@link PauseState#PENDING} — pause requested, but no tool call is blocking yet
     *   <li>{@link PauseState#PAUSED}  — at least one tool call is actively blocked
     * </ul>
     */
    public PauseState getPauseState() {
        if (!pauseRequested) return PauseState.RUNNING;
        return blockedCallCount.get() > 0 ? PauseState.PAUSED : PauseState.PENDING;
    }

    /**
     * Requests or clears a pause. When {@code false} is passed, all threads blocked in
     * {@link #awaitResumeIfPaused} are unblocked immediately.
     */
    public void setPaused(boolean paused) {
        lock.lock();
        try {
            this.pauseRequested = paused;
            if (!paused) {
                resumed.signalAll();
            }
        } finally {
            lock.unlock();
        }
        notifyListeners();
    }

    /**
     * Blocks the calling thread while the service is in the paused state. Returns
     * immediately if not paused. Transitions from {@link PauseState#PENDING} to
     * {@link PauseState#PAUSED} while blocking, and back to {@link PauseState#RUNNING}
     * (or next PENDING cycle) when the pause is cleared.
     *
     * <p>Restores the interrupt flag if interrupted while waiting.
     */
    public void awaitResumeIfPaused() {
        if (!pauseRequested) return;
        blockedCallCount.incrementAndGet();
        notifyListeners();
        lock.lock();
        try {
            while (pauseRequested) {
                resumed.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
            blockedCallCount.decrementAndGet();
            notifyListeners();
        }
    }

    public void addListener(@NotNull PauseListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull PauseListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        PauseState state = getPauseState();
        for (PauseListener listener : listeners) {
            listener.onPauseStateChanged(state);
        }
    }
}
