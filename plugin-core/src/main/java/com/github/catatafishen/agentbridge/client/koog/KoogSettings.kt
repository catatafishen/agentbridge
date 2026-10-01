package com.github.catatafishen.agentbridge.client.koog

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent

/**
 * Settings for the built-in Koog agent.
 *
 * Plain choices live in [PropertiesComponent]. Secrets (the Copilot OAuth token and an API key) go to the
 * IDE's [PasswordSafe]. These are credentials this plugin created itself, so reading them back is not the
 * "inspect another tool's credential store" that docs/AUTH-HANDLING.md rules out.
 *
 * Credential calls can block (OS keychain), so call them from a background thread, not the EDT.
 */
object KoogSettings {
    private const val PREFIX = "agentbridge.koog."
    private const val KEY_PROVIDER = PREFIX + "provider"
    private const val KEY_BASE_URL = PREFIX + "openaiBaseUrl"
    private const val KEY_MODEL = PREFIX + "model"
    private const val KEY_CLIENT_ID = PREFIX + "copilotClientId"
    private const val CLIENT_ID_RESOURCE = "/koog/copilot-oauth-client-id.txt"

    private val props get() = PropertiesComponent.getInstance()

    var provider: KoogProviderKind
        get() = KoogProviderKind.fromId(props.getValue(KEY_PROVIDER))
        set(value) = props.setValue(KEY_PROVIDER, value.id)

    var openAiBaseUrl: String
        get() = props.getValue(KEY_BASE_URL, "")
        set(value) = props.setValue(KEY_BASE_URL, value.trim(), "")

    var modelId: String
        get() = props.getValue(KEY_MODEL, "")
        set(value) = props.setValue(KEY_MODEL, value.trim(), "")

    /** A client id the user typed in, which wins over the one bundled with the plugin. */
    var copilotClientIdOverride: String
        get() = props.getValue(KEY_CLIENT_ID, "")
        set(value) = props.setValue(KEY_CLIENT_ID, value.trim(), "")

    /** The OAuth app client id used for Copilot sign-in, or blank when this build has none configured. */
    fun copilotClientId(): String =
        copilotClientIdOverride.ifBlank { parseClientId(bundledClientIdFile()) }

    var copilotToken: String?
        get() = secret("copilot.oauth-token")
        set(value) = setSecret("copilot.oauth-token", value)

    var openAiApiKey: String?
        get() = secret("openai.api-key")
        set(value) = setSecret("openai.api-key", value)

    /**
     * What stops the agent from starting, or null when it is ready. Phrased as the next step to take.
     */
    fun configurationProblem(): String? = problem(
        provider, copilotToken, openAiApiKey, modelId, copilotClientId(),
    )

    /** Pure so it can be tested: the rules for "is this configuration usable". */
    @JvmStatic
    fun problem(
        provider: KoogProviderKind,
        copilotToken: String?,
        openAiApiKey: String?,
        modelId: String,
        copilotClientId: String,
    ): String? = when (provider) {
        KoogProviderKind.COPILOT -> when {
            copilotToken.isNullOrBlank() && copilotClientId.isBlank() ->
                "Koog is not authenticated: this build has no GitHub OAuth client id for Copilot sign-in. " +
                    "Set one under Settings → Tools → AgentBridge → Agents → Koog (see the setup guide), " +
                    "or use an OpenAI-compatible API key instead."

            copilotToken.isNullOrBlank() ->
                "Koog is not authenticated with GitHub Copilot. " +
                    "Sign in under Settings → Tools → AgentBridge → Agents → Koog."

            else -> null
        }

        KoogProviderKind.OPENAI_COMPATIBLE -> when {
            openAiApiKey.isNullOrBlank() ->
                "Koog is not authenticated: no API key is set. " +
                    "Enter one under Settings → Tools → AgentBridge → Agents → Koog."

            modelId.isBlank() ->
                "Koog has no model selected. Enter a model id under Settings → Tools → AgentBridge → Agents → Koog."

            else -> null
        }
    }

    /** Parses the bundled client-id file: `#` comments and blank lines are ignored, the first value wins. */
    @JvmStatic
    fun parseClientId(fileContent: String?): String =
        fileContent?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()

    private fun bundledClientIdFile(): String? =
        KoogSettings::class.java.getResourceAsStream(CLIENT_ID_RESOURCE)?.use { it.readBytes().decodeToString() }

    private fun attributes(key: String) =
        CredentialAttributes(generateServiceName("AgentBridge Koog", key))

    private fun secret(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))

    private fun setSecret(key: String, value: String?) {
        val credentials = value?.takeIf { it.isNotBlank() }?.let { Credentials(key, it) }
        PasswordSafe.instance.set(attributes(key), credentials)
    }
}
