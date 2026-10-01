package com.github.catatafishen.agentbridge.client.koog

import ai.koog.http.client.KoogHttpClientException

/**
 * Turns provider failures into messages the user can act on.
 *
 * Authentication failures must contain the word "authenticated" so the plugin's shared auth handling
 * (`AuthCommandBuilder.isAuthenticationError`) recognises them and shows its sign-in banner; see
 * docs/AUTH-HANDLING.md.
 */
object KoogErrors {

    data class Classified(val message: String, val isAuthentication: Boolean)

    private const val MAX_BODY_CHARS = 400

    @JvmStatic
    fun classify(error: Throwable, providerLabel: String): Classified {
        val http = generateSequence(error) { it.cause }.filterIsInstance<KoogHttpClientException>().firstOrNull()
        if (http == null) {
            val detail = generateSequence(error) { it.cause }.mapNotNull { it.message }.firstOrNull { it.isNotBlank() }
            return Classified("Koog request failed: ${detail ?: error.javaClass.simpleName}", false)
        }
        return classifyHttp(http.statusCode, http.errorBody, providerLabel)
    }

    @JvmStatic
    fun classifyHttp(status: Int?, body: String?, providerLabel: String): Classified {
        val snippet = body?.trim()?.take(MAX_BODY_CHARS).orEmpty()
        return when (status) {
            401, 403 -> Classified(
                "Koog is not authenticated with $providerLabel (HTTP $status). " +
                    "Sign in again under Settings → Tools → AgentBridge → Agents → Koog." +
                    (if (snippet.isNotEmpty()) " Server said: $snippet" else ""),
                true,
            )

            429 -> Classified(
                "$providerLabel is rate limiting requests (HTTP 429). Wait a moment and retry." +
                    (if (snippet.isNotEmpty()) " Server said: $snippet" else ""),
                false,
            )

            else -> Classified(
                "Koog request to $providerLabel failed" + (status?.let { " (HTTP $it)" } ?: "") +
                    (if (snippet.isNotEmpty()) ": $snippet" else ""),
                false,
            )
        }
    }
}
