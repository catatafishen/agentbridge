package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.github.catatafishen.agentbridge.psi.tools.rider.ReSharperMcpClient
import com.github.catatafishen.agentbridge.services.ToolDefinition
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.github.catatafishen.agentbridge.ui.ToolKindColors
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.*
import java.util.concurrent.ExecutionException
import javax.swing.*
import javax.swing.event.HyperlinkEvent

class ToolsConfigurable(private val project: Project) :
    BoundConfigurable("Tools"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.tools"

    private val toolCheckboxes = LinkedHashMap<String, JBCheckBox>()
    private val categoryCheckboxes = LinkedHashMap<ToolRegistry.Category, MutableList<JBCheckBox>>()

    private var counterLabel: JBLabel? = null
    private var readColorCombo: ThemeColorComboBox? = null
    private var searchColorCombo: ThemeColorComboBox? = null
    private var editColorCombo: ThemeColorComboBox? = null
    private var executeColorCombo: ThemeColorComboBox? = null

    override fun createPanel() = panel {
        row {
            cell(buildToolsContent())
                .align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
        }.resizableRow().layout(RowLayout.PARENT_GRID)
        onIsModified { computeIsModified() }
        onApply { applySettings() }
        onReset { resetFromSettings() }
    }

    private fun buildToolsContent(): JComponent {
        val settings = McpServerSettings.getInstance(project)

        val toolsPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        counterLabel = JBLabel().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        }
        toolsPanel.add(counterLabel)

        val limitHint = JBLabel(
            "<html>MCP clients enforce a <b>${McpToolFilter.MAX_TOOLS}-tool limit</b>. " +
                "Only enable tools you intend to use.</html>"
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyBottom(6)
            alignmentX = Component.LEFT_ALIGNMENT
            isAllowAutoWrapping = true
        }
        toolsPanel.add(limitHint)

        if (PlatformApiCompat.isPluginInstalled("com.intellij.modules.rider")) {
            val container = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            toolsPanel.add(container)
            object : SwingWorker<Boolean, Unit>() {
                override fun doInBackground(): Boolean = ReSharperMcpClient.isAvailable()
                override fun done() {
                    var available = false
                    try {
                        available = get()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (_: ExecutionException) {
                        // probe failed; treat as unavailable
                    }
                    if (!available) {
                        container.add(buildRiderInfoPanel())
                        container.revalidate(); container.repaint()
                    }
                }
            }.execute()
        }

        val enableAllBtn = JButton("Enable All").apply {
            addActionListener {
                toolCheckboxes.values.forEach { it.isSelected = true }
                updateCounter()
            }
        }
        val disableAllBtn = JButton("Disable All").apply {
            addActionListener {
                toolCheckboxes.values.forEach { it.isSelected = false }
                updateCounter()
            }
        }
        val topRow = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyBottom(4)
            add(enableAllBtn)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(disableAllBtn)
            add(Box.createHorizontalGlue())
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        toolsPanel.add(topRow)

        addToolRows(toolsPanel, settings)

        toolsPanel.add(buildColorPickerSection(settings))
        toolsPanel.add(Box.createVerticalGlue())

        // Revalidate when the panel's width changes. BoxLayout commits to each child's
        // preferredSize.height before layout assigns them a real width. JBLabel with
        // isAllowAutoWrapping=true can only recalculate its wrapped height once getWidth()>0,
        // so we need a second layout pass after the first width arrives.
        toolsPanel.addComponentListener(object : ComponentAdapter() {
            private var lastWidth = -1
            override fun componentResized(e: ComponentEvent) {
                val w = toolsPanel.width
                if (w > 0 && w != lastWidth) {
                    lastWidth = w
                    SwingUtilities.invokeLater {
                        toolsPanel.revalidate()
                        toolsPanel.repaint()
                    }
                }
            }
        })

        val scrollContent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolsPanel, BorderLayout.NORTH)
        }
        val scrollPane = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            // Cap preferred width so the Settings dialog does not grow to fit the
            // full single-line text width of tool descriptions. AlignX.FILL in
            // createPanel() will expand the pane to the actual dialog width anyway;
            // this only controls what the dialog uses when computing its own preferred size.
            preferredSize = Dimension(JBUI.scale(580), JBUI.scale(480))
        }
        updateCounter()
        return scrollPane
    }

    private fun addToolRows(toolsPanel: JPanel, settings: McpServerSettings) {
        var currentCategory: ToolRegistry.Category? = null
        for (tool in sortedConfigurableTools()) {
            val category = tool.category()
            if (category != currentCategory) {
                currentCategory = category
                toolsPanel.add(buildCategoryHeader(category))
            }
            addToolRow(toolsPanel, tool, category, settings)
        }
    }

    private fun sortedConfigurableTools(): List<ToolDefinition> =
        McpToolFilter.getConfigurableTools(project)
            .sortedWith(
                compareBy<ToolDefinition> { it.category().displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.displayName().lowercase(Locale.ROOT) }
                    .thenBy { it.id() }
            )

    private fun addToolRow(
        toolsPanel: JPanel,
        tool: ToolDefinition,
        category: ToolRegistry.Category,
        settings: McpServerSettings
    ) {
        val kindColor = kindColorFor(tool, settings)
        val toolRow = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val dot = JBLabel("● ").apply {
            foreground = kindColor
            border = JBUI.Borders.empty(1, 16, 0, 0)
        }
        toolRow.add(dot)
        val cb = JBCheckBox(tool.displayName(), settings.isToolEnabled(tool.id())).apply {
            border = JBUI.Borders.emptyTop(1)
            addItemListener { updateCounter() }
        }
        toolCheckboxes[tool.id()] = cb
        categoryCheckboxes.getOrPut(category) { mutableListOf() }.add(cb)
        toolRow.add(cb)
        toolRow.add(Box.createHorizontalGlue())
        toolsPanel.add(toolRow)
        addToolDescription(toolsPanel, tool)
    }

    private fun addToolDescription(toolsPanel: JPanel, tool: ToolDefinition) {
        val desc = tool.description()
        if (desc.isBlank()) return
        val descLabel = JBLabel("<html>$desc</html>").apply {
            font = font.deriveFont((JBUI.Fonts.label().size - 1).toFloat())
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 36, 3, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            isAllowAutoWrapping = true
        }
        toolsPanel.add(descLabel)
    }

    private fun countEnabled(): Int = toolCheckboxes.values.count { it.isSelected }

    private fun updateCounter() {
        val label = counterLabel ?: return
        val enabled = countEnabled()
        val max = McpToolFilter.MAX_TOOLS
        val overLimit = enabled > max
        label.text =
            "<html><b>$enabled / $max tools enabled</b>${if (overLimit) "  ⚠ over limit!" else ""}</html>"
        label.foreground = if (overLimit) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
    }

    private fun buildCategoryHeader(category: ToolRegistry.Category): JComponent {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 0, 2, 0)
        }
        row.add(TitledSeparator(category.displayName), BorderLayout.CENTER)

        val sectionEnableBtn = JButton("Enable").apply {
            font = JBUI.Fonts.smallFont()
            addActionListener {
                categoryCheckboxes[category]?.forEach { it.isSelected = true }
                updateCounter()
            }
        }
        val sectionDisableBtn = JButton("Disable").apply {
            font = JBUI.Fonts.smallFont()
            addActionListener {
                categoryCheckboxes[category]?.forEach { it.isSelected = false }
                updateCounter()
            }
        }
        val btnRow = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sectionEnableBtn)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(sectionDisableBtn)
        }
        row.add(btnRow, BorderLayout.EAST)
        return row
    }

    private fun buildColorPickerSection(settings: McpServerSettings): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val sep = TitledSeparator("Tool Kind Colors").apply {
            border = JBUI.Borders.empty(16, 0, 4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        section.add(sep)
        val hint = JBLabel(
            "<html>Customize the accent color used for each tool kind in this settings panel, " +
                "tool-chip labels in the chat view, and permission dropdowns. " +
                "Colors adapt automatically when the IDE theme changes.</html>"
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = Component.LEFT_ALIGNMENT
            isAllowAutoWrapping = true
        }
        section.add(hint)

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
        section.add(colorRow("Read & Navigate", readColorCombo!!))
        section.add(colorRow("Search & Query", searchColorCombo!!))
        section.add(colorRow("Edit & Refactor", editColorCombo!!))
        section.add(colorRow("Run & Execute", executeColorCombo!!))
        return section
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

    private fun kindColorFor(tool: ToolDefinition, settings: McpServerSettings): Color =
        when (tool.kind()) {
            ToolDefinition.Kind.SEARCH -> ToolKindColors.searchColor(settings)
            ToolDefinition.Kind.EDIT, ToolDefinition.Kind.WRITE,
            ToolDefinition.Kind.DELETE, ToolDefinition.Kind.MOVE -> ToolKindColors.editColor(settings)

            ToolDefinition.Kind.EXECUTE -> ToolKindColors.executeColor(settings)
            else -> ToolKindColors.readColor(settings)
        }

    private fun computeIsModified(): Boolean {
        val settings = McpServerSettings.getInstance(project)
        for ((id, cb) in toolCheckboxes) {
            if (cb.isSelected != settings.isToolEnabled(id)) return true
        }
        if (readColorCombo != null && keyOf(readColorCombo!!) != settings.kindReadColorKey) return true
        if (searchColorCombo != null && keyOf(searchColorCombo!!) != settings.kindSearchColorKey) return true
        if (editColorCombo != null && keyOf(editColorCombo!!) != settings.kindEditColorKey) return true
        return executeColorCombo != null && keyOf(executeColorCombo!!) != settings.kindExecuteColorKey
    }

    private fun applySettings() {
        val settings = McpServerSettings.getInstance(project)
        for ((id, cb) in toolCheckboxes) settings.setToolEnabled(id, cb.isSelected)
        readColorCombo?.let { settings.kindReadColorKey = keyOf(it) }
        searchColorCombo?.let { settings.kindSearchColorKey = keyOf(it) }
        editColorCombo?.let { settings.kindEditColorKey = keyOf(it) }
        executeColorCombo?.let { settings.kindExecuteColorKey = keyOf(it) }
    }

    private fun resetFromSettings() {
        val settings = McpServerSettings.getInstance(project)
        for ((id, cb) in toolCheckboxes) cb.isSelected = settings.isToolEnabled(id)
        readColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindReadColorKey)
        searchColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindSearchColorKey)
        editColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindEditColorKey)
        executeColorCombo?.selectedThemeColor = ThemeColor.fromKey(settings.kindExecuteColorKey)
        updateCounter()
    }

    override fun disposeUIResources() {
        super<BoundConfigurable>.disposeUIResources()
        counterLabel = null
        readColorCombo = null
        searchColorCombo = null
        editColorCombo = null
        executeColorCombo = null
        toolCheckboxes.clear()
        categoryCheckboxes.clear()
    }

    private fun keyOf(combo: ThemeColorComboBox): String? = combo.selectedThemeColor?.name

    private fun buildRiderInfoPanel(): JComponent {
        val disabledList = PsiBridgeService.getRiderDisabledToolIds().joinToString(", ")
        val pane = JEditorPane(
            "text/html",
            "<html><body><b>⚠ Rider:</b> The following tools are unavailable in Rider without the " +
                "<a href='https://github.com/joshua-light/resharper-mcp'>resharper-mcp</a> " +
                "companion plugin: <br><i>$disabledList</i></body></html>"
        ).apply {
            isEditable = false
            isOpaque = false
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0, 8, 0)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            addHyperlinkListener { e ->
                if (HyperlinkEvent.EventType.ACTIVATED == e.eventType && e.url != null) {
                    BrowserUtil.browse(e.url.toExternalForm())
                }
            }
        }
        return pane
    }
}
