package com.github.catatafishen.agentbridge.ui.renderers

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
object ListProjectFilesRenderer : ToolResultRenderer {

    private val COUNT_HEADER = Regex("""^(\d+)\s+files?:?\s*$""")
    private val FILE_ENTRY = Regex("""^(.+?)\s+\[([^]]+)]$""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.size < 2) return null

        val countMatch = COUNT_HEADER.find(lines.first().trim()) ?: return null
        val fileCount = countMatch.groupValues[1].toIntOrNull() ?: return null

        // The file list ends at the first blank line; anything after it (the excluded-directories
        // section and its guidance) is plain text, not file entries.
        val (fileLines, trailer) = splitBody(lines.drop(1))

        val entries = fileLines.mapNotNull { parseEntry(it.trim()) }
        if (entries.isEmpty()) return null

        val displayEntries = entries.take(ToolRenderers.MAX_LIST_ENTRIES)
        val grouped = displayEntries.groupBy { it.dir }

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.headerPanel(ToolIcons.FOLDER, fileCount, "files"))
        for ((dir, files) in grouped) {
            renderDirectoryGroup(panel, dir, files)
        }

        val remaining = entries.size - displayEntries.size
        if (remaining > 0) {
            ToolRenderers.addTruncationIndicator(panel, remaining, "files")
        }
        for (text in trailer) {
            panel.add(ToolRenderers.mutedLabel(text.trim()).apply { border = JBUI.Borders.emptyTop(2) })
        }
        return panel
    }

    /**
     * Splits the lines after the count header into file-entry lines (up to the first blank line) and the
     * non-blank trailing text lines after it.
     */
    @JvmStatic
    fun splitBody(body: List<String>): Pair<List<String>, List<String>> {
        val split = body.indexOfFirst { it.isBlank() }
        if (split < 0) return body to emptyList()
        return body.subList(0, split) to body.subList(split, body.size).filter { it.isNotBlank() }
    }

    private fun renderDirectoryGroup(panel: JComponent, dir: String, files: List<FileEntry>) {
        if (dir.isNotEmpty()) {
            val dirRow = ToolRenderers.rowPanel()
            dirRow.border = JBUI.Borders.emptyTop(4)
            dirRow.add(ToolRenderers.monoLabel("$dir/").apply { font = font.deriveFont(Font.BOLD) })
            dirRow.add(ToolRenderers.mutedLabel("${files.size}"))
            panel.add(dirRow)
        }
        for (f in files) {
            panel.add(renderFileRow(dir, f))
        }
    }

    private fun renderFileRow(dir: String, f: FileEntry): JComponent {
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
        return row
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
        "test_sources", "test_resources", "generated_test_sources" -> ToolRenderers.SUCCESS_COLOR
        "generated_sources", "ide", "other" -> ToolRenderers.MUTED_COLOR
        else -> UIUtil.getLabelForeground()
    }
}
