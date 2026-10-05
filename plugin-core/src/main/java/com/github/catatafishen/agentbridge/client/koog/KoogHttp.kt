package com.github.catatafishen.agentbridge.client.koog

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * Wraps a [KoogHttpClient.Factory] to (a) add fixed default headers to every client it creates and
 * (b) add headers that depend on the request body.
 *
 * Needed because Koog's JDK transport labels a pre-serialised body `text/plain` (real endpoints expect
 * `application/json`) and because the Copilot API wants an `x-initiator` header that changes per request.
 * Koog documents that per-request headers replace same-named defaults, so these always win.
 */
class HeaderInjectingHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val fixedHeaders: Map<String, String>,
    private val perRequestHeaders: (requestBody: Any?) -> Map<String, String> = { emptyMap() },
    private val normalizeChunk: (String) -> String = { it },
) : KoogHttpClient.Factory {

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = HeaderInjectingHttpClient(
        delegate.create(
            clientName, baseUrl, headers + fixedHeaders, queryParameters,
            requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json,
        ),
        perRequestHeaders,
        normalizeChunk,
    )
}

internal class HeaderInjectingHttpClient(
    private val delegate: KoogHttpClient,
    private val perRequestHeaders: (Any?) -> Map<String, String>,
    private val normalizeChunk: (String) -> String = { it },
) : KoogHttpClient by delegate {

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.post(
        path, requestBody, requestBodyType, responseType, parameters, headers + perRequestHeaders(requestBody),
    )

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> {
        val allHeaders = headers + perRequestHeaders(requestBody)
        return delegate.sse(
            path, requestBody, requestBodyType, dataFilter, { data -> decodeStreamingResponse(normalizeChunk(data)) },
            processStreamingChunk, parameters, allHeaders,
        ).catch { e ->
            if (e !is KoogHttpClientException || !e.errorBody.isNullOrBlank() || (e.statusCode ?: 0) !in 400..499) {
                throw e
            }
            // Koog's SSE transport drops the response body of a failed request, which leaves the user with a bare
            // "HTTP 400". A 4xx is a validation failure rejected before any generation, so replaying the request once
            // as a plain POST is cheap and returns the server's explanation.
            throw withServerBody(e, path, requestBody, requestBodyType, parameters, allHeaders)
        }
    }

    private suspend fun <T : Any> withServerBody(
        original: KoogHttpClientException,
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): KoogHttpClientException {
        val replayed = try {
            delegate.post(path, requestBody, requestBodyType, String::class, parameters, headers)
            return original
        } catch (e: KoogHttpClientException) {
            e
        } catch (_: Exception) {
            return original
        }
        if (replayed.errorBody.isNullOrBlank()) return original
        return KoogHttpClientException(
            original.clientName, original.statusCode, replayed.errorBody, original.message, original,
        )
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = delegate.lines(
        path, requestBody, requestBodyType, parameters, headers + perRequestHeaders(requestBody),
    )
}

/** Headers the Copilot API expects from a client. */
object CopilotHeaders {
    const val API_BASE = "https://api.githubcopilot.com"
    const val CHAT_COMPLETIONS_PATH = "chat/completions"

    /** Version header sent when listing models. */
    const val API_VERSION = "2026-06-01"

    /**
     * Copilot's routing contract, not a statement about which program is calling: without an integration id the
     * API serves a reduced model catalog, and the session token is only valid for the integration it was
     * exchanged under, so the same value goes on the exchange, `/models` and `/chat/completions`.
     */
    const val INTEGRATION_ID = "vscode-chat"

    const val DEFAULT_EDITOR_VERSION = "JetBrains-IDE/unknown"

    /**
     * Identity headers Copilot expects on the session exchange, the model list and chat requests.
     * [userAgent] is e.g. `AgentBridge/1.2.3`; [editorVersion] the host IDE, e.g. `IntelliJ-IDEA/2026.1`.
     */
    @JvmStatic
    @JvmOverloads
    fun identity(
        userAgent: String,
        editorVersion: String = DEFAULT_EDITOR_VERSION,
        pluginVersion: String = userAgent,
    ): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Editor-Version" to editorVersion,
        "Editor-Plugin-Version" to pluginVersion,
        "Copilot-Integration-Id" to INTEGRATION_ID,
    )

    /** Fixed headers for chat requests. */
    @JvmStatic
    @JvmOverloads
    fun fixed(
        userAgent: String,
        editorVersion: String = DEFAULT_EDITOR_VERSION,
        pluginVersion: String = userAgent,
    ): Map<String, String> = identity(userAgent, editorVersion, pluginVersion) + mapOf(
        "Content-Type" to JsonContentType.MEDIA_TYPE,
        "Openai-Intent" to "conversation-edits",
    )

    /**
     * `agent` when this request continues an automated tool loop (the last message is not from the user),
     * `user` when it starts from a person's message. Copilot uses this to tell the two apart. A body that
     * cannot be read counts as a user turn.
     */
    @JvmStatic
    fun initiator(requestBody: Any?): String {
        val text = requestBody as? String ?: return "user"
        val role = try {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
                ?.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("role")?.takeIf { it.isJsonPrimitive }?.asString
        } catch (_: JsonParseException) {
            null
        }
        return if (role != null && role != "user") "agent" else "user"
    }

    @JvmStatic
    fun perRequest(requestBody: Any?): Map<String, String> =
        JsonContentType.perRequest(requestBody) + ("x-initiator" to initiator(requestBody))
}

/**
 * Copilot's streaming chunks are not complete OpenAI chunks: the first one (content-filter results) has no `object`
 * or `model`, and others omit `id` or `created`. Koog's parser requires all of them and fails the whole response
 * ("Field 'object' is required"). Only these envelope fields are filled in, with neutral values; `choices`, the
 * deltas and `usage` are never touched, so the content the model sent is exactly what is parsed.
 */
object CopilotStreamChunks {
    private val DEFAULTS = listOf(
        "id" to { JsonPrimitive("") },
        "object" to { JsonPrimitive("chat.completion.chunk") },
        "created" to { JsonPrimitive(0) },
        "model" to { JsonPrimitive("") },
        "choices" to { JsonArray() },
    )

    @JvmStatic
    fun normalize(json: String): String {
        val chunk = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: JsonParseException) {
            null
        } ?: return json
        var changed = false
        for ((key, default) in DEFAULTS) {
            if (!chunk.has(key) || chunk.get(key).isJsonNull) {
                chunk.add(key, default())
                changed = true
            }
        }
        if (ReasoningFields.alias(chunk)) changed = true
        return if (changed) chunk.toString() else json
    }
}

/**
 * Koog's chat-completions streaming reads reasoning only from `delta.reasoning_content`; a delta that carries it under
 * another name produces no reasoning frame, so the model's thinking never reaches the chat. Providers disagree on the
 * name, so the known alternatives are copied to `reasoning_content` when it is absent. Nothing is removed or
 * rewritten, and a delta that already has `reasoning_content` is left alone.
 */
object ReasoningFields {
    /** Names providers use for streamed reasoning text, in order of preference. */
    private val ALIASES = listOf("reasoning_text", "reasoning")

    private const val CANONICAL = "reasoning_content"

    /** Normalizer for providers that need no envelope repair. Returns the chunk unchanged when nothing applies. */
    @JvmStatic
    fun normalize(json: String): String {
        // Cheap pre-check: nearly every chunk carries no reasoning, and parsing each one would be wasted work.
        if (!json.contains("\"reasoning")) return json
        val chunk = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: JsonParseException) {
            null
        } ?: return json
        return if (alias(chunk)) chunk.toString() else json
    }

    /** Adds [CANONICAL] to each choice's delta where only an alias is present. True if anything was added. */
    @JvmStatic
    fun alias(chunk: JsonObject): Boolean {
        var changed = false
        val choices = chunk.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        for (choice in choices) {
            val delta = choice.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (delta.get(CANONICAL)?.takeIf { it.isJsonPrimitive } != null) continue
            val text = ALIASES.firstNotNullOfOrNull { name ->
                delta.get(name)?.takeIf { it.isJsonPrimitive && it.asString.isNotEmpty() }
            } ?: continue
            delta.add(CANONICAL, text)
            changed = true
        }
        return changed
    }
}

/**
 * Koog's JDK transport infers `Content-Type: text/plain` for a pre-serialised (String) body, and the
 * inferred value beats a default header. Real endpoints expect JSON, so it is set on every request,
 * where it replaces the inferred one.
 */
object JsonContentType {
    const val MEDIA_TYPE = "application/json"

    @JvmStatic
    fun perRequest(@Suppress("UNUSED_PARAMETER") requestBody: Any?): Map<String, String> =
        mapOf("Content-Type" to MEDIA_TYPE)
}
