package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.client.koog.CopilotAuth
import com.github.catatafishen.agentbridge.client.koog.KoogNetwork
import com.github.catatafishen.agentbridge.client.koog.KoogProviderKind
import com.github.catatafishen.agentbridge.client.koog.KoogSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.UIUtil
import java.io.IOException
import javax.swing.SwingUtilities

/**
 * Registers the Koog settings page. Where the agent can run it is the real page; everywhere else (Kotlin runtime
 * too old, or the Koog classes fail to load) it is [KoogUnavailableConfigurable], which says why. The Koog classes
 * are only touched after the gate passes, and any failure while building the real page falls back to the
 * placeholder instead of silently dropping the page.
 */
class KoogConfigurableProvider(private val project: Project) : ConfigurableProvider() {
    override fun createConfigurable(): Configurable {
        if (!com.github.catatafishen.agentbridge.client.koog.KoogSupport.isSupported()) {
            return KoogUnavailableConfigurable()
        }
        return try {
            KoogClientConfigurable(project)
        } catch (e: Throwable) {
            LOG.warn("The Koog settings page could not be created", e)
            KoogUnavailableConfigurable(e)
        }
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(KoogConfigurableProvider::class.java)
    }
}

class KoogClientConfigurable(private val project: Project) :
    BoundConfigurable("Built-in Agent (Koog)"),
    SearchableConfigurable {

    private val copilotStatus = JBLabel()
    private val apiKeyField = JBPasswordField()
    private var apiKeyLoaded = false

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row {
            text(
                "AgentBridge's own agent. It talks to the model provider directly and runs every tool call " +
                    "through AgentBridge's tool layer, so the model only ever sees AgentBridge's instructions and " +
                    "tools. Tool permissions and availability are configured under <b>MCP -> Tools</b>. " +
                    "Changes apply the next time the agent starts.",
                MAX_LINE_LENGTH_WORD_WRAP
            ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
        }
        row("Provider:") {
            comboBox(KoogProviderKind.entries, textListCellRenderer { it?.label })
                .bindItem(
                    { KoogSettings.provider },
                    // bindItem hands the setter a nullable; keep the previous choice if it is ever null.
                    { KoogSettings.provider = it ?: KoogSettings.provider },
                )
        }

        group("GitHub Copilot subscription") {
            row("Status:") { cell(copilotStatus) }
            row {
                button("Sign in with GitHub…") { signIn() }
                button("Sign out") { signOut() }
            }
            row {
                text(
                    "Signs in with the GitHub app that GitHub's own Copilot clients use, so your subscription's " +
                        "full model list is available. Using a Copilot subscription outside GitHub's own clients " +
                        "follows the same device sign-in other agent tools use. GitHub has announced official " +
                        "support for OpenCode only; check that it is acceptable under your plan and employer's " +
                        "policy.",
                    MAX_LINE_LENGTH_WORD_WRAP
                ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
            }
        }

        group("OpenAI-compatible API") {
            row("Base URL:") {
                textField()
                    .align(AlignX.FILL)
                    .applyToComponent { emptyText.text = "https://api.openai.com" }
                    .comment("Any OpenAI-style endpoint (OpenRouter, Ollama, a gateway). A trailing /v1 is fine.")
                    .bindText(
                        { KoogSettings.openAiBaseUrl },
                        { KoogSettings.openAiBaseUrl = it },
                    )
            }
            row("API key:") {
                cell(apiKeyField)
                    .align(AlignX.FILL)
                    .comment("Stored in the IDE's password safe, never in a file.")
            }
            row("Model:") {
                textField()
                    .align(AlignX.FILL)
                    .applyToComponent { emptyText.text = "e.g. gpt-4.1" }
                    .comment("With Copilot the models are listed from your subscription; here you name the model.")
                    .bindText(
                        { KoogSettings.modelId },
                        { KoogSettings.modelId = it },
                    )
            }
            row("Context window:") {
                intTextField(0..10_000_000)
                    .comment(
                        "Tokens the model accepts. Used to trim long conversations before the endpoint rejects " +
                            "them; 0 means unknown, and nothing is trimmed.",
                    )
                    .bindIntText(
                        { KoogSettings.openAiContextWindow },
                        { KoogSettings.openAiContextWindow = it },
                    )
            }
        }
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshCopilotStatus()
        apiKeyLoaded = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = KoogSettings.openAiApiKey.orEmpty()
            SwingUtilities.invokeLater {
                apiKeyField.text = key
                cachedApiKey = key
                apiKeyLoaded = true
            }
        }
    }

    override fun isModified(): Boolean =
        super<BoundConfigurable>.isModified() || (apiKeyLoaded && String(apiKeyField.password) != cachedApiKey)

    override fun apply() {
        super<BoundConfigurable>.apply()
        if (apiKeyLoaded) {
            val key = String(apiKeyField.password)
            cachedApiKey = key
            ApplicationManager.getApplication().executeOnPooledThread { KoogSettings.openAiApiKey = key }
        }
    }

    @Volatile
    private var cachedApiKey: String = ""

    private fun refreshCopilotStatus() {
        copilotStatus.text = "Checking…"
        copilotStatus.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val signedIn = !KoogSettings.copilotToken.isNullOrBlank()
            SwingUtilities.invokeLater {
                if (signedIn) {
                    copilotStatus.text = "✓ Signed in to GitHub Copilot"
                    copilotStatus.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    copilotStatus.text = "Not signed in"
                    copilotStatus.foreground = UIUtil.getLabelForeground()
                }
            }
        }
    }

    private fun signOut() {
        ApplicationManager.getApplication().executeOnPooledThread {
            KoogSettings.copilotToken = null
            refreshCopilotStatus()
        }
    }

    private fun signIn() {
        ProgressManager.getInstance().run(RequestCodeTask(project, CopilotAuth.CLIENT_ID) { refreshCopilotStatus() })
    }

    /**
     * Asks GitHub for a device code (a quick call, so a small modal progress), then hands over to
     * [CopilotSignInDialog], which shows the code and waits for the user to authorize it.
     */
    private class RequestCodeTask(project: Project, private val clientId: String, private val onDone: () -> Unit) :
        Task.Modal(project, SIGN_IN_TITLE, true) {

        private val http = KoogNetwork.jsonPoster("AgentBridge/" + BuildInfo.getVersion())
        private var code: CopilotAuth.DeviceCode? = null
        private var error: String? = null

        override fun run(indicator: ProgressIndicator) {
            indicator.text = "Requesting a sign-in code from GitHub…"
            try {
                code = CopilotAuth.start(clientId, http)
            } catch (e: CopilotAuth.AuthException) {
                error = e.message
            } catch (e: IOException) {
                error = "Could not reach GitHub: ${e.message}"
            }
        }

        override fun onFinished() {
            val deviceCode = code
            if (deviceCode == null) {
                error?.let { Messages.showErrorDialog(project, it, SIGN_IN_TITLE) }
                onDone()
                return
            }
            val dialog = CopilotSignInDialog(
                project, deviceCode,
                waitForToken = { isCancelled ->
                    CopilotAuth.awaitToken(
                        clientId, deviceCode, http,
                        sleeper = { millis -> sleepUnlessCancelled(millis, isCancelled) },
                        isCancelled = isCancelled,
                    )
                },
                onToken = { KoogSettings.copilotToken = it },
            )
            dialog.show()
            onDone()
        }

        private fun sleepUnlessCancelled(millis: Long, isCancelled: () -> Boolean) {
            var left = millis
            while (left > 0 && !isCancelled()) {
                val slice = minOf(left, 250L)
                Thread.sleep(slice)
                left -= slice
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.client.koog"
        private const val SIGN_IN_TITLE = "Sign in to GitHub Copilot"
    }
}
