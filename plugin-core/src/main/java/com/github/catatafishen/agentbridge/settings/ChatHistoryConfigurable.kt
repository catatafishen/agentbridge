package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.session.exporters.ExportUtils
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

class ChatHistoryConfigurable(private val project: Project) :
    BoundConfigurable("Chat History"),
    SearchableConfigurable {

    override fun getId(): String = ID

    private val summaryLabel = JBLabel().apply { border = JBUI.Borders.emptyBottom(4) }
    private val tableModel = ConversationTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        emptyText.text = "Start a chat to create conversation history"
        accessibleContext.accessibleName = "Conversation history files"
        columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(80)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(80)
        columnModel.getColumn(3).preferredWidth = JBUI.scale(160)
        columnModel.getColumn(0).cellRenderer = ConversationNameRenderer()
        columnModel.getColumn(1).cellRenderer = MessageCountRenderer()
        columnModel.getColumn(2).cellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable, value: Any?,
                selected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ) {
                if (value != null) append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                setTextAlign(SwingConstants.RIGHT)
            }
        }
    }

    override fun createPanel() = panel {
        val histSettings = ChatHistorySettings.getInstance(project)

        row {
            comment(
                "Conversation sessions stored in the AgentBridge sessions directory."
            )
        }
        row { cell(summaryLabel) }
        row {
            checkBox("Inject prior conversation summary into new sessions")
                .comment(
                    "When enabled, a compressed summary of recent conversation history " +
                        "is prepended to the first prompt of each new session. " +
                        "Useful as a fallback for clients without native session restore, " +
                        "but uses extra tokens. Disabled by default."
                )
                .bindSelected(
                    { ActiveAgentManager.getInjectConversationHistory(project) },
                    { ActiveAgentManager.setInjectConversationHistory(project, it) }
                )
        }
        group("History Limits") {
            row("Web event log size:") {
                spinner(100..10_000, 100)
                    .comment("Maximum number of JS events buffered for web/PWA clients")
                    .bindIntValue({ histSettings.eventLogSize }, { histSettings.eventLogSize = it })
            }
            row("DOM message limit:") {
                spinner(10..1000, 10)
                    .comment("Maximum chat messages visible in the DOM before older ones are trimmed")
                    .bindIntValue(
                        { histSettings.domMessageLimit },
                        { histSettings.domMessageLimit = it }
                    )
            }
            row("Recent turns on restore:") {
                spinner(1..100, 1)
                    .comment("Number of recent turns loaded immediately when restoring a session")
                    .bindIntValue(
                        { histSettings.recentTurnsOnRestore },
                        { histSettings.recentTurnsOnRestore = it }
                    )
            }
            row("Load-more batch size:") {
                spinner(1..50, 1)
                    .comment("Number of turns loaded per 'Load More' click")
                    .bindIntValue(
                        { histSettings.loadMoreBatchSize },
                        { histSettings.loadMoreBatchSize = it }
                    )
            }
        }
        row {
            val decorated = ToolbarDecorator.createDecorator(table)
                .disableAddAction()
                .disableUpDownActions()
                .setRemoveAction { deleteSelectedConversations() }
                .setRemoveActionUpdater {
                    val rows = table.selectedRows
                    rows.isNotEmpty() && rows.none { tableModel.getEntryAt(it).isCurrentSession }
                }
                .addExtraAction(createDeleteAllArchivesAction())
                .addExtraAction(createRefreshAction())
                .addExtraAction(createRevealInFinderAction())
                .createPanel()
            cell(decorated)
                .align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
                .onReset { loadConversations() }
        }.resizableRow().layout(RowLayout.PARENT_GRID)

        ApplicationManager.getApplication().invokeLater(::loadConversations)
    }

    private fun createDeleteAllArchivesAction(): AnAction =
        object : AnAction(
            "Delete All Archives",
            "Delete all archived conversations (keeps current session)",
            AllIcons.Actions.GC
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val result = Messages.showYesNoDialog(
                    project,
                    "Delete all archived conversations?\nThe current session will be kept.\n\n" +
                        "This action cannot be undone.",
                    "Delete All Archives",
                    Messages.getWarningIcon()
                )
                if (result != Messages.YES) return
                val toDelete = tableModel.entries
                    .filter { !it.isCurrentSession }
                    .map { it.path }
                deleteFiles(toDelete)
                loadConversations()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val hasArchives = tableModel.entries.any { !it.isCurrentSession }
                e.presentation.isEnabled = hasArchives
            }
        }

    private fun createRefreshAction(): AnAction =
        object : AnAction("Refresh", "Rescan conversation directory", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = loadConversations()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    private fun createRevealInFinderAction(): AnAction =
        object : AnAction(
            "Show in ${RevealFileAction.getFileManagerName()}",
            "Open sessions directory in file manager",
            AllIcons.Actions.MenuOpen
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                RevealFileAction.openDirectory(ExportUtils.sessionsDir(project))
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    private fun deleteSelectedConversations() {
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) return
        val message = if (selectedRows.size == 1)
            "Delete the selected conversation?\n\nThis action cannot be undone."
        else
            "Delete ${selectedRows.size} selected conversations?\n\nThis action cannot be undone."
        val result = Messages.showYesNoDialog(
            project, message, "Delete Conversations", Messages.getWarningIcon()
        )
        if (result != Messages.YES) return
        val toDelete = selectedRows.map { tableModel.getEntryAt(it).path }
        deleteFiles(toDelete)
        loadConversations()
    }

    private fun deleteFiles(paths: List<Path>) {
        val failures = mutableListOf<String>()
        for (path in paths) {
            try {
                Files.deleteIfExists(path)
            } catch (e: IOException) {
                LOG.warn("Failed to delete conversation file: $path", e)
                failures.add(path.fileName.toString())
            }
        }
        if (failures.isNotEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("AgentBridge Notifications")
                .createNotification(
                    "Failed to delete ${failures.size} file(s): ${failures.joinToString(", ")}",
                    NotificationType.WARNING
                )
                .notify(project)
        }
    }

    private fun loadConversations() {
        table.emptyText.text = "Loading…"
        tableModel.setEntries(emptyList())
        updateSummary(emptyList())
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = scanConversations().sortedByDescending { it.dateMillis }
            ApplicationManager.getApplication().invokeLater {
                tableModel.setEntries(entries)
                updateSummary(entries)
                table.emptyText.text = "No conversations found"
                table.emptyText.appendSecondaryText(
                    "Start a chat to create conversation history",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES, null
                )
            }
        }
    }

    private fun scanConversations(): List<ConversationEntry> {
        val sessionsDir = ExportUtils.sessionsDir(project).toPath()
        if (!Files.isDirectory(sessionsDir)) return emptyList()
        val currentSessionId = runCatching {
            Files.readString(sessionsDir.resolve(".current-session-id")).trim()
        }.getOrNull()
        val indexFile = sessionsDir.resolve("sessions-index.json")
        return if (Files.isRegularFile(indexFile))
            scanFromIndex(sessionsDir, indexFile, currentSessionId)
        else
            scanFromDirectory(sessionsDir, currentSessionId)
    }

    private fun scanFromIndex(sessionsDir: Path, indexFile: Path, currentSessionId: String?): List<ConversationEntry> {
        val normalizedDir = sessionsDir.normalize()
        return try {
            val array = JsonParser.parseString(Files.readString(indexFile)).asJsonArray
            val entries = mutableListOf<ConversationEntry>()
            for (el in array) {
                try {
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val jsonlPath = obj.get("jsonlPath")?.asString ?: "$id.jsonl"
                    val updatedAt = obj.get("updatedAt")?.asLong ?: 0L
                    val jsonlFile = sessionsDir.resolve(jsonlPath).normalize()
                    if (!jsonlFile.startsWith(normalizedDir)) continue  // path traversal guard
                    if (!Files.isRegularFile(jsonlFile)) continue
                    val size = runCatching { Files.size(jsonlFile) }.getOrDefault(0L)
                    val turnCount = obj.get("turnCount")?.takeIf { !it.isJsonNull }?.asInt
                    val messageCount = turnCount ?: ConversationFileUtils.countJsonlLines(jsonlFile)
                    val isCurrentSession = id == currentSessionId
                    val displayName = if (isCurrentSession) CURRENT_SESSION_LABEL
                    else ConversationFileUtils.formatDateMillis(updatedAt)
                    entries.add(
                        ConversationEntry(
                            jsonlFile,
                            displayName,
                            messageCount,
                            size,
                            updatedAt,
                            isCurrentSession
                        )
                    )
                } catch (e: Exception) {
                    LOG.warn("Skipping malformed session index entry: $el", e)
                }
            }
            entries
        } catch (e: Exception) {
            LOG.warn("Failed to parse sessions index: $indexFile", e)
            emptyList()
        }
    }

    private fun scanFromDirectory(sessionsDir: Path, currentSessionId: String?): List<ConversationEntry> {
        return runCatching {
            Files.list(sessionsDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                    .map { file ->
                        val id = file.fileName.toString().removeSuffix(".jsonl")
                        val size = runCatching { Files.size(file) }.getOrDefault(0L)
                        val dateMillis = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
                        val messageCount = ConversationFileUtils.countJsonlLines(file)
                        val isCurrentSession = id == currentSessionId
                        val displayName = if (isCurrentSession) CURRENT_SESSION_LABEL
                        else ConversationFileUtils.formatDateMillis(dateMillis)
                        ConversationEntry(file, displayName, messageCount, size, dateMillis, isCurrentSession)
                    }
                    .sorted(Comparator.comparingLong<ConversationEntry> { it.dateMillis }.reversed())
                    .toList()
            }
        }.getOrElse { e ->
            LOG.warn("Failed to scan sessions directory: $sessionsDir", e)
            emptyList()
        }
    }

    private fun updateSummary(entries: List<ConversationEntry>) {
        if (entries.isEmpty()) {
            summaryLabel.text = " "
            return
        }
        val totalSize = entries.sumOf { it.size }
        val countText = if (entries.size == 1) "1 conversation" else "${entries.size} conversations"
        summaryLabel.text = "$countText using ${ConversationFileUtils.formatFileSize(totalSize)}"
    }

    @JvmRecord
    data class ConversationEntry(
        val path: Path,
        val displayName: String,
        val messageCount: Int,
        val size: Long,
        val dateMillis: Long,
        val isCurrentSession: Boolean
    )

    private class ConversationTableModel : AbstractTableModel() {
        private val rows = mutableListOf<ConversationEntry>()
        val entries: List<ConversationEntry> get() = rows.toList()

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = COLUMNS.size
        override fun getColumnName(column: Int): String = COLUMNS[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = rows[rowIndex]
            return when (columnIndex) {
                0 -> entry.displayName
                1 -> if (entry.messageCount >= 0) entry.messageCount.toString() else "—"
                2 -> ConversationFileUtils.formatFileSize(entry.size)
                3 -> ConversationFileUtils.formatDateMillis(entry.dateMillis)
                else -> ""
            }
        }

        fun getEntryAt(row: Int): ConversationEntry = rows[row]

        fun setEntries(newEntries: List<ConversationEntry>) {
            rows.clear()
            rows.addAll(newEntries)
            fireTableDataChanged()
        }

        companion object {
            private val COLUMNS = arrayOf("Conversation", "Messages", "Size", "Date")
        }
    }

    private class ConversationNameRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable, value: Any?,
            selected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ) {
            if (value == null) return
            val model = table.model as ConversationTableModel
            val entry = model.getEntryAt(row)
            if (entry.isCurrentSession) {
                icon = AllIcons.Actions.Execute
                append(value.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  (active)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            } else {
                icon = AllIcons.Vcs.History
                append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    private class MessageCountRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable, value: Any?,
            selected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ) {
            setTextAlign(SwingConstants.RIGHT)
            if (value == null) return
            val text = value.toString()
            if (text == "—") {
                append(text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = "Unable to read message count from file"
            } else {
                append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = null
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.chatHistory"
        private val LOG = Logger.getInstance(ChatHistoryConfigurable::class.java)
        private const val CURRENT_SESSION_LABEL = "Current Session"
    }
}
