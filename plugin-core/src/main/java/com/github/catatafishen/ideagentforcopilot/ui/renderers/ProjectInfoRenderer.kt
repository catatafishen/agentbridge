package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders get_project_info output as a structured info card with
 * sections for IDE, SDK, modules, build system, and run configs.
 *
 * Input format:
 * ```
 * Project: MyProject
 * Path: /home/user/project
 * Agent Workspace: /home/user/project/.agent-work/ (for temp/working files)
 * IDE: IntelliJ IDEA 2025.1
 * ...
 * Modules (3):
 *   - module1
 *   - module2
 * ```
 */
internal object ProjectInfoRenderer : ToolResultRenderer {

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null
        if (!lines.first().startsWith("Project:")) return null

        val sb = StringBuilder("<div class='project-info'>")
        var inSection = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (inSection) { sb.append("</div>"); inSection = false }
                continue
            }

            if (trimmed.startsWith("- ") && inSection) {
                val item = trimmed.removePrefix("- ")
                sb.append("<div class='project-info-item'>${esc(item)}</div>")
                continue
            }

            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0 && !trimmed.startsWith("- ")) {
                val key = trimmed.substring(0, colonIdx).trim()
                val value = trimmed.substring(colonIdx + 1).trim()

                val sectionMatch = Regex("""\((\d+)\)""").find(key)
                if (sectionMatch != null) {
                    if (inSection) sb.append("</div>")
                    val sectionName = key.replace(sectionMatch.value, "").trim()
                    val count = sectionMatch.groupValues[1]
                    sb.append("<div class='project-info-section'>")
                    sb.append("<div class='outline-header'>")
                    sb.append("<span style='font-weight:600'>${esc(sectionName)}</span>")
                    sb.append("<span class='inspection-file-count'>$count</span>")
                    sb.append("</div>")
                    inSection = true
                } else if (key == "Project") {
                    sb.append("<div class='project-info-header'>${esc(value)}</div>")
                } else {
                    sb.append("<div class='project-info-kv'>")
                    sb.append("<span class='project-info-key'>${esc(key)}</span>")
                    sb.append("<span class='project-info-value'>${esc(value)}</span>")
                    sb.append("</div>")
                }
            }
        }
        if (inSection) sb.append("</div>")
        sb.append("</div>")
        return sb.toString()
    }
}
