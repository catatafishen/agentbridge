package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
import com.github.catatafishen.agentbridge.ui.side.PromptsPanel.Companion.MAX_CHARS
import com.github.catatafishen.agentbridge.ui.side.PromptsPanel.Companion.MAX_ROWS
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.event.DocumentEvent

internal class PromptsPanel(
    private val project: Project,
    private val chatConsole: ChatConsolePanel
) : JPanel(BorderLayout()), Disposable {

    private data class PromptItem(
        val prompt: EntryData.Prompt,
        val stats: EntryData.TurnStats?,
        val commits: List<String>,
        val sessionId: String = ""
    )

    private val searchField = SearchTextField()
    private val listModel = DefaultListModel<PromptItem>()
    private val promptList = JBList(listModel)
    private val sessionStore = ConversationService.getInstance(project)
    private val historyLoadSerial = AtomicInteger()
    private val entriesListener = Runnable {
        ApplicationManager.getApplication().invokeLater(::onEntriesChanged)
    }

    private val hierarchyListener = java.awt.event.HierarchyListener { e ->
        if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isShowing) {
            SwingUtilities.invokeLater { scrollToBottom() }
        }
    }

    private var displayedCount = PAGE_SIZE
    private var lastQuery = ""

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

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val q = searchField.text.orEmpty()
                if (q != lastQuery) {
                    lastQuery = q
                    displayedCount = PAGE_SIZE
                }
                refresh(scrollToBottom = false)
            }
        })
        searchField.textEditor.emptyText.text = "Search prompts…"

        val top = JPanel(BorderLayout())
        top.border = JBUI.Borders.empty(4)
        top.add(searchField, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)

        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(loadMorePanel, BorderLayout.NORTH)
        val scrollPane = JBScrollPane(promptList)
        scrollPane.border = JBUI.Borders.empty()
        centerPanel.add(scrollPane, BorderLayout.CENTER)
        add(centerPanel, BorderLayout.CENTER)

        chatConsole.addEntriesChangeListener(entriesListener)

        addHierarchyListener(hierarchyListener)

        reloadHistoryAsync()
        refresh()
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
                refresh()
            }
        }
    }

    private fun refresh() {
        refresh(scrollToBottom = true)
    }

    private fun refresh(scrollToBottom: Boolean) {
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
            listModel.addElement(PromptItem(p, data?.stats, data?.commits ?: emptyList(), sid))
        }

        if (scrollToBottom && listModel.size() > 0) {
            SwingUtilities.invokeLater {
                promptList.ensureIndexIsVisible(listModel.size() - 1)
            }
        }
    }

    private fun loadMore() {
        // Preserve scroll: after adding PAGE_SIZE new items at top, scroll back to item at PAGE_SIZE
        val targetIndex = PAGE_SIZE.coerceAtMost(listModel.size())
        displayedCount += PAGE_SIZE
        refresh(scrollToBottom = false)
        if (targetIndex > 0 && targetIndex < listModel.size()) {
            val bounds = promptList.getCellBounds(targetIndex, targetIndex)
            if (bounds != null) promptList.scrollRectToVisible(bounds)
        }
    }

    override fun dispose() {
        chatConsole.removeEntriesChangeListener(entriesListener)
        removeHierarchyListener(hierarchyListener)
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
            tsLabel.text = formatTimestamp(value.prompt.timestamp)
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
        private const val MAX_CHARS = 200
        private const val MAX_ROWS = 5

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
