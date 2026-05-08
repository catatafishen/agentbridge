package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.ui.NudgeSource;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class AgentNudgeService {

    private final java.util.concurrent.atomic.AtomicReference<String> pendingHumanNudge =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<String> pendingReprimandNudge =
        new java.util.concurrent.atomic.AtomicReference<>();
    /**
     * When true, {@link #consumePendingNudge()} is suppressed and returns {@code null}.
     * Set while a sub-agent is active so nudges are held until the main agent resumes.
     */
    private volatile boolean nudgesHeld = false;
    private final java.util.Queue<String> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> onNudgeConsumed =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.BiConsumer<String, NudgeSource>> onNudgeRequested =
        new java.util.concurrent.atomic.AtomicReference<>();

    public static AgentNudgeService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, AgentNudgeService.class);
    }

    /**
     * Sets or accumulates a human nudge. Passing {@code null} clears all pending nudge state
     * (both human and reprimand), which is done at turn start.
     */
    public void setPendingNudge(@Nullable String nudge) {
        if (nudge == null) {
            pendingHumanNudge.set(null);
            pendingReprimandNudge.set(null);
            return;
        }
        pendingHumanNudge.updateAndGet(existing -> mergeNudges(existing, nudge));
    }

    /**
     * Clears only the human nudge slot, leaving any pending reprimand intact.
     * Called at turn start — reprimands from a previous turn's denial should survive
     * until the model makes an MCP call and the reprimand can be delivered.
     */
    public void clearHumanNudge() {
        pendingHumanNudge.set(null);
    }

    /**
     * Replaces the pending reprimand text without touching any human nudge.
     * Subsequent reprimands replace the previous one (only the latest issue is shown),
     * but human nudges are always preserved and delivered alongside the reprimand.
     */
    public void setReprimandNudge(@NotNull String nudge) {
        pendingReprimandNudge.set(nudge);
    }

    public void setOnNudgeConsumed(@Nullable Runnable callback) {
        onNudgeConsumed.set(callback);
    }

    /**
     * Holds or releases nudge delivery. While held, {@link #consumePendingNudge()} returns
     * {@code null} so nudges are not injected into sub-agent tool results — they wait until the
     * main agent resumes and makes the next tool call.
     */
    public void setNudgesHeld(boolean held) {
        nudgesHeld = held;
    }

    public void addOnNudgeConsumed(@NotNull Runnable callback) {
        onNudgeConsumed.accumulateAndGet(callback, (current, newCb) ->
            current == null ? newCb : () -> {
                current.run();
                newCb.run();
            }
        );
    }

    public void setOnNudgeRequested(@Nullable java.util.function.BiConsumer<String, NudgeSource> callback) {
        this.onNudgeRequested.set(callback);
    }

    /**
     * Delivers a plugin-initiated reprimand nudge to the UI.
     */
    public void fireNudge(@NotNull String text) {
        fireNudge(text, NudgeSource.REPRIMAND);
    }

    /**
     * Delivers a plugin-initiated nudge to the UI with an explicit source.
     * <p>
     * If the UI callback ({@link #setOnNudgeRequested}) is not registered (e.g. chat panel is
     * not open), falls back to direct injection so the nudge still reaches the model via the
     * next MCP tool result.
     */
    public void fireNudge(@NotNull String text, @NotNull NudgeSource source) {
        java.util.function.BiConsumer<String, NudgeSource> cb = onNudgeRequested.get();
        if (cb != null) {
            cb.accept(text, source);
        } else {
            // Chat panel not open — inject directly so the model still receives the guidance.
            if (source == NudgeSource.REPRIMAND) setReprimandNudge(text);
            else setPendingNudge(text);
        }
    }

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

    /**
     * Atomically consumes all pending nudges (human + reprimand) and fires the registered callback.
     * Human nudges are always preserved — reprimands never overwrite them.
     * Returns {@code null} while nudges are held (sub-agent active) or when nothing is pending.
     */
    @Nullable
    public String consumePendingNudge() {
        if (nudgesHeld) return null;
        String human = pendingHumanNudge.getAndSet(null);
        String reprimand = pendingReprimandNudge.getAndSet(null);
        String merged;
        if (human != null && reprimand != null) {
            merged = human + "\n\n" + reprimand;
        } else if (human != null) {
            merged = human;
        } else {
            merged = reprimand;
        }
        if (merged != null) {
            Runnable cb = onNudgeConsumed.getAndSet(null);
            if (cb != null) cb.run();
        }
        return merged;
    }

    /**
     * Merges a new nudge with any existing nudge text.
     * Returns just the new nudge if there's no existing text; concatenates with double-newline otherwise.
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
}
