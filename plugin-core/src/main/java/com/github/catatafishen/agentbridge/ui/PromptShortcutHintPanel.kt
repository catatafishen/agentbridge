package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Footer panel showing keyboard shortcut hints below the chat prompt.
 * Dismisses on hover (and focus); re-appears when the input is empty and idle.
 * The shortcut hints feature can be disabled in Settings → AgentBridge → Chat Input.
 *
 * Reads actual key bindings from the IntelliJ keymap so customized
 * shortcuts are reflected in the displayed hints.
 */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val sendLabel: JBLabel

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 4, 8)

        val inner = JPanel()
        inner.isOpaque = false
        inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)

        val row1 = createRow()
        sendLabel = addShortcutEntry(
            row1,
            PromptShortcutAction.SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "Send"
        )
        addVerticalSeparator(row1)
        addShortcutEntry(
            row1,
            PromptShortcutAction.NEW_LINE_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "New Line"
        )

        val row2 = createRow()
        addShortcutEntry(
            row2,
            PromptShortcutAction.STOP_AND_SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "Stop && Send"
        )
        addVerticalSeparator(row2)
        addShortcutEntry(
            row2,
            PromptShortcutAction.QUEUE_ID,
            KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ),
            "Queue"
        )

        inner.add(row1)
        inner.add(Box.createVerticalStrut(JBUI.scale(6)))
        inner.add(row2)

        add(inner, BorderLayout.CENTER)
    }

    /** Toggle between "send" and "nudge" label when the agent is working. */
    fun setNudgeMode(nudge: Boolean) {
        sendLabel.text = if (nudge) "Nudge" else "Send"
    }

    private fun createRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0))
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT
        return row
    }

    private fun addShortcutEntry(
        row: JPanel,
        actionId: String,
        fallbackStroke: KeyStroke,
        label: String
    ): JBLabel {
        val stroke = PromptShortcutAction.resolveKeystroke(actionId, fallbackStroke)
        KeyBadge.keystrokeTokens(stroke).forEach { row.add(KeyBadge(it)) }
        val lbl = JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
        }
        row.add(lbl)
        return lbl
    }

    private fun addVerticalSeparator(row: JPanel) {
        val line = JPanel().apply {
            isOpaque = true
            background = JBColor.namedColor("Separator.foreground", JBColor(0xcdcdcd, 0x3d3d3d))
            preferredSize = Dimension(JBUI.scale(1), JBUI.scale(11))
        }
        // Wrap in an empty-bordered panel to add symmetric horizontal margins.
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, JBUI.scale(6))
            add(line, BorderLayout.CENTER)
        }
        row.add(wrapper)
    }
}
