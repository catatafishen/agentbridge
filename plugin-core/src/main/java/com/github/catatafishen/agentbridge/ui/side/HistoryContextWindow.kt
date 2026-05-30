package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.ui.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JDialog
import javax.swing.JPanel

/**
 * Non-modal floating window showing a single conversation turn from history.
 *
 * Only one instance per project is kept open at a time — clicking a new prompt
 * reuses the existing window ([navigateTo]) instead of spawning a second one.
 *
 * The popup loads one turn at a time directly from the database by turn ID,
 * and exposes Previous / Next toolbar buttons that navigate to the immediately
 * adjacent turns within the same session (full reload each time, panel
 * scrolled to the top on display).
 *
 * A "Reference in chat" toolbar button attaches the displayed turn to the
 * chat input as a [AttachmentKind.PROMPT] chip.
 */
internal class HistoryContextWindow private constructor(
    private val project: Project,
    private var sessionId: String,
    initialTurnId: String,
) : JDialog(WindowManager.getInstance().getFrame(project), "Conversation History", false) {

    companion object {
        private val instances = ConcurrentHashMap<Project, HistoryContextWindow>()

        fun open(project: Project, sessionId: String, targetEntryId: String) {
            val existing = instances[project]
            if (existing != null && existing.isDisplayable) {
                existing.navigateTo(sessionId, targetEntryId)
                existing.toFront()
                return
            }
            val win = HistoryContextWindow(project, sessionId, targetEntryId)
            instances[project] = win
            win.pack()
            win.setLocationRelativeTo(WindowManager.getInstance().getFrame(project))
            win.isVisible = true
        }
    }

    /** A git commit shown in the commits dropdown. Subject is loaded asynchronously. */
    private data class CommitEntry(val hash: String, var subject: String = "…") {
        override fun toString() = "${hash.take(7)}  $subject"
    }

    private val chatPanel = NativeChatPanel(project)

    private val metaTextLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(0, 8, 0, 4)
    }

    private val metaDiffContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 0, 4)
        isVisible = false
    }

    private val commitsCombo = ComboBox<CommitEntry>().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
        maximumSize = Dimension(JBUI.scale(220), Int.MAX_VALUE)
    }

    /** Set to true while programmatically populating the combo to suppress navigation. */
    @Volatile
    private var ignoreComboAction = false

    private val metaPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(metaTextLabel)
        add(metaDiffContainer)
        add(commitsCombo)
        add(Box.createHorizontalGlue())
    }

    init {
        commitsCombo.addActionListener {
            if (ignoreComboAction) return@addActionListener
            val entry = commitsCombo.selectedItem as? CommitEntry ?: return@addActionListener
            ApplicationManager.getApplication().executeOnPooledThread {
                FileNavigator(project).handleFileLink("gitshow://${entry.hash}")
            }
        }
    }

    private val loadSerial = AtomicInteger(0)

    @Volatile
    private var currentTurnId: String = initialTurnId

    @Volatile
    private var currentSessionRecord: ConversationService.SessionRecord? = null

    @Volatile
    private var hasPrev: Boolean = false

    @Volatile
    private var hasNext: Boolean = false

    @Volatile
    private var currentEntries: List<EntryData> = emptyList()

    @Volatile
    private var currentPrompt: EntryData.Prompt? = null

    @Volatile
    private var currentStats: EntryData.TurnStats? = null

    private val prevAction: AnAction =
        object : AnAction("Previous Turn", "Load the previous turn", AllIcons.Actions.Back) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = hasPrev
            }

            override fun actionPerformed(e: AnActionEvent) = navigate(-1)
        }

    private val nextAction: AnAction = object : AnAction("Next Turn", "Load the next turn", AllIcons.Actions.Forward) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = hasNext
        }

        override fun actionPerformed(e: AnActionEvent) = navigate(1)
    }

    private val referenceAction: AnAction = object : AnAction(
        "Reference in Chat",
        "Attach this turn to the chat input as a context chip",
        AllIcons.Vcs.History
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled =
                currentEntries.isNotEmpty() && ChatToolWindowContent.getInstance(project) != null
        }

        override fun actionPerformed(e: AnActionEvent) = referenceInChat()
    }

    init {
        // History window never auto-scrolls to bottom — each load scrolls to top instead.
        chatPanel.setAutoScroll(false)

        val toolbarGroup = DefaultActionGroup().apply {
            add(prevAction)
            add(nextAction)
            add(Separator.getInstance())
            add(referenceAction)
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, toolbarGroup, true)
        toolbar.targetComponent = chatPanel.component

        val titleBar = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(2, 4)
            add(toolbar.component, BorderLayout.WEST)
            add(metaPanel, BorderLayout.CENTER)
        }

        val body = JPanel(BorderLayout()).apply {
            add(titleBar, BorderLayout.NORTH)
            add(chatPanel.component, BorderLayout.CENTER)
        }
        contentPane.add(body, BorderLayout.CENTER)

        preferredSize = Dimension(JBUI.scale(700), JBUI.scale(750))
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = true

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                instances.remove(project, this@HistoryContextWindow)
                Disposer.dispose(chatPanel)
            }
        })

        loadTurnAsync(currentTurnId)
    }

    /** Loads a turn from a possibly-different session into this window. */
    fun navigateTo(newSessionId: String, turnId: String) {
        sessionId = newSessionId
        loadTurnAsync(turnId)
    }

    private fun navigate(direction: Int) {
        val service = ConversationService.getInstance(project)
        val ref = currentTurnId
        val sid = sessionId
        ApplicationManager.getApplication().executeOnPooledThread {
            val adjacent = service.loadAdjacentTurnIds(sid, ref, direction)
            if (adjacent.isEmpty()) return@executeOnPooledThread
            val targetId = if (direction < 0) adjacent.last() else adjacent.first()
            ApplicationManager.getApplication().invokeLater {
                if (!isDisplayable) return@invokeLater
                loadTurnAsync(targetId)
            }
        }
    }

    private fun loadTurnAsync(turnId: String) {
        val serial = loadSerial.incrementAndGet()
        val service = ConversationService.getInstance(project)
        val sid = sessionId
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries: List<EntryData> = service.loadTurnEntries(turnId)
            val earlier: List<String> = service.loadAdjacentTurnIds(sid, turnId, -1)
            val later: List<String> = service.loadAdjacentTurnIds(sid, turnId, 1)
            val sessionRec: ConversationService.SessionRecord? = service.listSessions().find { it.id == sid }
            ApplicationManager.getApplication().invokeLater {
                if (!isDisplayable) return@invokeLater
                if (serial != loadSerial.get()) return@invokeLater  // stale: a newer load superseded this one
                currentTurnId = turnId
                currentSessionRecord = sessionRec
                currentEntries = entries
                currentPrompt = entries.firstNotNullOfOrNull { it as? EntryData.Prompt }
                currentStats = entries.firstNotNullOfOrNull { it as? EntryData.TurnStats }
                hasPrev = earlier.isNotEmpty()
                hasNext = later.isNotEmpty()

                chatPanel.clear()
                if (entries.isNotEmpty()) {
                    chatPanel.appendEntries(entries)
                }
                updateMetaLabels()
                // autoScroll is disabled — scrollToTop reliably reaches the top after layout.
                chatPanel.scrollToTop()
            }
        }
    }

    private fun updateMetaLabels() {
        val parts = mutableListOf<String>()

        currentPrompt?.let { prompt ->
            val ts = PromptsPanel.formatTimestamp(prompt.timestamp)
            if (ts.isNotEmpty()) parts.add(ts)
        }

        val turnShort = currentTurnId.takeIf { it.length >= 8 }?.take(8) ?: currentTurnId
        if (turnShort.isNotEmpty()) parts.add(turnShort)

        val agentName = currentSessionRecord?.agent ?: ""
        val stats = PromptsPanel.formatStats(currentStats, agentName)
        if (stats.isNotEmpty()) parts.add(stats)

        val linesAdded = currentStats?.linesAdded ?: 0
        val linesRemoved = currentStats?.linesRemoved ?: 0
        if (linesAdded > 0 || linesRemoved > 0) {
            metaDiffContainer.removeAll()
            metaDiffContainer.add(LineDiffBar(linesAdded, linesRemoved))
            metaDiffContainer.isVisible = true
        } else {
            metaDiffContainer.isVisible = false
        }

        val hashes = currentStats?.commitHashes.orEmpty()
        if (hashes.isEmpty()) {
            commitsCombo.isVisible = false
        } else {
            val entries = hashes.map { CommitEntry(it) }.toTypedArray()
            ignoreComboAction = true
            try {
                commitsCombo.removeAllItems()
                val jCombo = commitsCombo as javax.swing.JComboBox<CommitEntry>
                entries.forEach { jCombo.addItem(it) }
            } finally {
                ignoreComboAction = false
            }
            commitsCombo.isVisible = true
            loadCommitSubjectsAsync(entries)
        }

        metaTextLabel.text = parts.joinToString(" · ")
    }

    private fun loadCommitSubjectsAsync(entries: Array<CommitEntry>) {
        if (entries.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val updates = entries.map { e -> e to fetchCommitSubject(e.hash) }
            ApplicationManager.getApplication().invokeLater {
                if (!isDisplayable) return@invokeLater
                // Guard against stale updates: if the combo was already replaced by a newer turn, skip.
                if (commitsCombo.itemCount == 0 || commitsCombo.getItemAt(0) !== entries.first()) return@invokeLater
                ignoreComboAction = true
                try {
                    val selectedIdx = commitsCombo.selectedIndex
                    updates.forEach { (e, subject) -> e.subject = subject }
                    val jCombo = commitsCombo as javax.swing.JComboBox<CommitEntry>
                    jCombo.removeAllItems()
                    entries.forEach { jCombo.addItem(it) }
                    if (selectedIdx >= 0 && selectedIdx < entries.size) {
                        commitsCombo.selectedIndex = selectedIdx
                    }
                } finally {
                    ignoreComboAction = false
                }
            }
        }
    }

    private fun fetchCommitSubject(hash: String): String {
        return try {
            val gitDir = project.basePath ?: return hash.take(7)
            val process = ProcessBuilder("git", "log", "--format=%s", "-1", hash)
                .directory(File(gitDir))
                .redirectErrorStream(true)
                .start()
            try {
                if (!process.waitFor(5L, TimeUnit.SECONDS)) {
                    return hash.take(7)
                }
                process.inputStream.bufferedReader().readText().trim().ifEmpty { hash.take(7) }
            } finally {
                process.destroy()
            }
        } catch (_: Exception) {
            hash.take(7)
        }
    }

    private fun referenceInChat() {
        val chat = ChatToolWindowContent.getInstance(project) ?: return
        val prompt = currentPrompt ?: return
        val timestampLabel = PromptsPanel.formatTimestamp(prompt.timestamp)
        val displayName = "prompt-${timestampLabel.ifEmpty { currentTurnId.take(8) }}"
        val serialized = TurnSerializer.serialize(prompt, currentEntries, currentStats)
        chat.insertContextChip(
            ContextItemData(
                path = "agentbridge://prompt/$sessionId/$currentTurnId",
                name = displayName,
                startLine = 0,
                endLine = 0,
                fileTypeName = null,
                isSelection = false,
                attachmentKind = AttachmentKind.PROMPT,
                inlineText = serialized,
            )
        )
    }
}
