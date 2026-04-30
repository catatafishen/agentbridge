package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.annotations.VisibleForTesting;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Application-level single-slot store for the popup that is currently waiting for an agent
 * response.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>Only one popup can be pending at a time, intentionally:
 * <ul>
 *   <li>The EDT can only host one heavyweight popup at a time anyway.</li>
 *   <li>Having a single slot keeps the agent's mental model trivial: "either there is a popup
 *       or there isn't".</li>
 *   <li>{@link PopupGateLogic} blocks <em>all</em> other tool calls while a popup is pending,
 *       so multi-popup support would be dead complexity.</li>
 * </ul>
 *
 * <h2>Auto-expiry</h2>
 * The pending popup is cleared on:
 * <ul>
 *   <li><b>Time</b>: {@link #MAX_AGE} since {@link Pending#createdAt()}, checked lazily on
 *       {@link #peek()}.</li>
 *   <li><b>Unrelated calls</b>: {@link #MAX_UNRELATED_CALLS} tool calls from the
 *       <em>same MCP session</em> that aren't {@code popup_respond} bump the counter via
 *       {@link #recordUnrelatedCall(String)}; the {@code MAX_UNRELATED_CALLS}-th call clears
 *       the slot. Foreign-session calls do not burn the budget.</li>
 *   <li><b>Explicit</b>: {@link #cancelAndClear(String)} from {@code popup_respond}'s
 *       {@code cancel} action.</li>
 * </ul>
 *
 * <p>All methods are thread-safe (synchronized on the service instance).
 */
@Service(Service.Level.APP)
public final class PendingPopupService {

    private static final Logger LOG = Logger.getInstance(PendingPopupService.class);

    /**
     * After this many tool calls from the owning session that aren't {@code popup_respond},
     * the pending popup is auto-cancelled. Public for {@link PopupGateLogic} and tests.
     */
    public static final int MAX_UNRELATED_CALLS = 5;

    /**
     * After this duration since {@link Pending#createdAt()}, the pending popup is auto-cancelled
     * the next time {@link #peek()} is called. Defensive bound for the case where the agent
     * hangs without making any tool calls at all.
     */
    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    /**
     * Snapshot + replay state stored in the slot.
     *
     * @param id                            generated UUID — agents pass this back as
     *                                      {@code popup_id} to {@code popup_respond}
     * @param originalTool                  e.g. {@code "apply_action"}
     * @param originalArgs                  the JSON object the agent originally sent
     * @param project                       project the action targets
     * @param fingerprint                   captured at invocation time, validated on replay
     * @param snapshot                      what the popup looked like
     * @param createdAt                     wall-clock time the snapshot was captured
     * @param owningSessionKey              MCP session that owns this pending; only this session's
     *                                      calls count toward {@link #MAX_UNRELATED_CALLS}
     * @param unrelatedCallsSinceCreated    bumped by {@link #recordUnrelatedCall(String)}
     * @param previousReplayDigest          {@link PopupSnapshot#contentDigest()} of the previous
     *                                      replay attempt (for loop detection across replays);
     *                                      {@code null} on first registration
     */
    public record Pending(
        @NotNull String id,
        @NotNull String originalTool,
        @NotNull JsonObject originalArgs,
        @Nullable Project project,
        @NotNull ContextFingerprint fingerprint,
        @NotNull PopupSnapshot snapshot,
        @NotNull Instant createdAt,
        @NotNull String owningSessionKey,
        int unrelatedCallsSinceCreated,
        @Nullable String previousReplayDigest
    ) {
    }

    private @Nullable Pending current;
    private @NotNull java.util.function.Supplier<Instant> clock = Instant::now;

    @NotNull
    public static PendingPopupService getInstance() {
        return ApplicationManager.getApplication().getService(PendingPopupService.class);
    }

    /**
     * Replaces the clock used by {@link #peek()} to evaluate {@link #MAX_AGE}. Tests only.
     */
    @TestOnly
    public synchronized void setClockForTests(@NotNull java.util.function.Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * Atomically claims the pending slot.
     *
     * @return the registered {@link Pending}, or {@code null} when the slot was already taken.
     */
    @Nullable
    public synchronized Pending register(@NotNull String originalTool,
                                         @NotNull JsonObject originalArgs,
                                         @Nullable Project project,
                                         @NotNull ContextFingerprint fingerprint,
                                         @NotNull PopupSnapshot snapshot,
                                         @NotNull String owningSessionKey,
                                         @Nullable String previousReplayDigest) {
        if (current != null) {
            LOG.warn("PendingPopupService: register rejected — slot already holds id="
                + current.id);
            return null;
        }
        Pending p = new Pending(
            UUID.randomUUID().toString(),
            originalTool,
            originalArgs,
            project,
            fingerprint,
            snapshot,
            clock.get(),
            owningSessionKey,
            0,
            previousReplayDigest
        );
        current = p;
        return p;
    }

    /**
     * Returns the current pending popup, or {@code null} if none. Lazily clears the slot if the
     * pending popup is older than {@link #MAX_AGE}.
     */
    @Nullable
    public synchronized Pending peek() {
        if (current == null) return null;
        if (Duration.between(current.createdAt, clock.get()).compareTo(MAX_AGE) > 0) {
            LOG.info("PendingPopupService: auto-cancelling pending '" + current.id
                + "' (older than " + MAX_AGE + ")");
            current = null;
            return null;
        }
        return current;
    }

    /**
     * Atomically removes and returns the pending popup if its id matches.
     */
    @Nullable
    public synchronized Pending take(@NotNull String popupId) {
        Pending c = peek();
        if (c == null || !c.id.equals(popupId)) return null;
        current = null;
        return c;
    }

    /**
     * Clears the slot. When {@code popupId} is non-null, only clears if the id matches.
     *
     * @return true if a pending popup was cleared.
     */
    public synchronized boolean cancelAndClear(@Nullable String popupId) {
        if (current == null) return false;
        if (popupId != null && !current.id.equals(popupId)) return false;
        current = null;
        return true;
    }

    /**
     * Records a tool call from {@code callerSessionKey} that is not {@code popup_respond}. Only
     * calls from the owning session bump the counter. Returns the pending popup if this call
     * just exhausted the budget and cleared the slot, or {@code null} otherwise.
     */
    @Nullable
    public synchronized Pending recordUnrelatedCall(@NotNull String callerSessionKey) {
        Pending c = peek();
        if (c == null) return null;
        if (!c.owningSessionKey.equals(callerSessionKey)) {
            return null;
        }
        int next = c.unrelatedCallsSinceCreated + 1;
        if (next >= MAX_UNRELATED_CALLS) {
            current = null;
            return c;
        }
        current = new Pending(
            c.id, c.originalTool, c.originalArgs, c.project, c.fingerprint,
            c.snapshot, c.createdAt, c.owningSessionKey, next, c.previousReplayDigest
        );
        return null;
    }

    /**
     * Updates the {@code previousReplayDigest} of the current pending after a replay rendered a
     * new popup. Used by loop detection across consecutive replays.
     */
    public synchronized void noteReplayDigest(@NotNull String popupId, @NotNull String digest) {
        if (current == null || !current.id.equals(popupId)) return;
        current = new Pending(
            current.id, current.originalTool, current.originalArgs, current.project,
            current.fingerprint, current.snapshot, current.createdAt, current.owningSessionKey,
            current.unrelatedCallsSinceCreated, digest
        );
    }

    @VisibleForTesting
    public synchronized void clearForTests() {
        current = null;
    }
}
