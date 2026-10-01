package com.github.catatafishen.agentbridge.client.koog

import ai.koog.agents.core.tools.ToolParameterType
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoogToolSchemasTest {

    private fun spec(schemaJson: String) =
        McpToolSpec("demo", "A demo tool", JsonParser.parseString(schemaJson).asJsonObject)

    @Test
    fun `name and description carry over`() {
        val descriptor = KoogToolSchemas.toDescriptor(spec("""{"type":"object","properties":{}}"""))

        assertEquals("demo", descriptor.name)
        assertEquals("A demo tool", descriptor.description)
        assertTrue(descriptor.requiredParameters.isEmpty())
        assertTrue(descriptor.optionalParameters.isEmpty())
    }

    @Test
    fun `required and optional parameters are split by the required list`() {
        val descriptor = KoogToolSchemas.toDescriptor(
            spec(
                """{"type":"object","properties":{
                    "path":{"type":"string","description":"File to read"},
                    "start_line":{"type":"integer","description":"First line"}},
                  "required":["path"]}"""
            )
        )

        assertEquals(listOf("path"), descriptor.requiredParameters.map { it.name })
        assertEquals("File to read", descriptor.requiredParameters.single().description)
        assertEquals(listOf("start_line"), descriptor.optionalParameters.map { it.name })
    }

    @Test
    fun `scalar types map to their Koog counterparts`() {
        val descriptor = KoogToolSchemas.toDescriptor(
            spec(
                """{"type":"object","properties":{
                    "s":{"type":"string"},"i":{"type":"integer"},"n":{"type":"number"},"b":{"type":"boolean"}}}"""
            )
        )
        val byName = descriptor.optionalParameters.associate { it.name to it.type }

        assertEquals(ToolParameterType.String, byName["s"])
        assertEquals(ToolParameterType.Integer, byName["i"])
        assertEquals(ToolParameterType.Float, byName["n"])
        assertEquals(ToolParameterType.Boolean, byName["b"])
    }

    @Test
    fun `arrays carry their item type and default to strings without one`() {
        val descriptor = KoogToolSchemas.toDescriptor(
            spec(
                """{"type":"object","properties":{
                    "paths":{"type":"array","items":{"type":"string"}},
                    "counts":{"type":"array","items":{"type":"integer"}},
                    "bare":{"type":"array"}}}"""
            )
        )
        val byName = descriptor.optionalParameters.associate { it.name to it.type }

        assertEquals(ToolParameterType.List(ToolParameterType.String), byName["paths"])
        assertEquals(ToolParameterType.List(ToolParameterType.Integer), byName["counts"])
        assertEquals(ToolParameterType.List(ToolParameterType.String), byName["bare"])
    }

    @Test
    fun `enums keep their allowed values`() {
        val type = KoogToolSchemas.parameterType(
            JsonParser.parseString("""{"type":"string","enum":["a","b"]}""").asJsonObject
        )

        assertTrue(type is ToolParameterType.Enum)
        assertEquals(listOf("a", "b"), (type as ToolParameterType.Enum).entries.toList())
    }

    @Test
    fun `dictionary parameters keep their value type`() {
        val type = KoogToolSchemas.parameterType(
            JsonParser.parseString("""{"type":"object","additionalProperties":{"type":"string"}}""").asJsonObject
        )

        assertTrue(type is ToolParameterType.Object)
        assertEquals(ToolParameterType.String, (type as ToolParameterType.Object).additionalPropertiesType)
    }

    @Test
    fun `nested object properties and their required list are kept`() {
        val type = KoogToolSchemas.parameterType(
            JsonParser.parseString(
                """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["name"]}"""
            ).asJsonObject
        ) as ToolParameterType.Object

        assertEquals(listOf("name", "age"), type.properties.map { it.name })
        assertEquals(listOf("name"), type.requiredProperties)
    }

    @Test
    fun `unknown or missing types fall back to string`() {
        val descriptor = KoogToolSchemas.toDescriptor(
            spec("""{"type":"object","properties":{"x":{"type":"mystery"},"y":{}}}""")
        )

        assertEquals(listOf(ToolParameterType.String, ToolParameterType.String), descriptor.optionalParameters.map { it.type })
    }

    @Test
    fun `a spec without a schema yields a tool without parameters`() {
        val descriptor = KoogToolSchemas.toDescriptor(McpToolSpec("bare", "No schema", com.google.gson.JsonObject()))

        assertTrue(descriptor.requiredParameters.isEmpty())
        assertTrue(descriptor.optionalParameters.isEmpty())
    }
}
