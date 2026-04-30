package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row in a {@link com.intellij.openapi.ui.popup.JBPopup} chooser, captured at snapshot time.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>{@code valueId} is a stable identifier derived from the platform's
 * {@link com.intellij.openapi.ui.popup.ListPopupStep#getTextFor} and the row position. It is used
 * by {@code popup_respond} to drive selection during replay (NOT the raw list index, which can
 * shift when separators / disabled rows / filtering are involved). Agents may pass either
 * {@code valueId} or {@code index} when responding; {@code valueId} wins when both are provided.
 *
 * @param valueId       stable id; pattern: {@code <text>|<index>}
 * @param index         display-only row position (0-based)
 * @param text          primary text from {@code step.getTextFor(value)}
 * @param secondaryText optional secondary text (subtitle/secondary label) — may be {@code null}
 * @param selectable    whether the row is selectable (from {@code step.isSelectable(value)})
 * @param hasSubstep    whether selecting this row opens a further popup
 *                      (from {@code step.hasSubstep(value)})
 */
public record PopupChoice(
    @NotNull String valueId,
    int index,
    @NotNull String text,
    @Nullable String secondaryText,
    boolean selectable,
    boolean hasSubstep
) {

    /**
     * Builds the canonical valueId used at both snapshot and replay sides so the same value
     * round-trips deterministically.
     */
    @NotNull
    public static String buildValueId(@NotNull String text, int index) {
        return text + "|" + index;
    }
}
