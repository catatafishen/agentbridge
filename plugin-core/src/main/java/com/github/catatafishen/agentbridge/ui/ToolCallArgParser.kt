package com.github.catatafishen.agentbridge.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/**
 * Pure parsing and extraction functions for tool-call arguments and results.
 * Extracted from [ChatConsolePanel] to enable unit testing without Swing/JCEF dependencies.
 */
object ToolCallArgParser {

    private val TERMINAL_TOOLS = setOf(
        "run_in_terminal", "read_terminal_output", "write_terminal_input", "list_terminals"
    )
    private val RUN_TOOLS = setOf(
        "run_command", "read_run_output", "run_configuration", "run_tests"
    )
    private val BUILD_TOOLS = setOf(
        "read_build_output", "build_project"
    )

    /**
     * Returns `true` when [text] looks like a JSON object or array
     * (starts/ends with `{}`/`[]`).
     */
    fun isJson(text: String): Boolean =
        (text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))

    /**
     * Pretty-prints a JSON string. Returns the input unchanged on parse failure.
     */
    fun prettyJson(json: String): String {
        return try {
            val el = JsonParser.parseString(json)
            GsonBuilder().setPrettyPrinting().create().toJson(el)
        } catch (_: Exception) {
            json
        }
    }

    /**
     * Extracts a file path from tool-call arguments JSON.
     * Checks common keys: `path`, `file`, `filename`, `filepath`.
     * Returns `null` if no file path is found or arguments are not valid JSON.
     */
    fun extractFilePathFromArgs(arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        try {
            val json = JsonParser.parseString(arguments)
            if (!json.isJsonObject) return null
            val obj = json.asJsonObject
            for (key in listOf("path", "file", "filename", "filepath")) {
                if (obj.has(key) && obj[key].isJsonPrimitive) {
                    return obj[key].asString
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
        return null
    }

    /**
     * Extracts the summary text from `task_complete` arguments JSON.
     * Returns the `"summary"` field if present, otherwise the raw arguments string.
     */
    fun extractTaskCompleteSummary(arguments: String?): String {
        if (arguments.isNullOrBlank()) return ""
        try {
            val json = JsonParser.parseString(arguments)
            if (json.isJsonObject) {
                val obj = json.asJsonObject
                if (obj.has("summary") && obj["summary"].isJsonPrimitive) {
                    return obj["summary"].asString
                }
            }
        } catch (_: Exception) {
            // not valid JSON — fall through to raw text
        }
        return arguments
    }

    /**
     * Extracts before/after text from tool arguments for diff viewing.
     * Supports `edit_text` (`old_str`/`new_str`).
     * Returns `null` if no diff pair is found.
     */
    fun extractDiffFromArgs(arguments: String?): Pair<String, String>? {
        if (arguments.isNullOrBlank()) return null
        return try {
            val json = JsonParser.parseString(arguments).asJsonObject
            val oldStr = json["old_str"]?.asString
            val newStr = json["new_str"]?.asString
            if (oldStr != null && newStr != null && (oldStr.isNotBlank() || newStr.isNotBlank())) {
                Pair(oldStr, newStr)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the appropriate tab name from tool arguments based on the tool type.
     * Returns `null` if no tab name can be determined.
     */
    fun extractTabName(baseName: String?, arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        val name = baseName?.trim('\'', '"') ?: return null
        return try {
            val json = JsonParser.parseString(arguments).asJsonObject
            when (name) {
                "run_in_terminal", "read_terminal_output", "write_terminal_input" ->
                    json["tab_name"]?.asString

                "run_command" ->
                    json["title"]?.asString

                "read_run_output", "read_build_output" ->
                    json["tab_name"]?.asString

                "run_configuration" ->
                    json["name"]?.asString

                "run_tests" ->
                    json["target"]?.asString

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the IntelliJ tool window ID (`"Terminal"`, `"Run"`, `"Build"`) for a given
     * tool base name. Returns `null` if the tool doesn't map to a known tool window.
     */
    fun resolveToolWindowId(baseName: String?): String? {
        val name = baseName?.trim('\'', '"') ?: return null
        return when (name) {
            in TERMINAL_TOOLS -> "Terminal"
            in RUN_TOOLS -> "Run"
            in BUILD_TOOLS -> "Build"
            else -> null
        }
    }

    /**
     * Normalizes a stored chip status string to a valid CSS class token.
     * Non-canonical values fall back to [MessageFormatter.ChipStatus.FAILED].
     */
    fun normalizeChipStatus(raw: String?): String {
        return when (raw) {
            MessageFormatter.ChipStatus.PENDING,
            MessageFormatter.ChipStatus.RUNNING,
            MessageFormatter.ChipStatus.COMPLETE,
            MessageFormatter.ChipStatus.FAILED,
            MessageFormatter.ChipStatus.DENIED,
            MessageFormatter.ChipStatus.THINKING -> raw

            null, "completed" -> MessageFormatter.ChipStatus.COMPLETE
            else -> MessageFormatter.ChipStatus.FAILED
        }
    }
}
