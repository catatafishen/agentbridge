package com.github.catatafishen.agentbridge.client.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Throwaway spike (branch feat/koog-harness-spike, see docs/KOOG-HARNESS-INVESTIGATION.md).
 *
 * Runs a Koog agent against a fake OpenAI-compatible server inside the IDE process and reports:
 *  1. whether Koog loads and runs under the plugin classloader (go/no-go for embedding it), and
 *     which jar each shared library (kotlinx.serialization, Ktor, coroutines, Jackson) is loaded from;
 *  2. the exact HTTP request bodies and headers Koog sends, to check for hidden prompt text.
 */
object KoogSpike {

    const val SYSTEM_PROMPT = "SPIKE-SYSTEM-PROMPT: you only have the echo tool."

    /** A tool taking free-form JSON args, the shape needed to wrap AgentBridge's JSON-schema tools. */
    private class EchoTool : ToolBase<JSONObject, String>(
        argsType = typeToken<JSONObject>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(
            name = "echo",
            description = "Echo the text back.",
            requiredParameters = listOf(ToolParameterDescriptor("text", "Text to echo", ToolParameterType.String)),
        ),
    ) {
        override suspend fun execute(args: JSONObject, metadata: ToolCallMetadata): String = "echoed: $args"
        override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): JSONObject = rawArgs
        override fun encodeArgs(args: JSONObject, serializer: JSONSerializer): JSONObject = args
        override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result
    }

    fun run(): String {
        val report = StringBuilder()
        report.appendLine("== class origins")
        for (name in listOf(
            "kotlinx.serialization.json.Json",
            "kotlinx.coroutines.CoroutineScope",
            "io.ktor.client.HttpClient",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "ai.koog.agents.core.agent.AIAgent",
            "io.github.oshai.kotlinlogging.KotlinLogging",
        )) {
            report.appendLine("$name -> ${describe(name)}")
        }

        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val headers = ex.requestHeaders.entries.joinToString { "${it.key}=${it.value}" }
            requests += "${ex.requestMethod} ${ex.requestURI}\nheaders: $headers\nbody: $body"
            val reply = if (requests.size == 1) TOOL_CALL_REPLY else FINAL_REPLY
            val bytes = reply.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val settings = OpenAIClientSettings(baseUrl = "http://127.0.0.1:${server.address.port}")
            val client = OpenAILLMClient("spike-key", settings, JavaKoogHttpClient.Factory())
            val agent = AIAgent(
                promptExecutor = MultiLLMPromptExecutor(client),
                llmModel = OpenAIModels.Chat.GPT4o,
                systemPrompt = SYSTEM_PROMPT,
                toolRegistry = ToolRegistry { tool(EchoTool()) },
            )
            val result = runBlocking { agent.run("say hi via the echo tool") }
            report.appendLine("== agent result\n$result")
        } catch (t: Throwable) {
            report.appendLine("== agent FAILED\n${t.stackTraceToString()}")
        } finally {
            server.stop(0)
        }
        report.appendLine("== captured requests (${requests.size})")
        requests.forEachIndexed { i, r -> report.appendLine("-- request ${i + 1}\n$r") }
        return report.toString()
    }

    private fun describe(className: String): String = try {
        val cls = Class.forName(className, false, KoogSpike::class.java.classLoader)
        val loc = cls.getResource("/" + className.replace('.', '/') + ".class")
        "$loc [loader=${cls.classLoader?.javaClass?.simpleName}]"
    } catch (t: Throwable) {
        "NOT LOADABLE: $t"
    }

    private const val TOOL_CALL_REPLY = """{"id":"c1","object":"chat.completion","created":1,"model":"gpt-4o",
"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
"tool_calls":[{"id":"call_1","type":"function","function":{"name":"echo","arguments":"{\"text\":\"hi\"}"}}]}}],
"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    private const val FINAL_REPLY = """{"id":"c2","object":"chat.completion","created":2,"model":"gpt-4o",
"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}],
"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
}
