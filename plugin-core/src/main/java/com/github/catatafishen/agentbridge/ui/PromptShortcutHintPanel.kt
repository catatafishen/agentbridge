package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Transparent overlay that shows keyboard shortcut hints centered inside
 * the prompt editor. Mouse-transparent so all events pass through to the
 * editor underneath.
 *
 * Reads actual key bindings from the IntelliJ keymap so customized
 * shortcuts are reflected in the displayed hints.
 *
 * Visibility is managed by [ChatToolWindowContent]: shown when the editor
 * is empty, hidden once the user types.
 */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>(GridBagLayout()) {

    private val sendLabel: JBLabel

    init {
        isOpaque = false

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

        add(inner)
    }

    /** Toggle between "send" and "nudge" label when the agent is working. */
    fun setNudgeMode(nudge: Boolean) {
        sendLabel.text = if (nudge) "nudge" else "send"
    }

    /** All mouse events pass through to the editor underneath. */
    override fun contains(x: Int, y: Int): Boolean = false

    private fun createRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0))
        row.isOpaque = false
        row.alignmentX = Component.CENTER_ALIGNMENT
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

    /**
     * Small label rendered with a rounded-rect border and subtle background,
     * mimicking a physical keyboard key cap.
     */
    private class KeyBadge(text: String) : JBLabel(text) {

        init {
            font = JBUI.Fonts.miniFont()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(1, 4, 1, 4)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(4)
            g2.color = KEY_BACKGROUND
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = KEY_BORDER
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            super.paintComponent(g)
        }

        companion object {
            private val KEY_BACKGROUND = JBColor(0xF0F0F0, 0x3C3F41)
            private val KEY_BORDER = JBColor(0xC8C8C8, 0x5E6060)
        }
    }
}
