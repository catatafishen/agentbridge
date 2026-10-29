package com.github.catatafishen.agentbridge.ui.sessions

import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.session.db.SessionSearch
import com.github.catatafishen.agentbridge.ui.FileNavigator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent

/**
 * Advanced session search: free text over session names, prompts, branches and commits, filters,
 * grouping, and a preview of the selected session with clickable commits.
 *
 * Resume semantics are unchanged: choosing a session resumes the whole session. The chosen session is
 * available in [selected] after the dialog closes with OK.
 */
class SessionPickerDialog(
    private val project: Project,
    initialQuery: String = "",
    initialBranch: String? = null,
) : DialogWrapper(project, true) {

    private sealed interface Row {
        data class Header(val title: String, val count: Int) : Row
        data class Item(val session: SessionSearch.SessionSummary) : Row
    }

    private enum class TimeRange(val label: String, val days: Long) {
        ANY("Any time", 0),
        TODAY("Today", 1),
        WEEK("Last 7 days", 7),
        MONTH("Last 30 days", 30),
    }

    /** The session the user picked, or null when cancelled. */
    var selected: SessionSearch.SessionSummary? = null
        private set

    private val service = ConversationService.getInstance(project)
    private val loadSerial = AtomicInteger()
    private val previewSerial = AtomicInteger()

    private val searchField = SearchTextField(false).apply { text = initialQuery }
    private val branchModel = DefaultComboBoxModel(arrayOf(ALL_BRANCHES))
    private val branchCombo = ComboBox(branchModel)
    private val agentModel = DefaultComboBoxModel(arrayOf(ALL_AGENTS))
    private val agentCombo = ComboBox(agentModel)
    private val timeCombo = ComboBox(TimeRange.entries.toTypedArray())
    private val groupCombo = ComboBox(GroupBy.entries.toTypedArray())
    private val withCommitsBox = JBCheckBox("Only with commits")
    private val currentBranch: String? = GitBranchLookup.current(project)
    private val currentBranchBox = JBCheckBox(
        if (currentBranch != null) "On current branch ($currentBranch)" else "On current branch"
    ).apply { isVisible = currentBranch != null }

    private val listModel = DefaultListModel<Row>()
    private val list = JBList(listModel)
    private val countLabel = JBLabel(" ").apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val preview = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKitBuilder.simple()
        border = JBUI.Borders.empty(8)
        background = UIUtil.getPanelBackground()
    }

    private val debounce = Timer(DEBOUNCE_MS) { reload() }.apply { isRepeats = false }
    private var lastQuery = initialQuery
    private var adjusting = false

    init {
        title = "Resume Session"
        setOKButtonText("Resume")
        setSize(JBUI.scale(980), JBUI.scale(600))

        if (initialBranch != null) {
            branchModel.addElement(initialBranch)
            branchCombo.selectedItem = initialBranch
        }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RowRenderer()
        list.addListSelectionListener { if (!it.valueIsAdjusting) onSelectionChanged() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && list.selectedValue is Row.Item) doOKAction()
            }
        })
        preview.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) openCommit(e.description)
        }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                lastQuery = searchField.text
                debounce.restart()
            }
        })
        val refresh = java.awt.event.ActionListener { reload() }
        listOf(branchCombo, agentCombo, timeCombo, groupCombo, withCommitsBox, currentBranchBox)
            .forEach { c ->
                when (c) {
                    is ComboBox<*> -> c.addActionListener(refresh)
                    is JBCheckBox -> c.addActionListener(refresh)
                }
            }
        init()
        loadFilterValues()
        reload()
    }

    override fun getDimensionServiceKey() = "AgentBridge.SessionPickerDialog"

    override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

    override fun createCenterPanel(): JComponent {
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(JBLabel("Branch:")); add(branchCombo)
            add(JBLabel("Agent:")); add(agentCombo)
            add(JBLabel("When:")); add(timeCombo)
            add(JBLabel("Group by:")); add(groupCombo)
            add(withCommitsBox)
            add(currentBranchBox)
        }
        val top = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(searchField, BorderLayout.NORTH)
            add(filters, BorderLayout.CENTER)
        }
        val left = JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
            add(countLabel, BorderLayout.SOUTH)
        }
        val right = ScrollPaneFactory.createScrollPane(preview).apply {
            border = BorderFactory.createEmptyBorder()
        }
        val splitter = OnePixelSplitter(false, 0.48f).apply {
            firstComponent = left
            secondComponent = right
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(940), JBUI.scale(540))
            add(top, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val item = list.selectedValue as? Row.Item ?: return
        selected = item.session
        super.doOKAction()
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private fun loadFilterValues() {
        AppExecutorUtil.getAppExecutorService().execute {
            val branches: List<String> = service.listSessionBranches()
            val agents: List<String> = service.listDistinctAgents()
            ApplicationManager.getApplication().invokeLater({
                val keepBranch = branchCombo.selectedItem as? String
                branches.filter { branchModel.getIndexOf(it) < 0 }.forEach(branchModel::addElement)
                agents.filter { agentModel.getIndexOf(it) < 0 }.forEach(agentModel::addElement)
                if (keepBranch != null) branchCombo.selectedItem = keepBranch
            }, ModalityState.any())
        }
    }

    private fun currentParams(): SessionSearch.Params {
        val branch = when {
            currentBranchBox.isSelected && currentBranch != null -> currentBranch
            else -> (branchCombo.selectedItem as? String)?.takeIf { it != ALL_BRANCHES }
        }
        val agent = (agentCombo.selectedItem as? String)?.takeIf { it != ALL_AGENTS }
        val range = timeCombo.selectedItem as? TimeRange ?: TimeRange.ANY
        val since = if (range == TimeRange.ANY) 0L else System.currentTimeMillis() - range.days * 86_400_000L
        return SessionSearch.Params(
            lastQuery.trim().ifEmpty { null }, agent, branch, null, since,
            withCommitsBox.isSelected, RESULT_LIMIT, 0,
        )
    }

    private fun reload() {
        val serial = loadSerial.incrementAndGet()
        val params = currentParams()
        val groupBy = groupCombo.selectedItem as? GroupBy ?: GroupBy.RECENT
        val previousId = (list.selectedValue as? Row.Item)?.session?.id()
        AppExecutorUtil.getAppExecutorService().execute {
            val result: List<SessionSearch.SessionSummary> = service.searchSessions(params)
            val groups = SessionGrouping.group(result, groupBy, currentBranch)
            ApplicationManager.getApplication().invokeLater({
                if (serial == loadSerial.get()) showResults(result.size, groups, previousId)
            }, ModalityState.any())
        }
    }

    private fun showResults(total: Int, groups: List<SessionGroup>, keepSelectedId: String?) {
        adjusting = true
        try {
            listModel.clear()
            var restore = -1
            var firstItem = -1
            for (g in groups) {
                listModel.addElement(Row.Header(g.title, g.sessions.size))
                for (s in g.sessions) {
                    listModel.addElement(Row.Item(s))
                    if (firstItem < 0) firstItem = listModel.size() - 1
                    if (restore < 0 && s.id() == keepSelectedId) restore = listModel.size() - 1
                }
            }
            countLabel.text = when (total) {
                0 -> "No matching sessions"
                RESULT_LIMIT -> "Showing the $RESULT_LIMIT most recent matches. Refine your search to narrow down."
                else -> "$total session${if (total == 1) "" else "s"}"
            }
            val target = if (restore >= 0) restore else firstItem
            if (target >= 0) list.selectedIndex = target else list.clearSelection()
        } finally {
            adjusting = false
        }
        onSelectionChanged()
    }

    // ── Selection and preview ─────────────────────────────────────────────────

    private fun onSelectionChanged() {
        if (adjusting) return
        val row = list.selectedValue
        if (row is Row.Header) {
            val next = (list.selectedIndex + 1 until listModel.size()).firstOrNull { listModel[it] is Row.Item }
            if (next != null) list.selectedIndex = next
            return
        }
        val item = row as? Row.Item
        isOKActionEnabled = item != null
        if (item == null) {
            preview.text = EMPTY_PREVIEW
            return
        }
        val serial = previewSerial.incrementAndGet()
        val query = lastQuery
        AppExecutorUtil.getAppExecutorService().execute {
            val turns: List<SessionSearch.TurnPreview> = service.listSessionTurns(item.session.id())
            val html = SessionFormat.previewHtml(
                item.session, turns, query, ColorUtil.toHtmlColor(JBUI.CurrentTheme.ContextHelp.FOREGROUND),
            )
            ApplicationManager.getApplication().invokeLater({
                if (serial == previewSerial.get()) {
                    preview.text = html
                    preview.caretPosition = 0
                }
            }, ModalityState.any())
        }
    }

    private fun openCommit(description: String?) {
        val hash = description?.removePrefix("commit:") ?: return
        FileNavigator(project).handleFileLink("gitshow://$hash")
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private inner class RowRenderer : ListCellRenderer<Row> {
        private val panel = JPanel(BorderLayout())
        private val title = JBLabel()
        private val detail = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        private val time = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        private val header = JBLabel().apply {
            font = JBUI.Fonts.smallFont().asBold()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            border = JBUI.Borders.empty(8, 8, 2, 8)
            isOpaque = true
        }

        init {
            val text = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(title, BorderLayout.NORTH)
                add(detail, BorderLayout.SOUTH)
            }
            panel.border = JBUI.Borders.empty(4, 8)
            panel.add(text, BorderLayout.CENTER)
            panel.add(time, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out Row>, value: Row, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            if (value is Row.Header) {
                header.text = "${value.title}  (${value.count})"
                header.background = list.background
                return header
            }
            val s = (value as Row.Item).session
            val bg = if (isSelected) list.selectionBackground else list.background
            val fg = if (isSelected) list.selectionForeground else list.foreground
            panel.background = bg
            panel.isOpaque = true
            title.foreground = fg
            val shown = s.matchedPrompt() ?: s.label()
            title.text = "<html>" + SessionFormat.highlight(SessionFormat.truncate(shown, 90), lastQuery) + "</html>"
            detail.text = SessionFormat.detailLine(s)
            time.text = SessionFormat.relativeTime(s.lastActivityMs())
            return panel
        }
    }

    companion object {
        private const val ALL_BRANCHES = "All branches"
        private const val ALL_AGENTS = "All agents"
        private const val DEBOUNCE_MS = 250
        private const val RESULT_LIMIT = 300
        private const val EMPTY_PREVIEW = "<html><body></body></html>"
    }
}
