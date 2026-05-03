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
 * <p>
 * Completion uses {@link LiveToolCallEntry#callId()} (a monotonic ID) instead
 * of list indices, so eviction of old entries never causes a completion to
 * silently fail.
 */
@Service(Service.Level.PROJECT)
public final class LiveToolCallService {

    private static final int MAX_ENTRIES = 200;

    private final List<LiveToolCallEntry> entries = new ArrayList<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Records a tool call starting. Returns the call ID for later completion via {@link #complete}.
     */
    public synchronized long recordStart(@NotNull String toolName,
                                         @NotNull String displayName,
                                         @NotNull String inputJson,
                                         @org.jetbrains.annotations.Nullable String kind,
                                         boolean hasHooks) {
        LiveToolCallEntry entry = LiveToolCallEntry.started(toolName, displayName, inputJson, kind, hasHooks);
        entries.add(entry);
        evictIfNeeded();
        fireChanged();
        return entry.callId();
    }

    /**
     * Marks the entry with the given {@code callId} as completed.
     * Scans from the end (most recent) since completions usually arrive in order.
     * If the entry has already been evicted, this is a safe no-op.
     */
    public synchronized void complete(long callId, @NotNull String output,
                                      long durationMs, boolean success) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).callId() == callId) {
                entries.set(i, entries.get(i).completed(output, durationMs, success));
                fireChanged();
                return;
            }
        }
    }

    /**
     * Attaches hook stage results to the entry with the given {@code callId}.
     * Called from McpProtocolHandler after each hook stage completes.
     */
    public synchronized void setHookStages(long callId, @NotNull java.util.List<com.github.catatafishen.agentbridge.services.hooks.HookStageResult> stages) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).callId() == callId) {
                entries.set(i, entries.get(i).withHookStages(stages));
                fireChanged();
                return;
            }
        }
    }

    /**
     * Returns a snapshot of all current entries (newest last).
     */
    public synchronized @NotNull List<LiveToolCallEntry> getEntries() {
        return List.copyOf(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

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
