package com.github.catatafishen.agentbridge.client.koog

import ai.koog.http.client.KoogHttpClient
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
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
    )
}

internal class HeaderInjectingHttpClient(
    private val delegate: KoogHttpClient,
    private val perRequestHeaders: (Any?) -> Map<String, String>,
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
    ): Flow<O> = delegate.sse(
        path, requestBody, requestBodyType, dataFilter, decodeStreamingResponse, processStreamingChunk,
        parameters, headers + perRequestHeaders(requestBody),
    )

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

    /** Fixed headers for chat requests. [userAgent] identifies this client, e.g. `AgentBridge/1.2.3`. */
    @JvmStatic
    fun fixed(userAgent: String): Map<String, String> = mapOf(
        "Content-Type" to JsonContentType.MEDIA_TYPE,
        "User-Agent" to userAgent,
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
