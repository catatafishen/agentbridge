package com.github.catatafishen.agentbridge.client.koog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KoogSystemPromptTest {

    private val preamble = "PREAMBLE"
    private val guidance = "TOOL GUIDANCE"

    @Test
    fun `without project instructions the prompt is the preamble, the root and the guidance`() {
        val prompt = KoogClient.systemPrompt(preamble, "/proj", guidance)

        assertEquals("PREAMBLE\n\nProject root: /proj\n\nTOOL GUIDANCE", prompt)
    }

    @Test
    fun `project instructions come after the project root and before the tool guidance`() {
        val prompt = KoogClient.systemPrompt(preamble, "/proj", guidance, "RULES")

        val order = listOf("Project root: /proj", "RULES", "TOOL GUIDANCE").map { prompt.indexOf(it) }

        assertEquals(order.sorted(), order, "expected root, then rules, then guidance, got $order")
        assertTrue(order.all { it >= 0 }, "every section must be present, got $order")
    }

    @Test
    fun `project instructions are labelled with the file they came from`() {
        val prompt = KoogClient.systemPrompt(preamble, "/proj", guidance, "RULES")

        assertTrue(prompt.contains("Project instructions (AGENTS.md):\n\nRULES"), prompt)
    }

    @Test
    fun `a null or blank project instruction adds no section`() {
        assertFalse(KoogClient.systemPrompt(preamble, "/proj", guidance, null).contains("Project instructions"))
        assertEquals(
            KoogClient.systemPrompt(preamble, "/proj", guidance),
            KoogClient.systemPrompt(preamble, "/proj", guidance, null),
        )
    }
}
