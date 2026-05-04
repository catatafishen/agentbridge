package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
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
 * Initially displays exactly the target prompt turn (user input + AI response).
 * The window is scrolled to the top so the user prompt is immediately visible.
 * "Load more" links at the top and bottom expand by one turn at a time using
 * [ChatConsolePanel.prependEntries] and [ChatConsolePanel.appendEntries] — the JCEF
 * panel is never recreated on load-more, so there is no flash or re-scroll.
 */
internal class HistoryContextWindow private constructor(
    project: Project,
    allEntries: List<EntryData>,
    targetEntryId: String,
) : JDialog(WindowManager.getInstance().getFrame(project), "Conversation History", false) {

    companion object {
        fun open(project: Project, allEntries: List<EntryData>, targetEntryId: String) {
            val win = HistoryContextWindow(project, allEntries, targetEntryId)
            win.pack()
            win.setLocationRelativeTo(WindowManager.getInstance().getFrame(project))
            win.isVisible = true
        }

        /**
         * Splits a flat entry list into conversation turns.
         * A new turn begins at every [EntryData.Prompt]; entries before the first prompt
         * are collected into a preamble turn at index 0.
         */
        private fun splitIntoTurns(entries: List<EntryData>): List<List<EntryData>> {
            if (entries.isEmpty()) return emptyList()
            val turns = mutableListOf<MutableList<EntryData>>()
            for (entry in entries) {
                if (entry is EntryData.Prompt) {
                    turns.add(mutableListOf(entry))
                } else {
                    if (turns.isEmpty()) turns.add(mutableListOf())
                    turns.last().add(entry)
                }
            }
            return turns
        }

        /** Returns the index of the turn whose entries contain [targetEntryId]. */
        private fun findTurnIndex(turns: List<List<EntryData>>, targetEntryId: String): Int {
            val idx = turns.indexOfFirst { turn ->
                turn.any { e ->
                    e.entryId == targetEntryId ||
                        (e is EntryData.Prompt && e.id == targetEntryId)
                }
            }
            return if (idx >= 0) idx else (turns.size - 1).coerceAtLeast(0)
        }
    }

    private val turns: List<List<EntryData>> = splitIntoTurns(allEntries)
    private val targetTurnIdx: Int = findTurnIndex(turns, targetEntryId)
    private var displayStartTurnIdx: Int = targetTurnIdx
    private var displayEndTurnIdx: Int = targetTurnIdx

    private val chatPanel = ChatConsolePanel(project, registerAsMain = false)

    private val loadEarlierLabel = makeLoadMoreLabel("↑ Load earlier")
    private val loadLaterLabel = makeLoadMoreLabel("↓ Load later")

    private val topBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        add(loadEarlierLabel, BorderLayout.CENTER)
    }
    private val bottomBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        add(loadLaterLabel, BorderLayout.CENTER)
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
        chatPanel.appendEntries(turns.subList(displayStartTurnIdx, displayEndTurnIdx + 1).flatten())
        chatPanel.scrollToTop()

        loadEarlierLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = loadEarlier()
        })
        loadLaterLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = loadLater()
        })

        updateBars()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = Disposer.dispose(chatPanel)
        })
    }

    private fun loadEarlier() {
        val newStart = (displayStartTurnIdx - 1).coerceAtLeast(0)
        if (newStart >= displayStartTurnIdx) return
        chatPanel.prependEntries(turns[newStart])
        displayStartTurnIdx = newStart
        updateBars()
    }

    private fun loadLater() {
        val newEnd = (displayEndTurnIdx + 1).coerceAtMost(turns.size - 1)
        if (newEnd <= displayEndTurnIdx) return
        chatPanel.appendEntries(turns[newEnd])
        displayEndTurnIdx = newEnd
        updateBars()
    }

    private fun updateBars() {
        topBar.isVisible = displayStartTurnIdx > 0
        bottomBar.isVisible = displayEndTurnIdx < turns.size - 1
    }

    private fun makeLoadMoreLabel(text: String): JLabel = JLabel(text).apply {
        font = JBUI.Fonts.miniFont()
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(4)
    }
}
