package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal class PromptsPanel(
    @Suppress("UNUSED_PARAMETER") project: Project,
    private val chatConsole: ChatConsolePanel
) : JPanel(BorderLayout()), Disposable {

    private val searchField = SearchTextField()
    private val listModel = DefaultListModel<EntryData.Prompt>()
    private val promptList = JBList(listModel)
    private val entriesListener = Runnable {
        ApplicationManager.getApplication().invokeLater(::refresh)
    }

    init {
        promptList.cellRenderer = PromptRenderer()
        promptList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = promptList.locationToIndex(e.point)
                if (idx < 0) return
                val prompt = listModel.getElementAt(idx) ?: return
                val entryId = promptEntryId(prompt)
                if (entryId.isNotEmpty()) chatConsole.scrollToEntry(entryId)
            }
        })

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        searchField.textEditor.emptyText.text = "Search prompts…"

        val top = JPanel(BorderLayout())
        top.border = JBUI.Borders.empty(4)
        top.add(searchField, BorderLayout.CENTER)

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(promptList), BorderLayout.CENTER)

        chatConsole.addEntriesChangeListener(entriesListener)
        refresh()
    }

    private fun refresh() {
        val query = searchField.text.orEmpty()
        val prompts = chatConsole.entriesSnapshot().filterIsInstance<EntryData.Prompt>()
        val filtered = filterPrompts(prompts, query)
        listModel.clear()
        filtered.forEach(listModel::addElement)
    }

    override fun dispose() {
        chatConsole.removeEntriesChangeListener(entriesListener)
    }

    private class PromptRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is EntryData.Prompt && c is JLabel) {
                val text = value.text.replace('\n', ' ').trim()
                val preview = if (text.length > 120) text.substring(0, 120) + "…" else text
                c.text = "<html><b>${value.timestamp}</b> &nbsp; $preview</html>"
                c.border = JBUI.Borders.empty(4, 6)
            }
            return c
        }
    }

    companion object {
        fun filterPrompts(prompts: List<EntryData.Prompt>, query: String): List<EntryData.Prompt> {
            val q = query.trim()
            if (q.isEmpty()) return prompts
            val needle = q.lowercase()
            return prompts.filter { it.text.lowercase().contains(needle) }
        }

        fun promptEntryId(p: EntryData.Prompt): String = p.id.ifEmpty { p.entryId }
    }
}
