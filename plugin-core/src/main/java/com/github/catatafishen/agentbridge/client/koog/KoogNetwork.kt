package com.github.catatafishen.agentbridge.client.koog

import com.google.gson.JsonObject
import com.intellij.util.io.HttpRequests

/**
 * The few direct HTTP calls the agent makes outside Koog (sign-in and the Copilot model list). They use
 * IntelliJ's [HttpRequests] so the IDE's proxy and certificate settings apply.
 */
object KoogNetwork {
    private const val TIMEOUT_MS = 30_000

    @JvmStatic
    fun jsonPoster(userAgent: String): JsonPoster = JsonPoster { url, body -> postJson(url, body, userAgent) }

    private fun postJson(url: String, body: JsonObject, userAgent: String): String =
        HttpRequests.post(url, JsonContentType.MEDIA_TYPE)
            .accept(JsonContentType.MEDIA_TYPE)
            .userAgent(userAgent)
            .connectTimeout(TIMEOUT_MS)
            .readTimeout(TIMEOUT_MS)
            .connect { request ->
                request.write(body.toString())
                request.readString()
            }

    /** Fetches the models the signed-in account can use. Throws on HTTP errors; callers classify them. */
    @JvmStatic
    fun fetchCopilotModels(token: String, userAgent: String): List<CopilotModel> {
        val json = HttpRequests.request(CopilotHeaders.API_BASE + "/models")
            .accept(JsonContentType.MEDIA_TYPE)
            .userAgent(userAgent)
            .connectTimeout(TIMEOUT_MS)
            .readTimeout(TIMEOUT_MS)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-GitHub-Api-Version", CopilotHeaders.API_VERSION)
            }
            .readString()
        return CopilotModels.parse(json)
    }
}
