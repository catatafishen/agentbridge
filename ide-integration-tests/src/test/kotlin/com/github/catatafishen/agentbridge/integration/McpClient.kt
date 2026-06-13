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
 *   GET  http://127.0.0.1:{port}/health  → {"status":"ok","version":...}
 *   POST http://127.0.0.1:{port}/mcp     → JSON-RPC 2.0 tools/call
 */
class McpClient(private val port: Int, private val host: String = "127.0.0.1") {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(10.seconds.toJavaDuration())
        .build()

    private val baseUrl = "http://$host:$port"

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

    /**
     * Calls a tool via JSON-RPC tools/call and returns the concatenated text content.
     * Throws if the server returns a JSON-RPC error or a tool execution error.
     */
    fun callTool(name: String, arguments: Map<String, Any>): String {
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
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(resp.statusCode() == 200) { "tools/call $name → HTTP ${resp.statusCode()}: ${resp.body()}" }

        val json = JsonParser.parseString(resp.body()).asJsonObject
        if (json.has("error")) {
            throw AssertionError("tools/call $name → JSON-RPC error: ${json.get("error")}")
        }
        val result = json.getAsJsonObject("result")
            ?: throw AssertionError("tools/call $name → no result in response: ${resp.body()}")

        val sb = StringBuilder()
        result.getAsJsonArray("content")?.forEach { item ->
            val obj = item.asJsonObject
            if (obj.get("type")?.asString == "text") {
                sb.append(obj.get("text")?.asString.orEmpty())
            }
        }
        return sb.toString()
    }
}
