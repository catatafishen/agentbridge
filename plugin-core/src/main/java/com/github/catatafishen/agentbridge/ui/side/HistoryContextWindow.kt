package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog

/**
 * Non-modal floating window showing conversation history centred on a specific prompt.
 *
 * Opened when the user clicks a prompt in the Search tab that has not yet been rendered
 * in the main JCEF chat view (i.e., it is still in the deferred history queue). The full
 * conversation is rendered using the same {@link ChatConsolePanel} as the main chat panel,
 * preserving all formatting, tool calls, and sub-agent segments. The target prompt is
 * scrolled into view and briefly highlighted after rendering.
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
    }

    private val chatPanel = ChatConsolePanel(project, registerAsMain = false)

    init {
        contentPane.add(chatPanel.component, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(700), JBUI.scale(750))
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = true

        // Remove the DOM entry cap so the full history is always visible.
        chatPanel.setDomMessageLimit(100_000)

        // Load all history, then scroll to the target. Both calls go through executeJs's
        // pending queue so they fire in order after the JCEF page finishes loading.
        chatPanel.appendEntries(allEntries)
        chatPanel.scrollToEntry(targetEntryId)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = Disposer.dispose(chatPanel)
        })
    }
}
