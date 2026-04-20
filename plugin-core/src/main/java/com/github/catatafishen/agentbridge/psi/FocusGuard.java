package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.beans.PropertyChangeEvent;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Prevents programmatic focus changes during MCP tool execution from stealing focus
 * away from the chat prompt.
 *
 * <p><b>The problem.</b> When the chat is focused (JCEF holds keyboard focus), a tool
 * may call Swing APIs like {@code FileEditorManager.openFile(vf, false)} or
 * {@code navigate(false)}. Even though {@code focusEditor=false} is requested, Swing
 * frequently moves keyboard focus to the newly created editor because JCEF focus is
 * invisible to the Java {@code KeyboardFocusManager}. A 150ms delayed alarm then
 * restores focus — but during that window the user's in-flight keystrokes land in
 * the editor instead of the chat prompt.
 *
 * <p><b>The fix.</b> This guard uses a {@link java.beans.VetoableChangeListener} on
 * the {@code focusOwner} property of {@link KeyboardFocusManager}. When a programmatic
 * focus change attempts to move focus away from the chat tool window to a component in
 * the <em>same</em> IDE main frame (editors, other tool windows), the guard throws a
 * {@link java.beans.PropertyVetoException} — <b>preventing the focus change entirely</b>.
 * Focus never leaves the chat component, so zero keystrokes can leak.
 *
 * <p><b>Targeted veto, not blanket.</b> The guard only vetoes focus changes to components
 * in the same {@link Window} as the chat tool window (i.e., the IDE main frame). Focus
 * changes to dialog windows, popups, completion lookups, and other separate windows are
 * allowed through. This prevents interference with IDE plumbing that opens modal dialogs
 * or popup menus during tool execution.
 *
 * <p><b>User-initiated focus changes are respected.</b> The guard inspects the AWT
 * event currently being dispatched; if it is a {@link java.awt.event.InputEvent} (user
 * click or keystroke) the focus change is allowed through. This preserves the ability to
 * click away to an editor during a long-running tool (e.g. build).
 *
 * <p><b>Circuit breaker.</b> If the guard vetoes more than {@value #MAX_VETOES} focus
 * changes in a single lifecycle, it disables itself to prevent unbounded veto storms
 * from degrading IDE responsiveness. This is a safety net — under normal operation,
 * a tool execution triggers at most 2-4 focus changes (editor open, tab creation).
 *
 * <p>Install via {@link #install(Project)} on the EDT; call {@link #uninstall()} in
 * the tool-call finally block. Both methods are idempotent and safe to call from any
 * thread — they self-dispatch to the EDT.
 */
final class FocusGuard implements java.beans.VetoableChangeListener {
    private static final Logger LOG = Logger.getInstance(FocusGuard.class);

    /**
     * Maximum number of vetoes before the guard disables itself. Safety net against
     * pathological scenarios where a component retries focus acquisition in a loop.
     */
    private static final int MAX_VETOES = 20;

    private final Project project;
    private final KeyboardFocusManager kfm;
    private final Component chatFocusOwner;
    private final Function<Component, Window> windowResolver;
    /**
     * Package-private so tests can simulate the uninstalled state without requiring the IDE platform.
     */
    volatile boolean uninstalled;
    /**
     * Tracks how many vetoes have fired in this guard's lifecycle. Once {@value #MAX_VETOES}
     * is reached, the guard stops vetoing to prevent runaway storms.
     */
    private final java.util.concurrent.atomic.AtomicInteger vetoCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Package-private for tests; use {@link #install(Project)} in production code.
     */
    FocusGuard(Project project, KeyboardFocusManager kfm, Component chatFocusOwner) {
        this(project, kfm, chatFocusOwner, SwingUtilities::getWindowAncestor);
    }

    FocusGuard(Project project,
               KeyboardFocusManager kfm,
               Component chatFocusOwner,
               Function<Component, Window> windowResolver) {
        this.project = project;
        this.kfm = kfm;
        this.chatFocusOwner = chatFocusOwner;
        this.windowResolver = windowResolver;
    }

    /**
     * Installs a focus guard if the current focus owner is inside the chat tool window.
     * Returns {@code null} if the chat is not focused or installation failed — in which
     * case no guarding is performed and {@link #uninstall()} need not be called.
     *
     * <p>This call blocks on the EDT briefly (up to 100ms) to capture the current focus
     * owner. If not called from the EDT, it posts to {@code invokeAndWait} semantics via
     * a latch — safe from any thread.
     */
    @Nullable
    static FocusGuard install(Project project) {
        AtomicReference<FocusGuard> ref = new AtomicReference<>();
        Runnable capture = () -> {
            try {
                if (project.isDisposed()) return;
                KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                Component owner = kfm.getFocusOwner();
                if (owner == null) return;
                if (!isInsideChatToolWindow(project, owner)) return;
                FocusGuard guard = new FocusGuard(project, kfm, owner);
                kfm.addVetoableChangeListener("focusOwner", guard);
                ref.set(guard);
            } catch (Exception e) {
                LOG.debug("FocusGuard install failed", e);
            }
        };

        var app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            capture.run();
        } else {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            app.invokeLater(() -> {
                try {
                    capture.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                //noinspection ResultOfMethodCallIgnored — timeout is best-effort; guard will still install if EDT is slow
                latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ref.get();
    }

    /**
     * Removes the focus guard. Safe to call multiple times and from any thread.
     *
     * <p>When called from a background thread (the usual case — from {@code callTool}'s
     * finally block), the actual removal is posted to the EDT via {@code invokeLater} and
     * the calling thread blocks until it completes.  This ensures that any
     * {@code invokeLater} callbacks enqueued <em>during</em> tool execution (e.g.
     * {@code followFileIfEnabled}'s deferred {@code navigate(false)}) are processed
     * while the guard is still active.  Without this, the eager {@code uninstalled = true}
     * flag would disable the guard before the EDT-queued focus-stealing operations fire,
     * leaving a window where {@code navigate(false)} can steal focus with no protection.
     */
    void uninstall() {
        Runnable remove = () -> {
            if (uninstalled) return;
            uninstalled = true;
            try {
                kfm.removeVetoableChangeListener("focusOwner", this);
            } catch (Exception e) {
                LOG.debug("FocusGuard uninstall failed", e);
            }
        };
        var app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            remove.run();
        } else {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            app.invokeLater(() -> {
                try {
                    remove.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                boolean removedOnEdt = latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!removedOnEdt) {
                    LOG.debug("FocusGuard EDT removal timed out; will run when EDT catches up");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Waiting is best-effort only; the removal is already queued on the EDT.
                LOG.debug("FocusGuard EDT removal wait interrupted; queued removal will finish on the EDT");
            }
        }
    }

    @Override
    public void vetoableChange(PropertyChangeEvent evt) throws java.beans.PropertyVetoException {
        if (uninstalled) return;
        if (project.isDisposed()) {
            uninstall();
            return;
        }

        Object newOwner = evt.getNewValue();
        if (!(newOwner instanceof Component newComp)) return;
        if (newComp == chatFocusOwner) return;

        // Allow focus changes inside the chat tool window (e.g. between JCEF and prompt,
        // or between chat-side components).
        if (isInsideChatToolWindow(project, newComp)) return;

        // Only veto focus changes within the IDE main frame. Focus changes to dialog
        // windows, popups, completion lookups, and other separate windows are allowed
        // — they are legitimate IDE UI that the user or platform needs to interact with.
        Window chatWindow = windowResolver.apply(chatFocusOwner);
        Window targetWindow = windowResolver.apply(newComp);
        if (chatWindow == null || targetWindow == null || targetWindow != chatWindow) return;

        // Respect user-initiated focus moves: a click or keystroke that transferred focus
        // should not be fought by the guard. Programmatic focus changes (from navigate(),
        // openFile(), tw.show(), etc.) are dispatched outside a user InputEvent.
        AWTEvent current = java.awt.EventQueue.getCurrentEvent();
        if (current instanceof InputEvent) return;

        // Also respect focus changes triggered while a user input event is being
        // processed on the IDE event queue (InputEvent may not be the "current" AWT
        // event if we're in a nested dispatch).
        try {
            AWTEvent ideEvent = com.intellij.ide.IdeEventQueue.getInstance().getTrueCurrentEvent();
            if (ideEvent instanceof InputEvent) return;
        } catch (Throwable ignored) {
            // IdeEventQueue unavailable in some test contexts — rely on EventQueue check above.
        }

        // Circuit breaker: stop vetoing after MAX_VETOES to prevent runaway storms.
        // Inline the removal here (we're on EDT in this callback) rather than calling
        // uninstall() which requires ApplicationManager, unavailable in some test contexts.
        if (vetoCount.incrementAndGet() > MAX_VETOES) {
            LOG.warn("FocusGuard circuit breaker: exceeded " + MAX_VETOES + " vetoes, disabling guard");
            uninstalled = true;
            try {
                kfm.removeVetoableChangeListener("focusOwner", this);
            } catch (Exception e) {
                LOG.debug("FocusGuard circuit breaker removal failed", e);
            }
            return;
        }

        // Veto the programmatic focus change — focus stays in the chat component.
        throw new java.beans.PropertyVetoException(
            "FocusGuard: vetoing programmatic focus steal from chat", evt);
    }

    static boolean isInsideChatToolWindow(Project project, Component comp) {
        try {
            var twm = ToolWindowManager.getInstance(project);
            var tw = twm.getToolWindow("AgentBridge");
            if (tw == null) return false;
            JComponent twRoot = tw.getComponent();
            // tw.getComponent() is @NotNull but we keep the defensive check — runtime ≠ compile-time.
            //noinspection ConstantValue
            if (twRoot == null) return false;
            if (SwingUtilities.isDescendingFrom(comp, twRoot)) return true;

            // JCEF browser components live in a separate heavyweight window; fall back to
            // comparing the root window of the focus owner with the tool window's window.
            Window ownerWindow = SwingUtilities.getWindowAncestor(comp);
            Window twWindow = SwingUtilities.getWindowAncestor(twRoot);
            return ownerWindow != null && ownerWindow == twWindow
                && SwingUtilities.isDescendingFrom(twRoot, ownerWindow);
        } catch (Exception e) {
            return false;
        }
    }
}
