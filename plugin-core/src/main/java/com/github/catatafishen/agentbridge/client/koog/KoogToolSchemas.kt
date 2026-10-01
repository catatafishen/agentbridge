package com.github.catatafishen.agentbridge.client.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * A tool as advertised by the in-process MCP handler: the exact name, description and JSON Schema
 * the tool would show to any external agent.
 */
data class McpToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** Human-readable name for the chat chip. */
    val displayName: String = name,
    /** Wire value of the tool's kind (read, edit, execute, ...), used to colour the chip. */
    val kind: String = "other",
)

/**
 * Maps the JSON Schema of AgentBridge tools onto Koog [ToolDescriptor]s so the model sees the same
 * parameters it would see over MCP.
 *
 * AgentBridge schemas use a small subset of JSON Schema (string, integer, number, boolean, array,
 * object with optional additionalProperties, optional enum). Unknown or missing types fall back to
 * string, which is what the model would have to send anyway.
 */
object KoogToolSchemas {

    fun toDescriptor(spec: McpToolSpec): ToolDescriptor {
        val properties = spec.inputSchema.objectOrNull("properties")
        val required = spec.inputSchema.stringList("required").toSet()
        val required0 = mutableListOf<ToolParameterDescriptor>()
        val optional = mutableListOf<ToolParameterDescriptor>()
        properties?.entrySet()?.forEach { (name, element) ->
            val schema = element.asJsonObjectOrNull() ?: JsonObject()
            val descriptor = ToolParameterDescriptor(name, schema.string("description").orEmpty(), parameterType(schema))
            if (name in required) required0 += descriptor else optional += descriptor
        }
        return ToolDescriptor(
            name = spec.name,
            description = spec.description,
            requiredParameters = required0,
            optionalParameters = optional,
        )
    }

    internal fun parameterType(schema: JsonObject): ToolParameterType {
        val enumValues = schema.stringList("enum")
        if (enumValues.isNotEmpty()) return ToolParameterType.Enum(enumValues.toTypedArray())
        return when (schema.string("type")) {
            "integer" -> ToolParameterType.Integer
            "number" -> ToolParameterType.Float
            "boolean" -> ToolParameterType.Boolean
            "array" -> ToolParameterType.List(
                schema.objectOrNull("items")?.let { parameterType(it) } ?: ToolParameterType.String
            )

            "object" -> objectType(schema)
            else -> ToolParameterType.String
        }
    }

    private fun objectType(schema: JsonObject): ToolParameterType.Object {
        val properties = schema.objectOrNull("properties")
        val nested = properties?.entrySet()?.map { (name, element) ->
            val child = element.asJsonObjectOrNull() ?: JsonObject()
            ToolParameterDescriptor(name, child.string("description").orEmpty(), parameterType(child))
        }.orEmpty()
        val additional = schema.get("additionalProperties")
        return ToolParameterType.Object(
            properties = nested,
            requiredProperties = schema.stringList("required"),
            additionalProperties = additional?.takeIf { it.isJsonPrimitive }?.asBoolean,
            additionalPropertiesType = additional?.asJsonObjectOrNull()?.let { parameterType(it) },
        )
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.asJsonObjectOrNull()

    private fun JsonObject.stringList(key: String): List<String> {
        val array: JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
    }

    private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject
}
