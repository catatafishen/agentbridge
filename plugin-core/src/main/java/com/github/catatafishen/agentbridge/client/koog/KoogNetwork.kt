package com.github.catatafishen.agentbridge.client.koog

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests

/**
 * The few direct HTTP calls the agent makes outside Koog (sign-in and the Copilot model list). They use
 * IntelliJ's [HttpRequests] so the IDE's proxy and certificate settings apply.
 */
object KoogNetwork {
    private const val TIMEOUT_MS = 30_000
    private val LOG = Logger.getInstance(KoogNetwork::class.java)

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

    /**
     * Exchanges the GitHub sign-in token for a Copilot session (see [CopilotSession]); returns the response body.
     * [identity] must be the same headers later sent on chat requests: the session is scoped to them.
     */
    @JvmStatic
    fun fetchCopilotSession(githubToken: String, identity: Map<String, String>): String = try {
        HttpRequests.request(CopilotAuth.SESSION_URL)
            .accept(JsonContentType.MEDIA_TYPE)
            .connectTimeout(TIMEOUT_MS)
            .readTimeout(TIMEOUT_MS)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "token $githubToken")
                identity.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            }
            .readString()
    } catch (e: HttpRequests.HttpStatusException) {
        throw CopilotAuth.AuthException(sessionRefusal(e.statusCode), e)
    }

    /** Pure so it can be tested. "authenticated" is what the shared authentication handling looks for. */
    @JvmStatic
    fun sessionRefusal(status: Int): String = when (status) {
        401, 403, 404 ->
            "Koog is not authenticated with GitHub Copilot: GitHub refused the Copilot session (HTTP $status). " +
                "Sign out and sign in again under ${KoogSettings.SETTINGS_PATH}, and check that the " +
                "account has an active Copilot subscription."

        else -> "GitHub could not start a Copilot session (HTTP $status). Try again in a moment."
    }

    /** Fetches the models the signed-in account can use. Throws on HTTP errors; callers classify them. */
    @JvmStatic
    fun fetchCopilotModels(sessionToken: String, apiBase: String, identity: Map<String, String>): List<CopilotModel> {
        val json = HttpRequests.request("$apiBase/models")
            .accept(JsonContentType.MEDIA_TYPE)
            .connectTimeout(TIMEOUT_MS)
            .readTimeout(TIMEOUT_MS)
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $sessionToken")
                connection.setRequestProperty("X-GitHub-Api-Version", CopilotHeaders.API_VERSION)
                identity.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            }
            .readString()
        val catalog = CopilotModels.describe(json)
        LOG.info("Copilot /models returned ${catalog.size} entries:\n" + catalog.joinToString("\n"))
        return CopilotModels.parse(json)
    }
}
