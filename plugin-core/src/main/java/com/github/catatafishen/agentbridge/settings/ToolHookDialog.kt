package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.hooks.HookEntryConfig
import com.github.catatafishen.agentbridge.services.hooks.HookRegistry
import com.github.catatafishen.agentbridge.services.hooks.HookTrigger
import com.github.catatafishen.agentbridge.services.hooks.ToolHookConfig
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.util.*
import javax.swing.*

/**
 * Dialog for editing the full hook configuration of a single MCP tool.
 * Reads from and writes to the per-tool JSON file in the hooks directory.
 *
 * <p>Each hook entry carries its own optional prependString/appendString.
 * At least one of script, prependString, or appendString is required per entry.
 * PERMISSION entries require a script and do not support text modifiers.
 */
class ToolHookDialog(
    private val project: Project,
    private val toolId: String,
    displayName: String
) : DialogWrapper(project) {

    private val triggerSections = EnumMap<HookTrigger, TriggerSection>(HookTrigger::class.java)

    init {
        title = "Hook Configuration — $displayName"
        loadFromConfig()
        init()
    }

    private fun loadFromConfig() {
        val config = HookRegistry.getInstance(project).findConfig(toolId) ?: return
        for (trigger in HookTrigger.entries) {
            val entries = config.entriesFor(trigger)
            if (entries.isNotEmpty()) {
                getSection(trigger).entries.addAll(entries)
            }
        }
    }

    private fun getSection(trigger: HookTrigger): TriggerSection =
        triggerSections.getOrPut(trigger) { TriggerSection(trigger) }

    override fun createCenterPanel(): JComponent {
        val root = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }

        root.add(buildHintLabel())
        root.add(Box.createVerticalStrut(JBUI.scale(12)))

        for (trigger in HookTrigger.entries) {
            root.add(buildTriggerSection(trigger))
            root.add(Box.createVerticalStrut(JBUI.scale(6)))
        }

        root.add(Box.createVerticalStrut(JBUI.scale(8)))
        root.add(buildOpenJsonLink())

        val scrollPane = JBScrollPane(root).apply {
            preferredSize = Dimension(JBUI.scale(580), JBUI.scale(500))
            border = JBUI.Borders.empty()
        }
        return scrollPane
    }

    override fun doOKAction() {
        try {
            val config = buildConfig()
            HookRegistry.getInstance(project).writeConfig(config)
        } catch (e: IOException) {
            LOG.warn("Failed to write hook config for $toolId", e)
            Messages.showErrorDialog(project, "Failed to save hook config: ${e.message}", "Error")
            return
        }
        super.doOKAction()
    }

    private fun buildConfig(): ToolHookConfig {
        val triggers = EnumMap<HookTrigger, List<HookEntryConfig>>(HookTrigger::class.java)
        for (trigger in HookTrigger.entries) {
            val section = triggerSections[trigger] ?: continue
            if (section.entries.isNotEmpty()) {
                triggers[trigger] = section.entries.toList()
            }
        }
        return ToolHookConfig(toolId, triggers, HookRegistry.getInstance(project).hooksDirectory)
    }

    private fun buildHintLabel(): JComponent = JBLabel(
        "<html>Configure hooks for <b>$toolId</b>. " +
            "Hook scripts receive JSON on stdin and can modify tool behavior. " +
            "Prepend/append text is added per-entry — at least one of script, prepend, or append is required.</html>"
    ).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = Component.LEFT_ALIGNMENT
        isAllowAutoWrapping = true
    }

    private fun buildTriggerSection(trigger: HookTrigger): JComponent {
        val section = getSection(trigger)
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
        }

        val headerRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        headerRow.add(JBLabel("${trigger.displayName()} hooks").apply {
            font = JBUI.Fonts.label().asBold()
        })
        headerRow.add(Box.createHorizontalStrut(JBUI.scale(8)))

        val addLink = JBLabel("+ Add entry").apply {
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = JBUI.Fonts.smallFont()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    showAddEntryDialog(trigger, section, panel)
                }
            })
        }
        headerRow.add(addLink)
        panel.add(headerRow)

        section.containerPanel = panel
        rebuildEntryRows(section, panel)
        return panel
    }

    private fun rebuildEntryRows(section: TriggerSection, panel: JComponent) {
        while (panel.componentCount > 1) {
            panel.remove(panel.componentCount - 1)
        }

        if (section.entries.isEmpty()) {
            panel.add(JBLabel("  (none configured)").apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getContextHelpForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            for ((index, entry) in section.entries.withIndex()) {
                panel.add(buildEntryRow(entry, section, index))
            }
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun buildEntryRow(
        entry: HookEntryConfig, section: TriggerSection, index: Int
    ): JComponent {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        }

        if (entry.script() != null) {
            row.add(JBLabel("  ${entry.script()}").apply { font = JBUI.Fonts.smallFont() })
        }
        entry.prependString()?.takeIf { it.isNotEmpty() }?.let { prepend ->
            row.add(JBLabel("↑ ${prepend.take(20)}${if (prepend.length > 20) "…" else ""}").apply {
                font = JBUI.Fonts.miniFont()
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        entry.appendString()?.takeIf { it.isNotEmpty() }?.let { append ->
            row.add(JBLabel("↓ ${append.take(20)}${if (append.length > 20) "…" else ""}").apply {
                font = JBUI.Fonts.miniFont()
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        row.add(JBLabel("${entry.timeout()}s").apply {
            font = JBUI.Fonts.miniFont()
            foreground = UIUtil.getContextHelpForeground()
        })
        if (section.trigger == HookTrigger.PERMISSION) {
            row.add(JBLabel(if (!entry.failSilently()) "Reject" else "Allow").apply {
                font = JBUI.Fonts.miniFont()
                foreground = UIUtil.getContextHelpForeground()
            })
        } else if (!entry.failSilently()) {
            row.add(JBLabel("Strict").apply {
                font = JBUI.Fonts.miniFont()
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        if (entry.async()) {
            row.add(JBLabel("Async").apply {
                font = JBUI.Fonts.miniFont()
                foreground = UIUtil.getContextHelpForeground()
            })
        }

        row.add(JBLabel("✖").apply {
            foreground = UIUtil.getContextHelpForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Remove this hook entry"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    section.entries.removeAt(index)
                    rebuildEntryRows(section, section.containerPanel!!)
                }
            })
        })
        return row
    }

    private fun showAddEntryDialog(trigger: HookTrigger, section: TriggerSection, panel: JComponent) {
        val isPermission = trigger == HookTrigger.PERMISSION
        val scriptLabel = if (isPermission) "Script path (required):" else "Script path (optional):"
        val scriptField = JTextField("", 30)
        val timeoutSpinner = JSpinner(SpinnerNumberModel(10, 1, 300, 1))
        val failBehavior = if (isPermission) JCheckBox("Reject on failure", true)
        else JCheckBox("Fail silently", true)
        val asyncBox = JCheckBox("Async (fire-and-forget)", false).apply {
            isEnabled = trigger == HookTrigger.SUCCESS || trigger == HookTrigger.FAILURE
        }

        val formPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel(scriptLabel))
            add(scriptField)
            add(Box.createVerticalStrut(JBUI.scale(6)))
        }

        var prependArea: JTextArea? = null
        var appendArea: JTextArea? = null
        if (!isPermission) {
            prependArea = JTextArea("", 2, 40).apply { lineWrap = true; wrapStyleWord = true }
            appendArea = JTextArea("", 2, 40).apply { lineWrap = true; wrapStyleWord = true }
            formPanel.add(JBLabel("Prepend text (optional, added before tool output):"))
            formPanel.add(JBScrollPane(prependArea).apply {
                preferredSize = Dimension(JBUI.scale(420), JBUI.scale(50))
            })
            formPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            formPanel.add(JBLabel("Append text (optional, added after tool output):"))
            formPanel.add(JBScrollPane(appendArea).apply {
                preferredSize = Dimension(JBUI.scale(420), JBUI.scale(50))
            })
            formPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
        }

        formPanel.add(JBLabel("Timeout (seconds):"))
        formPanel.add(timeoutSpinner)
        formPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
        formPanel.add(failBehavior)
        formPanel.add(asyncBox)

        val result = JOptionPane.showConfirmDialog(
            contentPanel, formPanel,
            "Add ${trigger.displayName()} Hook Entry",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return

        val script = scriptField.text.trim().ifEmpty { null }
        val prepend = prependArea?.text?.trim()?.ifEmpty { null }
        val append = appendArea?.text?.trim()?.ifEmpty { null }

        if (isPermission && script == null) {
            Messages.showErrorDialog(contentPanel, "A script path is required for permission hooks.", "Missing Script")
            return
        }
        if (!isPermission && script == null && prepend == null && append == null) {
            Messages.showErrorDialog(
                contentPanel,
                "At least one of script, prepend text, or append text is required.",
                "Empty Entry"
            )
            return
        }

        val failSilently = if (isPermission) !failBehavior.isSelected else failBehavior.isSelected
        section.entries.add(
            HookEntryConfig(
                script, timeoutSpinner.value as Int, failSilently, asyncBox.isSelected,
                emptyMap(), prepend, append
            )
        )
        rebuildEntryRows(section, panel)
    }

    private fun buildOpenJsonLink(): JComponent {
        val link = JBLabel("Open hooks JSON in editor").apply {
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = JBUI.Fonts.smallFont()
            alignmentX = Component.LEFT_ALIGNMENT
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    openJsonInEditor()
                }
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(link, BorderLayout.WEST)
        }
    }

    private fun openJsonInEditor() {
        val hooksDir = HookRegistry.getInstance(project).hooksDirectory
        val jsonFile = hooksDir.resolve("$toolId.json")
        if (!jsonFile.toFile().exists()) {
            Messages.showInfoMessage(
                project,
                "No hooks JSON file exists yet for this tool. Save the dialog to create one.",
                "No Hook File"
            )
            return
        }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(jsonFile)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
            close(CANCEL_EXIT_CODE)
        }
    }

    private class TriggerSection(val trigger: HookTrigger) {
        val entries = mutableListOf<HookEntryConfig>()
        var containerPanel: JComponent? = null
    }

    companion object {
        private val LOG = Logger.getInstance(ToolHookDialog::class.java)
    }
}
