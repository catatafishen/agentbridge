package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Non-modal floating window showing conversation history centred on a specific prompt.
 *
 * Initially loads and displays exactly the target turn (user prompt + agent response)
 * directly from the database by turn ID. "Load more" links expand by one turn at a time
 * by querying adjacent turns from the same session.
 *
 * This avoids loading the entire session upfront, which prevents offset/identity
 * mismatches when turn IDs collide across sessions (e.g. branched sessions).
 */
internal class HistoryContextWindow private constructor(
    private val project: Project,
    private val sessionId: String,
    private val targetTurnId: String,
) : JDialog(WindowManager.getInstance().getFrame(project), "Conversation History", false) {

    companion object {
        fun open(project: Project, sessionId: String, targetEntryId: String) {
            val win = HistoryContextWindow(project, sessionId, targetEntryId)
            win.pack()
            win.setLocationRelativeTo(WindowManager.getInstance().getFrame(project))
            win.isVisible = true
        }
    }

    /** Turn IDs currently displayed, ordered chronologically. */
    private val displayedTurnIds = mutableListOf<String>()
    private var hasEarlier = false
    private var hasLater = false

    private val chatPanel = ChatConsolePanel(project, registerAsMain = false)

    private val loadEarlierLabel = makeLoadMoreLabel("↑ Load earlier")
    private val loadLaterLabel = makeLoadMoreLabel("↓ Load later")

    private val topBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        add(loadEarlierLabel, BorderLayout.CENTER)
        isVisible = false
    }
    private val bottomBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        add(loadLaterLabel, BorderLayout.CENTER)
        isVisible = false
    }

    init {
        val body = JPanel(BorderLayout())
        body.add(topBar, BorderLayout.NORTH)
        body.add(chatPanel.component, BorderLayout.CENTER)
        body.add(bottomBar, BorderLayout.SOUTH)
        contentPane.add(body, BorderLayout.CENTER)

        preferredSize = Dimension(JBUI.scale(700), JBUI.scale(750))
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = true

        chatPanel.setDomMessageLimit(100_000)

        loadEarlierLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = loadEarlier()
        })
        loadLaterLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = loadLater()
        })

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = Disposer.dispose(chatPanel)
        })

        loadTargetTurnAsync()
    }

    private fun loadTargetTurnAsync() {
        val service = ConversationService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries: List<EntryData> = service.loadTurnEntries(targetTurnId)
            val earlier: List<String> = service.loadAdjacentTurnIds(sessionId, targetTurnId, -1)
            val later: List<String> = service.loadAdjacentTurnIds(sessionId, targetTurnId, 1)
            ApplicationManager.getApplication().invokeLater {
                if (!isDisplayable) return@invokeLater
                displayedTurnIds.add(targetTurnId)
                hasEarlier = earlier.isNotEmpty()
                hasLater = later.isNotEmpty()
                if (entries.isNotEmpty()) {
                    chatPanel.appendEntries(entries)
                }
                updateBars()
            }
        }
    }

    private fun loadEarlier() {
        if (!hasEarlier || displayedTurnIds.isEmpty()) return
        val firstDisplayed = displayedTurnIds.first()
        val service = ConversationService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val earlierIds: List<String> = service.loadAdjacentTurnIds(sessionId, firstDisplayed, -1)
            if (earlierIds.isEmpty()) return@executeOnPooledThread
            val turnId = earlierIds.last()
            val entries: List<EntryData> = service.loadTurnEntries(turnId)
            val moreEarlier: List<String> = service.loadAdjacentTurnIds(sessionId, turnId, -1)
            ApplicationManager.getApplication().invokeLater {
                if (!isDisplayable) return@invokeLater
                displayedTurnIds.add(0, turnId)
                hasEarlier = moreEarlier.isNotEmpty()
                if (entries.isNotEmpty()) {
                    chatPanel.prependEntries(entries)
                }
                updateBars()
            }
        }
    }

    private fun loadLater() {
        if (!hasLater || displayedTurnIds.isEmpty()) return
        val lastDisplayed = displayedTurnIds.last()
        val service = ConversationService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val laterIds: List<String> = service.loadAdjacentTurnIds(sessionId, lastDisplayed, 1)
            if (laterIds.isEmpty()) return@executeOnPooledThread
            val turnId = laterIds.first()
            val entries: List<EntryData> = service.loadTurnEntries(turnId)
            val moreLater: List<String> = service.loadAdjacentTurnIds(sessionId, turnId, 1)
            ApplicationManager.getApplication().invokeLater {
                if (!isDisplayable) return@invokeLater
                displayedTurnIds.add(turnId)
                hasLater = moreLater.isNotEmpty()
                if (entries.isNotEmpty()) {
                    chatPanel.appendEntries(entries)
                }
                updateBars()
            }
        }
    }

    private fun updateBars() {
        topBar.isVisible = hasEarlier
        bottomBar.isVisible = hasLater
    }

    private fun makeLoadMoreLabel(text: String): JLabel = JLabel(text).apply {
        font = JBUI.Fonts.miniFont()
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(4)
    }
}
