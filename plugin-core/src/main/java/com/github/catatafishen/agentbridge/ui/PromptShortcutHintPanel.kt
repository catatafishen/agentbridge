package com.github.catatafishen.agentbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Footer panel showing keyboard shortcut hints below the chat prompt.
 * Includes a close button that dismisses the panel and persists the
 * preference via [com.github.catatafishen.agentbridge.settings.ChatInputSettings].
 *
 * Reads actual key bindings from the IntelliJ keymap so customized
 * shortcuts are reflected in the displayed hints.
 */
class PromptShortcutHintPanel(private val onClose: () -> Unit) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val sendLabel: JBLabel

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 4, 4)

        val inner = JPanel()
        inner.isOpaque = false
        inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)

        val row1 = createRow()
        sendLabel = addShortcutEntry(
            row1,
            PromptShortcutAction.SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "send"
        )
        addDot(row1)
        addShortcutEntry(
            row1,
            PromptShortcutAction.NEW_LINE_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "new line"
        )

        val row2 = createRow()
        addShortcutEntry(
            row2,
            PromptShortcutAction.STOP_AND_SEND_ID,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "stop && send"
        )
        addDot(row2)
        addShortcutEntry(
            row2,
            PromptShortcutAction.QUEUE_ID,
            KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ),
            "queue"
        )

        inner.add(row1)
        inner.add(Box.createVerticalStrut(JBUI.scale(2)))
        inner.add(row2)

        add(inner, BorderLayout.CENTER)

        val closeLabel = JBLabel(AllIcons.Actions.Close).apply {
            toolTipText = "Hide shortcut hints"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 4, 0, 2)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onClose()
                }
            })
        }
        val closeWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
        closeWrapper.isOpaque = false
        closeWrapper.add(closeLabel)
        add(closeWrapper, BorderLayout.EAST)
    }

    /** Toggle between "send" and "nudge" label when the agent is working. */
    fun setNudgeMode(nudge: Boolean) {
        sendLabel.text = if (nudge) "nudge" else "send"
    }

    private fun createRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0))
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
        val keyText = KeymapUtil.getKeystrokeText(stroke)
        row.add(KeyBadge(keyText))
        val lbl = JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
        }
        row.add(lbl)
        return lbl
    }

    private fun addDot(row: JPanel) {
        row.add(JBLabel("\u00B7").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 2)
        })
    }
}
