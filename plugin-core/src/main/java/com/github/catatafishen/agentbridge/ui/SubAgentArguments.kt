package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads the sub-agent type, description and prompt a main agent passed to its sub-agent tool from the tool's
 * JSON arguments.
 *
 * Agents that send the sub-agent tool call with empty input (OpenCode: `rawInput: {}`, title `task` or `subagent`)
 * reveal these only in a later tool call update, so the same extraction is applied to both.
 */
object SubAgentArguments {

    /** Argument keys naming the sub-agent: `subagent_type` (Claude, OpenCode's `task`), `agent` (OpenCode's `subagent`). */
    private val AGENT_TYPE_KEYS = listOf("subagent_type", "agent")

    data class Details(val agentType: String?, val description: String?, val prompt: String?)

    /** @return the non-blank sub-agent type, description and prompt, or null when [arguments] is not a JSON object carrying any */
    @JvmStatic
    fun parse(arguments: String?): Details? {
        if (arguments.isNullOrBlank()) return null
        val obj = runCatching { JsonParser.parseString(arguments) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val agentType = AGENT_TYPE_KEYS.firstNotNullOfOrNull { obj.text(it) }
        val description = obj.text("description")
        val prompt = obj.text("prompt")
        return if (agentType == null && description == null && prompt == null) null
        else Details(agentType, description, prompt)
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
}
