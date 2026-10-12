package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads the description and prompt a main agent passed to its sub-agent tool from the tool's JSON arguments.
 *
 * Agents that send the sub-agent tool call with empty input (OpenCode: `rawInput: {}`, title `task`) reveal
 * these only in a later tool call update, so the same extraction is applied to both.
 */
object SubAgentArguments {

    data class Details(val description: String?, val prompt: String?)

    /** @return the non-blank description and prompt, or null when [arguments] is not a JSON object carrying either */
    @JvmStatic
    fun parse(arguments: String?): Details? {
        if (arguments.isNullOrBlank()) return null
        val obj = runCatching { JsonParser.parseString(arguments) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val description = obj.text("description")
        val prompt = obj.text("prompt")
        return if (description == null && prompt == null) null else Details(description, prompt)
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
