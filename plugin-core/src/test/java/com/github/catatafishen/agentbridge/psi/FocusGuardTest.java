package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FocusGuard}, focusing on the pure logic of {@link FocusGuard#vetoableChange}.
 *
 * <p><b>What these tests protect against:</b> programmatic focus steals from the chat prompt
 * during MCP tool execution. The guard uses a {@link java.beans.VetoableChangeListener} to
 * <em>prevent</em> focus changes from happening (by throwing {@link java.beans.PropertyVetoException})
 * rather than reactively reclaiming focus after the change. This eliminates transient focus loss
 * and the ping-pong storms that plagued the previous {@code PropertyChangeListener} approach.
 *
 * <p>Tests run without the IntelliJ platform. IDE-dependent paths ({@code ToolWindowManager},
 * {@code IdeEventQueue}) throw in the test context and are caught by existing try-catches,
 * making {@code isInsideChatToolWindow} always return {@code false} — accurately modelling
 * focus moving to a component outside the chat tool window, which is the scenario that triggers
 * a veto.
 */
class FocusGuardTest {

    private Project project;
    private FocusGuard guard;
    private Component chatOwner;
    private JPanel outsideSameWindowTarget;
    private JPanel differentWindowTarget;
    private Window mainWindow;
    private Window dialogWindow;

    @BeforeEach
    void setUp() {
        project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        chatOwner = mock(Component.class);
        outsideSameWindowTarget = new JPanel();
        differentWindowTarget = new JPanel();
        mainWindow = mock(Window.class);
        dialogWindow = mock(Window.class);

        Map<Component, Window> windows = new HashMap<>();
        windows.put(chatOwner, mainWindow);
        windows.put(outsideSameWindowTarget, mainWindow);
        windows.put(differentWindowTarget, dialogWindow);

        guard = new FocusGuard(
            project,
            KeyboardFocusManager.getCurrentKeyboardFocusManager(),
            chatOwner,
            windows::get
        );
    }

    /**
     * A focus event where focus moves from chatOwner to a JPanel outside the chat TW
     * but still inside the same IDE main window.
     */
    private PropertyChangeEvent outsideEvent() {
        return new PropertyChangeEvent(new Object(), "focusOwner", chatOwner, outsideSameWindowTarget);
    }

    // ── Core veto behaviour ──────────────────────────────────────────────────────────────────────

    @Test
    void vetoesProgrammaticFocusSteal() {
        assertThrows(java.beans.PropertyVetoException.class, () ->
            guard.vetoableChange(outsideEvent()));
    }

    @Test
    void vetoesEveryProgrammaticSteal_noOneShotLimit() {
        // Unlike the old PropertyChangeListener approach, the VetoableChangeListener
        // vetoes EVERY programmatic focus steal — there is no one-shot hasReclaimed flag.
        // This is safe because veto prevents focus from moving, so no ping-pong is possible.
        assertThrows(java.beans.PropertyVetoException.class, () ->
            guard.vetoableChange(outsideEvent()));
        assertThrows(java.beans.PropertyVetoException.class, () ->
            guard.vetoableChange(outsideEvent()));
        assertThrows(java.beans.PropertyVetoException.class, () ->
            guard.vetoableChange(outsideEvent()));
    }

    // ── Circuit breaker ──────────────────────────────────────────────────────────────────────────

    @Test
    void circuitBreakerDisablesAfterMaxVetoes() throws java.beans.PropertyVetoException {
        // Fire MAX_VETOES (20) vetoes — all should throw
        for (int i = 0; i < 20; i++) {
            assertThrows(java.beans.PropertyVetoException.class, () ->
                guard.vetoableChange(outsideEvent()));
        }
        // The 21st should NOT throw — circuit breaker tripped, guard is uninstalled
        guard.vetoableChange(outsideEvent());
        assertTrue(guard.uninstalled, "Guard should be uninstalled after circuit breaker trips");
    }

    // ── Pass-through cases (no veto expected) ────────────────────────────────────────────────────

    @Nested
    class NoVeto {

        @Test
        void whenNewOwnerIsChatComponent() throws java.beans.PropertyVetoException {
            // Focus returns to the guarded component — no steal, no veto.
            PropertyChangeEvent evt = new PropertyChangeEvent(new Object(), "focusOwner", null, chatOwner);
            guard.vetoableChange(evt);
            // No exception = pass-through
        }

        @Test
        void whenNewOwnerIsNull() throws java.beans.PropertyVetoException {
            // Null focus (e.g., window deactivated) — not instanceof Component, ignored.
            PropertyChangeEvent evt = new PropertyChangeEvent(new Object(), "focusOwner", chatOwner, null);
            guard.vetoableChange(evt);
        }

        @Test
        void whenGuardIsUninstalled() throws java.beans.PropertyVetoException {
            // After uninstall the guard is logically off — any lingering focus events must be no-ops.
            guard.uninstalled = true;
            guard.vetoableChange(outsideEvent());
        }

        @Test
        void whenTargetIsInDifferentWindow() throws java.beans.PropertyVetoException {
            // Focus to a component in a different Window (dialog, popup) — allowed through.
            PropertyChangeEvent evt = new PropertyChangeEvent(
                new Object(), "focusOwner", chatOwner, differentWindowTarget);
            guard.vetoableChange(evt);
        }
    }
}
