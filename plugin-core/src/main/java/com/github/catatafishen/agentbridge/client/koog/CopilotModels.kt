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
    /** Null means the catalog did not advertise this capability; false means it explicitly denied it. */
    val supportsToolCalls: Boolean?,
    val modelType: String?,
    val contextWindow: Long?,
    val maxOutputTokens: Long?,
    /** Endpoints the model is served on, e.g. `/chat/completions`, `/responses`, `/v1/messages`. */
    val endpoints: List<String>,
    /** Reasoning-effort levels the model accepts, as the catalog lists them; empty when it takes none. */
    val reasoningEfforts: List<String> = emptyList(),
    /** `capabilities.supports.vision`; null when the catalog does not say. */
    val supportsVision: Boolean? = null,
) {
    /** Copilot lists endpoints per model; an absent list means the classic chat-completions API. */
    val usesChatCompletions: Boolean
        get() = (endpoints.isEmpty() || CHAT_COMPLETIONS in endpoints)

    val isChatModel: Boolean
        get() = (modelType == null || modelType == "chat")

    companion object {
        const val CHAT_COMPLETIONS = "/chat/completions"
    }
}

/** Parsing and filtering for `GET <copilot-api>/models`. */
object CopilotModels {

    /**
     * Parses the catalog. Picker visibility is presentation metadata, not an invocation guarantee: some subscriptions
     * return usable chat models with `model_picker_enabled: false`. Policy-disabled entries are still dropped.
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

    /**
     * Models this agent can use. The endpoint and chat model type are authoritative. Copilot sometimes omits
     * tool capability metadata, so only an explicit `tool_calls: false` excludes a model.
     */
    @JvmStatic
    fun usable(models: List<CopilotModel>): List<CopilotModel> =
        models.filter { model ->
            model.isChatModel && model.supportsToolCalls != false && model.usesChatCompletions
        }

    /**
     * One diagnostic line per catalog entry, before any filtering, so a surprising model list can be traced to what
     * the endpoint actually returned (duplicate ids, missing models, endpoint or picker flags).
     */
    @JvmStatic
    fun describe(json: String): List<String> {
        val data = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: JsonParseException) {
            null
        } ?: return listOf("catalog has no data array")
        return data.map { element ->
            val o = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@map "non-object entry: $element"
            val capabilities = o.obj("capabilities")
            val endpoints = o["supported_endpoints"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?.joinToString(",") { it.asString } ?: "-"
            "id=${o.string("id")} name=${o.string("name")} type=${capabilities?.string("type")} " +
                "tools=${capabilities?.obj("supports")?.bool("tool_calls")} picker=${o.bool("model_picker_enabled")} " +
                "policy=${o.obj("policy")?.string("state")} version=${o.string("version")} endpoints=$endpoints " +
                "effort=${reasoningEfforts(capabilities).joinToString(",").ifEmpty { "-" }} " +
                "vision=${capabilities?.obj("supports")?.bool("vision")}"
        }
    }

    private fun parseModel(o: JsonObject): CopilotModel? {
        val id = o.string("id") ?: return null
        if (o.obj("policy")?.string("state") == "disabled") return null
        val capabilities = o.obj("capabilities")
        val limits = capabilities?.obj("limits")
        return CopilotModel(
            id = id,
            name = o.string("name") ?: id,
            supportsToolCalls = capabilities?.obj("supports")?.bool("tool_calls"),
            modelType = capabilities?.string("type"),
            contextWindow = limits?.long("max_context_window_tokens") ?: limits?.long("max_prompt_tokens"),
            maxOutputTokens = limits?.long("max_output_tokens"),
            endpoints = o["supported_endpoints"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty(),
            reasoningEfforts = reasoningEfforts(capabilities),
            supportsVision = capabilities?.obj("supports")?.bool("vision"),
        )
    }

    /** `capabilities.supports.reasoning_effort` is a list of level names on models that take one; absent otherwise. */
    private fun reasoningEfforts(capabilities: JsonObject?): List<String> =
        capabilities?.obj("supports")?.get("reasoning_effort")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            ?.distinct().orEmpty()

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}
