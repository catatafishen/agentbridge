package com.github.catatafishen.agentbridge.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.EnvironmentUtil
import java.util.concurrent.TimeUnit

/**
 * Data snapshot of a user's Copilot premium-request billing quota.
 */
data class BillingSnapshot(
    val entitlement: Int,
    val remaining: Int,
    val unlimited: Boolean,
    val overagePermitted: Boolean,
    val resetDate: String,
) {
    /** Number of premium requests used in this billing cycle. */
    val used: Int get() = entitlement - remaining
}

/**
 * Data-fetching layer for Copilot premium-request billing information.
 *
 * Fetches data from GitHub's undocumented `copilot_internal/user` API endpoint
 * via the `gh` CLI. The API returns quota snapshots including `premium_interactions`
 * with entitlement, remaining count, and reset date.
 *
 * **Prerequisites:**
 * - The `gh` CLI must be installed and authenticated (`gh auth login`).
 *
 * Data is fetched on demand (not polled automatically).
 */
internal class CopilotBillingClient {

    companion object {
        private val LOG = Logger.getInstance(CopilotBillingClient::class.java)
        private const val API_ENDPOINT = "/copilot_internal/user"
        private const val GH_CLI_TIMEOUT_SECONDS = 10L
        private const val AUTH_TIMEOUT_SECONDS = 5L
    }

    fun findGhCli(): String? {
        return com.github.catatafishen.agentbridge.settings.GhBinaryDetector().resolve("gh")
    }

    /**
     * Returns `true` when the `gh` CLI is authenticated with a valid session.
     */
    fun isGhAuthenticated(ghCli: String): Boolean {
        val pb = ProcessBuilder(ghCli, "auth", "status")
        pb.redirectErrorStream(true)
        // Use the user's actual shell environment to ensure PATH is correct
        pb.environment().putAll(EnvironmentUtil.getEnvironmentMap())
        val process = pb.start()
        val authOutput = process.inputStream.bufferedReader().readText()
        process.waitFor(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return process.exitValue() == 0
            && "not logged in" !in authOutput.lowercase()
            && "gh auth login" !in authOutput
    }

    /**
     * Calls `gh api /copilot_internal/user`, parses the JSON response, and
     * returns a [BillingSnapshot] extracted from `premium_interactions`, or
     * `null` when the request fails or the response cannot be parsed.
     */
    fun fetchBillingData(): BillingSnapshot? {
        val ghCli = findGhCli() ?: run {
            LOG.warn("gh CLI not found — cannot fetch billing data")
            return null
        }

        if (!isGhAuthenticated(ghCli)) {
            LOG.warn("gh CLI is not authenticated — cannot fetch billing data")
            return null
        }

        val pb = ProcessBuilder(ghCli, "api", API_ENDPOINT)
        pb.redirectErrorStream(true)
        pb.environment().putAll(EnvironmentUtil.getEnvironmentMap())
        val apiProcess = pb.start()
        val json = apiProcess.inputStream.bufferedReader().readText()
        val exited = apiProcess.waitFor(GH_CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val exitCode = if (exited) apiProcess.exitValue() else -1

        if (exitCode != 0) {
            LOG.warn("gh api $API_ENDPOINT exited with code $exitCode, output: ${json.take(500)}")
            return null
        }

        LOG.info("gh api $API_ENDPOINT succeeded (${json.length} chars)")

        return try {
            val obj = Gson().fromJson(json, JsonObject::class.java)
            val snapshots = obj.getAsJsonObject("quota_snapshots") ?: run {
                LOG.warn("No 'quota_snapshots' in response. Keys: ${obj.keySet()}")
                return null
            }
            val premium = snapshots.getAsJsonObject("premium_interactions") ?: run {
                LOG.warn("No 'premium_interactions' in quota_snapshots. Keys: ${snapshots.keySet()}")
                return null
            }

            BillingSnapshot(
                entitlement = premium["entitlement"]?.asInt ?: 0,
                remaining = premium["remaining"]?.asInt ?: 0,
                unlimited = premium["unlimited"]?.asBoolean ?: false,
                overagePermitted = premium["overage_permitted"]?.asBoolean ?: false,
                resetDate = obj["quota_reset_date"]?.asString ?: "",
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse billing response", e)
            null
        }
    }
}
