package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

/**
 * Utility for dispatching operations to the EDT.
 * <p>
 * Tool handlers that need VFS/PSI access should ideally perform those reads on a
 * background thread (e.g. via ReadAction) and only hop to EDT for actual UI mutations.
 */
public final class EdtUtil {

    private EdtUtil() {
    }

    /**
     * Dispatch a runnable to the EDT, using {@code ModalityState.any()} so that it
     * executes even when a modal dialog is open. This is essential for MCP tool
     * operations which must not stall behind user-facing dialogs.
     */
    public static void invokeLater(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any());
    }

    /**
     * Block the calling thread until the runnable completes on the EDT.
     * Uses {@code ModalityState.any()} so that it executes even when a modal
     * dialog is open — preventing tool operations from deadlocking.
     */
    public static void invokeAndWait(Runnable runnable) {
        ApplicationManager.getApplication().invokeAndWait(runnable, ModalityState.any());
    }
}
