package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders list_tests output as grouped test classes with method counts.
 *
 * Input format:
 * ```
 * 42 tests:
 * ClassName.methodName (path/to/File.java:12)
 * ClassName.otherMethod (path/to/File.java:20)
 * ```
 */
internal object ListTestsRenderer : ToolResultRenderer {

    private val ENTRY_PATTERN = Regex("""^(\S+)\.(\S+)\s+\((.+?):(\d+)\)$""")
    private val COUNT_HEADER = Regex("""^(\d+)\s+tests?:""")

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        if (lines.first() == "No tests found") {
            return "<div class='search-result'><div class='search-header search-empty'>" +
                    "<span class='search-icon'>∅</span>" +
                    "<span class='search-status'>No tests found</span>" +
                    "</div></div>"
        }

        val entries = lines.mapNotNull { ENTRY_PATTERN.find(it.trim()) }
        if (entries.isEmpty()) return null

        val countMatch = COUNT_HEADER.find(lines.first())
        val count = countMatch?.groupValues?.get(1)?.toIntOrNull() ?: entries.size

        // Group by class name
        data class TestEntry(val className: String, val method: String, val path: String, val line: String)

        val tests = entries.map {
            TestEntry(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4])
        }
        val grouped = tests.groupBy { it.className }

        val sb = StringBuilder("<div class='search-result'>")
        sb.append("<div class='search-header'>")
        sb.append("<span class='search-count'>$count</span>")
        sb.append("<span class='search-label'>tests</span>")
        sb.append("</div>")

        sb.append("<div class='search-files'>")
        for ((className, methods) in grouped) {
            sb.append("<div class='search-file'>")
            sb.append("<div class='search-file-header'>")
            sb.append("<span class='git-file-path'>${esc(className)}</span>")
            sb.append("<span class='search-file-count'>${methods.size}</span>")
            sb.append("</div>")
            sb.append("<div class='search-file-matches'>")
            for (t in methods) {
                sb.append("<div class='search-match'>")
                sb.append("<span class='search-line'>:${t.line}</span>")
                sb.append(" <span class='outline-name'>${esc(t.method)}</span>")
                sb.append("</div>")
            }
            sb.append("</div></div>")
        }
        sb.append("</div></div>")
        return sb.toString()
    }
}
