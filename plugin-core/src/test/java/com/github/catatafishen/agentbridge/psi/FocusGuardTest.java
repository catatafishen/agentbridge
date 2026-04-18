package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.beans.PropertyChangeEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FocusGuard}, focusing on the pure logic of {@link FocusGuard#propertyChange}.
 *
 * <p><b>What these tests protect against:</b> the focus ping-pong storm that froze the JCEF panel.
 * When {@code requestFocusInWindow()} routes focus to an intermediate component that is not exactly
 * {@code chatFocusOwner}, the resulting focus event passes all guards and would trigger a second
 * {@code requestFocusInWindow()} call — an infinite feedback loop that saturates the EDT.
 * The {@code hasReclaimed} flag cuts this loop. These tests verify that invariant cannot regress.
 *
 * <p>Tests run without the IntelliJ platform. IDE-dependent paths ({@code ToolWindowManager},
 * {@code IdeEventQueue}) throw in the test context and are caught by existing try-catches,
 * making {@code isInsideChatToolWindow} always return {@code false} — accurately modelling
 * focus moving to a component outside the chat tool window, which is the scenario that triggers
 * a reclaim.
 */
class FocusGuardTest {

    private Project project;
    private FocusGuard guard;
    private Component chatOwner;

    @BeforeEach
    void setUp() {
        project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        chatOwner = mock(Component.class);
        // KFM is passed to constructor only for uninstall(); tests don't call uninstall() so
        // the real KFM is fine — we just need a non-null reference.
        guard = new FocusGuard(project, KeyboardFocusManager.getCurrentKeyboardFocusManager(), chatOwner);
    }

    /**
     * A focus event where focus moves from chatOwner to a JPanel outside the chat TW.
     */
    private PropertyChangeEvent outsideEvent() {
        return new PropertyChangeEvent(new Object(), "focusOwner", chatOwner, new JPanel());
    }

    // ── Core reclaim behaviour ────────────────────────────────────────────────────────────────────

    @Test
    void reclaimsFocusOnFirstProgrammaticFocusSteal() {
        guard.propertyChange(outsideEvent());

        verify(chatOwner, times(1)).requestFocusInWindow();
    }

    @Test
    void reclaimsOnlyOnce_preventsStorm() {
        // REGRESSION GUARD: two consecutive programmatic focus steals MUST NOT call
        // requestFocusInWindow() twice. That would create a focus ping-pong (EDT storm)
        // that renders the JCEF panel completely unresponsive.
        guard.propertyChange(outsideEvent());
        guard.propertyChange(outsideEvent());

        verify(chatOwner, times(1)).requestFocusInWindow();
    }

    // ── Pass-through cases (no reclaim expected) ─────────────────────────────────────────────────

    @Nested
    class NoReclaim {

        @Test
        void whenNewOwnerIsChatComponent() {
            // Focus returns to the guarded component — no steal, no reclaim needed.
            PropertyChangeEvent evt = new PropertyChangeEvent(new Object(), "focusOwner", null, chatOwner);
            guard.propertyChange(evt);

            verify(chatOwner, never()).requestFocusInWindow();
        }

        @Test
        void whenNewOwnerIsNull() {
            // Null focus (e.g., window deactivated) — not instanceof Component, ignored.
            PropertyChangeEvent evt = new PropertyChangeEvent(new Object(), "focusOwner", chatOwner, null);
            guard.propertyChange(evt);

            verify(chatOwner, never()).requestFocusInWindow();
        }

        @Test
        void whenGuardIsUninstalled() {
            // After uninstall the guard is logically off — any lingering focus events must be no-ops.
            // Simulate the uninstalled state directly (calling uninstall() itself requires
            // ApplicationManager which is not available outside the platform).
            guard.uninstalled = true;

            guard.propertyChange(outsideEvent());

            verify(chatOwner, never()).requestFocusInWindow();
        }
    }
}
