package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders git branch list output with the current branch highlighted.
 *
 * Input format (git branch --list -v):
 * ```
 * * main       abc1234 Latest commit message
 *   feature-x  def5678 Work in progress
 *   fix/bug    ghi9012 Fix null pointer
 * ```
 */
internal object GitBranchRenderer : ToolResultRenderer {

    private val BRANCH_LINE = Regex("""^([* ])\s+(\S+)\s+([a-f0-9]+)\s+(.*)$""")
    private val REMOTE_PREFIX = Regex("""^remotes?/""")

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        val branches = lines.mapNotNull { parseBranch(it) }
        if (branches.size < 2) return null

        val locals = branches.filter { !it.isRemote }
        val remotes = branches.filter { it.isRemote }

        val sb = StringBuilder("<div class='branch-result'>")
        if (locals.isNotEmpty()) {
            appendSection(sb, "Local", locals)
        }
        if (remotes.isNotEmpty()) {
            appendSection(sb, "Remote", remotes)
        }
        sb.append("</div>")
        return sb.toString()
    }

    private data class Branch(
        val name: String,
        val hash: String,
        val message: String,
        val isCurrent: Boolean,
        val isRemote: Boolean,
    )

    private fun parseBranch(line: String): Branch? {
        val match = BRANCH_LINE.find(line) ?: return null
        val marker = match.groupValues[1]
        val name = match.groupValues[2]
        return Branch(
            name = name.replace(REMOTE_PREFIX, ""),
            hash = match.groupValues[3],
            message = match.groupValues[4],
            isCurrent = marker == "*",
            isRemote = name.startsWith("remotes/") || name.startsWith("remote/"),
        )
    }

    private fun appendSection(sb: StringBuilder, label: String, branches: List<Branch>) {
        sb.append("<div class='branch-section'>")
        sb.append("<div class='outline-header'><span style='font-weight:600'>$label</span>")
        sb.append("<span class='inspection-file-count'>${branches.size}</span></div>")
        sb.append("<div class='branch-entries'>")
        for (b in branches) {
            val cls = if (b.isCurrent) "branch-entry branch-current" else "branch-entry"
            sb.append("<div class='$cls'>")
            if (b.isCurrent) {
                sb.append("<span class='git-file-badge git-file-add'>●</span>")
            } else {
                sb.append("<span class='git-file-badge git-file-mod'> </span>")
            }
            sb.append("<span class='branch-name'>${esc(b.name)}</span>")
            sb.append("<span class='git-commit-hash'>${esc(b.hash)}</span>")
            sb.append("<span class='branch-msg'>${esc(b.message)}</span>")
            sb.append("</div>")
        }
        sb.append("</div></div>")
    }
}
