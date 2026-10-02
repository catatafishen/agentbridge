package com.github.catatafishen.agentbridge.client.koog

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** HTTP seam for the device flow: POST a JSON body, get the JSON response body back. Throws on transport errors. */
fun interface JsonPoster {
    fun post(url: String, body: JsonObject): String
}

/**
 * GitHub OAuth device flow (RFC 8628) for using a GitHub Copilot subscription as a model provider.
 *
 * This follows what Hermes and OpenCode do with a Copilot subscription: GitHub's device-code endpoints, scope
 * `read:user`, then an exchange of the resulting token for a short-lived Copilot session (see [CopilotSession]).
 * Copilot gives a reduced model catalog (older GPT models only) to tokens issued to an arbitrary OAuth app and
 * the full one to the Copilot GitHub App's tokens, so the bundled client id is that app's. GitHub has announced an
 * official partnership for OpenCode specifically; use by other clients is common but is not covered by a published
 * policy, and may be restricted.
 *
 * The client id is fixed on purpose and cannot be changed by the user: a token from any other OAuth app is only
 * given the reduced catalog, so a custom id would just make the agent worse.
 */
object CopilotAuth {
    /**
     * The GitHub App that GitHub's own Copilot clients (VS Code, the Copilot CLI), Hermes and OpenCode sign in with.
     * A client id is public, not a secret.
     */
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"

    const val SCOPE = "read:user"

    /** Exchanges the sign-in token (`Authorization: token …`) for a [CopilotSession]. */
    const val SESSION_URL = "https://api.github.com/copilot_internal/v2/token"
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    /** Added to every poll so we never hit the server slightly early because of clock or timer drift. */
    private const val POLL_SAFETY_MARGIN_MS = 3_000L

    /** RFC 8628 section 3.5: on `slow_down` the interval grows by at least five seconds. */
    private const val SLOW_DOWN_INCREMENT_S = 5

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    sealed interface PollResult {
        data class Token(val accessToken: String) : PollResult
        data object Pending : PollResult
        data class SlowDown(val newIntervalSeconds: Int?) : PollResult
        data class Failed(val message: String) : PollResult
    }

    class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @JvmStatic
    fun deviceCodeRequest(clientId: String): JsonObject = JsonObject().apply {
        addProperty("client_id", clientId)
        addProperty("scope", SCOPE)
    }

    @JvmStatic
    fun pollRequest(clientId: String, deviceCode: String): JsonObject = JsonObject().apply {
        addProperty("client_id", clientId)
        addProperty("device_code", deviceCode)
        addProperty("grant_type", DEVICE_GRANT_TYPE)
    }

    @JvmStatic
    fun parseDeviceCode(json: String): DeviceCode {
        val o = parseObject(json) ?: throw AuthException("GitHub returned an unreadable device-code response")
        o.string("error")?.let { error ->
            throw AuthException("GitHub rejected the sign-in request: ${o.string("error_description") ?: error}")
        }
        fun required(key: String) =
            o.string(key) ?: throw AuthException("GitHub's device-code response is missing '$key'")
        return DeviceCode(
            deviceCode = required("device_code"),
            userCode = required("user_code"),
            verificationUri = required("verification_uri"),
            intervalSeconds = o.int("interval") ?: DEFAULT_INTERVAL_S,
            expiresInSeconds = o.int("expires_in") ?: DEFAULT_EXPIRES_S,
        )
    }

    @JvmStatic
    fun parsePoll(json: String): PollResult {
        val o = parseObject(json) ?: return PollResult.Failed("GitHub returned an unreadable token response")
        o.string("access_token")?.takeIf { it.isNotBlank() }?.let { return PollResult.Token(it) }
        return when (val error = o.string("error")) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown(o.int("interval"))
            "expired_token" -> PollResult.Failed("The sign-in code expired before it was entered. Start again.")
            "access_denied" -> PollResult.Failed("The sign-in was cancelled on GitHub.")
            null -> PollResult.Failed("GitHub's token response had neither a token nor an error")
            else -> PollResult.Failed("GitHub sign-in failed: ${o.string("error_description") ?: error}")
        }
    }

    /** Requests a device code. The caller shows [DeviceCode.userCode] and opens [DeviceCode.verificationUri]. */
    @JvmStatic
    fun start(clientId: String, http: JsonPoster): DeviceCode =
        parseDeviceCode(http.post(DEVICE_CODE_URL, deviceCodeRequest(clientId)))

    /**
     * Polls until the user finishes (or fails) the sign-in and returns the access token.
     *
     * @param sleeper blocks for the given milliseconds; throws [InterruptedException] to abort
     * @param isCancelled checked between polls so the user can abandon the sign-in
     */
    @JvmStatic
    fun awaitToken(
        clientId: String,
        code: DeviceCode,
        http: JsonPoster,
        sleeper: (Long) -> Unit,
        isCancelled: () -> Boolean = { false },
        nowMillis: () -> Long = System::currentTimeMillis,
    ): String {
        var interval = code.intervalSeconds.coerceAtLeast(1)
        val deadline = nowMillis() + code.expiresInSeconds * 1000L
        while (true) {
            if (isCancelled()) throw AuthException("Sign-in cancelled")
            if (nowMillis() > deadline) throw AuthException("The sign-in code expired. Start again.")
            sleeper(interval * 1000L + POLL_SAFETY_MARGIN_MS)
            when (val result = parsePoll(http.post(ACCESS_TOKEN_URL, pollRequest(clientId, code.deviceCode)))) {
                is PollResult.Token -> return result.accessToken
                PollResult.Pending -> Unit
                is PollResult.SlowDown -> interval = result.newIntervalSeconds ?: (interval + SLOW_DOWN_INCREMENT_S)
                is PollResult.Failed -> throw AuthException(result.message)
            }
        }
    }

    private const val DEFAULT_INTERVAL_S = 5
    private const val DEFAULT_EXPIRES_S = 900

    private fun parseObject(json: String): JsonObject? = try {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: JsonParseException) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}
