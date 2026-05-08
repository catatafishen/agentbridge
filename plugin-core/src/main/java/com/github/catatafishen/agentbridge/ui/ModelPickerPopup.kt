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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Custom model picker popup with collapsible provider sections and favorite toggles.
 * Group expand/collapse state is persisted via PropertiesComponent.
 */
class ModelPickerPopup(
    private val groups: List<ModelGrouper.Group>
) {

    var onModelSelected: ((Int) -> Unit)? = null
    var onFavoriteToggled: ((String) -> Unit)? = null

    private var popup: JBPopup? = null
    private var focusedRowIndex: Int = 0

    private val favoritedIds: MutableSet<String> = groups
        .flatMap { it.models }
        .filter { it.isFavorite }
        .map { it.modelId }
        .toMutableSet()

    // Persistent expand/collapse state: key = provider name, value = expanded
    private val expandState: MutableMap<String, Boolean> = mutableMapOf()
    private val props = PropertiesComponent.getInstance()

    private fun isExpanded(provider: String): Boolean {
        if (provider !in expandState) {
            val key = "agentbridge.picker.expanded.$provider"
            val stored = props.getValue(key)
            expandState[provider] = if (stored != null) stored.toBoolean() else (provider == "Favorites")
        }
        return expandState[provider]!!
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

    private var outerPanel: JComponent? = null

    private fun buildContent(): JComponent {
        focusableRows.clear()

        val outer = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 4, 0)
            isFocusable = true
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    handleKeyPressed(e)
                }
            })
        }
        outerPanel = outer

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.weighty = 0.0
        gbc.anchor = GridBagConstraints.PAGE_START

        for ((gi, group) in groups.withIndex()) {
            val section = buildSection(gi, group)
            gbc.gridy = gi
            outer.add(section, gbc)
        }

        // Apply initial collapse state — model rows start visible but must
        // be hidden for collapsed groups before the popup renders.
        updateChildrenVisibility()

        gbc.gridy = groups.size
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
            border = JBUI.Borders.empty(2, 8, 2, 8)
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
        // Wrap in horizontal box: label left + glue right = guaranteed left-align
        // regardless of other children's alignmentX in the parent BoxLayout.
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

        for (model in group.models) {
            val row = buildModelRow(model)
            modelsPanel.add(row)
            focusableRows.add(
                FocusableRow(
                    component = row,
                    groupIndex = groupIndex,
                    modelRowIndex = group.models.indexOf(model),
                    isExpanded = { isExpanded(provider) },
                    toggleExpand = {}
                )
            )
        }

        sectionPanel.add(headerWrapper)
        sectionPanel.add(modelsPanel)

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

        return sectionPanel
    }

    private fun buildModelRow(model: ModelGrouper.GroupedModel): JComponent {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = UIManager.getColor("MenuItem.background")
            border = JBUI.Borders.empty(2, 20, 2, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = UIManager.getColor("MenuItem.selectionBackground")
                }

                override fun mouseExited(e: MouseEvent) {
                    background = UIManager.getColor("MenuItem.background")
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
                    toggleFavorite(model.modelId, this@apply)
                    e.consume()
                }
            })
        }

        row.add(nameLabel, BorderLayout.CENTER)
        row.add(starLabel, BorderLayout.EAST)

        return row
    }

    private fun toggleFavorite(modelId: String, starLabel: JLabel) {
        val wasFavorite = favoritedIds.contains(modelId)
        if (wasFavorite) {
            favoritedIds.remove(modelId)
            starLabel.text = "\u2606"
            starLabel.foreground = UIManager.getColor("MenuItem.disabledForeground")
            starLabel.toolTipText = "Add to favorites"
        } else {
            favoritedIds.add(modelId)
            starLabel.text = "\u2605"
            starLabel.foreground = JBColor(Color(0xFF, 0xB9, 0x00), Color(0xFF, 0xD7, 0x00))
            starLabel.toolTipText = "Remove from favorites"
        }
        onFavoriteToggled?.invoke(modelId)
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
        return focusableRows.filter { row ->
            row.modelRowIndex < 0 || row.isExpanded()
        }
    }

    private fun handleKeyPressed(e: KeyEvent) {
        val visible = getVisibleRows()
        if (visible.isEmpty()) return

        when (e.keyCode) {
            KeyEvent.VK_UP -> {
                e.consume()
                focusedRowIndex = (focusedRowIndex - 1).coerceAtLeast(0)
                highlightRow(focusedRowIndex)
            }

            KeyEvent.VK_DOWN -> {
                e.consume()
                focusedRowIndex = (focusedRowIndex + 1).coerceAtMost(visible.size - 1)
                highlightRow(focusedRowIndex)
            }

            KeyEvent.VK_ENTER -> {
                e.consume()
                if (focusedRowIndex in visible.indices) {
                    val row = visible[focusedRowIndex]
                    if (row.modelRowIndex >= 0) {
                        val model = findModel(row.groupIndex, row.modelRowIndex)
                        if (model != null) {
                            selectModel(model.index)
                        }
                    } else {
                        row.toggleExpand()
                        val newVisible = getVisibleRows()
                        focusedRowIndex = focusedRowIndex.coerceAtMost(newVisible.size - 1)
                        highlightRow(focusedRowIndex)
                    }
                }
            }

            KeyEvent.VK_ESCAPE -> {
                e.consume()
                popup?.cancel()
            }
        }
    }

    private fun findModel(groupIndex: Int, modelRowIndex: Int): ModelGrouper.GroupedModel? {
        if (groupIndex >= groups.size) return null
        val group = groups[groupIndex]
        if (modelRowIndex >= group.models.size) return null
        return group.models[modelRowIndex]
    }

    private fun highlightRow(index: Int) {
        val visible = getVisibleRows()
        for ((i, row) in visible.withIndex()) {
            row.component.background = if (i == index)
                UIManager.getColor("MenuItem.selectionBackground")
            else
                UIManager.getColor("MenuItem.background")
        }
        if (index in visible.indices) {
            visible[index].component.scrollRectToVisible(visible[index].component.bounds)
        }
    }
}
