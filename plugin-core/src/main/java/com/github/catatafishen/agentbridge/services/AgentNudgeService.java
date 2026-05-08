package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.ui.NudgeSource;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level service that owns the full lifecycle of pending nudges — human-typed instructions
 * and system reprimands waiting to be injected into the next MCP tool result.
 *
 * <h3>Architecture contract</h3>
 * <ul>
 *   <li>All nudge state lives here — the UI holds only display-layer references (active bubble ID).</li>
 *   <li>Callers decide whether a nudge should show a bubble ({@code showBubble}) before calling
 *       {@link #addNudge}, typically by reading the {@code ReprimandNudgeMode} setting.</li>
 *   <li>REPRIMAND nudges coalesce: a new REPRIMAND silently replaces the previous one without
 *       firing a cancel event, preventing UI flicker during rapid reprimand updates.</li>
 *   <li>{@link #clearHumanNudges()} is a silent purge (no listener events) — used at turn start
 *       so human nudges don't bleed into the next turn. Reprimands survive until consumed.</li>
 *   <li>{@link #consumePendingNudges()} drains the map atomically, merges human text first and
 *       reprimand text after, and fires {@link Listener#onNudgesInjected} once.</li>
 *   <li>Listener callbacks fire on the calling thread — UI listeners should dispatch to the EDT
 *       themselves (e.g. via {@code ApplicationManager.getApplication().invokeLater}).</li>
 * </ul>
 *
 * <p>The {@link #messageQueue} is a separate concern for queued messages scheduled to be
 * sent at the start of the next agent turn. It does not interact with nudge state.</p>
 */
@Service(Service.Level.PROJECT)
public final class AgentNudgeService {

    /**
     * A single pending nudge waiting to be injected into the next MCP tool result.
     */
    public record NudgeEntry(String id, String text, NudgeSource source, boolean showBubble) {
    }

    /**
     * Listener for nudge lifecycle events. Callbacks fire synchronously on the calling thread;
     * implementations that update Swing UI must dispatch to the EDT themselves.
     */
    public interface Listener {
        /**
         * A new nudge was added. For REPRIMAND, the previous reprimand has already been replaced.
         */
        void onNudgeAdded(@NotNull NudgeEntry entry);

        /**
         * All pending nudges were drained and injected into an MCP tool result.
         *
         * @param entries    the consumed entries, in insertion order
         * @param mergedText human nudge text followed by reprimand text, separated by blank lines
         */
        void onNudgesInjected(@NotNull List<NudgeEntry> entries, @NotNull String mergedText);

        /**
         * A nudge was explicitly cancelled by the user (cancel button).
         * Not fired for REPRIMAND coalescing or {@link #clearHumanNudges()}.
         */
        void onNudgeCancelled(@NotNull NudgeEntry entry);
    }

    /**
     * Insertion-ordered map of pending nudges, keyed by nudge ID. Guarded by {@code this}.
     */
    private final LinkedHashMap<String, NudgeEntry> pendingNudges = new LinkedHashMap<>();
    /**
     * When true, {@link #consumePendingNudges()} is suppressed so nudges wait until the
     * main agent resumes after a sub-agent finishes.
     */
    private volatile boolean nudgesHeld = false;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /**
     * Queued messages to be sent at the start of the next agent turn (separate concern).
     */
    private final java.util.Queue<String> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static AgentNudgeService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, AgentNudgeService.class);
    }

    // ─── Listener management ────────────────────────────────────────────────

    public void addListener(@NotNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Listener listener) {
        listeners.remove(listener);
    }

    // ─── Nudge lifecycle ────────────────────────────────────────────────────

    /**
     * Adds a nudge to the pending queue and notifies listeners.
     *
     * <p>REPRIMAND nudges coalesce: adding a new REPRIMAND silently removes any existing
     * REPRIMAND entry (no {@link Listener#onNudgeCancelled} event). HUMAN nudges accumulate.
     *
     * @param text       the nudge text to inject into the next MCP tool result
     * @param source     the originator; REPRIMAND entries coalesce, HUMAN entries accumulate
     * @param showBubble whether the UI should display a nudge bubble for this nudge
     * @return a UUID nudge ID that can be passed to {@link #cancelNudge(String)}
     */
    @NotNull
    public String addNudge(@NotNull String text, @NotNull NudgeSource source, boolean showBubble) {
        String id = UUID.randomUUID().toString();
        NudgeEntry entry = new NudgeEntry(id, text, source, showBubble);
        synchronized (pendingNudges) {
            if (source.isReprimand()) {
                // Coalesce per type: a new reprimand replaces any existing nudge of the same type.
                pendingNudges.values().removeIf(e -> e.source() == source);
            }
            pendingNudges.put(id, entry);
        }
        for (Listener l : listeners) l.onNudgeAdded(entry);
        return id;
    }

    /**
     * Cancels a pending nudge by ID and fires {@link Listener#onNudgeCancelled}.
     * Used for explicit user-initiated cancellation (cancel button).
     * Does nothing if the nudge was already consumed or not found.
     *
     * @return {@code true} if the nudge was found and removed
     */
    public boolean cancelNudge(@NotNull String id) {
        NudgeEntry removed;
        synchronized (pendingNudges) {
            removed = pendingNudges.remove(id);
        }
        if (removed != null) {
            for (Listener l : listeners) l.onNudgeCancelled(removed);
            return true;
        }
        return false;
    }

    /**
     * Silently removes all pending HUMAN nudges without firing any listener events.
     * Called at turn start — human nudges that were not delivered are discarded so they
     * do not bleed into the next turn. Reprimands survive until consumed.
     */
    public void clearHumanNudges() {
        synchronized (pendingNudges) {
            pendingNudges.values().removeIf(e -> e.source() == NudgeSource.HUMAN);
        }
    }

    /**
     * Returns the merged text of all pending nudges without consuming them.
     * Human text comes first, then reprimand text, separated by blank lines.
     * Returns {@code null} if no nudges are pending.
     */
    @Nullable
    public String getPendingNudgesText() {
        List<NudgeEntry> snapshot;
        synchronized (pendingNudges) {
            snapshot = new ArrayList<>(pendingNudges.values());
        }
        return mergeEntries(snapshot);
    }

    /**
     * Holds or releases nudge injection. While held, {@link #consumePendingNudges()} returns
     * {@code null} so nudges are not injected into sub-agent tool results — they wait until
     * the main agent resumes and makes the next tool call.
     */
    public void setNudgesHeld(boolean held) {
        nudgesHeld = held;
    }

    /**
     * Atomically drains all pending nudges, merges their text (human first, reprimand after),
     * fires {@link Listener#onNudgesInjected}, and returns the merged text.
     * Returns {@code null} when nudges are held (sub-agent active) or nothing is pending.
     */
    @Nullable
    public String consumePendingNudges() {
        if (nudgesHeld) return null;
        List<NudgeEntry> consumed;
        synchronized (pendingNudges) {
            if (pendingNudges.isEmpty()) return null;
            consumed = new ArrayList<>(pendingNudges.values());
            pendingNudges.clear();
        }
        String merged = mergeEntries(consumed);
        if (merged != null) {
            List<NudgeEntry> snap = List.copyOf(consumed);
            for (Listener l : listeners) l.onNudgesInjected(snap, merged);
        }
        return merged;
    }

    // ─── Message queue (separate concern) ───────────────────────────────────

    public void enqueueMessage(@NotNull String message) {
        if (!message.trim().isEmpty()) {
            messageQueue.offer(message.trim());
        }
    }

    public void removeQueuedMessage(@NotNull String message) {
        messageQueue.remove(message.trim());
    }

    @Nullable
    public String getNextQueuedMessage() {
        return messageQueue.poll();
    }

    // ─── Static utilities ────────────────────────────────────────────────────

    /**
     * Concatenates two nudge strings with a blank-line separator.
     * Returns {@code newNudge} unchanged if {@code existing} is null or blank.
     */
    public static String mergeNudges(@Nullable String existing, @NotNull String newNudge) {
        return (existing == null || existing.isEmpty()) ? newNudge : existing + "\n\n" + newNudge;
    }

    /**
     * Appends a nudge message to a tool result, or returns the result unchanged if nudge is null.
     */
    public static String appendNudgeToResult(@NotNull String result, @Nullable String nudge) {
        return nudge != null ? result + "\n\n[User nudge]: " + nudge : result;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    @Nullable
    private static String mergeEntries(@NotNull List<NudgeEntry> entries) {
        String humanMerged = null;
        String reprimandMerged = null;
        for (NudgeEntry entry : entries) {
            if (entry.source() == NudgeSource.HUMAN) {
                humanMerged = mergeNudges(humanMerged, entry.text());
            } else if (entry.source().isReprimand()) {
                reprimandMerged = mergeNudges(reprimandMerged, entry.text());
            }
        }
        if (humanMerged == null && reprimandMerged == null) return null;
        if (humanMerged == null) return reprimandMerged;
        if (reprimandMerged == null) return humanMerged;
        return humanMerged + "\n\n" + reprimandMerged;
    }
}
