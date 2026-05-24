package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Dialog that lists all registered inspections and lets the user pick one or more
 * IDs to add to the suppressed-inspections table. Supports free-text filtering by
 * group name, display name, or short (ID) name.
 */
class InspectionPickerDialog(project: Project) : DialogWrapper(project) {

    private data class Entry(val shortName: String, val displayName: String, val groupName: String) {
        override fun toString() = "$groupName / $displayName  [$shortName]"
    }

    private val allEntries: List<Entry>
    private val listModel = DefaultListModel<Entry>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    }
    private val searchField = SearchTextField()

    init {
        title = "Select Inspections to Suppress"
        setOKButtonText("Add Selected")
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        allEntries = profile.getInspectionTools(null).asSequence()
            .map { w -> Entry(w.shortName, w.displayName, w.groupDisplayName.ifBlank { "General" }) }
            .sortedWith(compareBy({ it.groupName }, { it.displayName }))
            .toList()
        init()
    }

    override fun createCenterPanel(): JComponent {
        populateList("")
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = populateList(searchField.text)
            },
        )

        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(460))
        panel.add(searchField, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        return panel
    }

    private fun populateList(filter: String) {
        val lf = filter.lowercase().trim()
        val previousSelection = list.selectedValuesList.toSet()
        listModel.clear()
        allEntries
            .filter {
                lf.isEmpty()
                    || it.shortName.lowercase().contains(lf)
                    || it.displayName.lowercase().contains(lf)
                    || it.groupName.lowercase().contains(lf)
            }
            .forEach { listModel.addElement(it) }
        if (previousSelection.isNotEmpty()) {
            (0 until listModel.size()).filter { listModel[it] in previousSelection }
                .forEach { list.addSelectionInterval(it, it) }
        }
    }

    fun getSelectedIds(): List<String> = list.selectedValuesList.map { it.shortName }
}
