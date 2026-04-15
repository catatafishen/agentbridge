package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks whether memory mining is currently active so the status bar widget
 * can show a visible indicator. Thread-safe — mining runs on pooled threads.
 */
@Service(Service.Level.PROJECT)
public class MiningTracker implements Disposable {

    public static final Topic<Listener> TOPIC =
        Topic.create("AgentBridge.MemoryMining", Listener.class);

    private final Project project;
    private final AtomicReference<MiningState> state = new AtomicReference<>(MiningState.IDLE);

    public MiningTracker(@NotNull Project project) {
        this.project = project;
    }

    /** Package-private no-arg constructor for test doubles that override {@link #fireChanged}. */
    MiningTracker() {
        this.project = null;
    }

    public static MiningTracker getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, MiningTracker.class);
    }

    /**
     * Signal that per-turn mining has started.
     */
    public void startTurnMining() {
        if (state.compareAndSet(MiningState.IDLE, MiningState.MINING_TURN)) {
            fireChanged(MiningState.MINING_TURN, null);
        }
    }

    /**
     * Signal that history backfill has started.
     */
    public void startBackfill() {
        state.set(MiningState.BACKFILLING);
        fireChanged(MiningState.BACKFILLING, null);
    }

    /**
     * Update progress text while mining is active (e.g., "session 3 of 15").
     */
    public void reportProgress(@NotNull String progressText) {
        MiningState current = state.get();
        if (current != MiningState.IDLE) {
            fireChanged(current, progressText);
        }
    }

    /**
     * Signal that mining has finished (either turn mining or backfill).
     */
    public void stop() {
        MiningState previous = state.getAndSet(MiningState.IDLE);
        if (previous != MiningState.IDLE) {
            fireChanged(MiningState.IDLE, null);
        }
    }

    public @NotNull MiningState getState() {
        return state.get();
    }

    /**
     * Subscribe to mining state changes. The connection is disposed when the
     * parent disposable is disposed.
     */
    public MessageBusConnection subscribe(@NotNull Disposable parent, @NotNull Listener listener) {
        MessageBusConnection conn = project.getMessageBus().connect(parent);
        conn.subscribe(TOPIC, listener);
        return conn;
    }

    void fireChanged(@NotNull MiningState newState, @Nullable String progressText) {
        project.getMessageBus().syncPublisher(TOPIC).miningStateChanged(newState, progressText);
    }

    @Override
    public void dispose() {
        state.set(MiningState.IDLE);
    }

    public enum MiningState {
        IDLE,
        MINING_TURN,
        BACKFILLING
    }

    @FunctionalInterface
    public interface Listener {
        void miningStateChanged(@NotNull MiningState state, @Nullable String progressText);
    }
}
