package com.github.catatafishen.agentbridge.client.koog

import ai.koog.http.client.KoogHttpClient
import com.github.catatafishen.agentbridge.services.McpProtocolHandler
import com.github.catatafishen.agentbridge.services.ToolDefinition
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import kotlin.reflect.KClass

class KoogHttpAndBackendTest {

    // ── header injecting HTTP client ────────────────────────────────────────

    private class RecordingClient : KoogHttpClient {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override val clientName = "recording"

        override suspend fun <R : Any> get(
            path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>,
        ): R {
            calls += "get" to headers
            error("not used")
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any, R : Any> post(
            path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
            parameters: Map<String, String>, headers: Map<String, String>,
        ): R {
            calls += "post" to headers
            return "posted" as R
        }

        override fun <T : Any, R : Any, O : Any> sse(
            path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>, headers: Map<String, String>,
        ): Flow<O> {
            calls += "sse" to headers
            return emptyFlow()
        }

        override fun <T : Any> lines(
            path: String, requestBody: T, requestBodyType: KClass<T>,
            parameters: Map<String, String>, headers: Map<String, String>,
        ): Flow<String> {
            calls += "lines" to headers
            return emptyFlow()
        }

        override fun close() = Unit
    }

    private class RecordingFactory : KoogHttpClient.Factory {
        val client = RecordingClient()
        var createdWithHeaders: Map<String, String> = emptyMap()
        override fun create(
            clientName: String, baseUrl: String, headers: Map<String, String>, queryParameters: Map<String, String>,
            requestTimeoutMillis: Long, connectTimeoutMillis: Long, socketTimeoutMillis: Long, json: Json,
        ): KoogHttpClient {
            createdWithHeaders = headers
            return client
        }
    }

    private fun create(factory: KoogHttpClient.Factory, defaults: Map<String, String> = emptyMap()) =
        factory.create("test", "http://x", defaults, emptyMap(), 1L, 1L, 1L, Json)

    @Nested
    inner class HeaderInjection {
        @Test
        fun `fixed headers join the defaults and win over a same-named default`() {
            val delegate = RecordingFactory()
            val factory = HeaderInjectingHttpClientFactory(delegate, mapOf("Content-Type" to "application/json", "X-A" to "1"))

            create(factory, defaults = mapOf("Content-Type" to "text/plain", "Authorization" to "Bearer t"))

            assertEquals(
                mapOf("Content-Type" to "application/json", "Authorization" to "Bearer t", "X-A" to "1"),
                delegate.createdWithHeaders,
            )
        }

        @Test
        fun `a post carries the per request headers computed from its body`() {
            val delegate = RecordingFactory()
            val client = create(
                HeaderInjectingHttpClientFactory(delegate, emptyMap(), CopilotHeaders::perRequest),
            )

            val result: String = runBlocking {
                client.post("/chat", """{"messages":[{"role":"tool"}]}""", String::class, String::class, emptyMap(), mapOf("X-Own" to "keep"))
            }

            assertEquals("posted", result)
            val (kind, headers) = delegate.client.calls.single()
            assertEquals("post", kind)
            assertEquals("agent", headers["x-initiator"])
            assertEquals("application/json", headers["Content-Type"])
            assertEquals("keep", headers["X-Own"])
        }

        @Test
        fun `a request header the caller set is replaced by the per request one of the same name`() {
            val delegate = RecordingFactory()
            val client = create(HeaderInjectingHttpClientFactory(delegate, emptyMap(), JsonContentType::perRequest))

            runBlocking {
                client.post<String, String>("/p", "{}", String::class, String::class, emptyMap(), mapOf("Content-Type" to "text/plain"))
            }

            assertEquals("application/json", delegate.client.calls.single().second["Content-Type"])
        }

        @Test
        fun `streaming requests carry the per request headers too`() {
            val delegate = RecordingFactory()
            val client = create(HeaderInjectingHttpClientFactory(delegate, emptyMap(), CopilotHeaders::perRequest))
            val body = """{"messages":[{"role":"user"}]}"""

            client.sse("/chat", body, String::class, { true }, { it }, { it }, emptyMap(), emptyMap())
            client.lines("/chat", body, String::class, emptyMap(), emptyMap())

            val (sse, lines) = delegate.client.calls
            assertEquals("sse", sse.first)
            assertEquals("user", sse.second["x-initiator"])
            assertEquals("lines", lines.first)
            assertEquals("user", lines.second["x-initiator"])
        }

        @Test
        fun `a get passes straight through and keeps the delegate's name`() {
            val delegate = RecordingFactory()
            val client = create(HeaderInjectingHttpClientFactory(delegate, emptyMap(), CopilotHeaders::perRequest))

            assertEquals("recording", client.clientName)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { client.get("/models", String::class, emptyMap(), mapOf("X" to "y")) }
            }
            assertEquals(mapOf("X" to "y"), delegate.client.calls.single().second)
        }
    }

    // ── in-process MCP backend ──────────────────────────────────────────────

    @Nested
    inner class McpBackend {
        private val handler: McpProtocolHandler = Mockito.mock(McpProtocolHandler::class.java)
        private val registry: ToolRegistry = Mockito.mock(ToolRegistry::class.java)
        private val project: Project = Mockito.mock(Project::class.java).also {
            Mockito.`when`(it.getService(ToolRegistry::class.java)).thenReturn(registry)
        }
        private val backend = McpToolBackend(project, handler)

        private fun reply(json: String) {
            Mockito.`when`(handler.handleMessage(Mockito.anyString())).thenReturn(json)
        }

        private fun lastRequest(): JsonObject {
            val captor = ArgumentCaptor.forClass(String::class.java)
            Mockito.verify(handler, Mockito.atLeastOnce()).handleMessage(captor.capture())
            return JsonParser.parseString(captor.allValues.last()).asJsonObject
        }

        @Test
        fun `initialize identifies this client and returns the handler's instructions`() {
            reply("""{"jsonrpc":"2.0","id":1,"result":{"instructions":"Be careful."}}""")

            val text = backend.instructions()

            assertEquals("Be careful.", text)
            val request = lastRequest()
            assertEquals("initialize", request.get("method").asString)
            assertEquals("AgentBridge Koog", request.getAsJsonObject("params").getAsJsonObject("clientInfo").get("name").asString)
        }

        @Test
        fun `listing tools adds the display name and kind from the registry`() {
            reply("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"read_file","description":"Read","inputSchema":{"type":"object"}},{"name":"mystery","description":"?"}]}}""")
            val definition = Mockito.mock(ToolDefinition::class.java)
            Mockito.`when`(definition.displayName()).thenReturn("Read File")
            Mockito.`when`(definition.kind()).thenReturn(ToolDefinition.Kind.READ)
            Mockito.`when`(registry.findById("read_file")).thenReturn(definition)

            val tools = backend.listTools()

            assertEquals("tools/list", lastRequest().get("method").asString)
            assertEquals(listOf("read_file", "mystery"), tools.map { it.name })
            assertEquals("Read File", tools[0].displayName)
            assertEquals("read", tools[0].kind)
            // A tool the registry does not know keeps its defaults.
            assertEquals("mystery", tools[1].displayName)
            assertEquals("other", tools[1].kind)
        }

        @Test
        fun `a call sends the name, arguments and the tool use id for chip correlation`() {
            reply("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"done"}],"isError":false}}""")
            val args = JsonParser.parseString("""{"path":"a.txt"}""").asJsonObject

            val outcome = backend.call("read_file", args, "call-9")

            assertEquals(ToolOutcome("done", false), outcome)
            val params = lastRequest().getAsJsonObject("params")
            assertEquals("read_file", params.get("name").asString)
            assertEquals("a.txt", params.getAsJsonObject("arguments").get("path").asString)
            assertEquals("call-9", params.getAsJsonObject("_meta").get("claudecode/toolUseId").asString)
        }

        @Test
        fun `a call without a tool use id sends no meta`() {
            reply("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"x"}]}}""")

            backend.call("git_status", JsonObject())

            assertFalse(lastRequest().getAsJsonObject("params").has("_meta"))
        }

        @Test
        fun `a refused call comes back as an error outcome the model can read`() {
            reply("""{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"Tool is disabled: run_command"}}""")

            val outcome = backend.call("run_command", JsonObject())

            assertTrue(outcome.isError)
            assertTrue(outcome.text.contains("Tool is disabled"))
        }

        @Test
        fun `a handler that answers nothing is an error, not a hang or a silent success`() {
            Mockito.`when`(handler.handleMessage(Mockito.anyString())).thenReturn(null)

            val e = assertThrows(IllegalStateException::class.java) { backend.call("read_file", JsonObject()) }

            assertTrue(e.message!!.contains("no response"))
        }

        @Test
        fun `request ids are unique so replies cannot be confused`() {
            reply("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""")

            backend.listTools()
            val first = lastRequest().get("id").asLong
            backend.listTools()
            val second = lastRequest().get("id").asLong

            assertTrue(second > first)
        }
    }
}
