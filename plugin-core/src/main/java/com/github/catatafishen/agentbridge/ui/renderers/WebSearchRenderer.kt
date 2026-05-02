package com.github.catatafishen.agentbridge.ui.renderers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

object WebSearchRenderer : ToolResultRenderer {
    private data class SearchResult(val title: String, val url: String?, val snippet: String?)
    private data class SearchContent(val query: String?, val results: List<SearchResult>, val fallbackBody: String?)

    override fun render(output: String): JComponent? {
        val parsed = parseOutput(output) ?: return null
        val panel = ToolRenderers.listPanel()
        if (parsed.results.isNotEmpty()) {
            panel.add(ToolRenderers.headerPanel(ToolIcons.SEARCH, parsed.results.size, "results"))
            parsed.query?.takeIf { it.isNotBlank() }?.let {
                val row = ToolRenderers.rowPanel()
                row.add(ToolRenderers.mutedLabel("Query"))
                row.add(ToolRenderers.monoLabel(it))
                panel.add(row)
            }
            val displayResults = parsed.results.take(ToolRenderers.MAX_LIST_ENTRIES)
            displayResults.forEach { panel.add(renderResultSection(it)) }
            val remaining = parsed.results.size - displayResults.size
            if (remaining > 0) {
                ToolRenderers.addTruncationIndicator(panel, remaining, "results")
            }
        } else {
            panel.add(ToolRenderers.statusHeader(ToolIcons.SEARCH, "Web Search", ToolRenderers.INFO_COLOR))
            parsed.fallbackBody?.takeIf { it.isNotBlank() }?.let { panel.add(HtmlToolRendererSupport.markdownPane(it)) }
        }
        return panel
    }

    private fun parseOutput(output: String): SearchContent? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null

        parseJson(trimmed)?.let { return it }
        return SearchContent(null, emptyList(), trimmed)
    }

    private fun parseJson(raw: String): SearchContent? {
        return try {
            val root = JsonParser.parseString(raw)
            when {
                root.isJsonArray -> SearchContent(null, parseResults(root.asJsonArray), null)
                root.isJsonObject -> {
                    val obj = root.asJsonObject
                    val query = firstString(obj, "query", "search", "prompt")
                    val results = parseResults(extractResultsArray(obj))
                    if (results.isEmpty()) null else SearchContent(query, results, null)
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderResultSection(result: SearchResult): JComponent {
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val row = ToolRenderers.rowPanel()
        val normalizedTitle = normalizeInlineText(result.title)
        val normalizedUrl = result.url?.let(::normalizeInlineText)
        if (!normalizedUrl.isNullOrBlank()) {
            row.add(HyperlinkLabel(normalizedTitle).apply {
                addHyperlinkListener { BrowserUtil.browse(normalizedUrl) }
            })
            row.add(ToolRenderers.mutedLabel(normalizedUrl))
        } else {
            row.add(JBLabel(normalizedTitle))
        }
        section.add(row)

        result.snippet
            ?.let(::normalizeInlineText)
            ?.takeIf { it.isNotEmpty() }
            ?.let { snippet ->
                section.add(ToolRenderers.mutedLabel(snippet).apply {
                    alignmentX = JComponent.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyLeft(8)
                })
            }
        return section
    }

    private fun normalizeInlineText(value: String): String {
        return value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun parseResults(resultsArray: JsonArray?): List<SearchResult> {
        if (resultsArray == null) return emptyList()
        return resultsArray.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val title = firstString(obj, "title", "name", "label") ?: return@mapNotNull null
            val url = firstString(obj, "url", "link")
            val snippet = firstString(obj, "snippet", "description", "content", "text")
            SearchResult(title, url, snippet)
        }
    }

    private fun extractResultsArray(obj: JsonObject): JsonArray? {
        return listOf("results", "items", "entries")
            .asSequence()
            .mapNotNull(obj::get)
            .firstOrNull { it.isJsonArray }
            ?.asJsonArray
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val element = obj[key] ?: continue
            val value = elementToText(element)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun elementToText(element: JsonElement): String? = when {
        element.isJsonNull -> null
        element.isJsonPrimitive -> element.asString
        else -> element.toString()
    }
}
