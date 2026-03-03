package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Icon
import javax.swing.UIManager

/**
 * Help popup showing toolbar guide and plugin info.
 * Displayed as a non-modal floating popup so users can keep it open while interacting with the plugin.
 */
internal class HelpAction(@Suppress("unused") private val project: Project) : AnAction(
    "Help", "Show help for all toolbar features and plugin behavior",
    com.intellij.icons.AllIcons.Actions.Help
) {
    private sealed class HelpItem {
        data class Row(val icon: Icon, val name: String, val description: String) : HelpItem()
        data class SubGroup(val title: String) : HelpItem()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(buildContent(), null)
            .setTitle("IDE Agent for Copilot \u2014 Help")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(false)
            .createPopup()
        popup.showInBestPositionFor(e.dataContext)
    }

    private fun buildToolbarHelpItems(): List<HelpItem> = listOf(
        HelpItem.SubGroup("Sending"),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.Execute,
            "Send",
            "Send your prompt to the agent. Shortcut: Enter. Use Shift+Enter for a new line."
        ),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.Suspend,
            "Stop",
            "While the agent is working, this replaces Send. Stops the current agent turn."
        ),
        HelpItem.SubGroup("Context"),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.AddFile,
            "Attach File",
            "Attach the currently open editor file to your prompt as context."
        ),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.AddMulticaret,
            "Attach Selection",
            "Attach the current text selection from the editor to your prompt."
        ),
        HelpItem.SubGroup("Configuration"),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.Lightning,
            "Model",
            "Dropdown: choose the AI model. Premium models show a cost multiplier (e.g. \u201c3\u00d7\u201d)."
        ),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.Diff,
            "Mode",
            "Dropdown: Agent = autonomous tool use. Plan = conversation only, no tool calls."
        ),
        HelpItem.SubGroup("Behaviour"),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.Preview,
            "Follow Agent",
            "Toggle: auto-open files in the editor as the agent reads or writes them."
        ),
        HelpItem.SubGroup("Project"),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Nodes.Folder,
            "Project Files",
            "Dropdown: open Instructions, TODO, Agent Definitions, or MCP Server Instructions."
        ),
        HelpItem.SubGroup("Session"),
        HelpItem.Row(
            com.intellij.icons.AllIcons.Actions.Restart,
            "Restart \u25be",
            "Dropdown: \u201cRestart (keep history)\u201d or \u201cClear and restart\u201d."
        ),
        HelpItem.SubGroup("Utilities"),
        HelpItem.Row(
            com.intellij.icons.AllIcons.ToolbarDecorator.Export,
            "Export Chat",
            "Copy the full conversation to clipboard (as text or HTML)."
        ),
        HelpItem.Row(
            com.intellij.icons.AllIcons.General.Settings,
            "Tool Permissions",
            "Open the tool permissions panel to review which tools the agent can use."
        ),
    )

    private fun buildContent(): JBPanel<JBPanel<*>> {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val mainPanel = object : JBPanel<JBPanel<*>>(), javax.swing.Scrollable {
                override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
                override fun getScrollableUnitIncrement(
                    visibleRect: java.awt.Rectangle, orientation: Int, direction: Int
                ) = JBUI.scale(16)

                override fun getScrollableBlockIncrement(
                    visibleRect: java.awt.Rectangle, orientation: Int, direction: Int
                ) = visibleRect.height

                override fun getScrollableTracksViewportWidth() = true
                override fun getScrollableTracksViewportHeight() = false
            }.apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(12)

                add(JBLabel("<html><b>Toolbar guide</b></html>").apply {
                    font = JBUI.Fonts.label(15f).asBold()
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(4)
                })
                add(JBLabel("Each button in the toolbar, from left to right:").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(10)
                })

                for (item in buildToolbarHelpItems()) {
                    when (item) {
                        is HelpItem.SubGroup -> addSubGroupHeader(this, item.title)
                        is HelpItem.Row -> add(createHelpRow(item))
                    }
                }

                add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
                addSubGroupHeader(this, "Right side")
                add(JBLabel("<html>A processing timer appears while the agent is working. Next to it, a usage graph shows premium requests consumed \u2014 click it for details.</html>").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(6)
                })

                addSubGroupHeader(this, "Chat panel")
                add(JBLabel("<html>Agent responses render as Markdown with syntax-highlighted code blocks. Tool calls appear as collapsible chips \u2014 click to expand and see arguments/results.</html>").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(6)
                })

                add(javax.swing.Box.createVerticalGlue())
            }

            val scrollPane = JBScrollPane(mainPanel).apply {
                preferredSize = JBUI.size(650, 600)
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = null
            }
            add(scrollPane, BorderLayout.CENTER)

            val versionText = com.github.catatafishen.ideagentforcopilot.BuildInfo.getSummary()
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(
                        UIManager.getColor("Separator.foreground") ?: JBColor.border(),
                        1, 0, 0, 0
                    ),
                    JBUI.Borders.empty(6, 12, 8, 12)
                )
                isOpaque = false
                add(JBLabel("IDE Agent for Copilot $versionText").apply {
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    font = JBUI.Fonts.smallFont()
                }, BorderLayout.WEST)
            }, BorderLayout.SOUTH)
        }
    }

    private fun addSubGroupHeader(panel: JBPanel<*>, title: String) {
        panel.add(JBLabel(title).apply {
            font = JBUI.Fonts.label().asBold()
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 0, 2, 0)
        })
    }

    private fun createHelpRow(item: HelpItem.Row): JBPanel<JBPanel<*>> {
        return JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border = JBUI.Borders.emptyBottom(4)

            add(JBLabel(item.icon).apply {
                preferredSize = JBUI.size(20, 20)
                horizontalAlignment = javax.swing.SwingConstants.CENTER
                toolTipText = item.name
                accessibleContext.accessibleName = "${item.name} icon"
            }, BorderLayout.WEST)

            add(JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBLabel(item.name).apply {
                    font = font.deriveFont(Font.BOLD)
                    preferredSize = JBUI.size(120, 20)
                    minimumSize = preferredSize
                }, BorderLayout.WEST)
                add(JBLabel("<html>${item.description}</html>").apply {
                    verticalAlignment = javax.swing.SwingConstants.TOP
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }
}
