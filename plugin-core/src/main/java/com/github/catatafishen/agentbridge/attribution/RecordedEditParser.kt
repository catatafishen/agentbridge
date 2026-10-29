package com.github.catatafishen.agentbridge.attribution

import com.google.gson.JsonParser

/**
 * Turns a stored editing tool call into a [RecordedEdit]. Pure: no IDE or database access.
 *
 * Only calls whose arguments carry the text they inserted can be replayed. Calls that edit by line
 * range, or whose arguments were truncated when stored, return null and are simply not attributed.
 */
object RecordedEditParser {

    fun parse(turnId: String, timestampMs: Long, toolName: String, argumentsJson: String?): RecordedEdit? {
        if (argumentsJson.isNullOrBlank()) return null
        val args = try {
            JsonParser.parseString(argumentsJson).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        fun str(key: String): String? =
            args.get(key)?.takeIf { it.isJsonPrimitive }?.asString

        return when (toolName) {
            "edit_text" -> str("new_str")?.let { RecordedEdit(turnId, timestampMs, it, wholeFile = false) }
            "replace_symbol_body" -> str("new_body")?.let { RecordedEdit(turnId, timestampMs, it, wholeFile = false) }
            "insert_after_symbol", "insert_before_symbol" ->
                str("content")?.let { RecordedEdit(turnId, timestampMs, it, wholeFile = false) }

            "create_file" -> str("content")?.let { RecordedEdit(turnId, timestampMs, it, wholeFile = true) }
            "write_file" -> parseWriteFile(turnId, timestampMs, ::str, args.has("start_line") || args.has("old_str"))
            else -> null
        }
    }

    /**
     * write_file either rewrites the whole file (just `content`), replaces a line range
     * (`start_line`/`end_line`), or edits in place (`old_str`/`new_str`).
     */
    private fun parseWriteFile(
        turnId: String, timestampMs: Long, str: (String) -> String?, partial: Boolean,
    ): RecordedEdit? {
        if (partial) {
            val text = str("content") ?: str("new_str") ?: return null
            return RecordedEdit(turnId, timestampMs, text, wholeFile = false)
        }
        return str("content")?.let { RecordedEdit(turnId, timestampMs, it, wholeFile = true) }
    }
}
