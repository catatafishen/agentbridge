package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PermissionRequestContentTest {

    @Test
    fun `uses question as headline when present`() {
        val json = """{"question":"Can I use Read File?","args":{"path":"/tmp/x.txt"}}"""
        val parsed = PermissionRequestContent.parse("Read File", json)

        assertEquals("Can I use Read File?", parsed.question)
        assertEquals("Read File", parsed.toolName)
        assertEquals(listOf(PermissionRequestContent.Arg("path", "\"/tmp/x.txt\"")), parsed.args)
    }

    @Test
    fun `question is null when field missing so caller renders default headline`() {
        val json = """{"args":{"path":"/tmp/x.txt"}}"""
        val parsed = PermissionRequestContent.parse("Read File", json)

        assertNull(parsed.question)
        assertEquals("Read File", parsed.toolName)
        assertEquals(listOf(PermissionRequestContent.Arg("path", "\"/tmp/x.txt\"")), parsed.args)
    }

    @Test
    fun `question is null when blank`() {
        val json = """{"question":"   ","args":{"k":1}}"""
        val parsed = PermissionRequestContent.parse("Tool", json)

        assertNull(parsed.question)
    }

    @Test
    fun `renders multiple args as separate entries preserving order`() {
        val json = """{"question":"Run?","args":{"command":"ls","cwd":"/tmp","timeoutSec":30}}"""
        val parsed = PermissionRequestContent.parse("Bash", json)

        assertEquals(
            listOf(
                PermissionRequestContent.Arg("command", "\"ls\""),
                PermissionRequestContent.Arg("cwd", "\"/tmp\""),
                PermissionRequestContent.Arg("timeoutSec", "30"),
            ),
            parsed.args,
        )
    }

    @Test
    fun `renders nested object args as pretty-printed JSON`() {
        val json = """{"args":{"opts":{"flag":true,"n":2}}}"""
        val parsed = PermissionRequestContent.parse("Tool", json)

        assertEquals(1, parsed.args.size)
        assertEquals("opts", parsed.args[0].key)
        // Pretty-printing keeps both fields visible on separate lines.
        assertTrue(parsed.args[0].value.contains("\"flag\""))
        assertTrue(parsed.args[0].value.contains("true"))
        assertTrue(parsed.args[0].value.contains("\"n\""))
        assertTrue(parsed.args[0].value.contains("2"))
    }

    @Test
    fun `string scalars are quoted but numbers booleans nulls are not`() {
        val json = """{"args":{"s":"hi","n":7,"b":false,"x":null}}"""
        val parsed = PermissionRequestContent.parse("Tool", json)

        assertEquals(
            listOf(
                PermissionRequestContent.Arg("s", "\"hi\""),
                PermissionRequestContent.Arg("n", "7"),
                PermissionRequestContent.Arg("b", "false"),
                PermissionRequestContent.Arg("x", "null"),
            ),
            parsed.args,
        )
    }

    @Test
    fun `non-JSON description is surfaced as description arg with null question`() {
        val parsed = PermissionRequestContent.parse("Tool", "just some text")

        assertNull(parsed.question)
        assertEquals(listOf(PermissionRequestContent.Arg("description", "just some text")), parsed.args)
    }

    @Test
    fun `null or blank description produces empty args and null question`() {
        val a = PermissionRequestContent.parse("Tool", null)
        assertNull(a.question)
        assertEquals(emptyList<PermissionRequestContent.Arg>(), a.args)

        val b = PermissionRequestContent.parse("Tool", "   ")
        assertNull(b.question)
        assertEquals(emptyList<PermissionRequestContent.Arg>(), b.args)
    }

    @Test
    fun `JSON object without args field yields no args but exposes question if present`() {
        val parsed = PermissionRequestContent.parse("Tool", """{"question":"Proceed?"}""")
        assertEquals("Proceed?", parsed.question)
        assertEquals(emptyList<PermissionRequestContent.Arg>(), parsed.args)
    }
}
