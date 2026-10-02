package com.github.catatafishen.agentbridge.client.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.model.ContentBlock
import com.github.catatafishen.agentbridge.model.SessionUpdate
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KoogConversationTest {

    // ── fakes ───────────────────────────────────────────────────────────────

    private class FakeBackend(
        private val specs: List<McpToolSpec> = emptyList(),
        private val outcome: (String, JsonObject) -> ToolOutcome = { _, _ -> ToolOutcome("ok", false) },
    ) : ToolBackend {
        val calls = mutableListOf<Triple<String, JsonObject, String?>>()
        override fun instructions() = "instructions"
        override fun listTools() = specs
        override fun call(name: String, arguments: JsonObject, toolUseId: String?): ToolOutcome {
            calls += Triple(name, arguments, toolUseId)
            return outcome(name, arguments)
        }
    }

    /** Returns the next scripted frame list for every model request and records the prompts it was sent. */
    private class ScriptedModel(private vararg val replies: List<StreamFrame>) : ModelStreamer {
        val prompts = mutableListOf<Prompt>()
        val toolsSeen = mutableListOf<List<ToolDescriptor>>()
        private var next = 0

        override fun stream(prompt: Prompt, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            prompts += prompt
            toolsSeen += tools
            return flowOf(*replies[minOf(next++, replies.size - 1)].toTypedArray())
        }
    }

    private fun end(reason: String = "stop", input: Int? = null, output: Int? = null) =
        StreamFrame.End(reason, ResponseMetaInfo.create(KoogClock.System, null, input, output))

    private fun text(s: String, reason: String = "stop", input: Int? = null, output: Int? = null) = listOf(
        StreamFrame.TextDelta(s, 0), StreamFrame.TextComplete(s, 0), end(reason, input, output),
    )

    private fun toolCall(id: String, name: String, args: String, input: Int? = null, output: Int? = null) = listOf(
        StreamFrame.ToolCallComplete(id, name, args, 0), end("tool_calls", input, output),
    )

    private val readFile = McpToolSpec(
        "read_file", "Read a file",
        JsonParser.parseString("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""").asJsonObject,
        displayName = "Read File", kind = "read",
    )

    private fun conversation(
        model: ModelStreamer,
        backend: ToolBackend,
        maxSteps: Int = 50,
        window: Long? = null,
    ) = KoogConversation(model, backend, systemPrompt = { "SYSTEM" }, maxSteps = maxSteps, contextWindow = { window })

    private fun user(s: String) = listOf<ContentBlock>(ContentBlock.Text(s))

    private fun run(conv: KoogConversation, input: String = "hello"): Pair<TurnResult, List<SessionUpdate>> {
        val updates = mutableListOf<SessionUpdate>()
        val result = runBlocking { conv.runTurn(user(input)) { updates += it } }
        return result to updates
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Nested
    inner class Restoring {
        private val stored = listOf(
            EntryData.Prompt("earlier question"),
            EntryData.Text(raw = "earlier answer"),
            EntryData.Prompt("a question that used a tool"),
            EntryData.ToolCall(title = "Read File", arguments = """{"path":"a"}""", result = "file body", pluginTool = "read_file"),
            EntryData.Text(raw = "answer after the tool"),
        )

        @Test
        fun `the stored history reaches the model before the new prompt`() {
            val model = ScriptedModel(text("ok"))
            val conv = conversation(model, FakeBackend(listOf(readFile)))
            conv.restore(stored)

            run(conv, "next")

            val messages = model.prompts.single().messages
            assertEquals(
                listOf("SYSTEM", "earlier question", "earlier answer", "a question that used a tool"),
                messages.take(4).map { it.textContent() },
            )
            assertEquals("answer after the tool", messages[messages.size - 2].textContent())
            assertEquals("next", messages.last().textContent())
        }

        @Test
        fun `a restored tool call is kept as a call with its result when this agent has the tool`() {
            val model = ScriptedModel(text("ok"))
            val conv = conversation(model, FakeBackend(listOf(readFile)))
            conv.restore(stored)

            run(conv, "next")

            val parts = model.prompts.single().messages.flatMap { it.parts }
            val call = parts.filterIsInstance<MessagePart.Tool.Call>().single()
            val result = parts.filterIsInstance<MessagePart.Tool.Result>().single()
            assertEquals("read_file", call.tool)
            assertEquals(call.id, result.id)
            assertEquals("file body", result.output)
        }

        @Test
        fun `a stored call to a tool this agent does not have is left out of what the model sees`() {
            val model = ScriptedModel(text("ok"))
            val conv = conversation(model, FakeBackend(emptyList()))
            conv.restore(stored)

            run(conv, "next")

            val parts = model.prompts.single().messages.flatMap { it.parts }
            assertTrue(parts.none { it is MessagePart.Tool.Call || it is MessagePart.Tool.Result })
        }

        @Test
        fun `the history is restored once and later turns keep building on it`() {
            val model = ScriptedModel(text("first reply"), text("second reply"))
            val conv = conversation(model, FakeBackend(listOf(readFile)))
            conv.restore(listOf(EntryData.Prompt("q"), EntryData.Text(raw = "a")))

            run(conv, "one")
            run(conv, "two")

            assertEquals(
                listOf("SYSTEM", "q", "a", "one", "first reply", "two"),
                model.prompts[1].messages.map { it.textContent() },
            )
        }

        @Test
        fun `only a new conversation can be restored`() {
            val model = ScriptedModel(text("ok"))
            val used = conversation(model, FakeBackend())
            run(used)

            assertThrows(IllegalStateException::class.java) { used.restore(stored) }

            val restoredTwice = conversation(model, FakeBackend())
            restoredTwice.restore(stored)
            assertThrows(IllegalStateException::class.java) { restoredTwice.restore(stored) }
        }

        @Test
        fun `a stored conversation with nothing usable in it changes nothing`() {
            val model = ScriptedModel(text("ok"))
            val conv = conversation(model, FakeBackend())
            conv.restore(listOf(EntryData.Prompt("unanswered")))

            run(conv, "next")

            assertEquals(listOf("SYSTEM", "next"), model.prompts.single().messages.map { it.textContent() })
        }
    }

    @Nested
    inner class ContextWindow {
        private val big = "x".repeat(2_000)

        private fun bigBackend() = FakeBackend(listOf(readFile)) { _, _ -> ToolOutcome(big, false) }

        /** Reads a file three times, then answers: enough history to need trimming in a small window. */
        private fun readingModel() = ScriptedModel(
            toolCall("c1", "read_file", """{"path":"a"}"""),
            toolCall("c2", "read_file", """{"path":"b"}"""),
            toolCall("c3", "read_file", """{"path":"c"}"""),
            text("done"),
        )

        private fun banners(updates: List<SessionUpdate>) = updates.filterIsInstance<SessionUpdate.Banner>()

        @Test
        fun `an unknown window trims nothing`() {
            val model = readingModel()
            val conv = conversation(model, bigBackend(), window = null)

            val (_, updates) = run(conv, "go")

            assertTrue(banners(updates).isEmpty())
            assertEquals(big, model.prompts.last().messages.flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool.Result>().first().output)
        }

        @Test
        fun `a roomy window trims nothing and says nothing`() {
            val (_, updates) = run(conversation(readingModel(), bigBackend(), window = 100_000), "go")

            assertTrue(banners(updates).isEmpty())
        }

        @Test
        fun `a tight window shortens old results before the next request and tells the user`() {
            val model = readingModel()

            val (_, updates) = run(conversation(model, bigBackend(), window = 1_500), "go")

            val results = model.prompts.last().messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
            assertTrue(results.first().output.contains("trimmed to fit the model's context window"), results.first().output)
            assertEquals(big, results.last().output)
            val banner = banners(updates).first()
            assertEquals(SessionUpdate.BannerLevel.WARNING, banner.level())
            assertTrue(banner.message().contains("shortened"), banner.message())
        }

        @Test
        fun `trimming keeps every tool call paired with its result`() {
            val model = readingModel()

            run(conversation(model, bigBackend(), window = 1_500), "go")

            val parts = model.prompts.last().messages.flatMap { it.parts }
            assertEquals(
                parts.filterIsInstance<MessagePart.Tool.Call>().map { it.id }.toSet(),
                parts.filterIsInstance<MessagePart.Tool.Result>().map { it.id }.toSet(),
            )
        }

        @Test
        fun `the window is read for every request, so switching to a smaller model takes effect at once`() {
            var window: Long? = 100_000
            val conv = KoogConversation(
                ScriptedModel(
                    toolCall("c1", "read_file", """{"path":"a"}"""),
                    toolCall("c2", "read_file", """{"path":"b"}"""),
                    text("first done"),
                    toolCall("c3", "read_file", """{"path":"c"}"""),
                    text("second done"),
                ),
                bigBackend(), systemPrompt = { "SYSTEM" }, contextWindow = { window },
            )

            val (_, first) = run(conv, "first")
            assertTrue(banners(first).isEmpty())
            window = 1_500
            val (_, second) = run(conv, "second")

            assertTrue(banners(second).isNotEmpty())
        }
    }

    @Nested
    inner class PlainAnswers {
        @Test
        fun `a text answer streams to the chat and ends the turn`() {
            val model = ScriptedModel(text("Hi there"))
            val (result, updates) = run(conversation(model, FakeBackend()))

            assertEquals("end_turn", result.stopReason)
            val chunks = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
            assertEquals("Hi there", chunks.joinToString("") { it.text() })
        }

        @Test
        fun `the first request carries our system prompt and the user message and nothing else`() {
            val model = ScriptedModel(text("ok"))
            run(conversation(model, FakeBackend()), "what is this project?")

            val messages = model.prompts.single().messages
            assertEquals(2, messages.size)
            assertEquals("SYSTEM", messages[0].textContent())
            assertEquals(Message.Role.System, messages[0].role)
            assertEquals("what is this project?", messages[1].textContent())
            assertEquals(Message.Role.User, messages[1].role)
        }

        @Test
        fun `history carries over to the next turn`() {
            val model = ScriptedModel(text("first answer"), text("second answer"))
            val conv = conversation(model, FakeBackend())

            run(conv, "one")
            run(conv, "two")

            val secondRequest = model.prompts[1].messages.map { it.role to it.textContent() }
            assertEquals(
                listOf(
                    Message.Role.System to "SYSTEM",
                    Message.Role.User to "one",
                    Message.Role.Assistant to "first answer",
                    Message.Role.User to "two",
                ),
                secondRequest,
            )
        }

        @Test
        fun `token usage is summed and reported`() {
            val model = ScriptedModel(
                toolCall("c1", "read_file", """{"path":"a"}""", input = 100, output = 10),
                text("done", input = 150, output = 20),
            )
            val (result, updates) = run(conversation(model, FakeBackend(listOf(readFile))))

            assertEquals(250L, result.inputTokens)
            assertEquals(30L, result.outputTokens)
            val usage = updates.filterIsInstance<SessionUpdate.TurnUsage>().single()
            assertEquals(250, usage.inputTokens)
            assertEquals(30, usage.outputTokens)
        }

        @Test
        fun `no usage is reported when the provider sends none`() {
            val (result, updates) = run(conversation(ScriptedModel(text("hi")), FakeBackend()))

            assertNull(result.inputTokens)
            assertTrue(updates.none { it is SessionUpdate.TurnUsage })
        }

        @Test
        fun `a length cut off is reported as max_tokens, not as a normal end`() {
            val (result, _) = run(conversation(ScriptedModel(text("partial", reason = "length")), FakeBackend()))

            assertEquals("max_tokens", result.stopReason)
        }

        @Test
        fun `reasoning deltas become thought chunks`() {
            val frames = listOf(
                StreamFrame.ReasoningDelta(text = "thinking", index = 0),
                StreamFrame.ReasoningComplete(null, listOf("thinking"), null, null, 0),
                StreamFrame.TextDelta("answer", 1), StreamFrame.TextComplete("answer", 1), end(),
            )
            val (_, updates) = run(conversation(ScriptedModel(frames), FakeBackend()))

            assertEquals("thinking", updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single().text())
        }
    }

    @Nested
    inner class ToolCalls {
        @Test
        fun `the model sees the backend's tools as Koog descriptors`() {
            val model = ScriptedModel(text("ok"))
            run(conversation(model, FakeBackend(listOf(readFile))))

            val descriptor = model.toolsSeen.single().single()
            assertEquals("read_file", descriptor.name)
            assertEquals(listOf("path"), descriptor.requiredParameters.map { it.name })
        }

        @Test
        fun `a tool call runs through the backend and its result goes back to the model`() {
            val backend = FakeBackend(listOf(readFile)) { _, _ -> ToolOutcome("file contents", false) }
            val model = ScriptedModel(toolCall("call-1", "read_file", """{"path":"src/A.java"}"""), text("It is Java."))

            val (result, _) = run(conversation(model, backend), "read A")

            assertEquals("end_turn", result.stopReason)
            val (name, args, toolUseId) = backend.calls.single()
            assertEquals("read_file", name)
            assertEquals("src/A.java", args.get("path").asString)
            assertEquals("call-1", toolUseId)

            val secondRequest = model.prompts[1].messages
            val toolResult = secondRequest.last().parts.filterIsInstance<MessagePart.Tool.Result>().single()
            assertEquals("file contents", toolResult.output)
            assertEquals("call-1", toolResult.id)
            assertFalse(toolResult.isError)
        }

        @Test
        fun `the chat gets a tool call and a completed update with the display name and kind`() {
            val model = ScriptedModel(toolCall("call-1", "read_file", """{"path":"a"}"""), text("done"))
            val (_, updates) = run(conversation(model, FakeBackend(listOf(readFile))))

            val call = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
            assertEquals("call-1", call.toolCallId())
            assertEquals("Read File", call.title())
            assertEquals("read_file", call.acpName())
            assertEquals(SessionUpdate.ToolKind.READ, call.kind())
            val update = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().single()
            assertEquals(SessionUpdate.ToolCallStatus.COMPLETED, update.status())
            assertEquals("ok", update.result())
            assertNull(update.error())
        }

        @Test
        fun `a failing tool is reported as failed and the model is told so`() {
            val backend = FakeBackend(listOf(readFile)) { _, _ -> ToolOutcome("Error: not found", true) }
            val model = ScriptedModel(toolCall("c", "read_file", """{"path":"x"}"""), text("sorry"))

            val (_, updates) = run(conversation(model, backend))

            val update = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().single()
            assertEquals(SessionUpdate.ToolCallStatus.FAILED, update.status())
            assertEquals("Error: not found", update.error())
            val toolResult = model.prompts[1].messages.last().parts.filterIsInstance<MessagePart.Tool.Result>().single()
            assertTrue(toolResult.isError)
        }

        @Test
        fun `malformed arguments are returned to the model without calling the tool`() {
            val backend = FakeBackend(listOf(readFile))
            val model = ScriptedModel(toolCall("c", "read_file", "{not json"), text("retrying"))

            val (result, _) = run(conversation(model, backend))

            assertEquals("end_turn", result.stopReason)
            assertTrue(backend.calls.isEmpty())
            val toolResult = model.prompts[1].messages.last().parts.filterIsInstance<MessagePart.Tool.Result>().single()
            assertTrue(toolResult.isError)
            assertTrue(toolResult.output.contains("not a valid JSON object"))
            // The model is told what it sent, but the history must stay valid JSON: it is re-sent to the provider.
            assertTrue(toolResult.output.contains("{not json"))
            val recordedCall = model.prompts[1].messages.filterIsInstance<Message.Assistant>()
                .single().parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertEquals("{}", recordedCall.args)
        }

        @Test
        fun `valid arguments are recorded exactly as the model sent them`() {
            val model = ScriptedModel(toolCall("c", "read_file", """{"path":"a"}"""), text("ok"))

            run(conversation(model, FakeBackend(listOf(readFile))))

            val recordedCall = model.prompts[1].messages.filterIsInstance<Message.Assistant>()
                .single().parts.filterIsInstance<MessagePart.Tool.Call>().single()
            assertEquals("""{"path":"a"}""", recordedCall.args)
        }

        @Test
        fun `stopping a turn while a tool runs leaves a history the next turn can use`() = runBlocking {
            val entered = java.util.concurrent.CountDownLatch(1)
            val backend = object : ToolBackend {
                override fun instructions() = ""
                override fun listTools() = listOf(readFile)
                override fun call(name: String, arguments: JsonObject, toolUseId: String?): ToolOutcome {
                    entered.countDown()
                    Thread.sleep(60_000) // interrupted by the cancellation
                    return ToolOutcome("never", false)
                }
            }
            val model = ScriptedModel(toolCall("c", "read_file", """{"path":"a"}"""), text("fresh start"))
            val conv = conversation(model, backend)

            val first = async(kotlinx.coroutines.Dispatchers.Default) { conv.runTurn(user("go")) { } }
            withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await() }
            first.cancelAndJoin()

            val (result, _) = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val updates = mutableListOf<SessionUpdate>()
                conv.runTurn(user("again")) { updates += it } to updates
            }

            assertEquals("end_turn", result.stopReason)
            val secondRequest = model.prompts.last().messages
            assertTrue(
                secondRequest.none { m -> m.parts.any { it is MessagePart.Tool.Call } },
                "a tool call without a result must not be left in the history",
            )
            assertEquals(listOf("go", "again"), secondRequest.filter { it.role == Message.Role.User }.map { it.textContent() })
        }

        @Test
        fun `a tool unknown to the backend still reaches it so the handler can refuse it`() {
            val backend = FakeBackend(emptyList()) { name, _ -> ToolOutcome("Error: Tool is disabled: $name", true) }
            val model = ScriptedModel(toolCall("c", "run_command", "{}"), text("ok"))

            run(conversation(model, backend))

            assertEquals("run_command", backend.calls.single().first)
        }

        @Test
        fun `several tool calls in one answer all run and all results go back together`() {
            val frames = listOf(
                StreamFrame.ToolCallComplete("a", "read_file", """{"path":"1"}""", 0),
                StreamFrame.ToolCallComplete("b", "read_file", """{"path":"2"}""", 1),
                end("tool_calls"),
            )
            val backend = FakeBackend(listOf(readFile))
            val model = ScriptedModel(frames, text("done"))

            run(conversation(model, backend))

            assertEquals(listOf("1", "2"), backend.calls.map { it.second.get("path").asString })
            val results = model.prompts[1].messages.last().parts.filterIsInstance<MessagePart.Tool.Result>()
            assertEquals(listOf("a", "b"), results.map { it.id })
        }

        @Test
        fun `a loop that never ends stops at the step limit`() {
            val model = ScriptedModel(toolCall("c", "read_file", """{"path":"a"}"""))

            val (result, _) = run(conversation(model, FakeBackend(listOf(readFile)), maxSteps = 3))

            assertEquals("max_turn_requests", result.stopReason)
            assertEquals(3, model.prompts.size)
        }
    }

    @Nested
    inner class Cancellation {
        @Test
        fun `cancelling during a model response stops the turn`() = runBlocking {
            val slow = ModelStreamer { _, _ ->
                flow {
                    emit(StreamFrame.TextDelta("start", 0))
                    delay(60_000)
                    emit(end())
                }
            }
            val conv = conversation(slow, FakeBackend())
            val updates = mutableListOf<SessionUpdate>()

            val turn: Job = async { conv.runTurn(user("go")) { updates += it } }
            while (updates.isEmpty()) delay(10)
            turn.cancelAndJoin()

            assertTrue(turn.isCancelled)
        }

        @Test
        fun `a cancelled turn surfaces as a cancellation, never as a completed one`() {
            var thrown: Throwable? = null
            runBlocking {
                val slow = ModelStreamer { _, _ -> flow { delay(60_000); emit(end()) } }
                val turn = async { conversationOf(slow).runTurn(user("go")) { } }
                delay(50)
                turn.cancel()
                try {
                    turn.await()
                } catch (e: CancellationException) {
                    thrown = e
                }
            }

            assertTrue(thrown is CancellationException)
        }

        private fun conversationOf(model: ModelStreamer) = conversation(model, FakeBackend())
    }

    @Nested
    inner class PromptFlattening {
        @Test
        fun `text blocks are joined`() {
            assertEquals("a\n\nb", PromptText.flatten(listOf(ContentBlock.Text("a"), ContentBlock.Text("b"))))
        }

        @Test
        fun `an embedded text resource is inlined with its path`() {
            val resource = ContentBlock.Resource(
                ContentBlock.EmbeddedResourceContents("file:///p/A.java", "A.java", "text/x-java", "class A {}", null),
            )

            val flattened = PromptText.flatten(listOf(ContentBlock.Text("look"), resource))

            assertTrue(flattened.contains("""<file path="file:///p/A.java">"""))
            assertTrue(flattened.contains("class A {}"))
        }

        @Test
        fun `a file reference is passed on as a path the model can open`() {
            val flattened = PromptText.flatten(listOf(ContentBlock.ResourceLink("file:///p/B.kt", "B.kt", null)))

            assertEquals("[referenced file: file:///p/B.kt]", flattened)
        }

        @Test
        fun `images are called out instead of silently dropped`() {
            val flattened = PromptText.flatten(listOf(ContentBlock.Image("abc", "image/png")))

            assertTrue(flattened.contains("not supported"))
        }
    }

    @Nested
    inner class ArgumentParsing {
        @Test
        fun `blank means no arguments`() {
            assertTrue(KoogConversation.parseArguments("")!!.entrySet().isEmpty())
            assertTrue(KoogConversation.parseArguments("  ")!!.entrySet().isEmpty())
        }

        @Test
        fun `an object parses`() {
            assertEquals("v", KoogConversation.parseArguments("""{"k":"v"}""")!!.get("k").asString)
        }

        @Test
        fun `non objects and garbage are rejected`() {
            assertNull(KoogConversation.parseArguments("[1,2]"))
            assertNull(KoogConversation.parseArguments("\"text\""))
            assertNull(KoogConversation.parseArguments("{oops"))
        }

        @Test
        fun `finish reasons map to ACP stop reasons`() {
            assertEquals("max_tokens", KoogConversation.stopReasonFor("length"))
            assertEquals("max_tokens", KoogConversation.stopReasonFor("MAX_TOKENS"))
            assertEquals("end_turn", KoogConversation.stopReasonFor("stop"))
            assertEquals("end_turn", KoogConversation.stopReasonFor(null))
        }
    }
}
