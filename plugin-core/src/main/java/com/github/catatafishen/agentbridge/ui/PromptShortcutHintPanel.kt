package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Footer panel showing keyboard shortcut hints below the chat prompt.
 * Dismisses on hover (and focus); re-appears when the input is empty and idle.
 * The shortcut hints feature can be disabled in Settings → AgentBridge → Chat Input.
 *
 * Reads actual key bindings from the IntelliJ keymap so customized
 * shortcuts are reflected in the displayed hints.
 *
 * The four hints are arranged in a 2×2 grid so items in the same column
 * are vertically aligned across rows.
 */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val sendLabel: JBLabel

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 4, 8)

        // 2×2 grid — equal-width columns keep entries vertically aligned.
        val grid = JPanel(GridLayout(2, 2, JBUI.scale(16), JBUI.scale(6)))
        grid.isOpaque = false

        sendLabel = addShortcutEntry(
            grid,
            PromptShortcutAction.SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "Send"
        )
        addShortcutEntry(
            grid,
            PromptShortcutAction.NEW_LINE_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "New Line"
        )
        addShortcutEntry(
            grid,
            PromptShortcutAction.STOP_AND_SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "Stop && Send"
        )
        addShortcutEntry(
            grid,
            PromptShortcutAction.QUEUE_ID,
            KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ),
            "Queue"
        )

        add(grid, BorderLayout.CENTER)
    }

    /** Toggle between "send" and "nudge" label when the agent is working. */
    fun setNudgeMode(nudge: Boolean) {
        sendLabel.text = if (nudge) "Nudge" else "Send"
    }

    private fun addShortcutEntry(
        grid: JPanel,
        actionId: String,
        fallbackStroke: KeyStroke,
        label: String
    ): JBLabel {
        val cell = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0))
        cell.isOpaque = false
        val stroke = PromptShortcutAction.resolveKeystroke(actionId, fallbackStroke)
        KeyBadge.keystrokeTokens(stroke).forEach { cell.add(KeyBadge(it)) }
        val lbl = JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
        }
        cell.add(lbl)
        grid.add(cell)
        return lbl
    }
}
