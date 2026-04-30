package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Immutable snapshot of a {@link com.intellij.openapi.ui.popup.JBPopup} chooser captured by
 * {@link PopupInterceptor} in {@link PopupHandler.Snapshot} mode.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>{@code popupKind} values:
 * <ul>
 *   <li>{@code "list-step"} — extracted via {@code ListPopupStep} (preferred, structurally accurate)</li>
 *   <li>{@code "list-fallback"} — extracted via raw {@code JBList}+{@code ListCellRenderer} walk
 *       (best-effort; structural metadata like {@code selectable}/{@code hasSubstep} unreliable)</li>
 *   <li>{@code "tree"} — flattened tree, depth ≤ 3</li>
 *   <li>{@code "unsupported"} — popup content not understood; choices empty</li>
 * </ul>
 *
 * <p>The popup is <em>cancelled</em> after the snapshot is taken (see {@link PopupInterceptor}
 * for why we cannot keep the popup open across tool calls). Replay re-invokes the original
 * action with {@link PopupHandler.SelectByValue}.
 */
public record PopupSnapshot(
    @NotNull String popupTitle,
    @NotNull List<PopupChoice> choices,
    @NotNull String popupKind
) {

    public static final String KIND_LIST_STEP = "list-step";
    public static final String KIND_LIST_FALLBACK = "list-fallback";
    public static final String KIND_TREE = "tree";
    public static final String KIND_UNSUPPORTED = "unsupported";

    public PopupSnapshot {
        choices = List.copyOf(choices);
    }

    /** True when the snapshot has no actionable selectable rows and replay would be pointless. */
    public boolean isEmpty() {
        return choices.isEmpty() || KIND_UNSUPPORTED.equals(popupKind);
    }

    /**
     * Stable digest of the snapshot's distinguishing content. Used by
     * {@link PendingPopupService} to detect popup-replay loops where the same chooser opens
     * again after replay (indicating the action will never converge non-interactively).
     */
    @NotNull
    public String contentDigest() {
        StringBuilder sb = new StringBuilder(popupTitle).append('\u0001').append(popupKind);
        for (PopupChoice c : choices) {
            sb.append('\u0001').append(c.valueId());
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
