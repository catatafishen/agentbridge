package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Project-level service that controls whether incoming MCP tool calls are deferred
 * (paused) until the user explicitly resumes execution.
 *
 * <p>Pause/resume is purely a deferral mechanism — the tool call is already accepted from
 * the agent's point of view, it just waits here before being dispatched to
 * {@link com.github.catatafishen.agentbridge.psi.PsiBridgeService}. The agent's transport sees no error; it simply receives the
 * result later than usual.
 *
 * <p>Threading: {@link #awaitResumeIfPaused} is designed to be called on the MCP worker
 * thread that would otherwise execute the tool call immediately. It blocks using a
 * {@link Condition} and wakes up as soon as {@link #setPaused(boolean)} is called with
 * {@code false}.
 */
@Service(Service.Level.PROJECT)
public final class McpPauseService {

    public interface PauseListener {
        void onPauseStateChanged(boolean paused);
    }

    private volatile boolean paused = false;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition resumed = lock.newCondition();
    private final List<PauseListener> listeners = new CopyOnWriteArrayList<>();

    public static McpPauseService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, McpPauseService.class);
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Sets the pause state. If {@code false} is passed, all threads blocked in
     * {@link #awaitResumeIfPaused} are unblocked.
     */
    public void setPaused(boolean paused) {
        lock.lock();
        try {
            this.paused = paused;
            if (!paused) {
                resumed.signalAll();
            }
        } finally {
            lock.unlock();
        }
        for (PauseListener listener : listeners) {
            listener.onPauseStateChanged(paused);
        }
    }

    /**
     * Blocks the calling thread while the service is in the paused state. Returns
     * immediately if not paused. Restores the interrupt flag if interrupted while waiting.
     */
    public void awaitResumeIfPaused() {
        if (!paused) return;
        lock.lock();
        try {
            while (paused) {
                resumed.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    public void addListener(@NotNull PauseListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull PauseListener listener) {
        listeners.remove(listener);
    }
}
