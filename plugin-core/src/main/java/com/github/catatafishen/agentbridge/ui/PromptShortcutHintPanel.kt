package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

class PromptShortcutHintPanel : JBPanel<JBPanel<*>>() {

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()
    }

    /**
     * Allow the panel to shrink all the way to zero width so the GridBag cell
     * can reclaim space for the model-selector and send-button (right group) when
     * the tool-window is narrow. Height stays at the computed preferred value so
     * the bottom bar height doesn't collapse.
     */
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    /**
     * Replace the displayed hints with the given keystroke/label pairs. The
     * order of [shortcuts] is preserved, rendered left-to-right.
     *
     * A tooltip is set on the panel so hovering reveals all hints even when
     * the panel is too narrow to show them all (peek-on-hover for clipped hints).
     */
    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        removeAll()
        shortcuts.forEachIndexed { i, (stroke, label) ->
            if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(buildEntry(stroke, label))
        }
        toolTipText = if (shortcuts.isEmpty()) null
        else shortcuts.joinToString("  ·  ") { (stroke, label) ->
            "${KeyBadge.formatKeystroke(stroke)} $label"
        }
        revalidate()
        repaint()
    }

    private fun buildEntry(stroke: KeyStroke, label: String): JComponent {
        val cell = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            alignmentY = CENTER_ALIGNMENT
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
