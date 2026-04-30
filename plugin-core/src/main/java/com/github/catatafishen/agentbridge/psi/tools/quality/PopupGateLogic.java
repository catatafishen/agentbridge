package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.tools.quality.PendingPopupService.Pending;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure decision function: given the current {@link Pending} (if any) and an incoming tool call,
 * decide whether the call is allowed, blocked, or should proceed with a "popup auto-cancelled"
 * note prepended to its result.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>Extracted from {@link com.github.catatafishen.agentbridge.services.McpProtocolHandler} so
 * the gate behavior is unit-testable in isolation. The protocol handler calls
 * {@link #evaluate(Pending, String, String, Instant)} before dispatching to {@code bridge.callTool}
 * and acts on the returned {@link Decision}.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li>{@link #POPUP_RESPOND_TOOL} always passes (it's the tool that resolves the pending).</li>
 *   <li>If no popup is pending → {@link Allow}.</li>
 *   <li>If a popup is pending and the time budget has expired → {@link AllowWithCancelNote}
 *       (the gate is responsible for telling the caller to drop the slot).</li>
 *   <li>If the call comes from the owning session and would be the
 *       {@link PendingPopupService#MAX_UNRELATED_CALLS}-th unrelated call →
 *       {@link AllowWithCancelNote}.</li>
 *   <li>Otherwise → {@link Block} with an actionable message naming the popup id.</li>
 * </ol>
 *
 * <p>Cross-session calls return {@link Block} (a popup on the EDT affects every session) but do
 * not bump the owning session's counter — they're a "noisy neighbor" signal, not progress
 * toward auto-cancel.
 */
public final class PopupGateLogic {

    public static final String POPUP_RESPOND_TOOL = "popup_respond";

    private PopupGateLogic() {
    }

    /**
     * Result of {@link #evaluate(Pending, String, String, Instant)}.
     *
     * <ul>
     *   <li>{@link Allow} — dispatch the call normally.</li>
     *   <li>{@link AllowWithCancelNote} — dispatch the call normally, but the caller MUST
     *       prepend {@link AllowWithCancelNote#note()} to the tool's response and clear the
     *       pending slot via {@link PendingPopupService#cancelAndClear(String)} before
     *       dispatching. The cancelled {@link Pending} is included for context.</li>
     *   <li>{@link Block} — return {@link Block#message()} as the tool's error response and do
     *       not dispatch.</li>
     * </ul>
     */
    public sealed interface Decision {
    }

    public record Allow() implements Decision {
    }

    public record AllowWithCancelNote(@NotNull Pending cancelled, @NotNull String note)
        implements Decision {
    }

    public record Block(@NotNull String message) implements Decision {
    }

    /**
     * Pure decision. Does NOT mutate {@link PendingPopupService} — the caller is responsible
     * for {@link PendingPopupService#recordUnrelatedCall(String)} or
     * {@link PendingPopupService#cancelAndClear(String)} based on the returned {@link Decision}.
     *
     * @param pending           current pending popup, or {@code null}
     * @param toolName          incoming tool name
     * @param callerSessionKey  MCP session key of the caller
     * @param now               current instant (injected for testability)
     */
    @NotNull
    public static Decision evaluate(@Nullable Pending pending,
                                    @NotNull String toolName,
                                    @NotNull String callerSessionKey,
                                    @NotNull Instant now) {
        if (POPUP_RESPOND_TOOL.equals(toolName)) {
            return new Allow();
        }
        if (pending == null) {
            return new Allow();
        }
        Duration age = Duration.between(pending.createdAt(), now);
        if (age.compareTo(PendingPopupService.MAX_AGE) > 0) {
            return new AllowWithCancelNote(pending, formatTimeoutNote(pending, age));
        }
        boolean owning = pending.owningSessionKey().equals(callerSessionKey);
        if (owning && pending.unrelatedCallsSinceCreated() + 1 >= PendingPopupService.MAX_UNRELATED_CALLS) {
            return new AllowWithCancelNote(pending, formatExhaustedNote(pending));
        }
        return new Block(formatBlockedMessage(pending, owning));
    }

    @NotNull
    static String formatBlockedMessage(@NotNull Pending pending, boolean owningSession) {
        int remaining = PendingPopupService.MAX_UNRELATED_CALLS
            - pending.unrelatedCallsSinceCreated() - 1;
        StringBuilder sb = new StringBuilder();
        sb.append("Error: a popup is awaiting response (id=").append(pending.id())
            .append(", originally opened by '").append(pending.originalTool())
            .append("' for action '").append(pending.fingerprint().actionIdentity())
            .append("'). Call popup_respond first.");
        if (owningSession) {
            sb.append(" Auto-cancels in ").append(Math.max(remaining, 0))
                .append(" more tool call(s)").append(remaining <= 0 ? " (next call cancels)" : "")
                .append(".");
        } else {
            sb.append(" The popup is owned by a different MCP session; this call is blocked")
                .append(" because the popup occupies the IDE's EDT.");
        }
        return sb.toString();
    }

    @NotNull
    static String formatExhaustedNote(@NotNull Pending pending) {
        return "Note: pending popup '" + pending.snapshot().popupTitle()
            + "' (id=" + pending.id() + ") was auto-cancelled — "
            + PendingPopupService.MAX_UNRELATED_CALLS
            + " unrelated tool calls without response.\n\n";
    }

    @NotNull
    static String formatTimeoutNote(@NotNull Pending pending, @NotNull Duration age) {
        return "Note: pending popup '" + pending.snapshot().popupTitle()
            + "' (id=" + pending.id() + ") was auto-cancelled — older than "
            + PendingPopupService.MAX_AGE.toMinutes() + " min (age "
            + age.toSeconds() + "s).\n\n";
    }
}
