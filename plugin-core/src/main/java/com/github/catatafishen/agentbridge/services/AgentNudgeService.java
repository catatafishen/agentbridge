package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level service that owns all nudge and message-queue state.
 *
 * <p>A <em>nudge</em> is a short plain-text hint injected into the next MCP tool result so the
 * agent sees it immediately without a round-trip message. Nudges are produced by user interactions
 * (e.g. the nudge bubble in the chat panel) or by plugin logic (e.g. built-in tool reprimands
 * from {@link com.github.catatafishen.agentbridge.acp.client.CopilotClient#onBuiltInToolApproved})
 * and consumed once by {@link #consumePendingNudge()} inside
 * {@link com.github.catatafishen.agentbridge.psi.PsiBridgeService}.</p>
 *
 * <p>The message queue holds pre-typed user messages that are auto-sent at the end of the current
 * turn via {@link com.github.catatafishen.agentbridge.ui.PromptOrchestrator}.</p>
 */
@Service(Service.Level.PROJECT)
public final class AgentNudgeService {

    private final java.util.concurrent.atomic.AtomicReference<String> pendingNudge =
        new java.util.concurrent.atomic.AtomicReference<>();
    /**
     * When true, {@link #consumePendingNudge()} is suppressed and returns {@code null}.
     * Set while a sub-agent is active so nudges are held until the main agent resumes.
     */
    private volatile boolean nudgesHeld = false;
    private final java.util.Queue<String> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> onNudgeConsumed =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> onNudgeRequested =
        new java.util.concurrent.atomic.AtomicReference<>();

    public static AgentNudgeService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, AgentNudgeService.class);
    }

    public void setPendingNudge(@Nullable String nudge) {
        if (nudge == null) {
            pendingNudge.set(null);
            return;
        }
        pendingNudge.updateAndGet(existing -> mergeNudges(existing, nudge));
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

    public void setOnNudgeRequested(@Nullable java.util.function.Consumer<String> callback) {
        this.onNudgeRequested.set(callback);
    }

    /** Delivers a plugin-initiated nudge (e.g. built-in tool reprimand) to the UI. */
    public void fireNudge(@NotNull String text) {
        java.util.function.Consumer<String> cb = onNudgeRequested.get();
        if (cb != null) cb.accept(text);
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
     * Atomically consumes the pending nudge and fires the registered callback.
     * Returns {@code null} while nudges are held (sub-agent active) or when no nudge is pending.
     */
    @Nullable
    public String consumePendingNudge() {
        if (nudgesHeld) return null;
        String nudge = pendingNudge.getAndSet(null);
        if (nudge != null) {
            // Clear the callback atomically to prevent stale callbacks from firing
            // on future nudge sources (e.g., tool reprimands, revert nudges)
            Runnable cb = onNudgeConsumed.getAndSet(null);
            if (cb != null) cb.run();
        }
        return nudge;
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
