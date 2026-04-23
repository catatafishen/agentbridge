package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Bottom-toolbar panel that lists the keyboard shortcuts relevant to the
 * current chat-input state, left-aligned alongside the model selector and
 * Send button.
 *
 *  - Idle  → Enter ▸ Send,  Shift+Enter ▸ New line
 *  - Busy  → Enter ▸ Nudge,  Ctrl+Enter ▸ Stop && send,  Ctrl+Shift+Enter ▸ Queue,  Shift+Enter ▸ New line
 *  - When a nudge or queued message is pending, an extra `↑ ▸ Edit last` hint
 *    is appended.
 *
 * Each entry renders as one or more [KeyBadge]s followed by a small action
 * label. Reads actual key bindings from the IntelliJ keymap so customized
 * shortcuts are reflected.
 *
 * Visibility honours the *Show shortcut hints* toggle in
 * Settings → AgentBridge → Chat Input.
 */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 4, 0, 4)
    }

    /**
     * Replace the displayed hints with the given keystroke/label pairs. The
     * order of [shortcuts] is preserved, rendered left-to-right.
     */
    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        removeAll()
        for ((stroke, label) in shortcuts) {
            add(buildEntry(stroke, label))
        }
        revalidate()
        repaint()
    }

    private fun buildEntry(stroke: KeyStroke, label: String): JComponent {
        val cell = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        KeyBadge.keystrokeTokens(stroke).forEach { cell.add(KeyBadge(it)) }
        val text = JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(2)
        }
        cell.add(text)
        return cell
    }
}
