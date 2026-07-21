package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.github.catatafishen.agentbridge.psi.tools.rider.ReSharperMcpClient
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.AgentUiSettings
import com.github.catatafishen.agentbridge.services.ToolDefinition
import com.github.catatafishen.agentbridge.services.ToolPermission
import com.github.catatafishen.agentbridge.services.ToolRegistry.Category
import com.github.catatafishen.agentbridge.services.hooks.DefaultHookProvisioner
import com.github.catatafishen.agentbridge.services.hooks.HookRegistry
import com.github.catatafishen.agentbridge.ui.NativeChatColors
import com.github.catatafishen.agentbridge.ui.ToolKindColors
import com.github.catatafishen.agentbridge.ui.cssKindName
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import java.util.concurrent.ExecutionException
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.*

private const val SILENT_TOOLTIP =
    "<html>This tool runs without a permission request — no control available</html>"

private const val PERM_LABEL_WIDTH = 150

/** Optional secondary filter that hides tools by enablement state. */
private enum class StatusFilter(val label: String) {
    ALL("All tools"),
    ENABLED("Enabled only"),
    DISABLED("Disabled only");

    override fun toString(): String = label
}

/** Permission batch groups, matching the tool-chip CSS color palette. */
private enum class KindGroup(val label: String, val description: String) {
    READ("Read & Navigate", "File reads, search, git log/diff, code quality checks"),
    EDIT("Edit & Refactor", "File writes, git stage/commit/merge, refactoring"),
    EXECUTE("Run & Execute", "Shell commands, run configs, git push/reset, delete files");

    fun color(settings: McpServerSettings?): Color = when (this) {
        READ -> ToolKindColors.readColor(settings)
        EDIT -> ToolKindColors.editColor(settings)
        EXECUTE -> ToolKindColors.executeColor(settings)
    }
}

private fun ToolDefinition.kindGroup(): KindGroup? = when {
    isBuiltIn -> null
    isReadOnly -> KindGroup.READ
    kind() == ToolDefinition.Kind.EDIT || kind() == ToolDefinition.Kind.WRITE ||
        kind() == ToolDefinition.Kind.DELETE || kind() == ToolDefinition.Kind.MOVE -> KindGroup.EDIT

    kind() == ToolDefinition.Kind.EXECUTE -> KindGroup.EXECUTE
    else -> null
}

private sealed class NavNode(val label: String) {
    object All : NavNode("All Tools")
    class Section(val isBuiltIn: Boolean) :
        NavNode(if (isBuiltIn) "Built-in Tools" else "Plugin Tools")

    class Cat(val category: Category, val isBuiltIn: Boolean) : NavNode(category.displayName)

    override fun toString() = label
}

private class NavTreeCellRenderer : TreeCellRenderer {
    private val label = SimpleColoredComponent()
    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        label.clear()
        label.font = UIUtil.getTreeFont()
        label.border = JBUI.Borders.empty(2, 4)
        label.background = if (selected) UIUtil.getTreeSelectionBackground(hasFocus) else UIUtil.SIDE_PANEL_BACKGROUND
        label.foreground = if (selected) UIUtil.getTreeSelectionForeground(hasFocus) else UIUtil.getTreeForeground()
        label.isOpaque = selected
        val node = value as? DefaultMutableTreeNode
        when (val nav = node?.userObject) {
            is NavNode.All -> {
                label.icon = AllIcons.Nodes.ConfigFolder
                label.append(nav.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }

            is NavNode.Section -> {
                label.icon = if (nav.isBuiltIn) AllIcons.Nodes.Plugin else AllIcons.Nodes.Module
                label.append(nav.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }

            is NavNode.Cat -> {
                label.icon = AllIcons.Nodes.Folder
                label.append(nav.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }

            else -> label.append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        return label
    }
}

/**
 * Unified MCP → Tools page. One card per tool combining: enablement (checkbox),
 * permission level (combo), in-/out-project sub-permissions for path-aware tools,
 * hook configuration (⚙ button + indicator), and description.
 *
 * Top bar: enabled counter, global Enable/Disable, search filter, hooks-only filter.
 * Quick Permissions: batch combos that apply across an entire kind group.
 * Tree (left) navigates Plugin/Built-in × category; cards (right) reflect the
 * tree selection and the search filter together.
 *
 * Project-wide kind colors are configured in [McpAppearanceConfigurable];
 * hook-script file integrity is managed at the bottom of this page.
 */
class ToolsConfigurable(private val project: Project) :
    BoundConfigurable("Tools"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.tools"

    private data class ToolRow(
        val tool: ToolDefinition,
        val checkbox: JBCheckBox,
        val permCombo: PermissionComboBox?,
        val hookIndicator: JBLabel,
        val card: JComponent,
    )

    private val toolRows = LinkedHashMap<String, ToolRow>()
    private val categoryRows = LinkedHashMap<Category, MutableList<ToolRow>>()
    private val groupCombos = mutableMapOf<KindGroup, PermissionComboBox>()
    private var outsideProjectCombo: PermissionComboBox? = null

    private var counterLabel: JBLabel? = null
    private var filterField: JTextField? = null
    private var hooksOnlyBox: JCheckBox? = null
    private var statusFilterCombo: ComboBox<StatusFilter>? = null
    private var hookStatusPanel: HookFileStatusPanel? = null
    private var cardsContent: JBPanel<*>? = null
    private var currentNavFilter: (ToolDefinition) -> Boolean = { true }

    private lateinit var uiSettings: AgentUiSettings
    private lateinit var mcpSettings: McpServerSettings

    override fun createPanel() = panel {
        row {
            cell(buildContent())
                .align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
        }.resizableRow().layout(RowLayout.PARENT_GRID)
        onIsModified { computeIsModified() }
        onApply { applySettings() }
        onReset { resetFromSettings() }
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private fun buildContent(): JComponent {
        mcpSettings = McpServerSettings.getInstance(project)
        uiSettings = ActiveAgentManager.getInstance(project).settings
        buildAllRows()

        val root = JBPanel<JBPanel<*>>(BorderLayout())
        root.border = JBUI.Borders.empty(8)

        val topStack = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        topStack.add(buildCounterRow())
        topStack.add(buildLimitHint())
        buildRiderInfoPanelIfNeeded()?.let { topStack.add(it) }
        buildClionInfoPanelIfNeeded()?.let { topStack.add(it) }
        topStack.add(buildButtonRow())
        topStack.add(buildFilterRow())
        topStack.add(buildQuickPermissionsRow())

        root.add(topStack, BorderLayout.NORTH)
        root.add(buildSplitter(), BorderLayout.CENTER)
        root.add(buildHookFilesSection(), BorderLayout.SOUTH)

        // Initial render
        rebuildCards()
        updateCounter()
        return root
    }

    private fun buildCounterRow(): JComponent {
        val label = JBLabel().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(2)
        }
        counterLabel = label
        return label
    }

    private fun buildLimitHint(): JComponent = JBLabel(
        "<html>MCP clients enforce a <b>${McpToolFilter.MAX_TOOLS}-tool limit</b>. " +
            "Only enable tools you intend to use.</html>"
    ).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.emptyBottom(8)
        alignmentX = Component.LEFT_ALIGNMENT
        isAllowAutoWrapping = true
    }

    private fun buildRiderInfoPanelIfNeeded(): JComponent? {
        if (!PlatformApiCompat.isPluginInstalled("com.intellij.modules.rider")) return null
        val container = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
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
        return container
    }

    private fun buildButtonRow(): JComponent {
        val enableAllBtn = JButton("Enable All").apply {
            addActionListener {
                toolRows.values.forEach { it.checkbox.isSelected = true }
                onAnyEnablementChange()
            }
        }
        val disableAllBtn = JButton("Disable All").apply {
            addActionListener {
                toolRows.values.forEach { it.checkbox.isSelected = false }
                onAnyEnablementChange()
            }
        }
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyBottom(4)
            add(enableAllBtn)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(disableAllBtn)
            add(Box.createHorizontalGlue())
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun buildFilterRow(): JComponent {
        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Filter tools by name or ID"
            toolTipText = "Filter by tool name or ID"
            maximumSize = Dimension(JBUI.scale(320), preferredSize.height)
            preferredSize = Dimension(JBUI.scale(280), preferredSize.height)
        }
        filterField = searchField.textEditor
        val hooksOnly = JCheckBox("Has hooks").apply {
            toolTipText = "Only show tools with hook configurations"
            isOpaque = false
        }
        hooksOnlyBox = hooksOnly

        val statusCombo = ComboBox(StatusFilter.entries.toTypedArray()).apply {
            toolTipText = "Filter by enablement state"
            maximumSize = Dimension(JBUI.scale(160), preferredSize.height)
            preferredSize = Dimension(JBUI.scale(140), preferredSize.height)
        }
        statusFilterCombo = statusCombo

        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildCards()
            override fun removeUpdate(e: DocumentEvent) = rebuildCards()
            override fun changedUpdate(e: DocumentEvent) = rebuildCards()
        })
        hooksOnly.addItemListener { rebuildCards() }
        statusCombo.addActionListener { rebuildCards() }

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyBottom(4)
            add(searchField)
            add(Box.createHorizontalStrut(JBUI.scale(12)))
            add(statusCombo)
            add(Box.createHorizontalStrut(JBUI.scale(12)))
            add(hooksOnly)
            add(Box.createHorizontalGlue())
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun buildQuickPermissionsRow(): JComponent {
        groupCombos.clear()
        val container = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 0, 4, 0)
        }

        container.add(TitledSeparator("Quick Permissions").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })

        // Kind groups evenly spaced across the row using horizontal glue.
        val kindRow = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0, 2, 0)
        }
        for ((i, group) in KindGroup.entries.withIndex()) {
            if (i > 0) kindRow.add(Box.createHorizontalGlue())
            kindRow.add(buildKindGroupPair(group))
        }
        kindRow.add(Box.createHorizontalGlue())
        kindRow.maximumSize = Dimension(Int.MAX_VALUE, kindRow.preferredSize.height)
        container.add(kindRow)

        container.add(buildOutsideProjectQuickRow())
        container.add(buildSecurityNotice())

        container.maximumSize = Dimension(Int.MAX_VALUE, container.preferredSize.height)
        return container
    }

    private fun buildKindGroupPair(group: KindGroup): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JBLabel(group.label).apply {
                toolTipText = group.description
                icon = ColorDotIcon(group.color(mcpSettings))
                border = JBUI.Borders.emptyRight(6)
            })
            val combo = PermissionComboBox(PermOption.ALLOW_ASK).apply {
                selectPermission(computeGroupInitialPermission(group))
                toolTipText =
                    "<html>Set <b>${group.label}</b> permission for all tools in this group</html>"
                addActionListener { applyGroupPermission(group) }
            }
            groupCombos[group] = combo
            add(combo)
        }

    private fun buildOutsideProjectQuickRow(): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 0, 2, 0)
            add(JBLabel("Access outside the project directory:").apply {
                icon = AllIcons.Nodes.Folder
                toolTipText =
                    "One project-wide policy applied to any path-aware tool acting on a file outside " +
                        "the project (the stricter of this and the tool's own permission wins)"
                border = JBUI.Borders.emptyRight(6)
            })
            val combo = PermissionComboBox(PermOption.ALL).apply {
                selectPermission(uiSettings.outsideProjectAccess)
                toolTipText = "Single policy for file access outside the project directory"
            }
            outsideProjectCombo = combo
            add(combo)
            add(Box.createHorizontalGlue())
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun buildSecurityNotice(): JComponent = JBLabel(
        "<html><b>Note:</b> Terminal and <i>Run command</i> tools inherit whatever permissions " +
            "the IDE process was started with — agents can still bypass these rules via the shell.</html>"
    ).apply {
        icon = AllIcons.General.BalloonWarning
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(4, 0, 4, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        isAllowAutoWrapping = true
    }

    private fun buildSplitter(): JComponent {
        val root = DefaultMutableTreeNode("root")

        val plugin = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = false))
        toolRows.values
            .filter { !it.tool.isBuiltIn }
            .map { it.tool.category() }
            .distinct()
            .forEach { plugin.add(DefaultMutableTreeNode(NavNode.Cat(it, isBuiltIn = false))) }

        val builtIn = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = true))
        toolRows.values
            .filter { it.tool.isBuiltIn }
            .map { it.tool.category() }
            .distinct()
            .forEach { builtIn.add(DefaultMutableTreeNode(NavNode.Cat(it, isBuiltIn = true))) }

        // Only surface "All Tools" when there's more than one source section to
        // aggregate. With a single section it's redundant with that section.
        val sections = listOfNotNull(
            plugin.takeIf { it.childCount > 0 },
            builtIn.takeIf { it.childCount > 0 },
        )
        if (sections.size > 1) root.add(DefaultMutableTreeNode(NavNode.All))
        sections.forEach { root.add(it) }

        val tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.border = JBUI.Borders.emptyLeft(4)
        tree.cellRenderer = NavTreeCellRenderer()
        tree.background = UIUtil.SIDE_PANEL_BACKGROUND
        for (i in 0 until root.childCount) {
            tree.expandPath(TreePath(arrayOf(root, root.getChildAt(i))))
        }
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        tree.addTreeSelectionListener { e ->
            val node = e?.newLeadSelectionPath?.lastPathComponent as? DefaultMutableTreeNode
                ?: return@addTreeSelectionListener
            currentNavFilter = when (val nav = node.userObject) {
                is NavNode.All -> { _ -> true }
                is NavNode.Section -> { t -> t.isBuiltIn == nav.isBuiltIn }
                is NavNode.Cat -> { t -> t.category() == nav.category && t.isBuiltIn == nav.isBuiltIn }
                else -> currentNavFilter
            }
            rebuildCards()
        }

        // Default selection → first node (All Tools when present, otherwise
        // the single available source section). Skip when the tree is empty,
        // which happens during searchable-options indexing before tools have
        // been registered with the configurable.
        if (root.childCount > 0) {
            tree.selectionPath = TreePath(arrayOf(root, root.getChildAt(0)))
        }

        val treeScroll = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(200, 0)
            minimumSize = JBUI.size(150, 0)
            viewport.background = UIUtil.SIDE_PANEL_BACKGROUND
        }

        val cards = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        cardsContent = cards
        val cardsScroll = JBScrollPane(cards).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = JBUI.size(560, 360)
        }

        return OnePixelSplitter(false, "AgentBridge.UnifiedTools.splitter", 0.25f).also { s ->
            s.firstComponent = treeScroll
            s.secondComponent = cardsScroll
        }
    }

    private fun buildHookFilesSection(): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(12)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        header.add(TitledSeparator("Hook Files"), BorderLayout.CENTER)
        val restoreBtn = JButton("Restore Defaults").apply {
            font = JBUI.Fonts.smallFont()
            toolTipText = "Reset hook configs and scripts to the bundled plugin defaults"
            addActionListener { restoreDefaultHooks() }
        }
        header.add(restoreBtn, BorderLayout.EAST)
        section.add(header)
        val hookStatus = HookFileStatusPanel(project).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        hookStatusPanel = hookStatus
        section.add(hookStatus)
        return section
    }

    // ── Row construction ──────────────────────────────────────────────────────

    private fun buildAllRows() {
        toolRows.clear()
        categoryRows.clear()
        for (tool in sortedConfigurableTools()) {
            val row = buildToolRow(tool)
            toolRows[tool.id()] = row
            categoryRows.getOrPut(tool.category()) { mutableListOf() }.add(row)
        }
    }

    private fun sortedConfigurableTools(): List<ToolDefinition> =
        McpToolFilter.getConfigurableTools(project)
            .sortedWith(
                compareBy<ToolDefinition> { it.category().displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.displayName().lowercase(Locale.ROOT) }
                    .thenBy { it.id() }
            )

    private fun buildToolRow(tool: ToolDefinition): ToolRow {
        val toolId = tool.id()
        val enabled = mcpSettings.isToolEnabled(toolId)

        val checkbox = JBCheckBox(tool.displayName(), enabled).apply {
            border = JBUI.Borders.emptyTop(1)
            isOpaque = false
            // Tint the tool name in the kind color so the card title matches
            // the chip color used for that tool in chat output.
            foreground = kindColorFor(tool)
            addItemListener {
                onAnyEnablementChange()
                refreshCardEnabledState(toolId)
            }
        }

        val permCombo = buildPermissionCombo(tool, enabled)

        val hookConfig = HookRegistry.getInstance(project).findConfig(toolId)
        val hasHooks = hookConfig != null && !hookConfig.isEmpty
        val hookIndicator = JBLabel().apply { updateHookIndicator(this, hasHooks) }

        val card = buildCard(tool, checkbox, permCombo, hookIndicator)

        return ToolRow(tool, checkbox, permCombo, hookIndicator, card)
    }

    /**
     * Returns the permission combo for a tool, or null when the tool runs silently (a built-in
     * without deny control). The combo offers Allow/Ask only — Deny is expressed by disabling the
     * tool. Path-aware tools are additionally governed by the single global "outside-project access"
     * control (see buildOutsideProjectQuickRow), not by per-tool sub-permissions.
     */
    private fun buildPermissionCombo(
        tool: ToolDefinition,
        enabled: Boolean,
    ): PermissionComboBox? {
        // Built-in tools without deny control run silently
        if (tool.isBuiltIn && !tool.hasDenyControl()) {
            return null
        }
        return PermissionComboBox(PermOption.ALLOW_ASK).apply {
            selectPermission(uiSettings.getToolPermission(tool.id()))
            toolTipText = if (enabled) "Permission when agent requests this tool"
            else "This tool is disabled — enable it to control its permission"
            isEnabled = enabled
            isOpaque = false
        }
    }

    // Kind-color tinting on combos was removed: each card now sits in a tinted
    // ToolChipCard and the combos use semantic green/amber/red Allow/Ask/Deny
    // swatches instead of the kind color.

    private fun buildCard(
        tool: ToolDefinition,
        checkbox: JBCheckBox,
        permCombo: PermissionComboBox?,
        hookIndicator: JBLabel,
    ): JComponent {
        val toolId = tool.id()
        val card = ToolChipCard(kindColorFor(tool)).apply {
            layout = GridBagLayout()
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(2)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        // Row 0: checkbox | (spacer) | hook indicator | ⚙
        gbc.gridy = 0; gbc.gridx = 0
        card.add(checkbox, gbc)

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        card.add(JBPanel<JBPanel<*>>().apply { isOpaque = false }, gbc)
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

        gbc.gridx = 2
        card.add(hookIndicator, gbc)

        gbc.gridx = 3
        val gear = JBLabel("⚙").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Edit hook configuration for this tool"
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont((JBUI.Fonts.label().size + 4).toFloat())
            border = JBUI.Borders.empty(0, 8)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) =
                    showToolOptionsDialog(toolId, tool.displayName())
            })
        }
        card.add(gear, gbc)

        // Row 1: description
        val desc = tool.description()
        if (desc.isNotBlank()) {
            gbc.gridy = 1; gbc.gridx = 0; gbc.gridwidth = 4
            gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            card.add(JBLabel("<html>$desc</html>").apply {
                font = font.deriveFont((JBUI.Fonts.label().size - 1).toFloat())
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.emptyBottom(2)
                isAllowAutoWrapping = true
            }, gbc)
            gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        }

        // Row 2 spans the full card: permission sub-panel owns its own column
        // widths so the combos line up identically across cards regardless of
        // the card's top-row layout (checkbox/spacer/hook indicator/gear).
        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 4
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        card.add(buildPermissionSubPanel(permCombo), gbc)
        gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

        return card
    }

    /**
     * Self-contained permission grid for one tool card. Owning its own
     * GridBagLayout keeps the label column (and therefore the combos) aligned
     * across cards — independent of whatever the card's top row contains.
     */
    private fun buildPermissionSubPanel(
        permCombo: PermissionComboBox?,
    ): JComponent {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout()).apply { isOpaque = false }
        val pgbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(2, 0, 2, 0)
            gridy = 0; gridx = 0
        }

        panel.add(permLabel("Permission:"), pgbc)
        pgbc.gridx = 1
        if (permCombo != null) {
            panel.add(permCombo, pgbc)
        } else {
            panel.add(JBLabel("Runs silently", AllIcons.Actions.Suspend, SwingConstants.LEFT).apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                font = JBUI.Fonts.smallFont()
                toolTipText = SILENT_TOOLTIP
            }, pgbc)
        }

        // Trailing glue cell absorbs horizontal space so the combo stays
        // pinned to the left even though the parent cell stretches.
        pgbc.gridx = 2; pgbc.weightx = 1.0; pgbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(JBPanel<JBPanel<*>>().apply { isOpaque = false }, pgbc)
        pgbc.weightx = 0.0; pgbc.fill = GridBagConstraints.NONE

        return panel
    }

    /** Fixed-width permission label so combos align in column across cards. */
    private fun permLabel(text: String, small: Boolean = false): JBLabel = JBLabel(text).apply {
        if (small) {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(1, 16, 1, 8)
        } else {
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(2, 0, 2, 8)
        }
        preferredSize = Dimension(JBUI.scale(PERM_LABEL_WIDTH), preferredSize.height)
    }

    // ── Filtering / repaint ───────────────────────────────────────────────────

    private fun rebuildCards() {
        val cards = cardsContent ?: return
        cards.removeAll()

        val query = filterField?.text?.trim()?.lowercase(Locale.ROOT) ?: ""
        val hooksOnly = hooksOnlyBox?.isSelected ?: false
        val statusFilter = statusFilterCombo?.selectedItem as? StatusFilter ?: StatusFilter.ALL
        val hookRegistry = HookRegistry.getInstance(project)

        var lastCategory: Category? = null
        var shown = 0
        for (row in toolRows.values) {
            val tool = row.tool
            if (!currentNavFilter(tool)) continue
            if (query.isNotEmpty() &&
                !tool.displayName().lowercase(Locale.ROOT).contains(query) &&
                !tool.id().lowercase(Locale.ROOT).contains(query)
            ) continue
            if (hooksOnly) {
                val cfg = hookRegistry.findConfig(tool.id())
                if (cfg == null || cfg.isEmpty) continue
            }
            when (statusFilter) {
                StatusFilter.ENABLED -> if (!row.checkbox.isSelected) continue
                StatusFilter.DISABLED -> if (row.checkbox.isSelected) continue
                StatusFilter.ALL -> {}
            }
            if (tool.category() != lastCategory) {
                lastCategory = tool.category()
                cards.add(buildCategoryHeader(tool.category()))
            } else if (shown > 0) {
                cards.add(Box.createVerticalStrut(JBUI.scale(12)))
            }
            cards.add(row.card)
            shown++
        }
        if (shown == 0) {
            cards.add(JBLabel("No tools match the current filter.", SwingConstants.CENTER).apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.empty(24)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        cards.add(Box.createVerticalGlue())
        cards.revalidate()
        cards.repaint()
    }

    private fun buildCategoryHeader(category: Category): JComponent {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(14, 0, 4, 0)
        }
        row.add(TitledSeparator(category.displayName), BorderLayout.CENTER)

        val enableBtn = JButton("Enable").apply {
            font = JBUI.Fonts.smallFont()
            addActionListener {
                categoryRows[category]?.forEach { it.checkbox.isSelected = true }
                onAnyEnablementChange()
            }
        }
        val disableBtn = JButton("Disable").apply {
            font = JBUI.Fonts.smallFont()
            addActionListener {
                categoryRows[category]?.forEach { it.checkbox.isSelected = false }
                onAnyEnablementChange()
            }
        }
        val btns = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(enableBtn); add(Box.createHorizontalStrut(JBUI.scale(4))); add(disableBtn)
        }
        row.add(btns, BorderLayout.EAST)
        return row
    }

    private fun refreshCardEnabledState(toolId: String) {
        val row = toolRows[toolId] ?: return
        row.permCombo?.isEnabled = row.checkbox.isSelected
        row.permCombo?.repaint()
    }

    // ── Counter & batch ───────────────────────────────────────────────────────

    private fun onAnyEnablementChange() {
        updateCounter()
        if ((statusFilterCombo?.selectedItem as? StatusFilter ?: StatusFilter.ALL) != StatusFilter.ALL) {
            rebuildCards()
        }
    }

    private fun updateCounter() {
        val label = counterLabel ?: return
        val enabled = toolRows.values.count { it.checkbox.isSelected }
        val max = McpToolFilter.MAX_TOOLS
        val over = enabled > max
        label.text = "<html><b>$enabled / $max tools enabled</b>${if (over) "  ⚠ over limit!" else ""}</html>"
        label.foreground = if (over) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
    }

    private fun computeGroupInitialPermission(group: KindGroup): ToolPermission {
        val groupRows = toolRows.values.filter { it.tool.kindGroup() == group && it.permCombo != null }
        return if (groupRows.any { it.permCombo!!.selectedPermission() == ToolPermission.ASK })
            ToolPermission.ASK else ToolPermission.ALLOW
    }

    private fun applyGroupPermission(group: KindGroup) {
        val perm = groupCombos[group]?.selectedPermission() ?: return
        toolRows.values.filter { it.tool.kindGroup() == group }.forEach { row ->
            row.permCombo?.selectPermission(perm)
        }
    }

    // ── Persist / modify ──────────────────────────────────────────────────────

    private fun computeIsModified(): Boolean {
        for (row in toolRows.values) {
            val id = row.tool.id()
            if (row.checkbox.isSelected != mcpSettings.isToolEnabled(id)) return true
            val pc = row.permCombo ?: continue
            if (pc.selectedPermission() != uiSettings.getToolPermission(id)) return true
        }
        outsideProjectCombo?.let {
            if (it.selectedPermission() != uiSettings.outsideProjectAccess) return true
        }
        return false
    }

    private fun applySettings() {
        for (row in toolRows.values) {
            val id = row.tool.id()
            mcpSettings.setToolEnabled(id, row.checkbox.isSelected)
            row.permCombo?.let { pc -> uiSettings.setToolPermission(id, pc.selectedPermission()) }
        }
        outsideProjectCombo?.let { uiSettings.setOutsideProjectAccess(it.selectedPermission()) }
    }

    private fun resetFromSettings() {
        for (row in toolRows.values) {
            val id = row.tool.id()
            row.checkbox.isSelected = mcpSettings.isToolEnabled(id)
            row.permCombo?.selectPermission(uiSettings.getToolPermission(id))
            val hookConfig = HookRegistry.getInstance(project).findConfig(id)
            updateHookIndicator(row.hookIndicator, hookConfig != null && !hookConfig.isEmpty)
            refreshCardEnabledState(id)
        }
        for ((group, combo) in groupCombos) {
            combo.selectPermission(computeGroupInitialPermission(group))
        }
        outsideProjectCombo?.selectPermission(uiSettings.outsideProjectAccess)
        updateCounter()
    }

    override fun disposeUIResources() {
        super<BoundConfigurable>.disposeUIResources()
        counterLabel = null
        filterField = null
        hooksOnlyBox = null
        statusFilterCombo = null
        hookStatusPanel = null
        cardsContent = null
        toolRows.clear()
        categoryRows.clear()
        groupCombos.clear()
        outsideProjectCombo = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun kindColorFor(tool: ToolDefinition): Color =
        NativeChatColors.kindColor(tool.cssKindName(), mcpSettings)

    private fun updateHookIndicator(indicator: JBLabel, hasHooks: Boolean) {
        indicator.text = if (hasHooks) "[hooks]" else ""
        indicator.font = if (hasHooks) JBUI.Fonts.miniFont() else indicator.font
        indicator.foreground = if (hasHooks) JBUI.CurrentTheme.Link.Foreground.ENABLED else indicator.foreground
        indicator.toolTipText = if (hasHooks) "Hook configured for this tool" else null
    }

    private fun showToolOptionsDialog(toolId: String, displayName: String) {
        val dialog = ToolHookDialog(project, toolId, displayName)
        if (dialog.showAndGet()) {
            val cfg = HookRegistry.getInstance(project).findConfig(toolId)
            val hasHooks = cfg != null && !cfg.isEmpty
            toolRows[toolId]?.let { updateHookIndicator(it.hookIndicator, hasHooks) }
        }
    }

    private fun restoreDefaultHooks() {
        val choice = Messages.showYesNoDialog(
            project,
            "Built-in hooks are now implemented in Java and cannot be customized here.\n\n" +
                "This will reload the hook registry. Any custom hook JSON configs in your hooks directory will be preserved.\n\nContinue?",
            "Restore Default Hooks",
            Messages.getWarningIcon()
        )
        if (choice != Messages.YES) return
        val restored = DefaultHookProvisioner.restoreDefaults(project)
        if (restored) {
            val registry = HookRegistry.getInstance(project)
            registry.reload()
            hookStatusPanel?.refresh()
            for (row in toolRows.values) {
                val cfg = registry.findConfig(row.tool.id())
                updateHookIndicator(row.hookIndicator, cfg != null && !cfg.isEmpty)
            }
        }
    }

    private fun buildRiderInfoPanel(): JComponent {
        val disabledList = PsiBridgeService.getRiderDisabledToolIds().joinToString(", ")
        return JEditorPane(
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
    }

    private fun buildClionInfoPanelIfNeeded(): JComponent? {
        if (!PlatformApiCompat.isPluginInstalled("com.intellij.modules.clion")) return null
        return buildClionInfoPanel()
    }

    private fun buildClionInfoPanel(): JComponent {
        val disabledList = PsiBridgeService.getClionDisabledToolIds().joinToString(", ")
        return JEditorPane(
            "text/html",
            "<html><body><b>⚠ CLion:</b> The following tools are unavailable for C/C++. The Nova " +
                "frontend has no semantic resolution or subtype/caller search for these — that index " +
                "lives in the clangd/Radler backend: <br><i>$disabledList</i></body></html>"
        ).apply {
            isEditable = false
            isOpaque = false
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0, 8, 0)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }
}

/** Tiny filled-circle icon used as a kind-color dot beside tool checkboxes. */
private class ColorDotIcon(private val color: Color, private val sizePx: Int = 10) : javax.swing.Icon {
    override fun paintIcon(c: Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            g2.color = color
            g2.fillOval(x, y, JBUI.scale(sizePx), JBUI.scale(sizePx))
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = JBUI.scale(sizePx)
    override fun getIconHeight(): Int = JBUI.scale(sizePx)
}

