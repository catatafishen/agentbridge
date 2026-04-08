package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.KeyStroke

/**
 * Compact hint bar below the prompt input that shows keyboard shortcuts
 * using JetBrains-styled key badges with OS-native keystroke text.
 *
 * Updates dynamically: "send" ↔ "nudge" when the agent is working.
 * Includes a settings link to open the UI/UX configuration page.
 */
class PromptShortcutHintPanel(private val project: Project) :
    com.intellij.ui.components.JBPanel<com.intellij.ui.components.JBPanel<*>>(
        FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)
    ) {

    private val sendLabel: JBLabel

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6, 2, 4)

        val enterStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnterStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        val ctrlEnterStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
        val ctrlShiftEnterStroke = KeyStroke.getKeyStroke(
            KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
        )

        sendLabel = addShortcutEntry(enterStroke, "send")
        addDot()
        addShortcutEntry(shiftEnterStroke, "new line")
        addDot()
        addShortcutEntry(ctrlEnterStroke, "stop && send")
        addDot()
        addShortcutEntry(ctrlShiftEnterStroke, "queue")

        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(createSettingsLink())
    }

    fun setNudgeMode(nudge: Boolean) {
        sendLabel.text = if (nudge) "nudge" else "send"
    }

    private fun addShortcutEntry(keystroke: KeyStroke, label: String): JBLabel {
        val keyText = KeymapUtil.getKeystrokeText(keystroke)
        add(KeyBadge(keyText))
        val lbl = JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
        }
        add(lbl)
        return lbl
    }

    private fun addDot() {
        add(JBLabel("\u00B7").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 2)
        })
    }

    private fun createSettingsLink(): Component {
        return LinkLabel<Any?>("Customize\u2026", null) { _, _ ->
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "com.github.catatafishen.agentbridge.chatInput")
        }.apply {
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.empty(0, 2)
        }
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
