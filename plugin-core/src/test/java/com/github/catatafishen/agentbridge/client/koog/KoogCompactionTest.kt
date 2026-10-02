package com.github.catatafishen.agentbridge.client.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoogCompactionTest {

    private val meta get() = RequestMetaInfo.create(KoogClock.System)

    private fun user(text: String) = Message.User(text, meta)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)

    private fun toolStep(id: String, resultText: String): List<Message> = listOf(
        Message.Assistant(
            listOf(MessagePart.Tool.Call(id = id, tool = "read_file", args = "{}")), ResponseMetaInfo.Empty,
        ),
        Message.User(listOf(MessagePart.Tool.Result(id = id, tool = "read_file", output = resultText)), meta),
    )

    /** A turn: a prompt, one tool step with a [resultChars]-long result, and an answer. */
    private fun turn(n: Int, resultChars: Int): List<Message> =
        listOf(user("question $n")) + toolStep("c$n", "x".repeat(resultChars)) + assistant("answer $n")

    private fun resultsOf(messages: List<Message>) =
        messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()

    private fun callIds(messages: List<Message>) =
        messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>().map { it.id }

    private fun assertToolCallsPaired(messages: List<Message>) {
        assertEquals(callIds(messages).toSet(), resultsOf(messages).map { it.id }.toSet())
    }

    // Window of 1,000 tokens = 3,000 characters; trigger at 2,400, target 1,800.
    private fun compact(messages: List<Message>, overhead: Long = 0) = KoogCompaction.compact(messages, 1_000, overhead)

    @Test
    fun `a conversation that fits is returned untouched`() {
        val messages = turn(1, 500) + turn(2, 500)

        val out = compact(messages)

        assertFalse(out.changed)
        assertSame(messages, out.messages)
    }

    @Test
    fun `the trigger is below the window, so there is headroom for the answer`() {
        val justUnder = listOf(user("q"), assistant("a".repeat(2_300)))
        val justOver = listOf(user("q"), assistant("a".repeat(2_500)))

        assertFalse(compact(justUnder).changed)
        // Over the trigger but nothing can be cut: a single turn with no tool results stays as it is.
        val out = compact(justOver)
        assertEquals(0, out.shrunkResults)
        assertEquals(0, out.droppedTurns)
        assertEquals(justOver, out.messages)
    }

    @Test
    fun `the system prompt and tool descriptions count towards the window`() {
        // Two turns, so there is an old result that can be cut once the overhead pushes the request over the trigger.
        val messages = turn(1, 1_000) + turn(2, 100)

        assertFalse(compact(messages, overhead = 0).changed)
        assertTrue(compact(messages, overhead = 1_500).changed)
    }

    @Test
    fun `old tool results are cut first and keep a visible marker`() {
        val messages = turn(1, 2_500) + turn(2, 100)

        val out = compact(messages)

        assertEquals(1, out.shrunkResults)
        assertEquals(0, out.droppedTurns)
        val first = resultsOf(out.messages).first().output
        assertTrue(first.startsWith("x".repeat(KoogCompaction.KEPT_RESULT_CHARS)))
        assertTrue(first.contains("more characters were trimmed"), first)
        assertEquals("x".repeat(100), resultsOf(out.messages)[1].output)
    }

    @Test
    fun `the result of the latest step is never cut, because the model is about to read it`() {
        val messages = turn(1, 100) + turn(2, 2_500)

        val out = compact(messages)

        assertEquals("x".repeat(2_500), resultsOf(out.messages).last().output)
    }

    @Test
    fun `only as much is cut as needed`() {
        // Each turn is 38 + 1,100 characters. Together with the small last turn that is 2,414, just over the 2,400
        // trigger. Cutting the oldest result saves 629 and lands at 1,785, under the 1,800 target, so one cut is enough.
        val messages = turn(1, 1_100) + turn(2, 1_100) + turn(3, 100)

        val out = compact(messages)

        assertEquals(1, out.shrunkResults)
        assertEquals("x".repeat(1_100), resultsOf(out.messages)[1].output)
    }

    @Test
    fun `a result barely longer than what is kept is not cut, since the marker would make it longer`() {
        val short = "x".repeat(KoogCompaction.KEPT_RESULT_CHARS + 50)
        val messages = listOf(user("q")) + toolStep("a", short) + toolStep("b", "y".repeat(2_600)) + assistant("done")

        val out = compact(messages)

        assertEquals(short, resultsOf(out.messages).first().output)
    }

    @Test
    fun `when cutting results is not enough, whole oldest turns are dropped`() {
        // 500-character results are too short to be worth cutting, so only dropping turns can help.
        val messages = (1..6).flatMap { turn(it, 500) }

        val out = compact(messages)

        assertEquals(0, out.shrunkResults)
        assertTrue(out.droppedTurns > 0)
        val prompts = out.messages.filterIsInstance<Message.User>().flatMap { it.parts }
            .filterIsInstance<MessagePart.Text>().map { it.text }
        // The newest turns survive, the oldest do not.
        assertTrue("question 6" in prompts)
        assertFalse("question 1" in prompts)
    }

    @Test
    fun `the history always starts at a user prompt and no tool call loses its result`() {
        val messages = (1..8).flatMap { turn(it, 400) }

        val out = compact(messages)

        assertTrue(out.droppedTurns > 0)
        val first = out.messages.first()
        assertTrue(first is Message.User && first.parts.none { it is MessagePart.Tool.Result })
        assertToolCallsPaired(out.messages)
    }

    @Test
    fun `the latest turn is kept even when it alone is over the limit`() {
        val messages = turn(1, 100) + turn(2, 100) + listOf(user("huge")) + toolStep("h", "z".repeat(5_000)) + assistant("ok")

        val out = compact(messages)

        assertEquals("huge", (out.messages.first().parts.first() as MessagePart.Text).text)
        assertEquals("z".repeat(5_000), resultsOf(out.messages).last().output)
        assertToolCallsPaired(out.messages)
    }

    @Test
    fun `the input list is not modified`() {
        val messages = turn(1, 1_500) + turn(2, 100)
        val before = messages.toList()

        compact(messages)

        assertEquals(before, messages)
    }

    @Test
    fun `an error result keeps its error flag when it is cut`() {
        val failingStep = listOf(
            Message.Assistant(
                listOf(MessagePart.Tool.Call(id = "e", tool = "t", args = "{}")), ResponseMetaInfo.Empty,
            ),
            Message.User(
                listOf(MessagePart.Tool.Result(id = "e", tool = "t", output = "e".repeat(2_600), isError = true)), meta,
            ),
        )
        val messages = listOf(user("q")) + failingStep + turn(2, 100)

        val out = compact(messages)

        val cut = resultsOf(out.messages).first { it.id == "e" }
        assertEquals(1, out.shrunkResults)
        assertTrue(cut.output.contains("more characters were trimmed"), cut.output)
        assertTrue(cut.isError)
    }

    @Test
    fun `an attachment is counted as a fixed amount rather than ignored`() {
        val image = Message.User(
            listOf(
                MessagePart.Attachment(
                    ai.koog.prompt.message.AttachmentSource.Image(
                        ai.koog.prompt.message.AttachmentContent.URL("https://example.test/x.png"), "png",
                    ),
                ),
            ),
            meta,
        )

        assertTrue(KoogCompaction.sizeOf(image) > 0)
    }

    @Test
    fun `size counts text, calls and results`() {
        assertEquals(5L, KoogCompaction.sizeOf(user("hello")))
        assertEquals("read_file".length + 2L, KoogCompaction.sizeOf(toolStep("1", "").first()))
        assertEquals("read_file".length + 3L, KoogCompaction.sizeOf(toolStep("1", "abc")[1]))
    }
}
