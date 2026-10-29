package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ButtonSpecParserTest {

    private val pushSchema = obj("""{"type":"object","properties":{"force":{"type":"boolean"}},"required":[]}""")
    private val commitSchema = obj(
        """{"type":"object","properties":{"message":{"type":"string"},"amend":{"type":"boolean"}},"required":["message"]}"""
    )

    private val catalog = object : ToolCatalog {
        private val schemas = mapOf("git_push" to pushSchema, "git_commit" to commitSchema, "git_reset" to pushSchema)
        override fun inputSchema(toolName: String) = schemas[toolName]
        override fun allToolNames() = schemas.keys
        override fun isDenied(toolName: String) = toolName == "git_reset"
    }

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun parse(json: String) = ButtonSpecParser.parse(obj(json), catalog)

    // ── replies ──────────────────────────────────────────────────────────────

    @Test
    fun `plain strings become reply buttons`() {
        val r = parse("""{"replies":["Yes","No"]}""")
        assertTrue(r.ok)
        assertEquals(listOf("Yes", "No"), r.replies.map { it.label })
        assertEquals(listOf("Yes", "No"), r.replies.map { it.text })
    }

    @Test
    fun `reply object supports text style and dismiss`() {
        val r =
            parse("""{"replies":[{"label":"Go","text":"Please proceed","style":"primary"},{"label":"Later","dismiss":true}]}""")
        assertTrue(r.ok)
        assertEquals("Please proceed", r.replies[0].text)
        assertEquals(ButtonStyle.PRIMARY, r.replies[0].style)
        assertTrue(r.replies[1].dismiss)
    }

    @Test
    fun `legacy options key is accepted as replies`() {
        val r = parse("""{"options":["A","B"]}""")
        assertTrue(r.ok)
        assertEquals(2, r.replies.size)
    }

    @Test
    fun `colon in label is plain text not a style`() {
        val r = parse("""{"replies":["Fix: danger"]}""")
        assertTrue(r.ok)
        assertEquals("Fix: danger", r.replies[0].label)
        assertEquals(ButtonStyle.DEFAULT, r.replies[0].style)
    }

    @Test
    fun `long reply labels are accepted and sent in full`() {
        val long = "Yes, please go ahead and do all of the things you listed above"
        val r = parse("""{"replies":["$long"]}""")
        assertTrue(r.ok, r.errors.toString())
        assertEquals(long, r.replies.single().text)
    }

    @Test
    fun `long action labels are rejected`() {
        val long = "x".repeat(ButtonSpecParser.MAX_LABEL_LENGTH + 1)
        val r = parse("""{"actions":[{"label":"$long","tool":"git_push"}]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("too long"))
    }

    @Test
    fun `non boolean dismiss is an error`() {
        val r = parse("""{"replies":[{"label":"Later","dismiss":"yes"}]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("dismiss"))
    }

    // ── actions ──────────────────────────────────────────────────────────────

    @Test
    fun `valid action parses`() {
        val r =
            parse("""{"actions":[{"label":"Force push","tool":"git_push","arguments":{"force":true},"style":"danger"}]}""")
        assertTrue(r.ok, r.errors.toString())
        val a = r.actions.single()
        assertEquals("git_push", a.tool)
        assertTrue(a.arguments.get("force").asBoolean)
        assertEquals(ButtonStyle.DANGER, a.style)
    }

    @Test
    fun `arguments may be omitted when nothing is required`() {
        val r = parse("""{"actions":[{"label":"Push","tool":"git_push"}]}""")
        assertTrue(r.ok, r.errors.toString())
        assertEquals(0, r.actions.single().arguments.size())
    }

    @Test
    fun `arguments may be a JSON string`() {
        val r = parse("""{"actions":[{"label":"Push","tool":"git_push","arguments":"{\"force\":true}"}]}""")
        assertTrue(r.ok, r.errors.toString())
        assertTrue(r.actions.single().arguments.get("force").asBoolean)
    }

    @Test
    fun `tool name prefixes are normalised`() {
        for (name in listOf("agentbridge/git_push", "mcp__agentbridge__git_push", " git_push ")) {
            val r = parse("""{"actions":[{"label":"Push","tool":"$name"}]}""")
            assertTrue(r.ok, "$name: ${r.errors}")
            assertEquals("git_push", r.actions.single().tool)
        }
    }

    @Test
    fun `unknown tool suggests a close match`() {
        val r = parse("""{"actions":[{"label":"Push","tool":"git_psh"}]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("Did you mean \"git_push\""), r.errors.toString())
    }

    @Test
    fun `denied tool is rejected at offer time`() {
        val r = parse("""{"actions":[{"label":"Reset","tool":"git_reset"}]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("denied"))
    }

    @Test
    fun `missing required argument lists the tool parameters`() {
        val r = parse("""{"actions":[{"label":"Commit","tool":"git_commit","arguments":{}}]}""")
        assertFalse(r.ok)
        val msg = r.errors.single()
        assertTrue(msg.contains("\"message\"") && msg.contains("amend:boolean"), msg)
    }

    @Test
    fun `prompt_user cannot be offered as an action`() {
        val r = parse("""{"actions":[{"label":"Ask","tool":"prompt_user"}]}""")
        assertFalse(r.ok)
    }

    @Test
    fun `missing tool shows a usage example`() {
        val r = parse("""{"actions":[{"label":"Push"}]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("\"tool\""))
    }

    @Test
    fun `non object action is rejected`() {
        assertFalse(parse("""{"actions":["git_push"]}""").ok)
    }

    // ── cross-cutting validation ─────────────────────────────────────────────

    @Test
    fun `all errors are reported together`() {
        val r = parse(
            """{"replies":[{"label":"","style":"loud"}],"actions":[{"label":"Push","tool":"nope"},{"label":"Commit","tool":"git_commit"}]}"""
        )
        assertFalse(r.ok)
        assertTrue(r.errors.size >= 4, r.errors.toString())
    }

    @Test
    fun `invalid style is rejected and lists valid values`() {
        val r = parse("""{"replies":[{"label":"A","style":"loud"}]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("danger"))
    }

    @Test
    fun `duplicate labels are rejected across both kinds case-insensitively`() {
        val r = parse("""{"replies":["Push"],"actions":[{"label":"push","tool":"git_push"}]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.single().contains("duplicate"))
    }

    @Test
    fun `more than max buttons is rejected`() {
        val labels = (1..ButtonSpecParser.MAX_BUTTONS + 1).joinToString(",") { "\"b$it\"" }
        val r = parse("""{"replies":[$labels]}""")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("Too many") })
    }

    @Test
    fun `no buttons is valid and empty`() {
        val r = parse("""{"question":"Hi"}""")
        assertTrue(r.ok)
        assertTrue(r.replies.isEmpty() && r.actions.isEmpty())
    }

    @Test
    fun `button style parse handles null and unknown`() {
        assertEquals(ButtonStyle.DEFAULT, ButtonStyle.parse(null))
        assertEquals(ButtonStyle.WARNING, ButtonStyle.parse("WARNING"))
        assertEquals(null, ButtonStyle.parse("loud"))
    }
}
