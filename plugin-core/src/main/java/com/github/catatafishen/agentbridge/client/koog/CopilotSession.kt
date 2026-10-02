package com.github.catatafishen.agentbridge.client.koog

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * A short-lived Copilot API session. The sign-in yields a long-lived GitHub token; Copilot only serves the full
 * model catalog (and accepts preview models) to the session token it exchanges for it, and tells the client which
 * host to call (`api.individual.…`, `api.business.…`) in the same response.
 */
data class CopilotSession(val token: String, val expiresAtSeconds: Long, val apiBase: String)

/**
 * Hands out a valid [CopilotSession], exchanging again shortly before the current one expires.
 *
 * @param fetch performs the exchange and returns the response body; throws [CopilotAuth.AuthException] when GitHub
 *   refuses it
 */
class CopilotSessions(
    private val fetch: () -> String,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    @Volatile
    private var cached: CopilotSession? = null

    fun current(): CopilotSession {
        cached?.takeIf(::isFresh)?.let { return it }
        synchronized(this) {
            cached?.takeIf(::isFresh)?.let { return it }
            return parse(fetch()).also { cached = it }
        }
    }

    private fun isFresh(session: CopilotSession) = session.expiresAtSeconds - nowSeconds() > REFRESH_MARGIN_SECONDS

    companion object {
        /** Exchange again this long before expiry so a request already in flight never carries a dead token. */
        const val REFRESH_MARGIN_SECONDS = 120L

        @JvmStatic
        fun parse(json: String): CopilotSession {
            val o = try {
                JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: JsonParseException) {
                null
            } ?: throw CopilotAuth.AuthException("GitHub returned an unreadable Copilot session response")
            fun missing(what: String): Nothing =
                throw CopilotAuth.AuthException("GitHub's Copilot session response has no $what")
            val token = o.primitive("token")?.asString?.takeIf { it.isNotBlank() } ?: missing("token")
            val expires = o.primitive("expires_at")?.takeIf { it.isNumber }?.asLong ?: missing("expiry")
            val api = o.get("endpoints")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.primitive("api")?.asString?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: missing("API endpoint")
            return CopilotSession(token, expires, api)
        }

        private fun JsonObject.primitive(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    }
}
