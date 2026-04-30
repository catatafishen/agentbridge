package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
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
 * Detects and cancels {@link JBPopup quick-fix / chooser popups} that an
 * {@link com.intellij.codeInsight.intention.IntentionAction} opens during {@code invoke()}.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/freeze-investigation-2026-04-30.md}.</b>
 *
 * <p>When an {@code IntentionAction} like {@code "Import class 'Cell'"} cannot decide what to do
 * non-interactively (e.g. multiple {@code Cell} classes are importable), it opens a heavyweight
 * {@link JBPopup} class chooser and pumps a <em>nested AWT event loop</em> on the EDT until the
 * user picks an option. From outside that nested loop, the EDT looks frozen — every other tool
 * call queued via {@code invokeLater} starves, and the entire IDE becomes unresponsive.
 * {@link com.github.catatafishen.agentbridge.psi.EdtUtil#describeModalBlocker EdtUtil} can
 * <em>diagnose</em> that situation after the fact (after the 1.5 s modal-poll timeout), but the
 * EDT is still stuck inside the popup loop because nothing has cancelled the popup.
 *
 * <p>This interceptor closes that gap. It installs a global AWT {@code WINDOW_OPENED} listener,
 * runs the action, and — when a new popup window appears <em>inside</em> the nested loop —
 * looks up the corresponding {@link JBPopup} via {@link JBPopupFactory#getChildPopups} and calls
 * {@link JBPopup#cancel()} on the EDT. {@code cancel()} disposes the popup synchronously, which
 * exits the nested event loop, and the original {@code action.invoke()} call returns to the
 * caller. The caller can then report a structured "popup blocked" error to the agent so it can
 * choose a different strategy ({@code edit_text}, ambiguous-import pre-flight, etc.).
 *
 * <p>The detection is <em>scoped</em> to a chosen owner component (typically the editor's
 * root pane). We snapshot the popups visible under that owner before invoking the action and
 * cancel only popups that were not present in the snapshot. This avoids cancelling unrelated
 * popups that happen to be open in the IDE at the same time (e.g. a tool-window quick search).
 *
 * <p>This handles the heavyweight-popup freeze case only. Lightweight popups (overlaid on
 * {@link javax.swing.JLayeredPane} without a nested event loop) cannot freeze the EDT and are
 * intentionally out of scope.
 *
 * <p>All methods must be called from the EDT.
 *
 * @see DialogInterceptor for the analogous pattern targeting modal {@link java.awt.Dialog}s.
 */
final class PopupInterceptor {

    private static final Logger LOG = Logger.getInstance(PopupInterceptor.class);

    /**
     * Snapshot returned to the caller after invocation.
     */
    record Result(boolean popupWasOpened, @NotNull List<String> popupTitles, boolean cancelled) {

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
     * Runs {@code action}, cancelling any new {@link JBPopup} that opens under {@code owner}'s
     * window during invocation. Returns a {@link Result} describing what was caught (if anything).
     *
     * @param owner  optional component used to scope popup detection. Pass the editor component
     *               or root pane. {@code null} falls back to a global scan across all frames.
     * @param action the EDT-bound action whose popup-opening side-effect should be intercepted.
     */
    @NotNull
    static Result runDetectingPopups(@Nullable Component owner, @NotNull Runnable action) {
        // Snapshot the popups already open under this owner so we cancel ONLY new ones.
        Set<JBPopup> baseline = collectActivePopups(owner);
        AtomicReference<List<JBPopup>> capturedRef = new AtomicReference<>(List.of());

        AWTEventListener listener = event -> onWindowEvent(event, owner, baseline, capturedRef);

        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
        try {
            action.run();
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        }

        List<JBPopup> captured = capturedRef.get();
        if (captured.isEmpty()) {
            return new Result(false, List.of(), false);
        }

        List<String> titles = new ArrayList<>(captured.size());
        boolean allCancelled = cancelAndCollectTitles(captured, titles);
        return new Result(true, List.copyOf(titles), allCancelled);
    }

    private static void onWindowEvent(@NotNull AWTEvent event, @Nullable Component owner,
                                      @NotNull Set<JBPopup> baseline,
                                      @NotNull AtomicReference<List<JBPopup>> capturedRef) {
        if (!(event instanceof WindowEvent we) || we.getID() != WindowEvent.WINDOW_OPENED) {
            return;
        }
        if (!capturedRef.get().isEmpty()) {
            return; // already captured one set; ignore further window-opens
        }
        try {
            List<JBPopup> newPopups = diffNewPopups(owner, baseline);
            if (newPopups.isEmpty()) {
                return;
            }
            capturedRef.set(newPopups);
            // cancel() must run on the EDT inside the popup's nested event loop so the loop
            // exits and action.invoke() returns. The AWT listener fires on the EDT, so we
            // call cancel() right here — the very same EDT cycle the popup opened in.
            for (JBPopup popup : newPopups) {
                tryCancel(popup);
            }
        } catch (Exception | LinkageError t) {
            // Defensive: never let a diagnostic listener crash the EDT.
            LOG.warn("PopupInterceptor: failed while inspecting opened window", t);
        }
    }

    private static boolean cancelAndCollectTitles(@NotNull List<JBPopup> popups,
                                                  @NotNull List<String> titlesOut) {
        boolean allCancelled = true;
        for (JBPopup popup : popups) {
            titlesOut.add(extractPopupTitle(popup));
            // Defensive second cancel pass for the unusual case where the listener saw the
            // window AFTER action.run() returned (the new window event was queued but not yet
            // dispatched while the action was still on the stack).
            if (!tryCancel(popup) || !popup.isDisposed()) {
                allCancelled = false;
            }
        }
        return allCancelled;
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

    /**
     * Returns the popups visible <em>now</em> that were not in {@code baseline}. Pure function
     * over the live UI state — exposed at package-private level so the listener path and tests
     * use the same logic.
     */
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

    /**
     * Collects active {@link JBPopup}s anchored under {@code owner}'s window. When {@code owner}
     * is {@code null} (no editor available), falls back to scanning every {@link Frame}.
     */
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

    /**
     * Best-effort title for a popup. JBPopup has no public title accessor, so we look for a
     * window title (heavyweight popups carry the title there) and fall back to the first visible
     * label inside the popup content. Returns {@code "(untitled popup)"} if nothing is found.
     */
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
     * Formats the agent-facing error returned when a popup was intercepted. Pure function.
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
