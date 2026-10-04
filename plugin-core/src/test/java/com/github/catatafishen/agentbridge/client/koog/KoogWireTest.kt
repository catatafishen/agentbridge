package com.github.catatafishen.agentbridge.client.koog

import ai.koog.prompt.llm.LLMCapability
import com.github.catatafishen.agentbridge.model.ContentBlock
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives a real Koog executor (JDK HTTP transport and all) against a local streaming server, so the
 * wire format the providers see is tested without a network or an IDE: paths, headers, bodies, and how an
 * HTTP error from the provider surfaces.
 */
class KoogWireTest {

    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: JsonObject)

    private val seen = CopyOnWriteArrayList<Seen>()
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun chunk(delta: String, finish: String? = null, usage: String? = null) =
        """{"id":"x","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]${usage?.let { ",\"usage\":$it" } ?: ""}}"""

    private val toolCall = sse(
        chunk("""{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}}]}"""),
        chunk("{}", "tool_calls", """{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110}"""),
    )

    private val answer = sse(
        chunk("""{"role":"assistant","content":"All done."}"""),
        chunk("{}", "stop", """{"prompt_tokens":150,"completion_tokens":20,"total_tokens":170}"""),
    )

    private fun serve(replies: List<Pair<Int, String>>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val body = JsonParser.parseString(ex.requestBody.readBytes().decodeToString()).asJsonObject
            seen += Seen(
                ex.requestMethod, ex.requestURI.path,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }, body,
            )
            val (status, payload) = replies[minOf(seen.size - 1, replies.size - 1)]
            ex.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            ex.sendResponseHeaders(status, 0)
            ex.responseBody.use { it.write(payload.toByteArray()) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val spec = McpToolSpec(
        "read_file", "Read a file",
        JsonParser.parseString("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""").asJsonObject,
    )

    private val backend = object : ToolBackend {
        override fun instructions() = ""
        override fun listTools() = listOf(spec)
        override fun call(name: String, arguments: JsonObject, toolUseId: String?) = ToolOutcome("file body", false)
    }

    private fun conversation(executor: ai.koog.prompt.executor.model.PromptExecutor) = KoogConversation(
        ExecutorStreamer(executor, KoogProviders.toLLModel(KoogModelChoice("test-model", "Test", 128000, 4096))),
        backend,
        systemPrompt = { "SYS" },
    )

    private fun runTurn(conv: KoogConversation) = runBlocking { conv.runTurn(listOf(ContentBlock.Text("go"))) { } }

    @Test
    fun `Copilot requests go to chat completions with the Copilot headers`() {
        val base = serve(listOf(200 to toolCall, 200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "gho_secret", "AgentBridge/test", base)

        val result = runTurn(conversation(executor))

        assertEquals("end_turn", result.stopReason)
        assertEquals(250L, result.inputTokens)
        assertEquals(listOf("/chat/completions", "/chat/completions"), seen.map { it.path })
        val first = seen[0]
        assertEquals("Bearer gho_secret", first.headers["authorization"])
        assertEquals("application/json", first.headers["content-type"])
        assertEquals("AgentBridge/test", first.headers["user-agent"])
        assertEquals("conversation-edits", first.headers["openai-intent"])
        assertEquals("test-model", first.body.get("model").asString)
        assertTrue(first.body.get("stream").asBoolean)
        assertEquals("read_file", first.body.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").get("name").asString)
    }

    @Test
    fun `Copilot chunks that lack object and model, as the real API sends them, are still parsed`() {
        val copilotAnswer = sse(
            """{"choices":[],"created":0,"id":"","prompt_filter_results":[{"content_filter_results":{},"index":0}]}""",
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello from Copilot"}}],"created":1,"id":"c1"}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"created":1,"id":"c1","usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""",
        )
        val base = serve(listOf(200 to copilotAnswer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "t", "ua", base)
        val streamed = StringBuilder()

        val result = runBlocking {
            conversation(executor).runTurn(listOf(ContentBlock.Text("go"))) { streamed.append(it.toString()) }
        }

        assertEquals("end_turn", result.stopReason)
        assertTrue(streamed.contains("Hello from Copilot"), streamed.toString())
    }

    @Test
    fun `a Copilot session supplies the host, and its token is renewed between the requests of one turn`() {
        val base = serve(listOf(200 to toolCall, 200 to answer))
        var exchanges = 0
        var now = 1_000L
        val sessions = CopilotSessions({
            exchanges++
            """{"token":"tid=$exchanges","expires_at":${now + 600},"endpoints":{"api":"$base"}}"""
        }, { now })
        val executor = KoogProviders.createExecutor(
            KoogProviderKind.COPILOT, null, "unused", "AgentBridge/test", "http://unreachable.invalid",
            sessions, "IntelliJ-IDEA/2026.1",
        )
        val conv = conversation(executor)

        runBlocking {
            // The first request carries session 1; time then passes beyond the refresh margin before the follow-up.
            conv.runTurn(listOf(ContentBlock.Text("go"))) { if (it.toString().contains("read_file")) now += 600 }
        }

        assertEquals(listOf("Bearer tid=1", "Bearer tid=2"), seen.map { it.headers["authorization"] })
        assertEquals("vscode-chat", seen[0].headers["copilot-integration-id"])
        assertEquals("IntelliJ-IDEA/2026.1", seen[0].headers["editor-version"])
    }

    @Test
    fun `the initiator header marks the first request as a user turn and the tool follow-up as an agent turn`() {
        val base = serve(listOf(200 to toolCall, 200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "t", "ua", base)

        runTurn(conversation(executor))

        assertEquals(listOf("user", "agent"), seen.map { it.headers["x-initiator"] })
    }

    @Test
    fun `a chosen reasoning effort goes out as reasoning_effort in the request body`() {
        val base = serve(listOf(200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "t", "ua", base)
        val streamer = ExecutorStreamer(
            executor, KoogProviders.toLLModel(KoogModelChoice("m", "M", reasoningEfforts = listOf("low", "high"))),
        )
        val withEffort = ModelStreamer { prompt, tools ->
            streamer.stream(prompt.withParams(KoogProviders.paramsFor("high", listOf("low", "high"))!!), tools)
        }

        runBlocking {
            KoogConversation(withEffort, backend, systemPrompt = { "SYS" }).runTurn(listOf(ContentBlock.Text("go"))) { }
        }

        assertEquals("high", seen.single().body.get("reasoning_effort").asString)
    }

    @Test
    fun `a model that takes an effort declares thinking, or Koog would drop the effort from the request`() {
        val withEffort = KoogProviders.toLLModel(KoogModelChoice("o", "O", reasoningEfforts = listOf("low", "high")))
        val without = KoogProviders.toLLModel(KoogModelChoice("p", "P"))
        val unsendable = KoogProviders.toLLModel(KoogModelChoice("q", "Q", reasoningEfforts = listOf("xhigh")))

        assertTrue(withEffort.supports(LLMCapability.Thinking))
        assertFalse(without.supports(LLMCapability.Thinking))
        assertFalse(unsendable.supports(LLMCapability.Thinking))
    }

    @Test
    fun `an attached image goes out as an image_url data url in the request body`() {
        val base = serve(listOf(200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "t", "ua", base)
        val streamer = ExecutorStreamer(executor, KoogProviders.toLLModel(KoogModelChoice("m", "M")))

        runBlocking {
            KoogConversation(streamer, backend, systemPrompt = { "SYS" })
                .runTurn(listOf(ContentBlock.Text("what is this"), ContentBlock.Image("QUJD", "image/png"))) { }
        }

        val content = seen.single().body.getAsJsonArray("messages").last().asJsonObject.get("content")
        assertTrue(content.isJsonArray, content.toString())
        val image = content.asJsonArray.map { it.asJsonObject }.single { it.get("type").asString == "image_url" }
        assertEquals("data:image/png;base64,QUJD", image.getAsJsonObject("image_url").get("url").asString)
    }

    @Test
    fun `a model the catalog says cannot see images is not given the capability`() {
        assertFalse(KoogProviders.toLLModel(KoogModelChoice("a", "A", supportsVision = false)).supports(LLMCapability.Vision.Image))
        assertTrue(KoogProviders.toLLModel(KoogModelChoice("b", "B", supportsVision = true)).supports(LLMCapability.Vision.Image))
        assertTrue(KoogProviders.toLLModel(KoogModelChoice("c", "C")).supports(LLMCapability.Vision.Image))
    }

    @Test
    fun `without a chosen effort the request carries no reasoning_effort at all`() {
        val base = serve(listOf(200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "t", "ua", base)

        runTurn(conversation(executor))

        assertFalse(seen.single().body.has("reasoning_effort"), seen.single().body.toString())
    }

    @Test
    fun `the tool result goes back to the provider in the second request`() {
        val base = serve(listOf(200 to toolCall, 200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "t", "ua", base)

        runTurn(conversation(executor))

        val roles = seen[1].body.getAsJsonArray("messages").map { it.asJsonObject.get("role").asString }
        assertEquals(listOf("system", "user", "assistant", "tool"), roles)
        assertEquals("file body", seen[1].body.getAsJsonArray("messages").last().asJsonObject.get("content").asString)
    }

    @Test
    fun `an OpenAI compatible base ending in v1 is not given the version twice`() {
        val base = serve(listOf(200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.OPENAI_COMPATIBLE, "$base/v1/", "sk-test", "ua")

        runTurn(conversation(executor))

        assertEquals("/v1/chat/completions", seen.single().path)
        assertEquals("Bearer sk-test", seen.single().headers["authorization"])
        assertEquals("application/json", seen.single().headers["content-type"])
    }

    @Test
    fun `an OpenAI compatible bare host gets the standard path and no Copilot headers`() {
        val base = serve(listOf(200 to answer))
        val executor = KoogProviders.createExecutor(KoogProviderKind.OPENAI_COMPATIBLE, base, "sk-test", "ua")

        runTurn(conversation(executor))

        val request = seen.single()
        assertEquals("/v1/chat/completions", request.path)
        assertNull(request.headers["x-initiator"])
        assertNull(request.headers["openai-intent"])
        assertEquals("ua", request.headers["user-agent"])
    }

    @Test
    fun `an authentication failure from the provider is recognised as one`() {
        val base = serve(listOf(401 to """{"error":{"message":"Bad credentials"}}"""))
        val executor = KoogProviders.createExecutor(KoogProviderKind.COPILOT, null, "expired", "ua", base)

        val failure = assertThrows(Exception::class.java) { runTurn(conversation(executor)) }

        val classified = KoogErrors.classify(failure, "GitHub Copilot")
        assertTrue(classified.isAuthentication, classified.message)
        assertTrue(classified.message.contains("authenticated"))
    }

    @Test
    fun `a server error is reported with its status but is not taken for an authentication failure`() {
        val base = serve(listOf(500 to """{"error":{"message":"upstream exploded"}}"""))
        val executor = KoogProviders.createExecutor(KoogProviderKind.OPENAI_COMPATIBLE, base, "k", "ua")

        val failure = assertThrows(Exception::class.java) { runTurn(conversation(executor)) }

        val classified = KoogErrors.classify(failure, "OpenAI")
        assertFalse(classified.isAuthentication)
        assertTrue(classified.message.contains("500"), classified.message)
    }
}
