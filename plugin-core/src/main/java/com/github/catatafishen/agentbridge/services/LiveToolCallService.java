package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level service that maintains a bounded, in-memory list of recent MCP
 * tool calls with raw input/output. Populated by {@code McpProtocolHandler}
 * immediately before and after each tool call.
 * <p>
 * The UI (ToolCallListPanel) subscribes via {@link #addChangeListener} to
 * receive notifications when entries are added or completed.
 * <p>
 * Thread-safe: entries are stored in a synchronized list, listeners fire on
 * the calling thread (expected to be the MCP handler thread). UI listeners
 * must marshal to EDT themselves.
 */
@Service(Service.Level.PROJECT)
public final class LiveToolCallService {

    /**
     * Maximum entries kept in memory. Oldest entries are evicted when exceeded.
     */
    private static final int MAX_ENTRIES = 200;

    private final List<LiveToolCallEntry> entries = new ArrayList<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Records a tool call starting. Returns the index for later completion via {@link #complete}.
     */
    public synchronized int recordStart(@NotNull String toolName, @NotNull String inputJson,
                                        @org.jetbrains.annotations.Nullable String category) {
        LiveToolCallEntry entry = LiveToolCallEntry.started(toolName, inputJson, category);
        entries.add(entry);
        evictIfNeeded();
        int idx = entries.size() - 1;
        fireChanged();
        return idx;
    }

    /**
     * Marks the entry at {@code index} as completed with the given output.
     * If the index is out of range (e.g. after eviction), this is a no-op.
     */
    public synchronized void complete(int index, @NotNull String output,
                                      long durationMs, boolean success) {
        if (index < 0 || index >= entries.size()) return;
        entries.set(index, entries.get(index).completed(output, durationMs, success));
        fireChanged();
    }

    /**
     * Returns a snapshot of all current entries (newest last).
     */
    public synchronized @NotNull List<LiveToolCallEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * Returns the number of entries.
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Clears all entries.
     */
    public synchronized void clear() {
        entries.clear();
        fireChanged();
    }

    public void addChangeListener(@NotNull ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(@NotNull ChangeListener listener) {
        listeners.remove(listener);
    }

    private void evictIfNeeded() {
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (ChangeListener l : listeners) {
            l.stateChanged(event);
        }
    }

    public static LiveToolCallService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, LiveToolCallService.class);
    }
}
