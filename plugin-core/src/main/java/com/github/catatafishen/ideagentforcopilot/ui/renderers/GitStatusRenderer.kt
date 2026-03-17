package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders git status output as a structured card with branch header,
 * staged/unstaged/untracked sections, and colored file badges.
 */
object GitStatusRenderer : ToolResultRenderer {

    private data class StatusFiles(
        val staged: List<Pair<Char, String>>,
        val unstaged: List<Pair<Char, String>>,
        val untracked: List<String>,
        val conflicted: List<String>,
    )

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        addBranchHeader(panel, lines)

        val files = categorizeFiles(lines)
        if (files.staged.isEmpty() && files.unstaged.isEmpty()
            && files.untracked.isEmpty() && files.conflicted.isEmpty()
        ) {
            panel.add(ToolRenderers.mutedLabel("Nothing to commit, working tree clean").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return panel
        }

        addBadgedSection(panel, "Conflicts", files.conflicted.map { 'U' to it }, ToolRenderers.FAIL_COLOR)
        addBadgedSection(panel, "Staged", files.staged, ToolRenderers.ADD_COLOR)
        addBadgedSection(panel, "Changes", files.unstaged, ToolRenderers.MOD_COLOR)
        addBadgedSection(panel, "Untracked", files.untracked.map { '?' to it }, ToolRenderers.MUTED_COLOR)
        return panel
    }

    private fun addBranchHeader(panel: javax.swing.JPanel, lines: List<String>) {
        val branchLine = lines.firstOrNull { it.startsWith("##") } ?: return
        val branchInfo = branchLine.removePrefix("## ").trim()
        val branchName = branchInfo.split("...").first()
        val tracking = if ("..." in branchInfo) branchInfo.substringAfter("...") else null

        val row = ToolRenderers.rowPanel()
        row.border = JBUI.Borders.emptyBottom(4)
        row.add(JBLabel(branchName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        if (tracking != null) row.add(ToolRenderers.mutedLabel(tracking))
        panel.add(row)
    }

    private fun categorizeFiles(lines: List<String>): StatusFiles {
        val staged = mutableListOf<Pair<Char, String>>()
        val unstaged = mutableListOf<Pair<Char, String>>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("##") || trimmed.isBlank() || trimmed.length < 4) continue
            // Git status format is "XY path"
            // If it's trimmed, we might lose the X space.
            // But if it's NOT trimmed, we might have leading spaces if it's from some tool output.
            // Let's use the untrimmed line if it looks like a status line, otherwise try to find the status.
            val statusLine = if (line.length >= 4 && (line[2] == ' ' || line[3] == ' ')) line else trimmed
            if (statusLine.length < 4) continue

            categorizeFile(
                statusLine[0],
                statusLine[1],
                statusLine.substring(3).trim(),
                staged,
                unstaged,
                untracked,
                conflicted
            )
        }
        return StatusFiles(staged, unstaged, untracked, conflicted)
    }

    private fun categorizeFile(
        x: Char, y: Char, path: String,
        staged: MutableList<Pair<Char, String>>,
        unstaged: MutableList<Pair<Char, String>>,
        untracked: MutableList<String>,
        conflicted: MutableList<String>,
    ) {
        when {
            x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D') -> conflicted.add(path)
            x == '?' && y == '?' -> untracked.add(path)
            else -> {
                if (x != ' ' && x != '?') staged.add(x to path)
                if (y != ' ' && y != '?') unstaged.add(y to path)
            }
        }
    }

    private fun addBadgedSection(
        panel: javax.swing.JPanel,
        label: String,
        items: List<Pair<Char, String>>,
        sectionColor: Color
    ) {
        if (items.isEmpty()) return
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val sectionHeader = ToolRenderers.rowPanel()
        sectionHeader.add(JBLabel(label).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = sectionColor
        })
        sectionHeader.add(ToolRenderers.mutedLabel("${items.size}"))
        section.add(sectionHeader)

        for ((code, path) in items) {
            val row = ToolRenderers.rowPanel()
            row.add(ToolRenderers.badgeLabel(code.toString(), badgeColor(code)))
            row.add(ToolRenderers.fileLink(path, path))
            section.add(row)
        }
        panel.add(section)
    }

    private fun badgeColor(code: Char): Color = when (code) {
        'M', 'T' -> ToolRenderers.MOD_COLOR
        'A' -> ToolRenderers.ADD_COLOR
        'D' -> ToolRenderers.DEL_COLOR
        'R', 'C' -> ToolRenderers.MOD_COLOR
        'U' -> ToolRenderers.FAIL_COLOR
        else -> ToolRenderers.MOD_COLOR
    }
}
