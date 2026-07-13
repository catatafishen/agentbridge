package com.github.catatafishen.agentbridge.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Minimal HTTP client for the plugin's MCP server (see
 * plugin-core .../services/McpHttpServer.java), used by the integration bench to drive
 * tools against a real running IDE.
 *
 * Contract (STREAMABLE_HTTP transport):
 *   GET    http://127.0.0.1:{port}/health  → {"status":"ok","version":...}
 *   POST   http://127.0.0.1:{port}/mcp     → initialize, then tools/call with Mcp-Session-Id
 *   DELETE http://127.0.0.1:{port}/mcp     → release the transport session
 */
class McpClient(private val port: Int, private val host: String = "127.0.0.1") : AutoCloseable {

    private companion object {
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
        const val PROTOCOL_VERSION = "2025-11-25"
        const val ACCEPT = "application/json, text/event-stream"
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(10.seconds.toJavaDuration())
        .build()

    private val baseUrl = "http://$host:$port"
    private var sessionId: String? = null

    /** Polls /health until it reports status=ok, or fails after [timeout]. Returns the plugin version. */
    fun awaitHealthy(timeout: kotlin.time.Duration): String {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        var lastError: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val resp = http.send(
                    HttpRequest.newBuilder(URI.create("$baseUrl/health"))
                        .timeout(5.seconds.toJavaDuration())
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                if (resp.statusCode() == 200) {
                    val json = JsonParser.parseString(resp.body()).asJsonObject
                    if (json.get("status")?.asString == "ok") {
                        return json.get("version")?.asString ?: "?"
                    }
                    lastError = "status=${json.get("status")?.asString}"
                } else {
                    lastError = "HTTP ${resp.statusCode()}"
                }
            } catch (e: Exception) {
                lastError = e.message
            }
            Thread.sleep(2000)
        }
        throw AssertionError("MCP server at $baseUrl did not become healthy within $timeout (last: $lastError)")
    }

    /** Initializes the Streamable HTTP transport and retains its server-issued session ID. */
    fun initialize(timeout: Duration = Duration.ofSeconds(30)) {
        check(sessionId == null) { "MCP transport session is already initialized" }

        val params = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "agentbridge-ide-integration-tests")
                addProperty("version", "1.0")
            })
        }
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", 1)
            addProperty("method", "initialize")
            add("params", params)
        }

        val resp = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/mcp"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(resp.statusCode() == 200) { "initialize → HTTP ${resp.statusCode()}: ${resp.body()}" }

        val json = JsonParser.parseString(resp.body()).asJsonObject
        check(!json.has("error") && json.get("result")?.isJsonObject == true) {
            "initialize → invalid JSON-RPC response: ${resp.body()}"
        }
        val establishedSessionId = resp.headers().firstValue(SESSION_HEADER).orElseThrow {
            IllegalStateException("initialize response did not include $SESSION_HEADER")
        }
        sessionId = establishedSessionId

        val initialized = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", "notifications/initialized")
        }
        val initializedResponse = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/mcp"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .header(SESSION_HEADER, establishedSessionId)
                .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(initialized.toString()))
                .build(),
            HttpResponse.BodyHandlers.discarding()
        )
        check(initializedResponse.statusCode() == 202) {
            "notifications/initialized → HTTP ${initializedResponse.statusCode()}"
        }
    }

    /**
     * Calls a tool via JSON-RPC tools/call and returns the concatenated text content.
     * Throws on HTTP/transport errors only. JSON-RPC errors are returned as plain strings
     * so the test assertion message includes the actual MCP error instead of a stack trace.
     */
    fun callTool(name: String, arguments: Map<String, Any>, timeout: Duration = Duration.ofSeconds(60)): String {
        val currentSessionId = checkNotNull(sessionId) {
            "MCP transport session is not initialized; call initialize() first"
        }
        val args = JsonObject()
        for ((k, v) in arguments) {
            when (v) {
                is Number -> args.addProperty(k, v)
                is Boolean -> args.addProperty(k, v)
                else -> args.addProperty(k, v.toString())
            }
        }
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", args)
        }
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", 1)
            addProperty("method", "tools/call")
            add("params", params)
        }

        val resp = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/mcp"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .header(SESSION_HEADER, currentSessionId)
                .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(resp.statusCode() == 200) { "tools/call $name → HTTP ${resp.statusCode()}: ${resp.body()}" }

        val json = JsonParser.parseString(resp.body()).asJsonObject
        if (json.has("error")) {
            return "Error: tools/call $name → JSON-RPC error: ${json.get("error")}"
        }
        val result = json.getAsJsonObject("result")
            ?: return "Error: tools/call $name → no result in response: ${resp.body()}"

        val sb = StringBuilder()
        result.getAsJsonArray("content")?.forEach { item ->
            val obj = item.asJsonObject
            if (obj.get("type")?.asString == "text") {
                sb.append(obj.get("text")?.asString.orEmpty())
            }
        }
        return sb.toString()
    }

    /** Ends the transport session so server-owned resources are released after each IDE run. */
    override fun close() {
        val currentSessionId = sessionId ?: return

        val resp = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header(SESSION_HEADER, currentSessionId)
                .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.discarding()
        )
        check(resp.statusCode() == 204) {
            "DELETE /mcp → HTTP ${resp.statusCode()}"
        }
        sessionId = null
    }
}
