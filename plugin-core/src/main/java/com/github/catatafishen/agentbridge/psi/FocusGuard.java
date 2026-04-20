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
import java.beans.PropertyChangeListener;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><b>The fix.</b> While a tool is running AND chat was focused at tool start, this
 * guard listens for {@code focusOwner} changes. When focus moves away to a component
 * that is NOT inside the chat tool window AND the change was not triggered by a user
 * input event (mouse click / key press), the guard synchronously requests focus back
 * to the originally focused chat component. Because the reclaim happens inside the
 * property-change dispatch — before any {@link java.awt.event.KeyEvent} is delivered
 * to the new component — typed characters can never leak into the editor.
 *
 * <p><b>Reclaim fires at most once per tool call.</b> If {@code requestFocusInWindow()}
 * on the JCEF component resolves to a component that is not exactly {@code chatFocusOwner}
 * (possible when JCEF routes focus to a parent or sibling component internally), a second
 * invocation of this listener would also pass the checks and fire another reclaim,
 * creating a focus ping-pong that starves the EDT of mouse/paint events and freezes the
 * JCEF panel. The {@code hasReclaimed} flag ensures we call {@code requestFocusInWindow()}
 * at most once — subsequent focus changes are left to the existing 150ms restore alarm.
 *
 * <p><b>User-initiated focus changes are respected.</b> The guard inspects the AWT
 * event currently being dispatched; if it is a {@link InputEvent} (user click or
 * keystroke) the focus change is allowed through. This preserves the ability to
 * click away to an editor during a long-running tool (e.g. build).
 *
 * <p>Install via {@link #install(Project)} on the EDT; call {@link #uninstall()} in
 * the tool-call finally block. Both methods are idempotent and safe to call from any
 * thread — they self-dispatch to the EDT.
 */
final class FocusGuard implements PropertyChangeListener {
    private static final Logger LOG = Logger.getInstance(FocusGuard.class);

    private final Project project;
    private final KeyboardFocusManager kfm;
    private final Component chatFocusOwner;
    /**
     * Package-private so tests can simulate the uninstalled state without requiring the IDE platform.
     */
    volatile boolean uninstalled;
    /**
     * Ensures {@code requestFocusInWindow()} is called at most once per tool call.
     * Without this, a failed or mis-routed reclaim produces a new focus event that
     * passes all the same checks, firing another reclaim and creating a focus storm
     * that starves the EDT and freezes the JCEF panel.
     */
    private final java.util.concurrent.atomic.AtomicBoolean hasReclaimed =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Package-private for tests; use {@link #install(Project)} in production code.
     */
    FocusGuard(Project project, KeyboardFocusManager kfm, Component chatFocusOwner) {
        this.project = project;
        this.kfm = kfm;
        this.chatFocusOwner = chatFocusOwner;
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
                kfm.addPropertyChangeListener("focusOwner", guard);
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
                kfm.removePropertyChangeListener("focusOwner", this);
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
                //noinspection ResultOfMethodCallIgnored — timeout means EDT is busy; removal will happen when EDT catches up
                latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Timeout or interruption — remove immediately to avoid a leaked listener.
                remove.run();
            }
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
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

        // Only reclaim once per tool call. If requestFocusInWindow() routes focus to
        // a component that is neither chatFocusOwner nor inside the chat TW (possible
        // with JCEF's internal focus delegation), a second invocation would also pass
        // the checks above and call requestFocusInWindow() again, creating a focus
        // ping-pong that freezes the JCEF panel. Let the 150ms alarm handle anything
        // the single reclaim doesn't fully resolve.
        if (!hasReclaimed.compareAndSet(false, true)) return;

        // Programmatic focus steal detected — synchronously reclaim focus for the chat.
        try {
            chatFocusOwner.requestFocusInWindow();
        } catch (Exception e) {
            LOG.debug("FocusGuard requestFocus failed", e);
        }
    }

    private static boolean isInsideChatToolWindow(Project project, Component comp) {
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
