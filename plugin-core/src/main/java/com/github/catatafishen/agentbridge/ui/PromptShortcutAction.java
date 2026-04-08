package com.github.catatafishen.agentbridge.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.KeymapManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Keymap placeholder for chat prompt keyboard shortcuts.
 * <p>
 * The actual behavior is registered locally on the prompt editor component
 * in {@link ChatToolWindowContent}. This action exists solely so the shortcuts
 * appear in <b>Settings → Keymap → AgentBridge Chat Prompt</b> and can be
 * customized by users.
 */
public final class PromptShortcutAction extends AnAction {

    public static final String SEND_ID = "AgentBridge.Prompt.Send";
    public static final String NEW_LINE_ID = "AgentBridge.Prompt.NewLine";
    public static final String STOP_AND_SEND_ID = "AgentBridge.Prompt.StopAndSend";
    public static final String QUEUE_ID = "AgentBridge.Prompt.Queue";

    /**
     * Returns the shortcut set for the given action ID from the active keymap,
     * falling back to a single-keystroke set if no keymap entry exists.
     */
    public static @NotNull CustomShortcutSet resolveShortcutSet(
            @NotNull String actionId, @NotNull KeyStroke fallback) {
        Shortcut[] shortcuts = KeymapManager.getInstance()
                .getActiveKeymap().getShortcuts(actionId);
        if (shortcuts.length > 0) {
            return new CustomShortcutSet(shortcuts);
        }
        return new CustomShortcutSet(fallback);
    }

    /**
     * Returns the first keyboard keystroke for the given action ID from the
     * active keymap, or the fallback if no keymap entry exists.
     */
    public static @NotNull KeyStroke resolveKeystroke(
            @NotNull String actionId, @NotNull KeyStroke fallback) {
        for (Shortcut shortcut : KeymapManager.getInstance()
                .getActiveKeymap().getShortcuts(actionId)) {
            if (shortcut instanceof KeyboardShortcut ks) {
                return ks.getFirstKeyStroke();
            }
        }
        return fallback;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // No-op: behavior is wired locally on the prompt editor component.
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Always disabled globally — the shortcut is registered locally by
        // ChatToolWindowContent on the prompt editor's content component.
        e.getPresentation().setEnabledAndVisible(false);
    }
}
