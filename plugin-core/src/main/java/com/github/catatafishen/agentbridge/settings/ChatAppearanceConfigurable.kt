package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.ui.NativeChatPanel
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.github.catatafishen.agentbridge.ui.side.ToolCallsWebPanel
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent

/**
 * Chat bubble appearance: user accent color and bubble layout style
 * (modern / minimal / accessible).
 *
 * Lives under UI/UX because these settings affect the chat presentation,
 * not any MCP-specific behavior. Agent bubble colors remain per-client in
 * each agent's own settings page.
 */
class ChatAppearanceConfigurable(private val project: Project) :
    BoundConfigurable("Appearance"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.ui-ux.appearance"

    private var userColorCombo: ThemeColorComboBox? = null
    private var styleCombo: ComboBox<String>? = null

    override fun createPanel() = panel {
        row {
            cell(buildContent())
                .align(AlignX.FILL).align(AlignY.TOP).resizableColumn()
        }.layout(RowLayout.PARENT_GRID)
        onIsModified { computeIsModified() }
        onApply { applySettings() }
        onReset { resetFromSettings() }
    }

    private fun buildContent(): JComponent {
        val settings = McpServerSettings.getInstance(project)
        val root = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        root.add(TitledSeparator("Bubble Colors").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })

        root.add(
            JBLabel(
                "<html>Customize the accent color for user message bubbles and choose " +
                    "a layout style. Agent bubble colors are set per-client in each " +
                    "agent's settings page.</html>"
            ).apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(10)
                alignmentX = Component.LEFT_ALIGNMENT
                isAllowAutoWrapping = true
            })

        userColorCombo = ThemeColorComboBox(ThemeColor.BLUE).also {
            it.selectedThemeColor = ThemeColor.fromKey(settings.userBubbleColorKey)
        }
        root.add(labeledRow("User bubble accent", userColorCombo!!))

        styleCombo = ComboBox(arrayOf("modern", "minimal", "accessible")).also {
            it.selectedItem = settings.bubbleStyle
        }
        root.add(labeledRow("Bubble style", styleCombo!!))

        root.add(Box.createVerticalGlue())
        return root
    }

    private fun labeledRow(label: String, combo: JComponent): JComponent {
        val row = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val lbl = JBLabel(label).apply {
            preferredSize = Dimension(JBUI.scale(140), preferredSize.height)
            maximumSize = Dimension(JBUI.scale(140), preferredSize.height)
        }
        row.add(lbl)
        combo.maximumSize = Dimension(JBUI.scale(180), combo.preferredSize.height)
        row.add(combo)
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun keyOf(combo: ThemeColorComboBox): String? = combo.selectedThemeColor?.name

    private fun computeIsModified(): Boolean {
        val settings = McpServerSettings.getInstance(project)
        return userColorCombo != null && keyOf(userColorCombo!!) != settings.userBubbleColorKey ||
            styleCombo != null && styleCombo!!.selectedItem != settings.bubbleStyle
    }

    private fun applySettings() {
        val settings = McpServerSettings.getInstance(project)
        userColorCombo?.let { settings.userBubbleColorKey = keyOf(it) }
        styleCombo?.let { settings.bubbleStyle = it.selectedItem as? String ?: "modern" }
        NativeChatPanel.getInstance(project)?.refreshUserBubbleColors()
        ToolCallsWebPanel.refreshForProject(project)
    }

    private fun resetFromSettings() {
        val settings = McpServerSettings.getInstance(project)
        userColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.userBubbleColorKey)
        styleCombo?.selectedItem = settings.bubbleStyle
    }

    override fun disposeUIResources() {
        super<BoundConfigurable>.disposeUIResources()
        userColorCombo = null
        styleCombo = null
    }
}
