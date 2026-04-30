package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ActiveAgentManager
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
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.io.IOException
import java.nio.file.DirectoryStream
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

    private val summaryLabel = JBLabel().apply { border = JBUI.Borders.empty(0, 0, 4, 0) }
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
                "Conversation files stored in this project's <code>.agent-work</code> directory."
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
                    if (rows.isEmpty()) false
                    else rows.none { tableModel.getEntryAt(it).isCurrentSession }
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
            "Open conversations directory in file manager",
            AllIcons.Actions.MenuOpen
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val basePath = project.basePath ?: return
                var dir = Path.of(basePath, AGENT_WORK_DIR, CONVERSATIONS_DIR)
                if (!Files.isDirectory(dir)) dir = Path.of(basePath, AGENT_WORK_DIR)
                RevealFileAction.openDirectory(dir.toFile())
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
        val basePath = project.basePath ?: return emptyList()
        val entries = mutableListOf<ConversationEntry>()
        val agentWorkDir = Path.of(basePath, AGENT_WORK_DIR)
        val current = agentWorkDir.resolve(CURRENT_SESSION_FILE)
        if (Files.isRegularFile(current)) entries.add(buildEntry(current, true))
        val conversationsDir = agentWorkDir.resolve(CONVERSATIONS_DIR)
        if (Files.isDirectory(conversationsDir)) {
            try {
                Files.newDirectoryStream(
                    conversationsDir, "$ARCHIVE_PREFIX*$JSON_EXTENSION"
                ).use { stream: DirectoryStream<Path> ->
                    for (file in stream) {
                        if (Files.isRegularFile(file)) entries.add(buildEntry(file, false))
                    }
                }
            } catch (e: IOException) {
                LOG.warn("Failed to scan conversations directory: $conversationsDir", e)
            }
        }
        return entries
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

    private fun buildEntry(file: Path, currentSession: Boolean): ConversationEntry {
        val size: Long
        val lastModifiedMillis: Long
        try {
            size = Files.size(file)
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis()
        } catch (e: IOException) {
            LOG.warn("Failed to read file attributes: $file", e)
            return ConversationEntry(file, file.fileName.toString(), 0, 0, 0, currentSession)
        }
        val messageCount = ConversationFileUtils.countMessages(file)
        val displayName: String
        val dateMillis: Long
        if (currentSession) {
            displayName = CURRENT_SESSION_LABEL
            dateMillis = lastModifiedMillis
        } else {
            val timestamp = file.fileName.toString()
                .removePrefix(ARCHIVE_PREFIX)
                .removeSuffix(JSON_EXTENSION)
            displayName = ConversationFileUtils.formatTimestamp(timestamp)
            dateMillis = ConversationFileUtils.parseTimestampMillis(timestamp, lastModifiedMillis)
        }
        return ConversationEntry(file, displayName, messageCount, size, dateMillis, currentSession)
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.chatHistory"
        private val LOG = Logger.getInstance(ChatHistoryConfigurable::class.java)
        private const val AGENT_WORK_DIR = ".agent-work"
        private const val CONVERSATIONS_DIR = "conversations"
        private const val CURRENT_SESSION_FILE = "conversation.json"
        private const val ARCHIVE_PREFIX = "conversation-"
        private const val JSON_EXTENSION = ".json"
        private const val CURRENT_SESSION_LABEL = "Current Session"
    }
}
