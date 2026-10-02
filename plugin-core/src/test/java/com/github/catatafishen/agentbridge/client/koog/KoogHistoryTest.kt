package com.github.catatafishen.agentbridge.client.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.github.catatafishen.agentbridge.bridge.EntryData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoogHistoryTest {

    private val known = setOf("read_file", "search_text")

    private fun prompt(text: String) = EntryData.Prompt(text)
    private fun text(raw: String) = EntryData.Text(raw = raw)

    private fun call(
        name: String = "read_file",
        args: String? = """{"path":"a"}""",
        result: String? = "file body",
        status: String? = "completed",
        pluginTool: String? = name,
        autoDenied: Boolean = false,
        denialReason: String? = null,
    ) = EntryData.ToolCall(
        title = "Display name of $name", arguments = args, result = result, status = status,
        pluginTool = pluginTool, autoDenied = autoDenied, denialReason = denialReason,
    )

    private fun restore(vararg entries: EntryData, budget: Int = KoogHistory.DEFAULT_BUDGET_CHARS) =
        KoogHistory.restore(entries.toList(), known, budget)

    private fun shape(restored: KoogHistory.Restored) = restored.messages.map {
        when (it) {
            is Message.User -> if (it.parts.any { part -> part is MessagePart.Tool.Result }) "results" else "user"
            is Message.Assistant -> if (it.parts.any { part -> part is MessagePart.Tool.Call }) "assistant+calls" else "assistant"
            else -> it::class.simpleName.orEmpty()
        }
    }

    private fun calls(message: Message) = message.parts.filterIsInstance<MessagePart.Tool.Call>()
    private fun results(message: Message) = message.parts.filterIsInstance<MessagePart.Tool.Result>()

    @Test
    fun `a prompt and its answer become a user and an assistant message`() {
        val restored = restore(prompt("what is this?"), text("A plugin."))

        assertEquals(listOf("user", "assistant"), shape(restored))
        assertEquals("what is this?", restored.messages[0].textContent())
        assertEquals("A plugin.", restored.messages[1].textContent())
    }

    @Test
    fun `the trailing prompt nobody answered is left out because the next prompt replaces it`() {
        val restored = restore(prompt("one"), text("answer"), prompt("the prompt being sent now"))

        assertEquals(listOf("user", "assistant"), shape(restored))
        assertEquals("one", restored.messages[0].textContent())
    }

    @Test
    fun `a conversation with nothing answered restores nothing`() {
        assertTrue(restore(prompt("only this")).messages.isEmpty())
        assertTrue(restore().messages.isEmpty())
    }

    @Test
    fun `a stored tool call becomes a call with its result under the same id`() {
        val restored = restore(prompt("read it"), text("Looking."), call(), text("Done."))

        assertEquals(listOf("user", "assistant+calls", "results", "assistant"), shape(restored))
        val assistant = restored.messages[1]
        val call = calls(assistant).single()
        val result = results(restored.messages[2]).single()
        assertEquals("read_file", call.tool)
        assertEquals("""{"path":"a"}""", call.args)
        assertEquals("Looking.", assistant.textContent())
        assertTrue(call.id!!.isNotBlank())
        assertEquals(call.id, result.id)
        assertEquals("file body", result.output)
        assertFalse(result.isError)
    }

    @Test
    fun `several calls of one step share one assistant message and one results message`() {
        val restored = restore(prompt("go"), call(), call(name = "search_text", args = """{"q":"x"}"""), text("ok"))

        assertEquals(listOf("user", "assistant+calls", "results", "assistant"), shape(restored))
        val ids = calls(restored.messages[1]).map { it.id }
        assertEquals(2, ids.toSet().size)
        assertEquals(ids, results(restored.messages[2]).map { it.id })
    }

    @Test
    fun `text after a tool result starts the model's next step`() {
        val restored = restore(prompt("go"), call(), text("first thought"), call(name = "search_text"), text("end"))

        assertEquals(
            listOf("user", "assistant+calls", "results", "assistant+calls", "results", "assistant"),
            shape(restored),
        )
        assertEquals("first thought", restored.messages[3].textContent())
    }

    @Test
    fun `a call to a tool this agent does not have is skipped together with its result`() {
        val restored = restore(prompt("go"), call(name = "bash"), call(), text("ok"))

        assertEquals(1, restored.skippedToolCalls)
        assertEquals(listOf("user", "assistant+calls", "results", "assistant"), shape(restored))
        assertEquals(listOf("read_file"), calls(restored.messages[1]).map { it.tool })
    }

    @Test
    fun `the prefix another harness added to a tool name is removed`() {
        val restored = restore(prompt("go"), call(pluginTool = "mcp__agentbridge__read_file"), text("ok"))

        assertEquals(listOf("read_file"), calls(restored.messages[1]).map { it.tool })
    }

    @Test
    fun `arguments that are not a JSON object are replaced by an empty one`() {
        val restored = restore(
            prompt("go"), call(args = "not json"), call(args = null), call(args = "[1,2]"), text("ok"),
        )

        assertEquals(listOf("{}", "{}", "{}"), calls(restored.messages[1]).map { it.args })
    }

    @Test
    fun `a missing result is said to be missing, a failed one is an error, a denied one says why`() {
        val restored = restore(
            prompt("go"),
            call(result = null),
            call(result = "boom", status = "failed"),
            call(result = null, autoDenied = true, denialReason = "not allowed here"),
            text("ok"),
        )

        val out = results(restored.messages[2])
        assertEquals(KoogHistory.NO_RESULT, out[0].output)
        assertFalse(out[0].isError)
        assertEquals("boom", out[1].output)
        assertTrue(out[1].isError)
        assertEquals("Denied: not allowed here", out[2].output)
        assertTrue(out[2].isError)
    }

    @Test
    fun `a long tool result is cut short with a visible marker`() {
        val long = "x".repeat(KoogHistory.MAX_RESULT_CHARS + 500)

        val out = results(restore(prompt("go"), call(result = long), text("ok")).messages[2]).single().output

        assertTrue(out.startsWith("x".repeat(KoogHistory.MAX_RESULT_CHARS)))
        assertTrue(out.contains("500 more characters were not restored"), out)
        assertTrue(out.length < long.length)
    }

    @Test
    fun `entries before the first prompt belong to a turn that was not loaded and are dropped`() {
        val restored = restore(text("tail of an earlier answer"), call(), prompt("q"), text("a"))

        assertEquals(listOf("user", "assistant"), shape(restored))
        assertEquals("q", restored.messages[0].textContent())
    }

    @Test
    fun `entries that are not part of the dialogue are ignored`() {
        val restored = restore(
            prompt("q"),
            EntryData.Thinking(raw = "pondering"),
            EntryData.Status("i", "working"),
            EntryData.TurnStats(turnId = "t"),
            EntryData.Nudge(text = "n", id = "1"),
            EntryData.SessionSeparator(timestamp = ""),
            text("a"),
        )

        assertEquals(listOf("user", "assistant"), shape(restored))
        assertEquals("a", restored.messages[1].textContent())
    }

    @Test
    fun `a blank prompt does not start a turn`() {
        val restored = restore(prompt("q"), text("a"), prompt("  "), text("belongs to the turn before"), prompt("z"), text("y"))

        assertEquals(listOf("user", "assistant", "user", "assistant"), shape(restored))
        assertEquals("a\n\nbelongs to the turn before", restored.messages[1].textContent())
    }

    @Test
    fun `over the budget the oldest whole turns are dropped and the newest are kept`() {
        val answer = "a".repeat(100)

        val restored = restore(
            prompt("p1"), text(answer), prompt("p2"), text(answer), prompt("p3"), text(answer),
            budget = 250,
        )

        assertEquals(1, restored.droppedTurns)
        assertEquals(listOf("p2", answer, "p3", answer), restored.messages.map { it.textContent() })
    }

    @Test
    fun `the newest turn is kept even when it alone exceeds the budget`() {
        val restored = restore(prompt("p1"), text("a1"), prompt("p2"), text("a2"), budget = 1)

        assertEquals(1, restored.droppedTurns)
        assertEquals(listOf("p2", "a2"), restored.messages.map { it.textContent() })
    }

    @Test
    fun `within the budget nothing is dropped`() {
        val restored = restore(prompt("p1"), text("a1"), prompt("p2"), text("a2"))

        assertEquals(0, restored.droppedTurns)
        assertEquals(4, restored.messages.size)
    }

    @Test
    fun `restored call ids never repeat across turns`() {
        val restored = restore(prompt("a"), call(), text("x"), prompt("b"), call(), text("y"))

        val ids = restored.messages.flatMap { calls(it) }.map { it.id }
        assertEquals(2, ids.toSet().size)
    }
}
