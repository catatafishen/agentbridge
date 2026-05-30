package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.ui.AttachmentKind
import com.github.catatafishen.agentbridge.ui.ChatToolWindowContent
import com.github.catatafishen.agentbridge.ui.ContextItemData
import com.github.catatafishen.agentbridge.ui.NativeChatPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingConstants

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

    private val chatPanel = NativeChatPanel(project)

    private val metaLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
        horizontalAlignment = SwingConstants.LEFT
        border = JBUI.Borders.empty(0, 8, 0, 4)
    }

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
            add(metaLabel, BorderLayout.CENTER)
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
        val service = ConversationService.getInstance(project)
        val sid = sessionId
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries: List<EntryData> = service.loadTurnEntries(turnId)
            val earlier: List<String> = service.loadAdjacentTurnIds(sid, turnId, -1)
            val later: List<String> = service.loadAdjacentTurnIds(sid, turnId, 1)
            val sessionRec: ConversationService.SessionRecord? = service.listSessions().find { it.id == sid }
            ApplicationManager.getApplication().invokeLater {
                if (!isDisplayable) return@invokeLater
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

        val rec = currentSessionRecord
        if (rec != null && rec.name.isNotEmpty()) parts.add(rec.name)

        currentPrompt?.let { prompt ->
            val ts = PromptsPanel.formatTimestamp(prompt.timestamp)
            if (ts.isNotEmpty()) parts.add(ts)
        }

        val turnShort = currentTurnId.takeIf { it.length >= 8 }?.take(8) ?: currentTurnId
        if (turnShort.isNotEmpty()) parts.add(turnShort)

        val agentName = rec?.agent ?: ""
        val stats = PromptsPanel.formatStats(currentStats, agentName)
        if (stats.isNotEmpty()) parts.add(stats)

        val commits = currentStats?.let { PromptsPanel.formatCommits(it.commitHashes) } ?: ""
        if (commits.isNotEmpty()) parts.add(commits)

        metaLabel.text = parts.joinToString(" · ")
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
