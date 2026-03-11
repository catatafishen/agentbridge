package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders list_project_files output as a compact file listing grouped by directory,
 * with type badges and a count header.
 */
internal object ListProjectFilesRenderer : ToolResultRenderer {

    private val COUNT_HEADER = Regex("""^(\d+)\s+files?:?\s*$""")
    private val FILE_ENTRY = Regex("""^(.+?)\s+\[([^]]+)]$""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.size < 2) return null

        val countMatch = COUNT_HEADER.find(lines.first().trim()) ?: return null
        val fileCount = countMatch.groupValues[1].toIntOrNull() ?: return null

        val entries = lines.drop(1).mapNotNull { parseEntry(it.trim()) }
        if (entries.isEmpty()) return null

        val displayEntries = entries.take(ToolRenderers.MAX_LIST_ENTRIES)
        val grouped = displayEntries.groupBy { it.dir }

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.headerPanel(ToolIcons.FOLDER, fileCount, "files"))

        for ((dir, files) in grouped) {
            if (dir.isNotEmpty()) {
                val dirRow = ToolRenderers.rowPanel()
                dirRow.border = JBUI.Borders.emptyTop(4)
                dirRow.add(ToolRenderers.monoLabel("$dir/").apply {
                    font = font.deriveFont(Font.BOLD)
                })
                dirRow.add(ToolRenderers.mutedLabel("${files.size}"))
                panel.add(dirRow)
            }
            for (f in files) {
                val row = ToolRenderers.rowPanel()
                row.border = JBUI.Borders.emptyLeft(if (dir.isNotEmpty()) 12 else 0)
                val fullPath = if (dir.isNotEmpty()) "$dir/${f.name}" else f.name
                row.add(ToolRenderers.fileLink(f.name, fullPath))
                for (tag in f.tags) {
                    row.add(JBLabel(tag).apply {
                        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
                        foreground = tagColor(tag)
                    })
                }
                panel.add(row)
            }
        }

        val remaining = entries.size - displayEntries.size
        if (remaining > 0) {
            ToolRenderers.addTruncationIndicator(panel, remaining, "files")
        }

        return panel
    }

    private data class FileEntry(val dir: String, val name: String, val tags: List<String>)

    private fun parseEntry(line: String): FileEntry? {
        if (line.isBlank()) return null
        val match = FILE_ENTRY.find(line)
        val path: String
        val tags: List<String>
        if (match != null) {
            path = match.groupValues[1].trim()
            tags = match.groupValues[2].split(" ").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            path = line
            tags = emptyList()
        }
        val lastSlash = path.lastIndexOf('/')
        val dir = if (lastSlash >= 0) path.substring(0, lastSlash) else ""
        val name = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
        return FileEntry(dir, name, tags)
    }

    private fun tagColor(tag: String): Color = when (tag.lowercase()) {
        "test" -> ToolRenderers.SUCCESS_COLOR
        "generated" -> ToolRenderers.MUTED_COLOR
        "excluded" -> ToolRenderers.FAIL_COLOR
        else -> UIUtil.getLabelForeground()
    }
}
