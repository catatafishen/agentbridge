package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopupStep;
import com.intellij.ui.popup.list.ListPopupImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects {@link JBPopup} popups opened during an
 * {@link com.intellij.codeInsight.intention.IntentionAction} invocation and either cancels them
 * (default), snapshots their structure for later replay, or programmatically selects a captured
 * value to drive the popup to completion.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/freeze-investigation-2026-04-30.md} and
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>When an {@code IntentionAction} like {@code "Import class 'Cell'"} cannot decide
 * non-interactively (e.g. multiple {@code Cell} classes are importable), it opens a heavyweight
 * {@link JBPopup} chooser and pumps a <em>nested AWT event loop</em> on the EDT until the user
 * picks. From outside that nested loop, the EDT looks frozen — every other tool call queued via
 * {@code invokeLater} starves, and the entire IDE becomes unresponsive.
 *
 * <h2>Handler modes</h2>
 * <ul>
 *   <li>{@link PopupHandler.Cancel} — the original PR #363 behavior: cancel the popup, return a
 *       {@code popupWasOpened=true} result. The caller surfaces a structured error to the agent
 *       and suggests {@code edit_text}.</li>
 *   <li>{@link PopupHandler.Snapshot} — extract a {@link PopupSnapshot} via
 *       {@link PopupContentExtractor}, push it through {@link PopupHandler.Snapshot#sink()},
 *       then cancel. The caller stores the snapshot in {@code PendingPopupService} and tells the
 *       agent it can call {@code popup_respond}.</li>
 *   <li>{@link PopupHandler.SelectByValue} — given a {@code valueId} captured by a prior
 *       Snapshot run, locate the matching value in the popup's {@link ListPopupStep} and invoke
 *       {@link ListPopupImpl#selectAndExecuteValue(Object)} via
 *       {@link ApplicationManager#invokeLater(Runnable, ModalityState)} with
 *       {@link ModalityState#any()} (per the rubber-duck review: dispatching the selection
 *       inside the {@code WINDOW_OPENED} listener can race popup initialisation).</li>
 * </ul>
 *
 * <p>The detection is <em>scoped</em> to a chosen owner component (typically the editor's root
 * pane). We snapshot popups visible under that owner before invoking the action and only act on
 * popups not present in the baseline. This avoids touching unrelated popups (e.g. tool-window
 * quick search).
 *
 * <p>Lightweight popups (overlaid on {@link javax.swing.JLayeredPane} without a nested event
 * loop) cannot freeze the EDT and are intentionally out of scope.
 *
 * <p>All public methods must be called from the EDT.
 *
 * @see DialogInterceptor for the analogous pattern targeting modal {@link java.awt.Dialog}s.
 */
final class PopupInterceptor {

    private static final Logger LOG = Logger.getInstance(PopupInterceptor.class);

    /**
     * Snapshot returned to the caller after invocation.
     *
     * @param popupWasOpened     whether at least one new popup opened during {@code action.run()}.
     * @param popupTitles        titles of the new popups (best-effort).
     * @param cancelled          whether <em>all</em> intercepted popups were dismissed (only
     *                           meaningful in {@link PopupHandler.Cancel} and
     *                           {@link PopupHandler.Snapshot} modes).
     * @param snapshot           structural extraction of the first intercepted popup; non-null only
     *                           when handler is {@link PopupHandler.Snapshot} and extraction
     *                           succeeded.
     * @param selectionScheduled true when a {@link PopupHandler.SelectByValue} replay scheduled
     *                           a selection via {@code invokeLater}; the popup will close
     *                           asynchronously after this method returns.
     */
    record Result(boolean popupWasOpened,
                  @NotNull List<String> popupTitles,
                  boolean cancelled,
                  @Nullable PopupSnapshot snapshot,
                  boolean selectionScheduled) {

        @NotNull
        String describe() {
            if (popupTitles.isEmpty()) {
                return "(unidentified popup)";
            }
            return String.join(", ", popupTitles.stream().map(t -> "'" + t + "'").toList());
        }
    }

    private PopupInterceptor() {
    }

    /**
     * Convenience overload that uses {@link PopupHandler.Cancel}. Maintained for callers that
     * want PR #363's original behavior.
     */
    @NotNull
    static Result runDetectingPopups(@Nullable Component owner, @NotNull Runnable action) {
        return runDetectingPopups(owner, new PopupHandler.Cancel(), action);
    }

    /**
     * Runs {@code action} with the given {@link PopupHandler}. See the class Javadoc for the
     * handler-mode semantics.
     *
     * @param owner   optional component used to scope popup detection. Pass the editor component
     *                or root pane. {@code null} falls back to a global scan across all frames.
     * @param handler what to do when a popup is detected.
     * @param action  the EDT-bound action whose popup-opening side-effect should be intercepted.
     */
    @NotNull
    static Result runDetectingPopups(@Nullable Component owner,
                                     @NotNull PopupHandler handler,
                                     @NotNull Runnable action) {
        Set<JBPopup> baseline = collectActivePopups(owner);
        AtomicReference<List<JBPopup>> capturedRef = new AtomicReference<>(List.of());
        AtomicReference<PopupSnapshot> snapshotRef = new AtomicReference<>();
        AtomicReference<Boolean> selectionScheduledRef = new AtomicReference<>(false);

        AWTEventListener listener = event -> onWindowEvent(
            event, owner, baseline, capturedRef, handler, snapshotRef, selectionScheduledRef);

        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
        try {
            action.run();
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        }

        List<JBPopup> captured = capturedRef.get();
        if (captured.isEmpty()) {
            return new Result(false, List.of(), false, null, false);
        }

        List<String> titles = new ArrayList<>(captured.size());
        for (JBPopup popup : captured) {
            titles.add(extractPopupTitle(popup));
        }

        boolean isSelect = handler instanceof PopupHandler.SelectByValue;
        boolean allCancelled = false;
        if (!isSelect) {
            // Cancel/Snapshot: ensure popups are gone (defensive — listener may have raced).
            allCancelled = ensureCancelled(captured);
        }

        return new Result(
            true,
            List.copyOf(titles),
            allCancelled,
            snapshotRef.get(),
            Boolean.TRUE.equals(selectionScheduledRef.get())
        );
    }

    private static boolean ensureCancelled(@NotNull List<JBPopup> popups) {
        boolean allCancelled = true;
        for (JBPopup popup : popups) {
            if (!tryCancel(popup) || !popup.isDisposed()) {
                allCancelled = false;
            }
        }
        return allCancelled;
    }

    private static void onWindowEvent(@NotNull AWTEvent event,
                                      @Nullable Component owner,
                                      @NotNull Set<JBPopup> baseline,
                                      @NotNull AtomicReference<List<JBPopup>> capturedRef,
                                      @NotNull PopupHandler handler,
                                      @NotNull AtomicReference<PopupSnapshot> snapshotRef,
                                      @NotNull AtomicReference<Boolean> selectionScheduledRef) {
        if (!(event instanceof WindowEvent we) || we.getID() != WindowEvent.WINDOW_OPENED) {
            return;
        }
        if (!capturedRef.get().isEmpty()) {
            return;
        }
        try {
            List<JBPopup> newPopups = diffNewPopups(owner, baseline);
            if (newPopups.isEmpty()) {
                return;
            }
            capturedRef.set(newPopups);
            handlePopupCaptured(newPopups, handler, snapshotRef, selectionScheduledRef);
        } catch (Exception | LinkageError t) {
            LOG.warn("PopupInterceptor: failed while inspecting opened window", t);
        }
    }

    private static void handlePopupCaptured(@NotNull List<JBPopup> popups,
                                            @NotNull PopupHandler handler,
                                            @NotNull AtomicReference<PopupSnapshot> snapshotRef,
                                            @NotNull AtomicReference<Boolean> selectionScheduledRef) {
        JBPopup first = popups.getFirst();
        switch (handler) {
            case PopupHandler.Cancel ignored -> {
                for (JBPopup p : popups) tryCancel(p);
            }
            case PopupHandler.Snapshot(var sink) -> {
                PopupSnapshot ps = PopupContentExtractor.extract(first);
                snapshotRef.set(ps);
                try {
                    sink.accept(ps);
                } catch (Exception | LinkageError t) {
                    LOG.warn("PopupInterceptor: snapshot sink threw", t);
                }
                for (JBPopup p : popups) tryCancel(p);
            }
            case PopupHandler.SelectByValue sel -> {
                if (first instanceof ListPopupImpl listPopup) {
                    selectionScheduledRef.set(true);
                    ApplicationManager.getApplication().invokeLater(
                        () -> selectInListPopup(listPopup, sel),
                        ModalityState.any()
                    );
                } else {
                    LOG.warn("PopupInterceptor: SelectByValue requires ListPopupImpl, got "
                        + first.getClass().getName() + " — cancelling");
                    for (JBPopup p : popups) tryCancel(p);
                }
            }
        }
    }

    /**
     * Locates the value matching {@code sel.valueId()} in {@code popup}'s
     * {@link ListPopupStep} and invokes {@link ListPopupImpl#selectAndExecuteValue(Object)}.
     * Falls back to {@code sel.fallbackIndex()} only when the captured value can't be found
     * by id but the index is still valid and selectable. Cancels the popup on failure.
     */
    private static void selectInListPopup(@NotNull ListPopupImpl popup,
                                          @NotNull PopupHandler.SelectByValue sel) {
        try {
            if (popup.isDisposed()) {
                LOG.warn("PopupInterceptor: popup disposed before selection could run");
                return;
            }
            ListPopupStep<Object> step = PlatformApiCompat.getListStep(popup);
            if (step == null) {
                LOG.warn("PopupInterceptor: SelectByValue — no ListPopupStep available");
                tryCancel(popup);
                return;
            }
            List<?> values = step.getValues();
            Object chosen = findValueByValueId(step, values, sel);
            if (chosen == null) {
                LOG.warn("PopupInterceptor: SelectByValue — value '" + sel.valueId()
                    + "' not found and fallback (index=" + sel.fallbackIndex() + ", text='"
                    + sel.fallbackText() + "') did not match. Cancelling popup.");
                tryCancel(popup);
                return;
            }
            popup.selectAndExecuteValue(chosen);
        } catch (Exception | LinkageError t) {
            LOG.warn("PopupInterceptor: SelectByValue dispatch failed; cancelling popup", t);
            tryCancel(popup);
        }
    }

    @Nullable
    private static Object findValueByValueId(@NotNull ListPopupStep<Object> step,
                                             @NotNull List<?> values,
                                             @NotNull PopupHandler.SelectByValue sel) {
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            String text = step.getTextFor(v);
            String id = PopupChoice.buildValueId(text, i);
            if (id.equals(sel.valueId()) && step.isSelectable(v)) {
                return v;
            }
        }
        // Fallback by index+text — protects against minor reordering.
        int fi = sel.fallbackIndex();
        if (fi >= 0 && fi < values.size()) {
            Object v = values.get(fi);
            String text = step.getTextFor(v);
            String fbText = sel.fallbackText();
            if (fbText.equals(text) && step.isSelectable(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean tryCancel(@NotNull JBPopup popup) {
        try {
            if (!popup.isDisposed()) {
                popup.cancel();
            }
            return true;
        } catch (Exception | LinkageError t) {
            LOG.warn("PopupInterceptor: failed to cancel popup", t);
            return false;
        }
    }

    // ── Pure helpers (unit-testable) ──────────────────────────

    @NotNull
    static List<JBPopup> diffNewPopups(@Nullable Component owner, @NotNull Set<JBPopup> baseline) {
        List<JBPopup> result = new ArrayList<>();
        for (JBPopup popup : collectActivePopups(owner)) {
            if (!baseline.contains(popup)) {
                result.add(popup);
            }
        }
        return result;
    }

    @NotNull
    static Set<JBPopup> collectActivePopups(@Nullable Component owner) {
        Set<JBPopup> result = new HashSet<>();
        try {
            JBPopupFactory factory = JBPopupFactory.getInstance();
            if (factory == null) {
                return result;
            }
            if (owner != null) {
                JComponent root = findRootJComponent(owner);
                if (root != null) {
                    result.addAll(factory.getChildPopups(root));
                    return result;
                }
            }
            for (Frame frame : Frame.getFrames()) {
                if (frame instanceof javax.swing.RootPaneContainer rpc) {
                    JRootPane rootPane = rpc.getRootPane();
                    if (rootPane != null) {
                        result.addAll(factory.getChildPopups(rootPane));
                    }
                }
            }
        } catch (Exception | LinkageError t) {
            LOG.warn("PopupInterceptor: failed to enumerate active popups", t);
        }
        return result;
    }

    @Nullable
    private static JComponent findRootJComponent(@NotNull Component component) {
        Window window = component instanceof Window w ? w
            : javax.swing.SwingUtilities.getWindowAncestor(component);
        if (window instanceof javax.swing.RootPaneContainer rpc) {
            return rpc.getRootPane();
        }
        return component instanceof JComponent jc ? jc : null;
    }

    @NotNull
    static String extractPopupTitle(@NotNull JBPopup popup) {
        try {
            JComponent content = popup.getContent();
            Window w = javax.swing.SwingUtilities.getWindowAncestor(content);
            if (w instanceof java.awt.Frame f && notBlank(f.getTitle())) {
                return f.getTitle();
            }
            if (w instanceof java.awt.Dialog d && notBlank(d.getTitle())) {
                return d.getTitle();
            }
            String label = firstVisibleLabel(content);
            if (label != null) return label;
        } catch (Exception | LinkageError t) {
            LOG.debug("PopupInterceptor: failed to extract popup title", t);
        }
        return "(untitled popup)";
    }

    @Nullable
    static String firstVisibleLabel(@NotNull Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JLabel lbl && comp.isVisible() && notBlank(lbl.getText())) {
                return lbl.getText();
            }
            if (comp instanceof Container c) {
                String nested = firstVisibleLabel(c);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    /**
     * Formats the agent-facing error returned when a popup was intercepted in
     * {@link PopupHandler.Cancel} mode (PR #363 fallback path).
     */
    @NotNull
    static String formatPopupBlockedError(@NotNull String actionName, @NotNull Result result) {
        Objects.requireNonNull(result);
        String desc = result.describe();
        StringBuilder sb = new StringBuilder();
        sb.append("Error: action '").append(actionName).append("' opened a popup chooser (")
            .append(desc).append(") that cannot be answered non-interactively.");
        if (result.cancelled()) {
            sb.append(" The popup was cancelled to prevent freezing the IDE.");
        } else {
            sb.append(" Attempted to cancel the popup but it may still be visible.");
        }
        sb.append(" Use edit_text to make the change directly, or call get_action_options first")
            .append(" to inspect dialog-style choices (note: popup choosers are not the same as")
            .append(" dialog options and cannot be selected via the 'option' parameter).");
        return sb.toString();
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }
}
