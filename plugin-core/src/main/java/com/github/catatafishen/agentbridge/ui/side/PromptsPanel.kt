package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.services.PromptDbService
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.session.db.ConversationQuery
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
import com.github.catatafishen.agentbridge.ui.side.PromptsPanel.Companion.MAX_CHARS
import com.github.catatafishen.agentbridge.ui.side.PromptsPanel.Companion.MAX_ROWS
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.event.DocumentEvent

private const val ALL_BRANCHES = "(all branches)"
private const val ALL_AGENTS = "(all agents)"
private const val ALL_TOOLS = "(all tools)"
private const val BRANCH_PREVIEW_SIZE = 5

internal class PromptsPanel(
    private val project: Project,
    private val chatConsole: ChatConsolePanel
) : JPanel(BorderLayout()), Disposable {

    private data class PromptItem(
        val prompt: EntryData.Prompt,
        val stats: EntryData.TurnStats?,
        val commits: List<String>,
        val sessionId: String = "",
        val turnId: String = ""
    )

    // ── Main search ──────────────────────────────────────────────────────────

    private val searchField = SearchTextField()

    // ── Search scope checkboxes (inside advanced panel) ──────────────────────

    private val scopePrompt = JCheckBox("Prompt", true).apply { font = JBUI.Fonts.miniFont(); isOpaque = false }
    private val scopeText = JCheckBox("Text events", true).apply { font = JBUI.Fonts.miniFont(); isOpaque = false }
    private val scopeThinking = JCheckBox("Thinking", false).apply { font = JBUI.Fonts.miniFont(); isOpaque = false }
    private val scopeToolCalls = JCheckBox("Tool I/O", false).apply { font = JBUI.Fonts.miniFont(); isOpaque = false }

    // ── Filter controls (inside advanced panel) ──────────────────────────────

    private var allBranches: List<String> = emptyList()
    private var branchUpdating = false
    private var lastBranchText = ""
    private val branchModel = DefaultComboBoxModel<String>().apply { addElement(ALL_BRANCHES) }
    private val branchCombo = ComboBox(branchModel).apply {
        isEditable = true
        font = JBUI.Fonts.miniFont()
    }

    private val agentModel = DefaultComboBoxModel(arrayOf(ALL_AGENTS))
    private val agentCombo = ComboBox(agentModel).apply { font = JBUI.Fonts.miniFont() }

    private val toolModel = DefaultComboBoxModel(arrayOf(ALL_TOOLS))
    private val toolCombo = ComboBox(toolModel).apply { font = JBUI.Fonts.miniFont() }

    private val fileField = JBTextField().apply {
        font = JBUI.Fonts.miniFont()
        emptyText.text = "file path…"
    }

    // ── Advanced panel toggle ────────────────────────────────────────────────

    private var advancedVisible = false
    private val advancedPanel = JPanel(GridBagLayout()).apply { isOpaque = false; isVisible = false }
    private val advancedToggle = JButton("Filters ▼").apply {
        font = JBUI.Fonts.miniFont()
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.emptyTop(2)
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = true
        horizontalAlignment = SwingConstants.CENTER
    }

    // ── List and loading state ───────────────────────────────────────────────

    private val listModel = DefaultListModel<PromptItem>()
    private val promptList = JBList(listModel)
    private val sessionStore = ConversationService.getInstance(project)
    private val historyLoadSerial = AtomicInteger()
    private val entriesListener = Runnable {
        ApplicationManager.getApplication().invokeLater(::onEntriesChanged)
    }
    private val hierarchyListener = java.awt.event.HierarchyListener { e ->
        if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isShowing) {
            ApplicationManager.getApplication().invokeLater { scrollToBottom() }
        }
    }

    private var displayedCount = PAGE_SIZE
    private var autoLoadingMore = false
    private var initialLoadDone = false

    @Volatile
    private var historyEntries: List<EntryData> = emptyList()

    @Volatile
    private var promptSessionMap: Map<String, String> = emptyMap()

    private val loadMoreLabel = JLabel("↑ Load earlier prompts").apply {
        font = JBUI.Fonts.miniFont()
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = loadMore()
        })
    }

    private val loadMorePanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        add(loadMoreLabel, BorderLayout.CENTER)
        isVisible = false
    }

    init {
        promptList.cellRenderer = BubbleRenderer()
        promptList.fixedCellHeight = -1
        promptList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = promptList.locationToIndex(e.point)
                if (idx < 0) return
                // locationToIndex returns the nearest index even when clicking empty space below
                // the last row — guard against that by verifying the click is inside the cell.
                val cellBounds = promptList.getCellBounds(idx, idx) ?: return
                if (!cellBounds.contains(e.point)) return
                val item = listModel.getElementAt(idx) ?: return
                val entryId = promptEntryId(item.prompt)
                if (entryId.isEmpty()) return
                if (chatConsole.isEntryRendered(entryId)) {
                    chatConsole.scrollToEntry(entryId)
                } else {
                    val sessionId = item.sessionId.ifEmpty { promptSessionMap[entryId] ?: return }
                    HistoryContextWindow.open(project, sessionId, entryId)
                }
            }
        })

        val filterChangeListener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                displayedCount = PAGE_SIZE
                refresh(scrollToBottom = false)
            }
        }

        searchField.addDocumentListener(filterChangeListener)
        searchField.textEditor.emptyText.text = "Search prompts…"
        fileField.document.addDocumentListener(filterChangeListener)

        agentCombo.addActionListener { displayedCount = PAGE_SIZE; refresh(scrollToBottom = false) }
        toolCombo.addActionListener { displayedCount = PAGE_SIZE; refresh(scrollToBottom = false) }

        // Branch editable combo: listen to editor text and action events (deduped via lastBranchText)
        val branchEditor = branchCombo.editor.editorComponent as? JTextField
        branchEditor?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (branchUpdating) return
                val text = branchEditor.text.orEmpty().trim()
                // Defer model mutation: modifying the combo model triggers configureEditor→setText,
                // which is forbidden while a document notification is already in progress.
                SwingUtilities.invokeLater { updateBranchPopup(text) }
                if (text != lastBranchText) {
                    lastBranchText = text
                    displayedCount = PAGE_SIZE
                    refresh(scrollToBottom = false)
                }
            }
        })
        branchCombo.addActionListener {
            if (!branchUpdating) {
                val text = (branchCombo.editor?.item as? String)?.trim().orEmpty()
                if (text != lastBranchText) {
                    lastBranchText = text
                    displayedCount = PAGE_SIZE
                    refresh(scrollToBottom = false)
                }
            }
        }

        val scopeListener = java.awt.event.ActionListener {
            displayedCount = PAGE_SIZE
            refresh(scrollToBottom = false)
        }
        scopePrompt.addActionListener(scopeListener)
        scopeText.addActionListener(scopeListener)
        scopeThinking.addActionListener(scopeListener)
        scopeToolCalls.addActionListener(scopeListener)

        advancedToggle.addActionListener { toggleAdvanced() }

        buildAdvancedPanel()

        val searchRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchField, BorderLayout.CENTER)
        }

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(searchRow, BorderLayout.NORTH)
            add(advancedPanel, BorderLayout.CENTER)
            add(advancedToggle, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)

        // loadMorePanel lives inside the scrollable area, above the promptList.
        // A Scrollable-delegating wrapper preserves JBList scroll behaviour while
        // allowing loadMorePanel to sit above the list and scroll with the content.
        val listWrapper = object : JPanel(BorderLayout()), Scrollable {
            override fun getScrollableTracksViewportWidth() = promptList.scrollableTracksViewportWidth
            override fun getScrollableTracksViewportHeight() = false
            override fun getPreferredScrollableViewportSize(): Dimension = promptList.preferredScrollableViewportSize
            override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = promptList.getScrollableUnitIncrement(r, o, d)
            override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = promptList.getScrollableBlockIncrement(r, o, d)
        }.apply {
            isOpaque = false
            add(loadMorePanel, BorderLayout.NORTH)
            add(promptList, BorderLayout.CENTER)
        }
        val scrollPane = JBScrollPane(listWrapper)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)

        // Auto-load when the user scrolls to the very top and loadMorePanel is visible.
        scrollPane.viewport.addChangeListener {
            val vp = scrollPane.viewport
            if (vp.viewPosition.y == 0 && loadMorePanel.isVisible && !autoLoadingMore && initialLoadDone) {
                autoLoadingMore = true
                try {
                    loadMore()
                } finally {
                    autoLoadingMore = false
                }
            }
        }

        chatConsole.addEntriesChangeListener(entriesListener)
        addHierarchyListener(hierarchyListener)
        PromptDbService.getInstance(project).registerNavigateCallback(::applySearchParams)
        populateFilterCombos()
        reloadHistoryAsync()
        refresh()
    }

    private fun buildAdvancedPanel() {
        advancedPanel.border = JBUI.Borders.emptyTop(4)
        val rowGap = JBUI.scale(3)
        val labelGap = JBUI.scale(4)
        val gap = JBUI.scale(2)

        // Row 0: scope checkboxes (spans all columns)
        val scopeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JLabel("Search in:").apply { font = JBUI.Fonts.miniFont() })
            add(scopePrompt)
            add(scopeText)
            add(scopeThinking)
            add(scopeToolCalls)
        }
        advancedPanel.add(
            scopeRow, gbc(
                gridx = 0, gridy = 0, gridwidth = 4, fillH = true, weightx = 1.0,
                bottom = rowGap
            )
        )

        // Row 1: Branch label + Branch combo (full width)
        advancedPanel.add(
            JLabel("Branch:").apply { font = JBUI.Fonts.miniFont() },
            gbc(gridx = 0, gridy = 1, right = labelGap, bottom = rowGap)
        )
        advancedPanel.add(
            branchCombo,
            gbc(gridx = 1, gridy = 1, gridwidth = 3, fillH = true, weightx = 1.0, bottom = rowGap)
        )

        // Row 2: Agent label + Agent combo | Tool label + Tool combo
        advancedPanel.add(
            JLabel("Agent:").apply { font = JBUI.Fonts.miniFont() },
            gbc(gridx = 0, gridy = 2, right = labelGap, bottom = rowGap)
        )
        advancedPanel.add(
            agentCombo,
            gbc(gridx = 1, gridy = 2, fillH = true, weightx = 0.4, right = JBUI.scale(8), bottom = rowGap)
        )
        advancedPanel.add(
            JLabel("Tool:").apply { font = JBUI.Fonts.miniFont() },
            gbc(gridx = 2, gridy = 2, right = labelGap, bottom = rowGap)
        )
        advancedPanel.add(
            toolCombo,
            gbc(gridx = 3, gridy = 2, fillH = true, weightx = 0.6, bottom = rowGap)
        )

        // Row 3: File label + File field (full width)
        advancedPanel.add(
            JLabel("File:").apply { font = JBUI.Fonts.miniFont() },
            gbc(gridx = 0, gridy = 3, right = labelGap, bottom = gap)
        )
        advancedPanel.add(
            fileField,
            gbc(gridx = 1, gridy = 3, gridwidth = 3, fillH = true, weightx = 1.0, bottom = gap)
        )
    }

    private fun gbc(
        gridx: Int, gridy: Int,
        gridwidth: Int = 1,
        fillH: Boolean = false,
        weightx: Double = 0.0,
        bottom: Int = 0,
        right: Int = 0
    ) = GridBagConstraints().also {
        it.gridx = gridx
        it.gridy = gridy
        it.gridwidth = gridwidth
        it.fill = if (fillH) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
        it.weightx = weightx
        it.anchor = GridBagConstraints.WEST
        it.insets = JBUI.insets(0, 0, bottom, right)
    }

    private fun toggleAdvanced() {
        advancedVisible = !advancedVisible
        advancedPanel.isVisible = advancedVisible
        advancedToggle.text = if (advancedVisible) "Filters ▲" else "Filters ▼"
        revalidate()
        repaint()
    }

    private fun scrollToBottom() {
        if (listModel.size() > 0) {
            promptList.ensureIndexIsVisible(listModel.size() - 1)
        }
    }

    private fun onEntriesChanged() {
        if (chatConsole.entriesSnapshot().isEmpty()) {
            historyEntries = emptyList()
            listModel.clear()
            loadMorePanel.isVisible = false
            reloadHistoryAsync()
        } else {
            refresh()
        }
    }

    /** Populates Branch (from DB ordered by recency), Agent, and Tool (from ToolRegistry) dropdowns. */
    private fun populateFilterCombos() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = sessionStore.listDistinctBranches().toList()
            val agents = sessionStore.listDistinctAgents().toList()
            val tools = ToolRegistry.getInstance(project).allTools.map { it.id() }
            ApplicationManager.getApplication().invokeLater {
                allBranches = branches
                updateBranchPopup("")

                val selectedAgent = agentCombo.selectedItem as? String
                agentModel.removeAllElements()
                agentModel.addElement(ALL_AGENTS)
                agents.forEach { agentModel.addElement(it) }
                if (selectedAgent != null && selectedAgent != ALL_AGENTS) {
                    agentCombo.selectedItem = selectedAgent
                }

                val selectedTool = toolCombo.selectedItem as? String
                toolModel.removeAllElements()
                toolModel.addElement(ALL_TOOLS)
                tools.forEach { toolModel.addElement(it) }
                if (selectedTool != null && selectedTool != ALL_TOOLS) {
                    toolCombo.selectedItem = selectedTool
                }
            }
        }
    }

    /**
     * Updates the branch combo popup items based on [filter] text.
     * When [filter] is blank, shows the [BRANCH_PREVIEW_SIZE] most recent branches.
     * Otherwise, shows all branches containing [filter] (case-insensitive).
     */
    private fun updateBranchPopup(filter: String) {
        branchUpdating = true
        try {
            val editorText = (branchCombo.editor?.item as? String).orEmpty()
            val filtered = if (filter.isBlank()) {
                allBranches.take(BRANCH_PREVIEW_SIZE)
            } else {
                allBranches.filter { it.contains(filter, ignoreCase = true) }
            }
            branchModel.removeAllElements()
            branchModel.addElement(ALL_BRANCHES)
            filtered.forEach { branchModel.addElement(it) }
            // Restore the editor text since model replacement clears it
            branchCombo.editor?.item = editorText
        } finally {
            branchUpdating = false
        }
    }

    private fun reloadHistoryAsync() {
        val serial = historyLoadSerial.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val allPrompts = sessionStore.loadPromptsFromAllSessions().toList()
            val entries = ArrayList<EntryData>()
            val sessionMap = HashMap<String, String>()
            for (pwc in allPrompts) {
                entries.add(pwc.prompt)
                pwc.stats?.let { entries.add(it) }
                val key = promptEntryId(pwc.prompt)
                if (key.isNotEmpty()) sessionMap[key] = pwc.sessionId
            }
            ApplicationManager.getApplication().invokeLater {
                if (serial != historyLoadSerial.get()) return@invokeLater
                historyEntries = entries
                promptSessionMap = sessionMap
                initialLoadDone = true
                refresh()
            }
        }
    }

    /** Returns true if any filter is active; triggers SQL-backed query when true. */
    private fun hasActiveFilters(): Boolean {
        if (searchField.text.isNotBlank()) return true
        val branch = (branchCombo.editor?.item as? String)?.trim().orEmpty()
        val agent = agentCombo.selectedItem as? String
        val tool = toolCombo.selectedItem as? String
        return (branch.isNotEmpty() && branch != ALL_BRANCHES) ||
            (agent != null && agent != ALL_AGENTS) ||
            (tool != null && tool != ALL_TOOLS) ||
            fileField.text.isNotBlank()
    }

    private fun refresh() {
        refresh(scrollToBottom = true)
    }

    private fun refresh(scrollToBottom: Boolean) {
        if (hasActiveFilters()) {
            reloadWithSqlFilters(scrollToBottom)
        } else {
            refreshFromMemory(scrollToBottom)
        }
    }

    /**
     * SQL-backed refresh: queries conversation DB with all active filters.
     * Text search is scoped to the checked event types (user prompt, text events, thinking, tool I/O).
     */
    private fun reloadWithSqlFilters(scrollToBottom: Boolean) {
        val combinedText = searchField.text.orEmpty().trim().takeIf { it.isNotEmpty() }
        val branch = (branchCombo.editor?.item as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it != ALL_BRANCHES }
        val agent = (agentCombo.selectedItem as? String)?.takeIf { it != ALL_AGENTS }
        val tool = (toolCombo.selectedItem as? String)?.takeIf { it != ALL_TOOLS }
        val file = fileField.text.trim().takeIf { it.isNotEmpty() }
        val scopes = if (combinedText != null) buildSearchScopes() else null

        val serial = historyLoadSerial.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val params = ConversationQuery.QueryParams(
                null, null, PAGE_SIZE, null,
                null, null, tool, file, branch, agent,
                null, null, false, false, Int.MAX_VALUE,
                combinedText, scopes
            )
            val turns = sessionStore.query(params).toList()
            val items = turns.map { turn ->
                val prompt = EntryData.Prompt(
                    turn.userMessage(), turn.timestamp().toString(),
                    null, turn.turnId(), turn.turnId()
                )
                PromptItem(prompt, null, emptyList(), turn.sessionId(), turn.turnId())
            }
            ApplicationManager.getApplication().invokeLater {
                if (serial != historyLoadSerial.get()) return@invokeLater
                loadMorePanel.isVisible = false
                listModel.clear()
                items.forEach { listModel.addElement(it) }
                if (scrollToBottom && listModel.size() > 0) {
                    ApplicationManager.getApplication().invokeLater {
                        promptList.ensureIndexIsVisible(listModel.size() - 1)
                    }
                }
            }
        }
    }

    private fun buildSearchScopes(): Set<ConversationQuery.SearchScope> {
        val scopes = EnumSet.noneOf(ConversationQuery.SearchScope::class.java)
        if (scopePrompt.isSelected) scopes.add(ConversationQuery.SearchScope.USER_PROMPT)
        if (scopeText.isSelected) scopes.add(ConversationQuery.SearchScope.TEXT_EVENTS)
        if (scopeThinking.isSelected) scopes.add(ConversationQuery.SearchScope.THINKING)
        if (scopeToolCalls.isSelected) scopes.add(ConversationQuery.SearchScope.TOOL_CALLS)
        return scopes
    }

    /** In-memory refresh: uses loaded history entries + live chatConsole entries. No filters active. */
    private fun refreshFromMemory(scrollToBottom: Boolean) {
        val query = searchField.text.orEmpty()
        val allEntries = mergeEntries(historyEntries, chatConsole.entriesSnapshot())
        val prompts = allEntries.filterIsInstance<EntryData.Prompt>()
            .sortedBy { it.timestamp }
        val filtered = filterPrompts(prompts, query)
        val turnDataMap = buildTurnDataMap(allEntries)

        val hasMore = filtered.size > displayedCount
        loadMorePanel.isVisible = hasMore
        val visible = if (hasMore) filtered.takeLast(displayedCount) else filtered

        listModel.clear()
        visible.forEach { p ->
            val key = promptEntryId(p)
            val data = turnDataMap[key]
            val sid = promptSessionMap[key] ?: ""
            val tid = p.id.takeIf { it.isNotEmpty() } ?: p.entryId
            listModel.addElement(PromptItem(p, data?.stats, data?.commits ?: emptyList(), sid, tid))
        }

        if (scrollToBottom && listModel.size() > 0) {
            ApplicationManager.getApplication().invokeLater {
                promptList.ensureIndexIsVisible(listModel.size() - 1)
            }
        }
    }

    private fun loadMore() {
        val targetIndex = PAGE_SIZE.coerceAtMost(listModel.size())
        displayedCount += PAGE_SIZE
        refresh(scrollToBottom = false)
        if (targetIndex > 0 && targetIndex < listModel.size()) {
            val bounds = promptList.getCellBounds(targetIndex, targetIndex)
            if (bounds != null) promptList.scrollRectToVisible(bounds)
        }
    }

    /**
     * Fills search fields from the given query params and runs the search.
     * Called by [PromptDbService] when follow-agent mode is on and the agent calls
     * [com.github.catatafishen.agentbridge.psi.tools.editor.QueryTurnsTool].
     * Must be called on the EDT.
     */
    fun applySearchParams(params: ConversationQuery.QueryParams) {
        // Clear all fields first so omitted filters don't carry over from a previous agent query.
        searchField.text = ""
        branchCombo.editor?.item = ""
        agentModel.selectedItem = null
        toolModel.selectedItem = null
        fileField.text = ""

        // Main search box: prefer combinedText, then userMessage, then assistantText.
        // When assistantText is the sole filter, select the Text Events scope so the panel
        // surfaces those turns (Prompt scope alone would miss assistant-text matches).
        val assistantTextOnly = params.assistantText != null
            && params.combinedText == null && params.userMessage == null
        searchField.text = params.combinedText ?: params.userMessage ?: params.assistantText ?: ""

        if (assistantTextOnly) {
            scopePrompt.isSelected = false
            scopeText.isSelected = true
        }

        params.branch?.takeIf { it.isNotEmpty() }?.let { branchCombo.editor?.item = it }
        params.agentName?.takeIf { it.isNotEmpty() }?.let { agentModel.selectedItem = it }
        params.toolName?.takeIf { it.isNotEmpty() }?.let { toolModel.selectedItem = it }
        params.filePath?.takeIf { it.isNotEmpty() }?.let { fileField.text = it }

        val hasAdvanced = !params.branch.isNullOrEmpty() || !params.agentName.isNullOrEmpty() ||
            !params.toolName.isNullOrEmpty() || !params.filePath.isNullOrEmpty()
        if (hasAdvanced && !advancedVisible) toggleAdvanced()

        refresh()
    }

    override fun dispose() {
        chatConsole.removeEntriesChangeListener(entriesListener)
        removeHierarchyListener(hierarchyListener)
        PromptDbService.getInstance(project).registerNavigateCallback(null)
    }

    private class BubbleRenderer : ListCellRenderer<PromptItem> {
        private val outer = JPanel(BorderLayout(0, JBUI.scale(2)))
        private val headerPanel = JPanel(BorderLayout())
        private val tsLabel = JLabel()
        private val statsLabel = JLabel()
        private val textArea = JTextArea()
        private val commitsLabel = JLabel()
        private val commitsPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(commitsLabel, BorderLayout.CENTER)
        }

        init {
            outer.isOpaque = true
            tsLabel.font = JBUI.Fonts.miniFont()
            tsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            statsLabel.font = JBUI.Fonts.miniFont()
            statsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            headerPanel.isOpaque = false
            headerPanel.add(tsLabel, BorderLayout.WEST)
            headerPanel.add(statsLabel, BorderLayout.EAST)
            textArea.isOpaque = false
            textArea.isEditable = false
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            textArea.font = UIManager.getFont("Label.font") ?: textArea.font
            textArea.border = null
            textArea.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            commitsLabel.font = JBUI.Fonts.miniFont()
            commitsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            outer.add(headerPanel, BorderLayout.NORTH)
            outer.add(textArea, BorderLayout.CENTER)
            outer.add(commitsPanel, BorderLayout.SOUTH)
            outer.border = JBUI.Borders.compound(
                JBUI.Borders.empty(1, 0),
                JBUI.Borders.empty(4, 8, 4, 6)
            )
        }

        override fun getListCellRendererComponent(
            list: JList<out PromptItem>?,
            value: PromptItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return outer
            val listWidth = list?.width ?: 0
            if (listWidth > 0) {
                textArea.setSize(listWidth - JBUI.scale(18), Short.MAX_VALUE.toInt())
            }
            val turnIdSuffix = value.turnId.takeIf { it.length >= 8 }?.let { " · ${it.take(8)}…" } ?: ""
            tsLabel.text = formatTimestamp(value.prompt.timestamp) + turnIdSuffix
            statsLabel.text = formatStats(value.stats)
            textArea.text = truncatePrompt(value.prompt.text.trim())

            val commitsText = formatCommits(value.commits)
            commitsLabel.text = commitsText
            commitsPanel.isVisible = commitsText.isNotEmpty()

            if (isSelected) {
                val selFg = list?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
                outer.background = list?.selectionBackground ?: UIManager.getColor("List.selectionBackground")
                textArea.foreground = selFg
                tsLabel.foreground = selFg
                statsLabel.foreground = selFg
                commitsLabel.foreground = selFg
            } else {
                outer.background = list?.background ?: UIManager.getColor("List.background")
                textArea.foreground = list?.foreground ?: UIManager.getColor("List.foreground")
                tsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                statsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                commitsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }

            val commitsHeight = if (commitsPanel.isVisible) commitsLabel.preferredSize.height + JBUI.scale(2) else 0
            val textHeight = textArea.preferredSize.height
            outer.preferredSize = Dimension(
                listWidth,
                tsLabel.preferredSize.height + JBUI.scale(2) + textHeight + commitsHeight + JBUI.scale(10)
            )
            return outer
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        const val MAX_CHARS = 200
        const val MAX_ROWS = 5

        private data class TurnData(val stats: EntryData.TurnStats, val commits: List<String>)

        /**
         * Truncates [text] to at most [MAX_ROWS] lines and at most [MAX_CHARS] characters,
         * whichever limit is reached first. Appends "…" when truncation occurs.
         */
        fun truncatePrompt(text: String): String {
            val lines = text.lines()
            val byRows = if (lines.size > MAX_ROWS) lines.take(MAX_ROWS).joinToString("\n") else text
            val truncated = byRows.take(MAX_CHARS)
            return if (truncated.length < text.length) truncated.trimEnd() + "…" else text
        }

        fun formatTimestamp(iso: String): String =
            com.github.catatafishen.agentbridge.ui.util.TimestampDisplayFormatter.formatIsoTimestamp(iso)

        fun filterPrompts(prompts: List<EntryData.Prompt>, query: String): List<EntryData.Prompt> {
            val q = query.trim()
            if (q.isEmpty()) return prompts
            val needle = q.lowercase()
            return prompts.filter { it.text.lowercase().contains(needle) }
        }

        fun mergeEntries(historyEntries: List<EntryData>, liveEntries: List<EntryData>): List<EntryData> {
            if (liveEntries.isEmpty()) return historyEntries
            if (historyEntries.isEmpty()) return liveEntries

            // Live entries are the source of truth — they include entries loaded at startup
            // plus any new entries added during the session. Supplement with history entries
            // that the live set doesn't have (old entries pruned from bounded chat memory).
            val liveIds = liveEntries.mapTo(HashSet()) { it.entryId }
            val supplemental = historyEntries.filter { it.entryId !in liveIds }
            return supplemental + liveEntries
        }

        fun promptEntryId(p: EntryData.Prompt): String = p.id.ifEmpty { p.entryId }

        /**
         * Builds a map from prompt key → TurnData (stats + commit hashes).
         *
         * For V2 sessions, [EntryData.TurnStats.turnId] matches [EntryData.Prompt.id] directly
         * and is used as the primary lookup. For V1 sessions where turnId is empty, we fall back
         * to positional matching: the TurnStats immediately following a Prompt is attributed to it.
         */
        private fun buildTurnDataMap(entries: List<EntryData>): Map<String, TurnData> {
            val result = mutableMapOf<String, TurnData>()
            var lastPromptId: String? = null
            for (entry in entries) {
                when (entry) {
                    is EntryData.Prompt -> lastPromptId = promptEntryId(entry)
                    is EntryData.TurnStats -> {
                        val key = entry.turnId.takeIf { it.isNotEmpty() } ?: lastPromptId
                        if (key != null) {
                            result[key] = TurnData(entry, entry.commitHashes)
                            lastPromptId = null
                        }
                    }

                    else -> Unit
                }
            }
            return result
        }

        fun formatStats(stats: EntryData.TurnStats?): String {
            if (stats == null) return ""
            val parts = mutableListOf<String>()
            if (stats.toolCallCount > 0) parts.add("${stats.toolCallCount} tools")
            if (stats.durationMs > 0) {
                val s = stats.durationMs / 1000.0
                parts.add(if (s < 60) "%.1fs".format(s) else "${(s / 60).toInt()}m ${(s % 60).toInt()}s")
            }
            return parts.joinToString(" · ")
        }

        fun formatCommits(hashes: List<String>): String {
            if (hashes.isEmpty()) return ""
            val abbrev = hashes.map { it.take(7) }
            val label = if (hashes.size == 1) "Commit" else "${hashes.size} commits"
            return "$label: ${abbrev.joinToString(", ")}"
        }
    }
}
