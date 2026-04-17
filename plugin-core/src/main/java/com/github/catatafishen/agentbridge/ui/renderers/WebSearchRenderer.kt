package com.github.catatafishen.agentbridge.ui.renderers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
            panel.add(HtmlToolRendererSupport.markdownPane(buildResultsMarkdown(parsed.results)))
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
                    val results = parseResults(firstArray(obj, "results", "items", "entries"))
                    if (results.isEmpty()) null else SearchContent(query, results, null)
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildResultsMarkdown(results: List<SearchResult>): String {
        return results.joinToString("\n\n") { result ->
            buildString {
                append("- ")
                if (!result.url.isNullOrBlank()) {
                    append("[").append(result.title).append("](").append(result.url).append(")")
                } else {
                    append(result.title)
                }
                result.snippet?.takeIf { it.isNotBlank() }?.let {
                    append("\n  ").append(it.trim())
                }
            }
        }
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

    private fun firstArray(obj: JsonObject, vararg keys: String): JsonArray? {
        for (key in keys) {
            val element = obj.get(key) ?: continue
            if (element.isJsonArray) return element.asJsonArray
        }
        return null
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val element = obj.get(key) ?: continue
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
