package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders glob / file-finding tool output as a grouped file tree
 * with a count header and clickable paths.
 */
internal object GlobRenderer : ToolResultRenderer {

    private val NO_FILES = Regex("""^No files matched""", RegexOption.IGNORE_CASE)

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null

        if (NO_FILES.containsMatchIn(text.lines().first())) {
            val panel = ToolRenderers.listPanel()
            panel.add(ToolRenderers.mutedLabel("∅ ${text.lines().first()}").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return panel
        }

        val paths = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.removePrefix("./") }

        if (paths.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.headerPanel(ToolIcons.FOLDER, paths.size, "files"))

        val displayPaths = paths.take(ToolRenderers.MAX_LIST_ENTRIES)

        val grouped = displayPaths.groupBy { path ->
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash >= 0) path.substring(0, lastSlash) else ""
        }

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
            for (filePath in files) {
                val fileName = filePath.substringAfterLast('/')
                val row = ToolRenderers.rowPanel()
                row.border = JBUI.Borders.emptyLeft(if (dir.isNotEmpty()) 12 else 0)
                row.add(ToolRenderers.fileLink(fileName, filePath))
                panel.add(row)
            }
        }

        val remaining = paths.size - displayPaths.size
        if (remaining > 0) {
            ToolRenderers.addTruncationIndicator(panel, remaining, "files")
        }

        return panel
    }
}
