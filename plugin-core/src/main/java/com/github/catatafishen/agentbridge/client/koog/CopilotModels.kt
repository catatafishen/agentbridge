package com.github.catatafishen.agentbridge.client.koog

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** A model offered by the Copilot API, reduced to what the agent needs to pick and call it. */
data class CopilotModel(
    val id: String,
    val name: String,
    val supportsToolCalls: Boolean,
    val contextWindow: Long?,
    val maxOutputTokens: Long?,
    /** Endpoints the model is served on, e.g. `/chat/completions`, `/responses`, `/v1/messages`. */
    val endpoints: List<String>,
) {
    /** Copilot lists endpoints per model; an absent list means the classic chat-completions API. */
    val usesChatCompletions: Boolean
        get() = endpoints.isEmpty() || CHAT_COMPLETIONS in endpoints

    companion object {
        const val CHAT_COMPLETIONS = "/chat/completions"
    }
}

/** Parsing and filtering for `GET <copilot-api>/models`. */
object CopilotModels {

    /**
     * Parses the catalog, keeping only models the user can pick in Copilot's own model picker. A model the
     * account may not use (policy disabled) is dropped here rather than failing on the first prompt.
     */
    @JvmStatic
    fun parse(json: String): List<CopilotModel> {
        val data: JsonArray = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: JsonParseException) {
            null
        } ?: return emptyList()
        return data.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(::parseModel) }
    }

    /** Models this agent can use: tool-capable and served by the chat-completions endpoint. */
    @JvmStatic
    fun usable(models: List<CopilotModel>): List<CopilotModel> =
        models.filter { it.supportsToolCalls && it.usesChatCompletions }

    private fun parseModel(o: JsonObject): CopilotModel? {
        val id = o.string("id") ?: return null
        if (o.bool("model_picker_enabled") == false) return null
        if (o.obj("policy")?.string("state") == "disabled") return null
        val capabilities = o.obj("capabilities")
        val limits = capabilities?.obj("limits")
        return CopilotModel(
            id = id,
            name = o.string("name") ?: id,
            supportsToolCalls = capabilities?.obj("supports")?.bool("tool_calls") ?: false,
            contextWindow = limits?.long("max_context_window_tokens") ?: limits?.long("max_prompt_tokens"),
            maxOutputTokens = limits?.long("max_output_tokens"),
            endpoints = o.get("supported_endpoints")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}
