package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.client.koog.CopilotAuth
import com.github.catatafishen.agentbridge.client.koog.CopilotSetupGuide
import com.github.catatafishen.agentbridge.client.koog.KoogNetwork
import com.github.catatafishen.agentbridge.client.koog.KoogProviderKind
import com.github.catatafishen.agentbridge.client.koog.KoogSettings
import com.intellij.ide.BrowserUtil
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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.UIUtil
import java.awt.datatransfer.StringSelection
import java.io.IOException
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Registers the Koog settings page only where the agent can run (see `KoogSupport`). Doing it through a
 * provider keeps the page, and the Koog classes behind it, out of IDEs that cannot load them.
 */
class KoogConfigurableProvider(private val project: Project) : ConfigurableProvider() {
    override fun canCreateConfigurable(): Boolean =
        com.github.catatafishen.agentbridge.client.koog.KoogSupport.isSupported()

    override fun createConfigurable(): Configurable? =
        if (canCreateConfigurable()) KoogClientConfigurable(project) else null
}

class KoogClientConfigurable(private val project: Project) :
    BoundConfigurable("Built-in Agent (Koog)"),
    SearchableConfigurable {

    private val copilotStatus = JBLabel()
    private val apiKeyField = JBPasswordField()
    private var clientIdField: JTextField? = null
    private var apiKeyLoaded = false

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row {
            val note = JBLabel(
                "<html>AgentBridge's own agent. It talks to the model provider directly and runs every tool call " +
                    "through AgentBridge's tool layer, so the model only ever sees AgentBridge's instructions and " +
                    "tools. Tool permissions are configured under <b>Tools</b>; there are no built-in tools to disable. " +
                    "Changes apply the next time the agent starts.</html>"
            )
            note.foreground = UIUtil.getContextHelpForeground()
            cell(note)
        }
        row("Provider:") {
            comboBox(KoogProviderKind.entries, textListCellRenderer { it?.label })
                .bindItem(
                    { KoogSettings.provider },
                    // bindItem hands the setter a nullable; keep the previous choice if it is ever null.
                    { KoogSettings.provider = it ?: KoogSettings.provider },
                )
        }

        val needsSetup = !KoogSettings.hasBundledClientId()

        group("GitHub Copilot subscription") {
            row("Status:") { cell(copilotStatus) }
            row {
                button("Sign in with GitHub…") { signIn() }
                button("Sign out") { signOut() }
            }
            if (needsSetup) {
                row {
                    val intro = JBLabel(
                        "<html><b>One-time setup needed.</b> GitHub's sign-in needs an OAuth app, and this build " +
                            "does not include one. Registering your own is free and takes about two minutes:</html>"
                    )
                    cell(intro)
                }
                row {
                    cell(JBLabel(CopilotSetupGuide.stepsAsHtml()))
                }
                row {
                    button("Open GitHub: new OAuth app") { BrowserUtil.browse(CopilotSetupGuide.NEW_APP_URL) }
                }
            }
            row("OAuth client id:") {
                textField()
                    .align(AlignX.FILL)
                    .applyToComponent {
                        clientIdField = this
                        emptyText.text =
                            if (needsSetup) "Paste the Client ID from step 4" else "Using the one bundled with this build"
                    }
                    .comment(
                        if (needsSetup) {
                            "The Client ID (public) of your GitHub OAuth app, with Device Flow enabled."
                        } else {
                            "Optional. Leave empty to use the one bundled with this build, or enter the Client ID " +
                                "of your own GitHub OAuth app with Device Flow enabled."
                        }
                    )
                    .validationOnInput { field ->
                        CopilotSetupGuide.clientIdProblem(field.text)?.let { warning(it) }
                    }
                    .validationOnApply { field ->
                        CopilotSetupGuide.clientIdProblem(field.text)?.let { error(it) }
                    }
                    .bindText(
                        { KoogSettings.copilotClientIdOverride },
                        { KoogSettings.copilotClientIdOverride = it },
                    )
            }
            row {
                val terms = JBLabel(
                    "<html>Using a Copilot subscription outside GitHub's own clients follows the same device sign-in " +
                        "other agent tools use. GitHub has announced official support for OpenCode only; " +
                        "check that it is acceptable under your plan and employer's policy.</html>"
                )
                terms.foreground = UIUtil.getContextHelpForeground()
                cell(terms)
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
                    copilotStatus.text =
                        if (KoogSettings.copilotClientId().isBlank()) "Not signed in. One-time setup needed, see below" else "Not signed in"
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
        val typed = clientIdField?.text?.trim().orEmpty()
        CopilotSetupGuide.clientIdProblem(typed)?.let {
            Messages.showErrorDialog(project, it, SIGN_IN_TITLE)
            clientIdField?.requestFocusInWindow()
            return
        }
        val clientId = typed.ifBlank { KoogSettings.copilotClientId() }
        if (clientId.isBlank()) {
            showSetupNeeded()
            return
        }
        ProgressManager.getInstance().run(SignInTask(project, clientId) { refreshCopilotStatus() })
    }

    /** Explains the one-time setup and offers to open the GitHub page where it starts, instead of a dead end. */
    private fun showSetupNeeded() {
        val choice = Messages.showDialog(
            project,
            "GitHub's sign-in needs an OAuth app, and this build does not include one. " +
                "You register your own once (it is free, takes about two minutes, and you do not need a client secret):\n\n" +
                CopilotSetupGuide.stepsAsText() +
                "\n\nYou can also use an OpenAI-compatible API key instead, under \"OpenAI-compatible API\" on this page.",
            SIGN_IN_TITLE,
            arrayOf("Open GitHub", "Cancel"),
            0,
            Messages.getInformationIcon(),
        )
        if (choice == 0) BrowserUtil.browse(CopilotSetupGuide.NEW_APP_URL)
        clientIdField?.requestFocusInWindow()
    }

    /** Modal because the user has to act on the code shown in the progress text; Cancel abandons the sign-in. */
    private class SignInTask(project: Project, private val clientId: String, private val onDone: () -> Unit) :
        Task.Modal(project, SIGN_IN_TITLE, true) {

        private var error: String? = null

        override fun run(indicator: ProgressIndicator) {
            try {
                val http = KoogNetwork.jsonPoster("AgentBridge/" + BuildInfo.getVersion())
                indicator.text = "Requesting a sign-in code from GitHub…"
                val code = CopilotAuth.start(clientId, http)
                ApplicationManager.getApplication().invokeLater {
                    CopyPasteManager.getInstance().setContents(StringSelection(code.userCode))
                    BrowserUtil.browse(code.verificationUri)
                }
                indicator.text = "Enter code ${code.userCode} at ${code.verificationUri} (copied to the clipboard)"
                val token = CopilotAuth.awaitToken(
                    clientId, code, http,
                    sleeper = { millis -> sleepCancellable(indicator, millis) },
                    isCancelled = { indicator.isCanceled },
                )
                KoogSettings.copilotToken = token
            } catch (e: CopilotAuth.AuthException) {
                // A user cancel is not an error worth a dialog.
                if (!indicator.isCanceled) error = e.message
            } catch (e: IOException) {
                error = "Could not reach GitHub: ${e.message}"
            }
        }

        override fun onFinished() {
            onDone()
            error?.let { Messages.showErrorDialog(project, it, SIGN_IN_TITLE) }
        }

        private fun sleepCancellable(indicator: ProgressIndicator, millis: Long) {
            var left = millis
            while (left > 0 && !indicator.isCanceled) {
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
