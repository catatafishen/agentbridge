package com.github.catatafishen.agentbridge.client.koog

import com.github.catatafishen.agentbridge.services.McpProtocolHandler
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicLong

/** The result of one tool call: the text the model sees, and whether it is an error. */
data class ToolOutcome(val text: String, val isError: Boolean)

/**
 * Where the Koog agent gets its tools and system instructions from, and where tool calls go.
 *
 * Kept as an interface so the conversation loop can be tested without an IDE.
 */
interface ToolBackend {
    /** Instructions every AgentBridge agent receives (user-editable startup instructions plus memory). */
    fun instructions(): String

    /** The tools currently enabled for this project. */
    fun listTools(): List<McpToolSpec>

    /**
     * Runs a tool; blocks until it finishes. Failures are returned as [ToolOutcome.isError], not thrown.
     *
     * @param toolUseId the model's id for this call; lets the UI match the call to its chip exactly
     */
    fun call(name: String, arguments: JsonObject, toolUseId: String? = null): ToolOutcome
}

/**
 * Talks to AgentBridge's own [McpProtocolHandler] in-process, as JSON-RPC, without the HTTP server.
 *
 * Going through the handler (rather than calling tools directly) keeps every behaviour that external
 * agents get: the user's enabled-tool filter, Allow/Ask/Deny permissions, pause/resume, hooks, popup
 * gating, live tool-call tracking, timeouts and result truncation. A tool the user disabled or denied
 * is refused here for the same reason it is refused for Copilot or Claude.
 */
class McpToolBackend(
    private val project: Project,
    private val handler: McpProtocolHandler = McpProtocolHandler(project),
) : ToolBackend {

    private val nextId = AtomicLong(1)

    override fun instructions(): String {
        val params = JsonObject().apply {
            addProperty("protocolVersion", "2025-06-18")
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", CLIENT_NAME)
                addProperty("version", "1")
            })
        }
        return parseInstructions(rpc("initialize", params))
    }

    override fun listTools(): List<McpToolSpec> {
        val registry = ToolRegistry.getInstance(project)
        return parseTools(rpc("tools/list", JsonObject())).map { spec ->
            val definition = registry.findById(spec.name) ?: return@map spec
            spec.copy(displayName = definition.displayName(), kind = definition.kind().value())
        }
    }

    override fun call(name: String, arguments: JsonObject, toolUseId: String?): ToolOutcome {
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
            if (toolUseId != null) {
                // The handler reads this key to correlate the call with its chat chip.
                add("_meta", JsonObject().apply { addProperty("claudecode/toolUseId", toolUseId) })
            }
        }
        return parseToolResult(rpc("tools/call", params))
    }

    private fun rpc(method: String, params: JsonObject): String {
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", nextId.getAndIncrement())
            addProperty("method", method)
            add("params", params)
        }
        return handler.handleMessage(request.toString())
            ?: error("AgentBridge MCP handler returned no response for $method")
    }

    companion object {
        const val CLIENT_NAME = "AgentBridge Koog"

        /** Extracts `result.instructions` from an `initialize` response. */
        @JvmStatic
        fun parseInstructions(responseJson: String): String {
            val result = resultOf(responseJson)
            return result.get("instructions")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        }

        /** Extracts the tool list from a `tools/list` response. */
        @JvmStatic
        fun parseTools(responseJson: String): List<McpToolSpec> {
            val tools: JsonArray = resultOf(responseJson).get("tools")
                ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return tools.mapNotNull { element ->
                val tool = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = tool.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                McpToolSpec(
                    name = name,
                    description = tool.get("description")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    inputSchema = tool.get("inputSchema")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject(),
                )
            }
        }

        /**
         * Reads a `tools/call` response. A JSON-RPC level error (unknown or disabled tool, bad
         * arguments) and a tool-level `isError` both become an error outcome the model can react to.
         */
        @JvmStatic
        fun parseToolResult(responseJson: String): ToolOutcome {
            val response = JsonParser.parseString(responseJson).asJsonObject
            response.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { error ->
                val message = error.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "Unknown error"
                return ToolOutcome("Error: $message", true)
            }
            val result = response.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return ToolOutcome("Error: tool call returned no result", true)
            val text = result.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { c -> c.isJsonObject }?.asJsonObject }
                ?.filter { it.get("type")?.asString == "text" }
                ?.joinToString("\n") { it.get("text")?.takeIf { t -> t.isJsonPrimitive }?.asString.orEmpty() }
                .orEmpty()
            val isError = result.get("isError")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            return ToolOutcome(text, isError)
        }

        private fun resultOf(responseJson: String): JsonObject {
            val response = JsonParser.parseString(responseJson).asJsonObject
            response.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { error ->
                val message = error.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "Unknown error"
                throw IllegalStateException("AgentBridge MCP handler error: $message")
            }
            return response.get("result")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        }
    }
}
