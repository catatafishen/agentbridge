package com.github.catatafishen.agentbridge.client.koog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpToolBackendParsingTest {

    @Test
    fun `instructions are read from the initialize result`() {
        val json = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"x","instructions":"Be careful."}}"""

        assertEquals("Be careful.", McpToolBackend.parseInstructions(json))
    }

    @Test
    fun `missing instructions parse as empty`() {
        assertEquals("", McpToolBackend.parseInstructions("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
    }

    @Test
    fun `an initialize error is raised, not hidden`() {
        val json = """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"boom"}}"""

        val e = assertThrows(IllegalStateException::class.java) { McpToolBackend.parseInstructions(json) }
        assertTrue(e.message!!.contains("boom"))
    }

    @Test
    fun `tools keep name description and schema`() {
        val json = """{"jsonrpc":"2.0","id":2,"result":{"tools":[
            {"name":"read_file","description":"Read a file","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}}},
            {"name":"git_status","description":"Status"}]}}"""

        val tools = McpToolBackend.parseTools(json)

        assertEquals(listOf("read_file", "git_status"), tools.map { it.name })
        assertEquals("Read a file", tools[0].description)
        assertTrue(tools[0].inputSchema.getAsJsonObject("properties").has("path"))
        // A tool without a schema still parses, with an empty one.
        assertTrue(tools[1].inputSchema.entrySet().isEmpty())
    }

    @Test
    fun `entries without a name are skipped`() {
        val json = """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"description":"nameless"},{"name":"ok"}]}}"""

        assertEquals(listOf("ok"), McpToolBackend.parseTools(json).map { it.name })
    }

    @Test
    fun `an empty tool list parses as empty`() {
        assertTrue(McpToolBackend.parseTools("""{"jsonrpc":"2.0","id":2,"result":{}}""").isEmpty())
    }

    @Test
    fun `a successful call returns the joined text`() {
        val json = """{"jsonrpc":"2.0","id":3,"result":{"content":[
            {"type":"text","text":"line one"},{"type":"text","text":"line two"}],"isError":false}}"""

        val outcome = McpToolBackend.parseToolResult(json)

        assertEquals("line one\nline two", outcome.text)
        assertFalse(outcome.isError)
    }

    @Test
    fun `a tool level error keeps its text and is flagged`() {
        val json = """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Error: nope"}],"isError":true}}"""

        val outcome = McpToolBackend.parseToolResult(json)

        assertEquals("Error: nope", outcome.text)
        assertTrue(outcome.isError)
    }

    @Test
    fun `a protocol level error such as a disabled tool becomes an error outcome`() {
        val json = """{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"Tool is disabled: run_command"}}"""

        val outcome = McpToolBackend.parseToolResult(json)

        assertTrue(outcome.isError)
        assertEquals("Error: Tool is disabled: run_command", outcome.text)
    }

    @Test
    fun `non text content blocks are ignored`() {
        val json = """{"jsonrpc":"2.0","id":3,"result":{"content":[
            {"type":"image","data":"abc"},{"type":"text","text":"caption"}]}}"""

        assertEquals("caption", McpToolBackend.parseToolResult(json).text)
    }

    @Test
    fun `a result without content is an error rather than silent success`() {
        val outcome = McpToolBackend.parseToolResult("""{"jsonrpc":"2.0","id":3}""")

        assertTrue(outcome.isError)
    }
}
