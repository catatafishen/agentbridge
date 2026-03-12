package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.font.TextAttribute
import javax.swing.JComponent

/**
 * Renders the agent's task-checklist tool results as a styled panel.
 * Parses markdown checkbox syntax and renders each item with status
 * icons and a progress summary header.
 */
object TodoRenderer : ArgumentAwareRenderer {

    private val CHECKBOX_LINE = Regex("""^-\s+\[([ xX])]\s+(.+)$""")
    private val HEADER_LINE = Regex("""^#{1,4}\s+(.+)$""")

    override fun render(output: String, arguments: String?): JComponent? {
        val markdown = extractTodos(arguments) ?: output
        if (markdown.isBlank()) return null
        return buildPanel(markdown)
    }

    override fun render(output: String): JComponent? = render(output, null)

    private fun extractTodos(arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        return try {
            val json = com.google.gson.JsonParser.parseString(arguments).asJsonObject
            json["todos"]?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPanel(markdown: String): JComponent {
        val lines = markdown.lines()
        var done = 0
        var total = 0

        for (line in lines) {
            val m = CHECKBOX_LINE.find(line.trim()) ?: continue
            total++
            if (m.groupValues[1] != " ") done++
        }

        val panel = ToolRenderers.listPanel()

        if (total > 0) {
            panel.add(buildProgressHeader(done, total))
            panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val headerMatch = HEADER_LINE.find(trimmed)
            if (headerMatch != null) {
                panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)))
                panel.add(buildHeaderRow(headerMatch.groupValues[1]))
                continue
            }

            val checkMatch = CHECKBOX_LINE.find(trimmed)
            if (checkMatch != null) {
                val checked = checkMatch.groupValues[1] != " "
                val text = checkMatch.groupValues[2]
                panel.add(buildCheckboxRow(text, checked))
                continue
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                panel.add(buildPlainRow(trimmed.removePrefix("- ").removePrefix("* ")))
            }
        }

        return panel
    }

    private fun buildProgressHeader(done: Int, total: Int): JComponent {
        val icon = if (done == total) ToolIcons.SUCCESS else ToolIcons.EXECUTE
        val text = "$done / $total completed"
        return JBLabel(text).apply {
            this.icon = icon
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = if (done == total) ToolRenderers.SUCCESS_COLOR else UIUtil.getLabelForeground()
            border = JBUI.Borders.emptyBottom(2)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }

    private fun buildHeaderRow(text: String): JComponent {
        return JBLabel(text).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D - 1f)
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(2, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }

    private fun buildCheckboxRow(text: String, checked: Boolean): JComponent {
        val icon = if (checked) AllIcons.RunConfigurations.TestPassed else AllIcons.RunConfigurations.TestNotRan
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel(icon))
        row.add(JBLabel(text).apply {
            if (checked) {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont().deriveFont(
                    mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON)
                )
            }
        })
        return row
    }

    private fun buildPlainRow(text: String): JComponent {
        return JBLabel("  $text").apply {
            foreground = UIUtil.getLabelForeground()
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }
}
