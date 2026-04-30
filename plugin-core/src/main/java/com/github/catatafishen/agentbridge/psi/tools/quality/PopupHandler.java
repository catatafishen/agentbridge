package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Strategy controlling what {@link PopupInterceptor} does when an action opens a
 * {@link com.intellij.openapi.ui.popup.JBPopup} chooser inside its nested AWT event loop.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>The handler is passed <em>explicitly</em> down the call chain (NOT via thread-local) because
 * the AWT {@code WINDOW_OPENED} listener fires on the EDT in a different stack from the MCP
 * worker thread. The closure captures the handler at registration time so the listener has the
 * correct strategy regardless of which thread originally triggered the action.
 *
 * <p>Variants:
 * <ul>
 *   <li>{@link Cancel} — current PR #363 behavior; cancel any new popup, surface an error.</li>
 *   <li>{@link Snapshot} — extract the popup's choices via {@link PopupContentExtractor}, push
 *       the snapshot through the sink, then cancel the popup. The caller registers the snapshot
 *       in {@link PendingPopupService} and tells the agent to call {@code popup_respond}.</li>
 *   <li>{@link SelectByValue} — used by {@code popup_respond} to drive the popup. On open,
 *       locate the value with the matching captured id and select it via
 *       {@link com.intellij.openapi.ui.popup.ListPopupStep#onChosen}.
 *       Falls back to {@code fallbackIndex} only if the captured text still occupies that row.</li>
 * </ul>
 */
public sealed interface PopupHandler {

    /** Cancel any new popup; reports back via {@link PopupInterceptor.Result#popupWasOpened()}. */
    record Cancel() implements PopupHandler {
    }

    /**
     * Snapshot the popup's choices then cancel. The {@code sink} is invoked on the EDT during
     * the listener callback, before the popup is cancelled.
     */
    record Snapshot(@NotNull Consumer<PopupSnapshot> sink) implements PopupHandler {
    }

    /**
     * Select a specific value in the popup. {@code valueId} is the stable id captured by
     * {@link PopupChoice#valueId()} at snapshot time. {@code fallbackIndex} is used only if
     * the value can't be located by id but the row at {@code fallbackIndex} still has the
     * captured text — this covers the common case where the popup is identical on re-open.
     */
    record SelectByValue(@NotNull String valueId, int fallbackIndex,
                         @NotNull String fallbackText) implements PopupHandler {
    }
}
