package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.ui.util.ModelGrouper
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Custom model picker popup with collapsible provider sections and favorite toggles.
 * Group expand/collapse state is persisted via PropertiesComponent.
 */
class ModelPickerPopup(
    groups: List<ModelGrouper.Group>
) {

    companion object {
        private const val MENU_ITEM_BG = "MenuItem.background"
        private const val MENU_ITEM_SELECTION_BG = "MenuItem.selectionBackground"
    }

    var onModelSelected: ((Int) -> Unit)? = null
    var onFavoriteToggled: ((String) -> Unit)? = null

    private var popup: JBPopup? = null
    private var focusedRowIndex: Int = 0

    private val favoritedIds: MutableSet<String> = groups
        .flatMap { it.models }
        .filter { it.isFavorite }
        .map { it.modelId }
        .toMutableSet()

    // Flat list of all models (exactly once each, no Favorites duplication) for rebuilding
    // groups after favorite toggles. Groups partition models — no overlap between sections.
    private val allGroupedModels: List<ModelGrouper.GroupedModel> = groups.flatMap { it.models }
    private var currentGroups: List<ModelGrouper.Group> = groups

    private val expandState: MutableMap<String, Boolean> = mutableMapOf()
    private val props = PropertiesComponent.getInstance()

    private fun isExpanded(provider: String): Boolean {
        if (provider !in expandState) {
            val key = "agentbridge.picker.expanded.$provider"
            val stored = props.getValue(key)
            // Default to expanded so all providers are visible on first open
            expandState[provider] = stored?.toBoolean() ?: true
        }
        return expandState[provider] ?: true
    }

    private fun setExpanded(provider: String, expanded: Boolean) {
        expandState[provider] = expanded
        props.setValue("agentbridge.picker.expanded.$provider", expanded.toString())
    }

    private data class FocusableRow(
        val component: JComponent,
        val groupIndex: Int,
        val modelRowIndex: Int,
        val isExpanded: () -> Boolean,
        val toggleExpand: () -> Unit
    )

    private val focusableRows = mutableListOf<FocusableRow>()

    fun createPopup(): JBPopup {
        val content = buildContent()
        val scrollPane = JBScrollPane(content).apply {
            border = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Bind keyboard navigation to the focused component (scrollPane) via InputMap/ActionMap
        // so key events are reliably received regardless of which child has Swing focus.
        registerKeyBindings(scrollPane)

        val p = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, scrollPane)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()
        this.popup = p
        return p
    }

    private fun registerKeyBindings(component: JComponent) {
        val im = component.getInputMap(JComponent.WHEN_FOCUSED)
        val am = component.actionMap

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "moveUp")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "moveDown")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activateRow")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closePopup")

        am.put("moveUp", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = moveUp()
        })
        am.put("moveDown", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = moveDown()
        })
        am.put("activateRow", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = activateCurrentRow()
        })
        am.put("closePopup", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                popup?.cancel()
            }
        })
    }

    private fun moveUp() {
        val visible = getVisibleRows()
        if (visible.isEmpty()) return
        focusedRowIndex = (focusedRowIndex - 1).coerceAtLeast(0)
        highlightRow(focusedRowIndex)
    }

    private fun moveDown() {
        val visible = getVisibleRows()
        if (visible.isEmpty()) return
        focusedRowIndex = (focusedRowIndex + 1).coerceAtMost(visible.size - 1)
        highlightRow(focusedRowIndex)
    }

    private fun activateCurrentRow() {
        val visible = getVisibleRows()
        if (focusedRowIndex !in visible.indices) return
        val row = visible[focusedRowIndex]
        if (row.modelRowIndex >= 0) {
            val model = findModel(row.groupIndex, row.modelRowIndex)
            if (model != null) selectModel(model.index)
        } else {
            row.toggleExpand()
            val newVisible = getVisibleRows()
            focusedRowIndex = focusedRowIndex.coerceAtMost(newVisible.size - 1)
            highlightRow(focusedRowIndex)
        }
    }

    private var outerPanel: JComponent? = null

    private fun buildContent(): JComponent {
        focusableRows.clear()

        val outer = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = JBUI.Borders.empty(4, 0)
        }
        outerPanel = outer

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.weighty = 0.0
        gbc.anchor = GridBagConstraints.PAGE_START

        for ((gi, group) in currentGroups.withIndex()) {
            val section = buildSection(gi, group)
            gbc.gridy = gi
            outer.add(section, gbc)
        }

        // Apply initial collapse state before the popup renders
        updateChildrenVisibility()

        gbc.gridy = currentGroups.size
        gbc.weighty = 1.0
        outer.add(JPanel().apply { isOpaque = false; preferredSize = Dimension(0, 0) }, gbc)

        if (focusableRows.isNotEmpty()) {
            highlightRow(0)
        }

        return outer
    }

    private fun buildSection(groupIndex: Int, group: ModelGrouper.Group): JComponent {
        val provider = group.provider

        val sectionPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val headerIcon = { if (isExpanded(provider)) "\u25BC" else "\u25B6" }
        val headerLabel = JBLabel("${headerIcon()}  $provider", SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD)
            foreground = JBColor.namedColor(
                "Label.infoForeground",
                JBUI.CurrentTheme.Label.disabledForeground()
            )
            border = JBUI.Borders.empty(2, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val now = !isExpanded(provider)
                    setExpanded(provider, now)
                    text = "${headerIcon()}  $provider"
                    updateChildrenVisibility()
                    outerPanel?.revalidate()
                    outerPanel?.repaint()
                }
            })
        }
        val headerWrapper = Box.createHorizontalBox().apply {
            add(headerLabel)
            add(Box.createHorizontalGlue())
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }

        val modelsPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Add header focus row FIRST so keyboard navigation order matches visual layout
        // (header appears above its model rows, so it should be traversed first).
        focusableRows.add(
            FocusableRow(
                component = headerWrapper,
                groupIndex = groupIndex,
                modelRowIndex = -1,
                isExpanded = { isExpanded(provider) },
                toggleExpand = {
                    val now = !isExpanded(provider)
                    setExpanded(provider, now)
                    headerLabel.text = "${headerIcon()}  $provider"
                    updateChildrenVisibility()
                    outerPanel?.revalidate()
                    outerPanel?.repaint()
                }
            )
        )

        // Use forEachIndexed for O(1) index and correct behaviour with duplicate GroupedModel values
        group.models.forEachIndexed { modelIdx, model ->
            val row = buildModelRow(model)
            modelsPanel.add(row)
            focusableRows.add(
                FocusableRow(
                    component = row,
                    groupIndex = groupIndex,
                    modelRowIndex = modelIdx,
                    isExpanded = { isExpanded(provider) },
                    toggleExpand = {}
                )
            )
        }

        sectionPanel.add(headerWrapper)
        sectionPanel.add(modelsPanel)

        return sectionPanel
    }

    private fun buildModelRow(model: ModelGrouper.GroupedModel): JComponent {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = UIManager.getColor(MENU_ITEM_BG)
            border = JBUI.Borders.empty(2, 20, 2, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = UIManager.getColor(MENU_ITEM_SELECTION_BG)
                }

                override fun mouseExited(e: MouseEvent) {
                    background = UIManager.getColor(MENU_ITEM_BG)
                }

                override fun mouseClicked(e: MouseEvent) {
                    selectModel(model.index)
                }
            })
        }

        val nameLabel = JBLabel(model.displayName).apply {
            font = UIUtil.getLabelFont()
            foreground = UIManager.getColor("MenuItem.foreground")
        }

        val isFav = favoritedIds.contains(model.modelId)
        val starLabel = JBLabel(if (isFav) "\u2605" else "\u2606").apply {
            font = font.deriveFont(14f)
            foreground = if (isFav)
                JBColor(Color(0xFF, 0xB9, 0x00), Color(0xFF, 0xD7, 0x00))
            else
                UIManager.getColor("MenuItem.disabledForeground")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (isFav) "Remove from favorites" else "Add to favorites"

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    toggleFavorite(model.modelId)
                    e.consume()
                }
            })
        }

        row.add(nameLabel, BorderLayout.CENTER)
        row.add(starLabel, BorderLayout.EAST)

        return row
    }

    private fun toggleFavorite(modelId: String) {
        if (favoritedIds.contains(modelId)) favoritedIds.remove(modelId) else favoritedIds.add(modelId)
        onFavoriteToggled?.invoke(modelId)
        // Rebuild so the Favorites section reflects the change immediately
        rebuildContent()
    }

    private fun recomputeGroups(): List<ModelGrouper.Group> {
        val (favs, rest) = allGroupedModels.partition { favoritedIds.contains(it.modelId) }
        val providerGroups = rest.groupBy { it.providerName ?: "Other" }
        val sortedProviders = providerGroups.keys.sortedWith(
            compareBy<String> { it == "Other" }.thenBy { it.lowercase() }
        )
        return buildList {
            if (favs.isNotEmpty()) {
                add(ModelGrouper.Group("Favorites", favs.map { it.copy(isFavorite = true) }))
            }
            for (provider in sortedProviders) {
                add(
                    ModelGrouper.Group(
                        provider, (providerGroups[provider] ?: emptyList())
                            .map { it.copy(isFavorite = false) })
                )
            }
        }
    }

    private fun rebuildContent() {
        currentGroups = recomputeGroups()
        focusedRowIndex = 0
        // Save old outer before buildContent() overwrites outerPanel
        val oldOuter = outerPanel
        val newContent = buildContent()
        val scrollPane = oldOuter?.parent?.parent as? JBScrollPane ?: return
        scrollPane.viewport.view = newContent
        scrollPane.revalidate()
        scrollPane.repaint()
    }

    private fun selectModel(index: Int) {
        onModelSelected?.invoke(index)
        popup?.cancel()
    }

    private fun updateChildrenVisibility() {
        for (row in focusableRows) {
            if (row.modelRowIndex >= 0) {
                row.component.isVisible = row.isExpanded()
            }
        }
        outerPanel?.revalidate()
        outerPanel?.repaint()
    }

    private fun getVisibleRows(): List<FocusableRow> {
        return focusableRows.filter { row -> row.modelRowIndex < 0 || row.isExpanded() }
    }

    private fun findModel(groupIndex: Int, modelRowIndex: Int): ModelGrouper.GroupedModel? {
        if (groupIndex >= currentGroups.size) return null
        val group = currentGroups[groupIndex]
        if (modelRowIndex >= group.models.size) return null
        return group.models[modelRowIndex]
    }

    private fun highlightRow(index: Int) {
        val visible = getVisibleRows()
        for ((i, row) in visible.withIndex()) {
            row.component.background = if (i == index)
                UIManager.getColor(MENU_ITEM_SELECTION_BG)
            else
                UIManager.getColor(MENU_ITEM_BG)
        }
        if (index in visible.indices) {
            visible[index].component.scrollRectToVisible(visible[index].component.bounds)
        }
    }
}
