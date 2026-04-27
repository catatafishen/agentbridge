package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.vcsUtil.showAbove
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Cheat-sheet popup listing all chat prompt keyboard shortcuts.
 * Shown when the user presses Ctrl+/ in the prompt — the same pattern
 * used by Slack, GitHub, and VS Code.
 */
object ShortcutCheatSheetPopup {

    private data class ShortcutRow(
        val actionId: String,
        val fallback: KeyStroke,
        val description: String,
    )

    private val ROWS = listOf(
        ShortcutRow(
            PromptShortcutAction.SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "Send prompt (or nudge agent)"
        ),
        ShortcutRow(
            PromptShortcutAction.NEW_LINE_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "Insert new line"
        ),
        ShortcutRow(
            PromptShortcutAction.STOP_AND_SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "Stop agent and send"
        ),
        ShortcutRow(
            PromptShortcutAction.QUEUE_ID,
            KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ),
            "Queue (send after agent finishes)"
        ),
        ShortcutRow(
            PromptShortcutAction.SHOW_SHORTCUTS_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.CTRL_DOWN_MASK),
            "Show this popup"
        ),
    )

    fun show(owner: JComponent) {
        val panel = buildPanel()
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("Chat shortcuts")
            .setFocusable(false)
            .setMovable(true)
            .setResizable(false)
            .createPopup()
            .showAbove(owner)
    }

    private fun buildPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(null)
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(4, 8, 8, 16)

        for ((i, row) in ROWS.withIndex()) {
            panel.add(buildRow(row))
            if (i < ROWS.size - 1) {
                panel.add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }
        return panel
    }

    private fun buildRow(row: ShortcutRow): JPanel {
        val stroke = PromptShortcutAction.resolveKeystroke(row.actionId, row.fallback)
        val keyText = KeyBadge.formatKeystroke(stroke)

        val p = JPanel(BorderLayout(JBUI.scale(12), 0))
        p.isOpaque = false
        p.maximumSize = Dimension(Int.MAX_VALUE, p.preferredSize.height)
        p.alignmentX = Component.LEFT_ALIGNMENT
        p.add(KeyBadge(keyText), BorderLayout.WEST)
        p.add(
            JBLabel(row.description).apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelForeground()
            },
            BorderLayout.CENTER
        )
        return p
    }
}
