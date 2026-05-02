package com.github.catatafishen.agentbridge.ui.renderers

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.swing.JComponent

object WebFetchRenderer : ToolResultRenderer {
    data class FetchContent(val url: String?, val status: String?, val body: String)

    override fun render(output: String): JComponent? {
        val parsed = parseOutput(output) ?: return null
        val panel = ToolRenderers.listPanel()
        val header = ToolRenderers.statusHeader(ToolIcons.SEARCH, "Fetched URL", ToolRenderers.INFO_COLOR)
        parsed.status?.takeIf { it.isNotBlank() }?.let { header.add(ToolRenderers.mutedLabel(it)) }
        panel.add(header)

        parsed.url?.takeIf { it.isNotBlank() }?.let {
            val row = ToolRenderers.rowPanel()
            row.add(ToolRenderers.mutedLabel("URL"))
            row.add(ToolRenderers.monoLabel(it))
            panel.add(row)
        }

        if (parsed.body.isNotBlank()) {
            panel.add(HtmlToolRendererSupport.markdownPane(parsed.body))
        }
        return panel
    }

    private fun parseOutput(output: String): FetchContent? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null

        parseJson(trimmed)?.let { return it }

        val lines = trimmed.lines()
        var url: String? = null
        var status: String? = null
        var bodyStart = lines.size
        var lastHeaderIndex = -1
        for ((index, line) in lines.withIndex()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                bodyStart = index + 1
                break
            }
            val isHeaderLine = when {
                trimmedLine.startsWith("URL:", ignoreCase = true) -> {
                    url = trimmedLine.substringAfter(":").trim()
                    true
                }

                trimmedLine.startsWith("Status:", ignoreCase = true) -> {
                    status = trimmedLine.substringAfter(":").trim()
                    true
                }

                url == null && (trimmedLine.startsWith("http://") || trimmedLine.startsWith("https://")) -> {
                    url = trimmedLine
                    true
                }

                else -> false
            }
            if (isHeaderLine) {
                lastHeaderIndex = index
                continue
            }
            bodyStart = index
            break
        }
        if (bodyStart == lines.size && lastHeaderIndex >= 0) {
            bodyStart = lastHeaderIndex + 1
        }
        val body = lines.drop(bodyStart.coerceAtMost(lines.size)).joinToString("\n").trim().ifBlank { trimmed }
        return FetchContent(url, status, body)
    }

    private fun parseJson(raw: String): FetchContent? {
        return try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            val url = firstString(obj, "url", "finalUrl", "link")
            val status = firstString(obj, "status", "statusCode", "code")
            val body = firstString(obj, "markdown", "content", "body", "text", "snippet")
            if (url == null && status == null && body == null) null else FetchContent(url, status, body ?: raw)
        } catch (_: Exception) {
            null
        }
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
