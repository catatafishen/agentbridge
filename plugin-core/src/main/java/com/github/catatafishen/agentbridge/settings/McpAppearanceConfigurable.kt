package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
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
 * Project-wide accent colors for tool kinds (read, search, edit, execute).
 *
 * These colors appear in the MCP → Tools list (kind dot per tool), the chat-view
 * tool chips, and permission combo tints. Extracted from [ToolsConfigurable] so
 * the Tools page can focus on per-tool configuration.
 */
class McpAppearanceConfigurable(private val project: Project) :
    BoundConfigurable("Appearance"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.mcp.appearance"

    private var readColorCombo: ThemeColorComboBox? = null
    private var searchColorCombo: ThemeColorComboBox? = null
    private var editColorCombo: ThemeColorComboBox? = null
    private var executeColorCombo: ThemeColorComboBox? = null

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

        root.add(TitledSeparator("Tool Kind Colors").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })

        root.add(JBLabel(
            "<html>Customize the accent color used for each tool kind in the MCP Tools " +
                "list, tool-chip labels in the chat view, and permission dropdowns. " +
                "Colors adapt automatically when the IDE theme changes.</html>"
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyBottom(10)
            alignmentX = Component.LEFT_ALIGNMENT
            isAllowAutoWrapping = true
        })

        readColorCombo = ThemeColorComboBox().also {
            it.selectedThemeColor = ThemeColor.fromKey(settings.kindReadColorKey)
        }
        searchColorCombo = ThemeColorComboBox().also {
            it.selectedThemeColor = ThemeColor.fromKey(settings.kindSearchColorKey)
        }
        editColorCombo = ThemeColorComboBox().also {
            it.selectedThemeColor = ThemeColor.fromKey(settings.kindEditColorKey)
        }
        executeColorCombo = ThemeColorComboBox().also {
            it.selectedThemeColor = ThemeColor.fromKey(settings.kindExecuteColorKey)
        }
        root.add(colorRow("Read & Navigate", readColorCombo!!))
        root.add(colorRow("Search & Query", searchColorCombo!!))
        root.add(colorRow("Edit & Refactor", editColorCombo!!))
        root.add(colorRow("Run & Execute", executeColorCombo!!))
        root.add(Box.createVerticalGlue())
        return root
    }

    private fun colorRow(label: String, combo: ThemeColorComboBox): JComponent {
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
        return readColorCombo != null && keyOf(readColorCombo!!) != settings.kindReadColorKey ||
            searchColorCombo != null && keyOf(searchColorCombo!!) != settings.kindSearchColorKey ||
            editColorCombo != null && keyOf(editColorCombo!!) != settings.kindEditColorKey ||
            executeColorCombo != null && keyOf(executeColorCombo!!) != settings.kindExecuteColorKey
    }

    private fun applySettings() {
        val settings = McpServerSettings.getInstance(project)
        readColorCombo?.let { settings.kindReadColorKey = keyOf(it) }
        searchColorCombo?.let { settings.kindSearchColorKey = keyOf(it) }
        editColorCombo?.let { settings.kindEditColorKey = keyOf(it) }
        executeColorCombo?.let { settings.kindExecuteColorKey = keyOf(it) }
    }

    private fun resetFromSettings() {
        val settings = McpServerSettings.getInstance(project)
        readColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindReadColorKey)
        searchColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindSearchColorKey)
        editColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindEditColorKey)
        executeColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindExecuteColorKey)
    }

    override fun disposeUIResources() {
        super<BoundConfigurable>.disposeUIResources()
        readColorCombo = null
        searchColorCombo = null
        editColorCombo = null
        executeColorCombo = null
    }
}
