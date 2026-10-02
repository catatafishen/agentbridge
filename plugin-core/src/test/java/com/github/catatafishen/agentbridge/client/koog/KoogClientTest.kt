package com.github.catatafishen.agentbridge.client.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest
import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.client.ClientPromptException
import com.github.catatafishen.agentbridge.client.ClientSessionException
import com.github.catatafishen.agentbridge.client.ClientStartException
import com.github.catatafishen.agentbridge.model.ContentBlock
import com.github.catatafishen.agentbridge.model.SessionUpdate
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KoogClientTest {

    // ── fakes ───────────────────────────────────────────────────────────────

    private class FakeConnection(val streamerFor: (KoogModelChoice) -> ModelStreamer) : ProviderConnection {
        var closed = false
        override fun streamer(model: KoogModelChoice) = streamerFor(model)
        override fun close() {
            closed = true
        }
    }

    private class FakeBackend : ToolBackend {
        override fun instructions() = ""
        override fun listTools() = emptyList<McpToolSpec>()
        override fun call(name: String, arguments: JsonObject, toolUseId: String?) = ToolOutcome("ok", false)
    }

    private class FakeEnv : KoogEnvironment {
        override val tools: ToolBackend = FakeBackend()
        var problem: String? = null
        var kind = KoogProviderKind.COPILOT
        var credential: String? = "token"
        var preferred = ""
        var models: () -> List<KoogModelChoice> = { listOf(KoogModelChoice("m1", "Model 1"), KoogModelChoice("m2", "Model 2")) }
        var streamerFor: (KoogModelChoice) -> ModelStreamer = { model -> textStreamer("answer from ${model.id}") }
        val remembered = mutableListOf<String>()
        val connections = mutableListOf<FakeConnection>()

        override fun configurationProblem() = problem
        override fun provider() = kind
        override fun credential(kind: KoogProviderKind) = credential
        override fun preferredModelId() = preferred
        override fun rememberModel(modelId: String) {
            remembered += modelId
        }

        override fun loadModels(kind: KoogProviderKind, credential: String) = models()
        override fun connect(kind: KoogProviderKind, credential: String) =
            FakeConnection { model -> streamerFor(model) }.also { connections += it }

        var systemSuffix = ""
        override fun systemPrompt(cwd: String) = "SYSTEM for $cwd$systemSuffix"

        var previous: List<EntryData> = emptyList()
        var previousLoads = 0
        var restoredNotices = 0

        override fun previousConversation(): List<EntryData> {
            previousLoads++
            return previous
        }

        override fun onHistoryRestored() {
            restoredNotices++
        }
    }

    private companion object {
        fun end(input: Int? = null, output: Int? = null) =
            StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System, null, input, output))

        fun textStreamer(text: String, input: Int? = null, output: Int? = null) = ModelStreamer { _, _ ->
            flowOf(StreamFrame.TextDelta(text, 0), StreamFrame.TextComplete(text, 0), end(input, output))
        }
    }

    private val env = FakeEnv()
    private val client = KoogClient(env)

    @AfterEach
    fun tearDown() = client.close()

    private fun request(sessionId: String, text: String = "hi", modelId: String? = null) =
        PromptRequest(sessionId, listOf<ContentBlock>(ContentBlock.Text(text)), modelId, null)

    private fun startedSession(): String {
        client.start()
        return client.createSession("/project")
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Nested
    inner class Identity {
        @Test
        fun `the agent identifies itself`() {
            assertEquals("koog", client.agentId())
            assertEquals("Built-in Agent (Koog)", client.displayName())
            assertEquals(com.github.catatafishen.agentbridge.client.AbstractClient.ModelDisplayMode.NAME, client.modelDisplayMode())
        }

        @Test
        fun `it is not connected until started`() {
            assertFalse(client.isConnected)
            assertTrue(client.availableModels.isEmpty())
            assertNull(client.currentModelId)
        }
    }

    @Nested
    inner class Starting {
        @Test
        fun `a configuration problem is the start error, verbatim`() {
            env.problem = "Koog is not authenticated with GitHub Copilot. Sign in."

            val e = assertThrows(ClientStartException::class.java) { client.start() }

            assertEquals("Koog is not authenticated with GitHub Copilot. Sign in.", e.message)
            assertFalse(client.isConnected)
        }

        @Test
        fun `missing credentials stop the start with an authentication message`() {
            env.credential = null

            val e = assertThrows(ClientStartException::class.java) { client.start() }

            assertTrue(e.message!!.contains("authenticated"))
        }

        @Test
        fun `a provider authentication failure while listing models is classified`() {
            env.models = { throw KoogHttpClientException("c", 401, "bad credentials") }

            val e = assertThrows(ClientStartException::class.java) { client.start() }

            assertTrue(e.message!!.contains("authenticated"), e.message)
            assertTrue(e.cause is KoogHttpClientException)
        }

        @Test
        fun `a network failure while listing models surfaces its message`() {
            env.models = { throw java.net.ConnectException("Connection refused") }

            val e = assertThrows(ClientStartException::class.java) { client.start() }

            assertTrue(e.message!!.contains("Connection refused"))
        }

        @Test
        fun `an empty model list is an error, not a silent empty agent`() {
            env.models = { emptyList() }

            val e = assertThrows(ClientStartException::class.java) { client.start() }

            assertTrue(e.message!!.contains("No usable models"))
        }

        @Test
        fun `a successful start lists the models and picks the first by default`() {
            client.start()

            assertTrue(client.isConnected)
            assertEquals(setOf("m1", "m2"), client.availableModels.map { it.id }.toSet())
            assertEquals("m1", client.currentModelId)
        }

        @Test
        fun `the remembered model wins when the provider still offers it`() {
            env.preferred = "m2"

            client.start()

            assertEquals("m2", client.currentModelId)
        }

        @Test
        fun `a remembered model the provider no longer offers is ignored`() {
            env.preferred = "gone"

            client.start()

            assertEquals("m1", client.currentModelId)
        }

        @Test
        fun `restarting closes the previous connection`() {
            client.start()
            client.start()

            assertEquals(2, env.connections.size)
            assertTrue(env.connections[0].closed)
            assertFalse(env.connections[1].closed)
        }
    }

    @Nested
    inner class Sessions {
        @Test
        fun `a session cannot be created before the agent is started`() {
            assertThrows(ClientSessionException::class.java) { client.createSession("/project") }
        }

        @Test
        fun `a session gets an id and becomes the active one`() {
            val id = startedSession()

            assertTrue(id.startsWith("koog-"))
            assertEquals(id, client.activeSessionId)
        }

        @Test
        fun `a live session is reused, so the next prompt still has the conversation so far`() {
            val seen = CopyOnWriteArrayList<Prompt>()
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen += prompt; flowOf(StreamFrame.TextComplete("reply", 0), end()) } }
            val first = startedSession()
            client.sendPrompt(request(first, "one")) {}

            // The chat calls createSession before every prompt.
            val second = client.createSession("/project")
            client.sendPrompt(request(second, "two")) {}

            assertEquals(first, second)
            assertEquals(listOf("SYSTEM for /project", "one", "reply", "two"), seen[1].messages.map { it.textContent() })
        }

        @Test
        fun `after the conversation is cleared the next session is a new one with an empty history`() {
            val seen = CopyOnWriteArrayList<Prompt>()
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen += prompt; flowOf(StreamFrame.TextComplete("reply", 0), end()) } }
            val first = startedSession()
            client.sendPrompt(request(first, "one")) {}

            client.clearPersistedSession()
            val second = client.createSession("/project")
            client.sendPrompt(request(second, "two")) {}

            assertNotEquals(first, second)
            assertEquals(listOf("SYSTEM for /project", "two"), seen[1].messages.map { it.textContent() })
        }

        @Test
        fun `a session created after a stop is a new one`() {
            val first = startedSession()
            client.stop()
            client.start()

            assertNotEquals(first, client.createSession("/project"))
        }

        @Test
        fun `dropping the current session makes its id unusable`() {
            val id = startedSession()

            client.dropCurrentSession()

            assertNull(client.activeSessionId)
            assertThrows(ClientPromptException::class.java) { client.sendPrompt(request(id)) {} }
        }

        @Test
        fun `clearing the persisted session forgets every conversation`() {
            val id = startedSession()

            client.clearPersistedSession()

            assertNull(client.activeSessionId)
            assertThrows(ClientPromptException::class.java) { client.sendPrompt(request(id)) {} }
        }

        @Test
        fun `the system prompt is read again for every turn, so edited instructions apply to the next message`() {
            val seen = CopyOnWriteArrayList<Prompt>()
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen += prompt; flowOf(StreamFrame.TextComplete("x", 0), end()) } }
            val id = startedSession()

            client.sendPrompt(request(id, "one")) {}
            env.systemSuffix = " (edited)"
            client.sendPrompt(request(id, "two")) {}

            assertEquals("SYSTEM for /project", seen[0].messages.first().textContent())
            assertEquals("SYSTEM for /project (edited)", seen[1].messages.first().textContent())
            // The conversation itself carries on: only the system message changed.
            assertEquals(listOf("one", "x", "two"), seen[1].messages.drop(1).map { it.textContent() })
        }

        @Test
        fun `the system prompt stays the same for all requests of one turn`() {
            val seen = CopyOnWriteArrayList<Prompt>()
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen += prompt; flowOf(StreamFrame.TextComplete("x", 0), end()) } }
            val id = startedSession()

            client.sendPrompt(request(id, "one")) {}

            assertEquals(1, seen.map { it.messages.first().textContent() }.toSet().size)
        }

        @Test
        fun `the system prompt comes from the environment and reaches the model`() {
            val seen = AtomicReference<Prompt>()
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen.set(prompt); flowOf(StreamFrame.TextComplete("x", 0), end()) } }
            val id = startedSession()

            client.sendPrompt(request(id)) {}

            assertEquals("SYSTEM for /project", seen.get().messages.first().textContent())
        }
    }

    @Nested
    inner class ModelLimits {
        @Test
        fun `a model with limits describes them for the picker`() {
            env.models = { listOf(KoogModelChoice("big", "Big", contextLength = 1_048_576, maxOutputTokens = 65_536)) }
            client.start()

            val model = client.availableModels.single()

            assertEquals("1M tokens context, 66k tokens max output", model.description())
        }

        @Test
        fun `a model without limits has no description rather than an empty one`() {
            env.models = { listOf(KoogModelChoice("plain", "Plain")) }
            client.start()

            assertNull(client.availableModels.single().description())
        }

        @Test
        fun `token counts read at a glance`() {
            assertEquals("128k tokens", KoogClient.formatTokens(128_000))
            assertEquals("200k tokens", KoogClient.formatTokens(200_000))
            assertEquals("1M tokens", KoogClient.formatTokens(1_000_000))
            assertEquals("1M tokens", KoogClient.formatTokens(1_048_576))
            assertEquals("2.5M tokens", KoogClient.formatTokens(2_500_000))
            assertEquals("800 tokens", KoogClient.formatTokens(800))
        }

        @Test
        fun `the selected model's window is what the conversation is trimmed to`() {
            val seen = CopyOnWriteArrayList<Prompt>()
            // A 1,000-token window is about 3,000 characters; trimming starts at 80% of that.
            env.models = { listOf(KoogModelChoice("tiny", "Tiny", contextLength = 1_000)) }
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen += prompt; flowOf(StreamFrame.TextComplete("y".repeat(1_500), 0), end()) } }
            val id = startedSession()

            // Two 1,500-character answers do not fit next to a third prompt, so the oldest exchange has to go.
            val banners = CopyOnWriteArrayList<SessionUpdate.Banner>()
            client.sendPrompt(request(id, "one")) {}
            client.sendPrompt(request(id, "two")) {}
            client.sendPrompt(request(id, "three")) { if (it is SessionUpdate.Banner) banners += it }

            assertEquals(1, banners.size)
            assertEquals(listOf("SYSTEM for /project", "two", "y".repeat(1_500), "three"), seen[2].messages.map { it.textContent() })
        }

        @Test
        fun `a model that does not report a window is never trimmed`() {
            val seen = CopyOnWriteArrayList<Prompt>()
            env.models = { listOf(KoogModelChoice("unknown", "Unknown")) }
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen += prompt; flowOf(StreamFrame.TextComplete("y".repeat(5_000), 0), end()) } }
            val id = startedSession()

            client.sendPrompt(request(id, "one")) {}
            client.sendPrompt(request(id, "two")) {}
            client.sendPrompt(request(id, "three")) {}

            // Nothing was dropped: system prompt, then both earlier exchanges, then the new prompt.
            assertEquals(6, seen[2].messages.size)
            assertEquals("one", seen[2].messages[1].textContent())
        }
    }

    @Nested
    inner class Resuming {
        private val seen = CopyOnWriteArrayList<Prompt>()
        private val earlier = listOf(EntryData.Prompt("earlier question"), EntryData.Text(raw = "earlier answer"))

        @org.junit.jupiter.api.BeforeEach
        fun capturePrompts() {
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> seen += prompt; flowOf(StreamFrame.TextComplete("reply", 0), end()) } }
        }

        private fun texts(index: Int = 0) = seen[index].messages.map { it.textContent() }

        @Test
        fun `a new session starts from the stored conversation`() {
            env.previous = earlier
            val id = startedSession()

            client.sendPrompt(request(id, "next")) {}

            assertEquals(listOf("SYSTEM for /project", "earlier question", "earlier answer", "next"), texts())
            assertEquals(1, env.restoredNotices)
        }

        @Test
        fun `the stored conversation is read once per session, not before every prompt`() {
            env.previous = earlier
            val id = startedSession()

            client.sendPrompt(request(id, "one")) {}
            client.createSession("/project")
            client.sendPrompt(request(id, "two")) {}

            assertEquals(1, env.previousLoads)
            assertEquals(1, env.restoredNotices)
        }

        @Test
        fun `a prompt that is already stored but not yet answered is not sent twice`() {
            env.previous = earlier + EntryData.Prompt("the prompt being sent")
            val id = startedSession()

            client.sendPrompt(request(id, "the prompt being sent")) {}

            assertEquals(
                listOf("SYSTEM for /project", "earlier question", "earlier answer", "the prompt being sent"),
                texts(),
            )
        }

        @Test
        fun `with nothing stored nothing is restored and the summary fallback is left alone`() {
            val id = startedSession()

            client.sendPrompt(request(id, "hi")) {}

            assertEquals(listOf("SYSTEM for /project", "hi"), texts())
            assertEquals(0, env.restoredNotices)
        }

        @Test
        fun `a new conversation is not restored even if the store has not been reset yet`() {
            env.previous = earlier
            client.start()

            client.clearPersistedSession()
            val id = client.createSession("/project")
            client.sendPrompt(request(id, "next")) {}

            assertEquals(listOf("SYSTEM for /project", "next"), texts())
            assertEquals(0, env.previousLoads)
        }

        @Test
        fun `only the session right after a new conversation skips the restore`() {
            env.previous = earlier
            client.start()
            client.clearPersistedSession()
            client.createSession("/project")

            client.stop()
            client.start()
            val id = client.createSession("/project")
            client.sendPrompt(request(id, "next")) {}

            assertEquals(listOf("SYSTEM for /project", "earlier question", "earlier answer", "next"), texts())
        }

        @Test
        fun `a dropped session is not restored into its replacement`() {
            env.previous = earlier
            startedSession()
            val loadsBefore = env.previousLoads

            client.dropCurrentSession()
            client.createSession("/project")

            assertEquals(loadsBefore, env.previousLoads)
        }
    }

    @Nested
    inner class Prompting {
        @Test
        fun `an answer streams to the caller and ends the turn`() {
            val id = startedSession()
            val updates = CopyOnWriteArrayList<SessionUpdate>()

            val response = client.sendPrompt(request(id)) { updates += it }

            assertEquals("end_turn", response.stopReason())
            assertEquals("answer from m1", updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().joinToString("") { it.text() })
            assertNull(response.usage())
        }

        @Test
        fun `token usage is reported with the response`() {
            env.streamerFor = { _ -> textStreamer("x", input = 12, output = 3) }
            val id = startedSession()

            val usage = client.sendPrompt(request(id)) {}.usage()

            assertNotNull(usage)
            assertEquals(12L, usage!!.inputTokens())
            assertEquals(3L, usage.outputTokens())
        }

        @Test
        fun `a prompt for an unknown session is rejected with a clear message`() {
            client.start()

            val e = assertThrows(ClientPromptException::class.java) { client.sendPrompt(request("nope")) {} }

            assertTrue(e.message!!.contains("nope"))
        }

        @Test
        fun `a prompt before the agent is started is rejected`() {
            val id = startedSession()
            client.stop()

            assertThrows(ClientPromptException::class.java) { client.sendPrompt(request(id)) {} }
        }

        @Test
        fun `the model named in the request is used from then on`() {
            val id = startedSession()
            val updates = CopyOnWriteArrayList<SessionUpdate>()

            client.sendPrompt(request(id, modelId = "m2")) { updates += it }

            assertEquals("m2", client.currentModelId)
            assertEquals("answer from m2", updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().joinToString("") { it.text() })
        }

        @Test
        fun `an unknown or blank model in the request is ignored`() {
            val id = startedSession()

            client.sendPrompt(request(id, modelId = "nonsense")) {}
            client.sendPrompt(request(id, modelId = " ")) {}

            assertEquals("m1", client.currentModelId)
        }

        @Test
        fun `a provider authentication failure becomes a classified prompt error`() {
            env.streamerFor = { _ -> ModelStreamer { _, _ -> flow { throw KoogHttpClientException("c", 401, "expired") } } }
            val id = startedSession()

            val e = assertThrows(ClientPromptException::class.java) { client.sendPrompt(request(id)) {} }

            assertTrue(e.message!!.contains("authenticated"), e.message)
            assertTrue(e.message!!.contains("GitHub Copilot"))
        }

        @Test
        fun `another provider failure keeps its detail`() {
            env.streamerFor = { _ -> ModelStreamer { _, _ -> flow { throw IllegalStateException("boom") } } }
            val id = startedSession()

            val e = assertThrows(ClientPromptException::class.java) { client.sendPrompt(request(id)) {} }

            assertTrue(e.message!!.contains("boom"))
        }

        @Test
        fun `history carries over between prompts of the same session`() {
            val prompts = CopyOnWriteArrayList<Prompt>()
            env.streamerFor = { _ -> ModelStreamer { prompt, _ -> prompts += prompt; flowOf(StreamFrame.TextComplete("ok", 0), end()) } }
            val id = startedSession()

            client.sendPrompt(request(id, "first")) {}
            client.sendPrompt(request(id, "second")) {}

            assertEquals(listOf("SYSTEM for /project", "first", "ok", "second"), prompts[1].messages.map { it.textContent() })
        }
    }

    @Nested
    inner class Stopping {
        private fun slowStreaming(entered: CountDownLatch) {
            env.streamerFor = { _ ->
                ModelStreamer { _: Prompt, _: List<ToolDescriptor> ->
                    flow<StreamFrame> {
                        entered.countDown()
                        delay(60_000)
                    }
                }
            }
        }

        @Test
        fun `cancelling a running turn ends it with the cancelled stop reason`() {
            val entered = CountDownLatch(1)
            slowStreaming(entered)
            val id = startedSession()
            val result = AtomicReference<com.github.catatafishen.agentbridge.model.PromptResponse>()
            val worker = Thread { result.set(client.sendPrompt(request(id)) {}) }
            worker.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            client.cancelSession(id)
            worker.join(10_000)

            assertEquals("cancelled", result.get().stopReason())
        }

        @Test
        fun `a cancelled session can take the next prompt`() {
            val entered = CountDownLatch(1)
            slowStreaming(entered)
            val id = startedSession()
            val worker = Thread { client.sendPrompt(request(id)) {} }
            worker.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            client.cancelSession(id)
            worker.join(10_000)
            env.streamerFor = { _ -> textStreamer("again") }

            val response = client.sendPrompt(request(id)) {}

            assertEquals("end_turn", response.stopReason())
        }

        @Test
        fun `cancelling a session with nothing running does nothing`() {
            val id = startedSession()

            client.cancelSession(id)
            client.cancelSession("unknown")

            assertEquals("end_turn", client.sendPrompt(request(id)) {}.stopReason())
        }

        @Test
        fun `stopping disconnects, closes the provider and forgets sessions`() {
            val id = startedSession()

            client.stop()

            assertFalse(client.isConnected)
            assertTrue(env.connections.single().closed)
            assertNull(client.activeSessionId)
            assertThrows(ClientPromptException::class.java) { client.sendPrompt(request(id)) {} }
        }

        @Test
        fun `stopping cancels a turn that is still running`() {
            val entered = CountDownLatch(1)
            slowStreaming(entered)
            val id = startedSession()
            val result = AtomicReference<com.github.catatafishen.agentbridge.model.PromptResponse>()
            val worker = Thread { result.set(client.sendPrompt(request(id)) {}) }
            worker.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            client.stop()
            worker.join(10_000)

            assertEquals("cancelled", result.get().stopReason())
        }
    }

    @Nested
    inner class Models {
        @Test
        fun `choosing a model switches it and remembers the choice`() {
            val id = startedSession()

            client.setModel(id, "m2")

            assertEquals("m2", client.currentModelId)
            assertEquals(listOf("m2"), env.remembered)
        }

        @Test
        fun `an unknown model is ignored and not remembered`() {
            val id = startedSession()

            client.setModel(id, "ghost")

            assertEquals("m1", client.currentModelId)
            assertTrue(env.remembered.isEmpty())
        }

        @Test
        fun `choosing a model before the agent starts does nothing`() {
            client.setModel("any", "m2")

            assertTrue(env.remembered.isEmpty())
        }
    }

    @Nested
    inner class SystemPromptAssembly {
        @Test
        fun `the preamble, project root and guidance are joined in that order`() {
            val text = KoogClient.systemPrompt("PREAMBLE", "/work/p", "  GUIDANCE  ")

            assertEquals("PREAMBLE\n\nProject root: /work/p\n\nGUIDANCE", text)
        }

        @Test
        fun `empty guidance leaves no dangling separator`() {
            assertEquals("PREAMBLE\n\nProject root: /p", KoogClient.systemPrompt("PREAMBLE", "/p", "   "))
        }
    }
}
