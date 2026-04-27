package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Displays keyboard shortcut hints in a horizontal, non-wrapping row. */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>(GridBagLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        alignmentY = CENTER_ALIGNMENT
    }

    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        removeAll()
        shortcuts.forEachIndexed { index, (stroke, label) ->
            add(shortcutCell(stroke, label), shortcutConstraints(index, index == shortcuts.lastIndex))
        }
        revalidate()
        repaint()
    }

    private fun shortcutConstraints(index: Int, isLast: Boolean): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = index
            gridy = 0
            anchor = GridBagConstraints.CENTER
            insets = if (isLast) JBUI.emptyInsets() else JBUI.insetsRight(JBUI.scale(8))
        }

    private fun shortcutCell(stroke: KeyStroke, label: String): JPanel {
        val cell = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        KeyBadge.keystrokeTokens(stroke).forEach { cell.add(KeyBadge(it)) }
        cell.add(JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(2)
        })
        return cell
    }
}
