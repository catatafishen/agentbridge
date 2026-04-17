package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.UIManager
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
        promptList.cellRenderer = BubbleRenderer()
        promptList.fixedCellHeight = -1
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
        val scrollPane = JBScrollPane(promptList)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)

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

    private class BubbleRenderer : ListCellRenderer<EntryData.Prompt> {
        private val outer = JPanel(BorderLayout(0, JBUI.scale(2)))
        private val tsLabel = javax.swing.JLabel()
        private val textArea = JTextArea()

        init {
            outer.isOpaque = true
            tsLabel.font = JBUI.Fonts.miniFont()
            tsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            textArea.isOpaque = false
            textArea.isEditable = false
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            textArea.font = UIManager.getFont("Label.font") ?: textArea.font
            textArea.border = null
            textArea.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            outer.add(tsLabel, BorderLayout.NORTH)
            outer.add(textArea, BorderLayout.CENTER)
            outer.border = JBUI.Borders.compound(
                JBUI.Borders.empty(1, 0),
                JBUI.Borders.compound(
                    SideBorder(JBColor(Color(0x005FB8), Color(0x5C9DFF)), SideBorder.LEFT),
                    JBUI.Borders.empty(4, 8, 4, 6)
                )
            )
        }

        override fun getListCellRendererComponent(
            list: JList<out EntryData.Prompt>?,
            value: EntryData.Prompt?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return outer
            val listWidth = list?.width ?: 0
            if (listWidth > 0) {
                textArea.setSize(listWidth - JBUI.scale(18), Short.MAX_VALUE.toInt())
            }
            tsLabel.text = formatTimestamp(value.timestamp)
            textArea.text = value.text.trim()
            if (isSelected) {
                outer.background = list?.selectionBackground ?: UIManager.getColor("List.selectionBackground")
                textArea.foreground = list?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
                tsLabel.foreground = list?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
            } else {
                outer.background = list?.background ?: UIManager.getColor("List.background")
                textArea.foreground = list?.foreground ?: UIManager.getColor("List.foreground")
                tsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
            val textHeight = textArea.preferredSize.height
            outer.preferredSize = Dimension(
                listWidth,
                tsLabel.preferredSize.height + JBUI.scale(2) + textHeight + JBUI.scale(10)
            )
            return outer
        }
    }

    companion object {
        fun formatTimestamp(iso: String): String {
            if (iso.isEmpty()) return ""
            return try {
                val instant = java.time.Instant.parse(iso)
                val zdt = instant.atZone(ZoneId.systemDefault())
                val today = LocalDate.now()
                val date = zdt.toLocalDate()
                val time = DateTimeFormatter.ofPattern("HH:mm").format(zdt)
                when {
                    date == today -> "Today $time"
                    date == today.minusDays(1) -> "Yesterday $time"
                    date.year == today.year -> "${DateTimeFormatter.ofPattern("MMM d").format(zdt)} $time"
                    else -> "${DateTimeFormatter.ofPattern("MMM d yyyy").format(zdt)} $time"
                }
            } catch (_: Exception) {
                iso
            }
        }

        fun filterPrompts(prompts: List<EntryData.Prompt>, query: String): List<EntryData.Prompt> {
            val q = query.trim()
            if (q.isEmpty()) return prompts
            val needle = q.lowercase()
            return prompts.filter { it.text.lowercase().contains(needle) }
        }

        fun promptEntryId(p: EntryData.Prompt): String = p.id.ifEmpty { p.entryId }
    }
}
