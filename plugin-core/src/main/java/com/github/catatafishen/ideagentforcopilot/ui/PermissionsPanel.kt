package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.services.AgentUiSettings
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.Category
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.tree.*

// Permission options
private val PLUGIN_PERM_OPTIONS = arrayOf("Allow", "Ask")

private fun ToolPermission.toPluginIndex() = when (this) {
    ToolPermission.ASK -> 1; else -> 0
}

private fun Int.toPluginPermission() = if (this == 1) ToolPermission.ASK else ToolPermission.ALLOW

private const val SILENT_TOOLTIP =
    "<html>This tool runs without a permission request — no control available</html>"

/**
 * Permission batch groups, matching the tool-chip CSS color palette.
 * Colors resolve through [ToolKindColors], honoring per-project user overrides.
 */
private enum class KindGroup(val label: String, val description: String) {
    READ("Read & Navigate", "File reads, search, git log/diff, code quality checks"),
    EDIT("Edit & Refactor", "File writes, git stage/commit/merge, refactoring"),
    EXECUTE("Run & Execute", "Shell commands, run configs, git push/reset, delete files");

    fun color(settings: McpServerSettings?): Color = when (this) {
        READ -> ToolKindColors.readColor(settings)
        EDIT -> ToolKindColors.editColor(settings)
        EXECUTE -> ToolKindColors.executeColor(settings)
    }

    fun tintedBackground(settings: McpServerSettings?): Color =
        ToolKindColors.tintedBackground(color(settings))
}

/** Maps a [ToolDefinition] to its batch [KindGroup], or null for unclassified tools. */
private fun ToolDefinition.kindGroup(): KindGroup? = when {
    isReadOnly -> KindGroup.READ
    kind() == ToolDefinition.Kind.EDIT || kind() == ToolDefinition.Kind.WRITE -> KindGroup.EDIT
    kind() == ToolDefinition.Kind.EXECUTE -> KindGroup.EXECUTE
    else -> null
}

/** Navigation node user-object for the tree. */
private sealed class NavNode(val label: String) {
    class Section(val isBuiltIn: Boolean) :
        NavNode(if (isBuiltIn) "Built-in Tools" else "Plugin Tools")

    class Cat(val category: Category, val isBuiltIn: Boolean) : NavNode(category.displayName)

    override fun toString() = label
}

/** Cell renderer that renders section nodes bold and category nodes normally, with icons. */
private class NavTreeCellRenderer : TreeCellRenderer {
    private val label = SimpleColoredComponent()

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        label.clear()
        label.font = UIUtil.getTreeFont()
        label.border = JBUI.Borders.empty(2, 4, 2, 4)
        label.background = if (selected) UIUtil.getTreeSelectionBackground(hasFocus) else UIUtil.SIDE_PANEL_BACKGROUND
        label.foreground = if (selected) UIUtil.getTreeSelectionForeground(hasFocus) else UIUtil.getTreeForeground()
        label.isOpaque = selected

        val node = value as? DefaultMutableTreeNode
        when (val nav = node?.userObject) {
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
 * Split-panel showing tool permissions:
 * top   = Quick Permissions batch controls (always visible, above the tree/detail split),
 * left  = navigation tree (sections + categories),
 * right = scrollable grid for the selected set of tools.
 *
 * Placing Quick Permissions above the splitter makes clear that the batch controls
 * affect all tools regardless of which category is currently selected in the tree.
 *
 * This panel is purely about permission levels (allow/ask/deny).
 * Tool enable/disable is managed in Tool Registration settings.
 */
internal class PermissionsPanel(
    private val settings: AgentUiSettings,
    private val registry: ToolRegistry,
    private val mcpSettings: McpServerSettings,
) {

    private data class ToolRow(
        val tool: ToolDefinition,
        val permCombo: ComboBox<String>?,        // null = tool runs silently (no permission UI)
        val inProjectCombo: ComboBox<String>?,   // sub-permission: inside project
        val outProjectCombo: ComboBox<String>?,  // sub-permission: outside project
    )

    private val rows = mutableListOf<ToolRow>()

    /** Maps each KindGroup to its batch combo; populated in [buildGroupControlsPanel]. */
    private val groupCombos = mutableMapOf<KindGroup, ComboBox<String>>()

    /** Panel holding the Quick Permissions section; rebuilt when [reload] is called. */
    private var groupControlsPanel: JBPanel<*> = JBPanel<JBPanel<*>>()

    private val rightContent = JBPanel<JBPanel<*>>(BorderLayout())

    /**
     * Outer panel returned as [component]. Uses BorderLayout:
     * NORTH = [groupControlsPanel] (always visible),
     * CENTER = OnePixelSplitter (tree + per-tool detail).
     */
    private val outerPanel = JBPanel<JBPanel<*>>(BorderLayout())
    val component: JComponent = outerPanel

    init {
        buildAllRows()
        groupControlsPanel = buildGroupControlsPanel()
        buildMainComponent()
    }

    // ── Row construction ──────────────────────────────────────────────────────

    private fun buildAllRows() {
        for (tool in registry.allTools) {
            if (tool.isBuiltIn) continue

            val group = tool.kindGroup()
            val toolEnabled = mcpSettings.isToolEnabled(tool.id())
            val permCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
                setMinimumAndPreferredWidth(JBUI.scale(108))
                selectedIndex = settings.getToolPermission(tool.id()).toPluginIndex()
                toolTipText = if (toolEnabled)
                    "Permission when agent requests this tool"
                else
                    "This tool is disabled in MCP → Tools settings"
                isEnabled = toolEnabled
                group?.let { applyKindTint(this, it) }
            }

            val (inProjectCombo, outProjectCombo) = buildSubPermCombos(tool, permCombo, toolEnabled)
            group?.let {
                inProjectCombo?.let { c -> applyKindTint(c, it) }
                outProjectCombo?.let { c -> applyKindTint(c, it) }
            }
            rows.add(ToolRow(tool, permCombo, inProjectCombo, outProjectCombo))
        }
    }

    /**
     * Applies a kind-group background tint to [combo] so that each permission
     * control is visually color-coded to match the Quick Permissions group above.
     */
    private fun applyKindTint(combo: ComboBox<String>, group: KindGroup) {
        combo.background = group.tintedBackground(mcpSettings)
        combo.isOpaque = true
    }

    private fun buildSubPermCombos(
        tool: ToolDefinition,
        permCombo: ComboBox<String>?,
        toolEnabled: Boolean,
    ): Pair<ComboBox<String>?, ComboBox<String>?> {
        if (!tool.supportsPathSubPermissions() || tool.isBuiltIn || permCombo == null) {
            return Pair(null, null)
        }

        val topIsAllow = permCombo.selectedIndex == 0

        fun subTooltip(inside: Boolean, active: Boolean) =
            if (active) "Permission for files ${if (inside) "inside" else "outside"} the current project"
            else "Controlled by the top-level permission above"

        val inProjectCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
            setMinimumAndPreferredWidth(JBUI.scale(108))
            isEnabled = toolEnabled && topIsAllow
            selectedIndex = settings.getToolPermissionInsideProject(tool.id()).toPluginIndex()
            toolTipText = subTooltip(true, topIsAllow)
        }
        val outProjectCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
            setMinimumAndPreferredWidth(JBUI.scale(108))
            isEnabled = toolEnabled && topIsAllow
            selectedIndex = settings.getToolPermissionOutsideProject(tool.id()).toPluginIndex()
            toolTipText = subTooltip(false, topIsAllow)
        }

        permCombo.addActionListener {
            val allow = permCombo.selectedIndex == 0
            inProjectCombo.isEnabled = toolEnabled && allow
            outProjectCombo.isEnabled = toolEnabled && allow
            inProjectCombo.toolTipText = subTooltip(true, allow)
            outProjectCombo.toolTipText = subTooltip(false, allow)
        }

        return Pair(inProjectCombo, outProjectCombo)
    }

    // ── Group controls panel ──────────────────────────────────────────────────

    /**
     * Builds the "Quick Permissions" panel shown above the tree/detail splitter,
     * followed immediately by an "Individual Tool Permissions" separator so it's
     * visually clear that the batch controls affect all tools in the panel below.
     *
     * Layout: label | combo | spacer (so labels and combos stay adjacent on the left).
     */
    private fun buildGroupControlsPanel(): JBPanel<*> {
        groupCombos.clear()
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.border = JBUI.Borders.empty(10, 16, 0, 16)

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(2, 0)
            gridx = 0; gridy = 0
        }

        // Section header spans all 3 columns (label | combo | spacer)
        gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(TitledSeparator("Quick Permissions"), gbc)
        gbc.gridy++
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 0, 2, 0)

        for (group in KindGroup.entries) {
            // Group name label — no weight so it stays adjacent to the combo
            gbc.gridx = 0; gbc.insets = JBUI.insets(4, 0, 2, 8)
            panel.add(JBLabel(group.label).apply {
                toolTipText = group.description
            }, gbc)

            // Batch combo
            gbc.gridx = 1; gbc.insets = JBUI.insets(4, 0, 2, 0)
            val combo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
                setMinimumAndPreferredWidth(JBUI.scale(108))
                selectedIndex = computeGroupInitialIndex(group)
                toolTipText = "<html>Set <b>${group.label}</b> permission for all tools in this group</html>"
                applyKindTint(this, group)
            }
            groupCombos[group] = combo
            combo.addActionListener { applyGroupPermission(group) }
            panel.add(combo, gbc)

            // Right spacer absorbs remaining width
            gbc.gridx = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(JBPanel<JBPanel<*>>(), gbc)
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

            gbc.gridy++
        }

        // "Individual Tool Permissions" separator divides quick controls from the detail grid
        gbc.gridx = 0; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(10, 0, 0, 0)
        panel.add(TitledSeparator("Individual Tool Permissions"), gbc)

        return panel
    }

    /**
     * Computes the initial combo index for a [KindGroup]:
     * if any tool in the group is set to Ask, shows Ask; otherwise shows Allow.
     */
    private fun computeGroupInitialIndex(group: KindGroup): Int {
        val groupRows = rows.filter { it.tool.kindGroup() == group && it.permCombo != null }
        return if (groupRows.any { it.permCombo!!.selectedIndex == 1 }) 1 else 0
    }

    /** Propagates the group combo selection to all individual tool combos in that group. */
    private fun applyGroupPermission(group: KindGroup) {
        val idx = groupCombos[group]?.selectedIndex ?: return
        rows.filter { it.tool.kindGroup() == group }.forEach { row ->
            row.permCombo?.selectedIndex = idx
        }
    }

    // ── Main split layout ─────────────────────────────────────────────────────

    private fun buildMainComponent() {
        val root = DefaultMutableTreeNode("root")

        // IntelliJ plugin tools section
        val pluginRoot = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = false))
        val pluginCats = rows.filter { !it.tool.isBuiltIn }.map { it.tool.category() }.distinct()
        for (cat in pluginCats) pluginRoot.add(DefaultMutableTreeNode(NavNode.Cat(cat, isBuiltIn = false)))
        root.add(pluginRoot)

        val tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.border = JBUI.Borders.emptyLeft(4)
        tree.cellRenderer = NavTreeCellRenderer()
        tree.background = UIUtil.SIDE_PANEL_BACKGROUND
        tree.expandPath(TreePath(arrayOf(root, pluginRoot)))

        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Initial selection → plugin section
        val pluginPath = TreePath(arrayOf(root, pluginRoot))
        tree.selectionPath = pluginPath
        showTools { !it.tool.isBuiltIn }

        tree.addTreeSelectionListener { e ->
            val node = e?.newLeadSelectionPath?.lastPathComponent as? DefaultMutableTreeNode
                ?: return@addTreeSelectionListener
            when (val nav = node.userObject) {
                is NavNode.Section -> showTools { it.tool.isBuiltIn == nav.isBuiltIn }
                is NavNode.Cat -> showTools {
                    it.tool.category() == nav.category && it.tool.isBuiltIn == nav.isBuiltIn
                }

                else -> Unit // other node types — no action needed
            }
        }

        val treeScroll = JBScrollPane(tree)
        treeScroll.border = JBUI.Borders.empty()
        treeScroll.preferredSize = JBUI.size(200, 0)
        treeScroll.minimumSize = JBUI.size(150, 0)
        treeScroll.viewport.background = UIUtil.SIDE_PANEL_BACKGROUND

        val splitter = OnePixelSplitter(false, "CopilotPermissionsPanel.splitter", 0.28f).also { s ->
            s.firstComponent = treeScroll
            s.secondComponent = rightContent
        }

        outerPanel.removeAll()
        outerPanel.add(groupControlsPanel, BorderLayout.NORTH)
        outerPanel.add(splitter, BorderLayout.CENTER)
    }

    // ── Right-panel rendering ─────────────────────────────────────────────────

    private fun showTools(filter: (ToolRow) -> Boolean) {
        val filtered = rows.filter(filter)
        rightContent.removeAll()
        if (filtered.isEmpty()) {
            val empty = JBLabel("No tools in this category.", SwingConstants.CENTER)
            empty.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            rightContent.add(empty, BorderLayout.CENTER)
        } else {
            rightContent.add(buildContentPanel(filtered), BorderLayout.CENTER)
        }
        rightContent.revalidate()
        rightContent.repaint()
    }

    private fun buildContentPanel(filtered: List<ToolRow>): JComponent {
        val content = JBPanel<JBPanel<*>>(GridBagLayout())
        content.border = JBUI.Borders.empty(10, 16, 12, 16)

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 0)
        }

        var lastCategory: Category? = null
        for (row in filtered) {
            if (row.tool.category() != lastCategory) {
                lastCategory = row.tool.category()
                addCategoryHeader(content, gbc, row.tool.category())
            }
            addToolRow(content, gbc, row)
            gbc.gridy++
            if (row.inProjectCombo != null && row.outProjectCombo != null) {
                addSubPermRow(content, gbc, "▸ Inside project:", row.inProjectCombo)
                addSubPermRow(content, gbc, "▸ Outside project:", row.outProjectCombo)
            }
        }

        // Bottom spacer
        gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0
        content.add(JBPanel<JBPanel<*>>(), gbc)

        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun addCategoryHeader(content: JBPanel<*>, gbc: GridBagConstraints, category: Category) {
        gbc.gridx = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(12, 0, 4, 0)
        content.add(TitledSeparator(category.displayName), gbc)
        gbc.gridy++
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.insets = JBUI.insets(2, 0)
    }

    private fun addToolRow(content: JBPanel<*>, gbc: GridBagConstraints, row: ToolRow) {
        gbc.gridwidth = 1; gbc.gridx = 0
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        val nameLabel = SimpleColoredComponent().apply {
            append(row.tool.displayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (row.tool.description().isNotEmpty()) toolTipText = row.tool.description()
            border = JBUI.Borders.empty(4, 4, 4, 8)
        }
        content.add(nameLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        if (row.permCombo != null) {
            val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
            wrapper.add(row.permCombo, BorderLayout.WEST)
            // Show "(custom)" indicator if tool perm differs from its group's batch setting
            val group = row.tool.kindGroup()
            val groupIdx = group?.let { groupCombos[it]?.selectedIndex }
            if (groupIdx != null && row.permCombo.selectedIndex != groupIdx) {
                wrapper.add(JBLabel(" (custom)").apply {
                    font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    toolTipText = "This tool's permission differs from the '${group.label}' group default"
                }, BorderLayout.CENTER)
            }
            content.add(wrapper, gbc)
        } else {
            content.add(JBLabel("Runs silently", AllIcons.Actions.Suspend, SwingConstants.LEFT).apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                font = JBUI.Fonts.smallFont()
                toolTipText = SILENT_TOOLTIP
            }, gbc)
        }
    }

    private fun addSubPermRow(
        panel: JBPanel<*>, gbc: GridBagConstraints,
        labelText: String, combo: ComboBox<String>
    ) {
        gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.gridx = 0
        gbc.insets = JBUI.insets(0, 24, 0, 8)
        panel.add(JBLabel(labelText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(1, 0)
        val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
        wrapper.add(combo, BorderLayout.WEST)
        panel.add(wrapper, gbc)
        gbc.gridy++
        gbc.insets = JBUI.insets(2, 0)
    }

    // ── Persist ───────────────────────────────────────────────────────────────

    fun isModified(): Boolean {
        for (row in rows) {
            val id = row.tool.id()
            val combo = row.permCombo ?: return false
            if (combo.selectedIndex.toPluginPermission() != settings.getToolPermission(id)) return true
            if (combo.selectedIndex == 0) {
                row.inProjectCombo?.let {
                    if (it.selectedIndex.toPluginPermission() != settings.getToolPermissionInsideProject(id)) return true
                }
                row.outProjectCombo?.let {
                    if (it.selectedIndex.toPluginPermission() != settings.getToolPermissionOutsideProject(id)) return true
                }
            }
        }
        return false
    }

    /** Rebuilds the panel from persisted settings. */
    fun reload() {
        rows.clear()
        buildAllRows()
        groupControlsPanel = buildGroupControlsPanel()
        outerPanel.remove(outerPanel.getComponent(0))
        outerPanel.add(groupControlsPanel, BorderLayout.NORTH, 0)
        rightContent.removeAll()
        rightContent.revalidate()
        rightContent.repaint()
        outerPanel.revalidate()
        outerPanel.repaint()
    }

    fun save() {
        for (row in rows) {
            val id = row.tool.id()
            row.permCombo?.let { combo ->
                val perm = combo.selectedIndex.toPluginPermission()
                settings.setToolPermission(id, perm)

                if (perm == ToolPermission.ALLOW) {
                    row.inProjectCombo?.let {
                        settings.setToolPermissionInsideProject(id, it.selectedIndex.toPluginPermission())
                    }
                    row.outProjectCombo?.let {
                        settings.setToolPermissionOutsideProject(id, it.selectedIndex.toPluginPermission())
                    }
                } else {
                    settings.clearToolSubPermissions(id)
                }
            }
        }
    }
}
