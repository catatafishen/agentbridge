package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * Displays keyboard shortcut hints in a horizontal row with automatic overflow support.
 *
 * When hints don't fit in the available width, a native `>>` overflow button appears
 * (ActionToolbar NOWRAP_LAYOUT_POLICY). Clicking it shows the clipped hints in a popup.
 *
 * Minimum width is 0 so the GridBag parent shrinks this cell before the model/send group.
 */
class PromptShortcutHintPanel : JBPanel<JBPanel<*>>() {

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty()
    }

    /** Width=0 allows GridBag to shrink this cell first; height preserves row stability. */
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    /**
     * Replace the displayed hints with the given keystroke/label pairs.
     * ActionToolbar handles overflow automatically via its native `>>` button.
     */
    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        removeAll()
        if (shortcuts.isNotEmpty()) {
            val group = DefaultActionGroup(shortcuts.map { (stroke, label) -> ShortcutHintAction(stroke, label) })
            val toolbar = ActionManager.getInstance()
                .createActionToolbar("AgentPromptShortcuts", group, true).also { tb ->
                    tb.setLayoutStrategy(ToolbarLayoutStrategy.NOWRAP_STRATEGY)
                    tb.isReservePlaceAutoPopupIcon = false
                    tb.component.isOpaque = false
                    tb.component.border = JBUI.Borders.empty()
                    tb.targetComponent = this
                }
            add(toolbar.component, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private class ShortcutHintAction(
        private val stroke: KeyStroke,
        private val label: String,
    ) : AnAction(), CustomComponentAction, DumbAware {

        init {
            templatePresentation.text = "${KeyBadge.formatKeystroke(stroke)} $label"
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = true
        }

        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val cell = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyRight(JBUI.scale(8))
                alignmentY = CENTER_ALIGNMENT
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
}
