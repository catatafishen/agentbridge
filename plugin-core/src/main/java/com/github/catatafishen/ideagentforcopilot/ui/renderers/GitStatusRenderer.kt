package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
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
internal object GitStatusRenderer : ToolResultRenderer {

    private val ADD_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val DEL_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val MOD_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
    private val CONFLICT_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val UNTRACKED_COLOR = JBColor(Color(0x6E, 0x77, 0x81), Color(0x8B, 0x94, 0x9E))

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

        addBadgedSection(panel, "Conflicts", files.conflicted.map { 'U' to it }, CONFLICT_COLOR)
        addBadgedSection(panel, "Staged", files.staged, ADD_COLOR)
        addBadgedSection(panel, "Changes", files.unstaged, MOD_COLOR)
        addBadgedSection(panel, "Untracked", files.untracked.map { '?' to it }, UNTRACKED_COLOR)
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
            if (line.startsWith("##") || line.isBlank() || line.length < 4) continue
            categorizeFile(line[0], line[1], line.substring(3).trim(), staged, unstaged, untracked, conflicted)
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
        'M', 'T' -> MOD_COLOR
        'A' -> ADD_COLOR
        'D' -> DEL_COLOR
        'R', 'C' -> MOD_COLOR
        'U' -> CONFLICT_COLOR
        else -> MOD_COLOR
    }
}
