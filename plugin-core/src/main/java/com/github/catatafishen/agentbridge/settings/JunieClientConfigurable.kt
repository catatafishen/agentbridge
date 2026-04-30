package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.agent.junie.JunieKeyStore
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.services.GenericSettings
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.SwingUtilities

@Suppress("unused")
class JunieClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("Junie"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()
    private val authTokenField = JBPasswordField().apply {
        emptyText.text = "Optional: enter token to bypass CLI auth"
        toolTipText =
            "Generate a token at https://junie.jetbrains.com/cli. Leave empty to use CLI credentials."
    }
    private val genericSettings = GenericSettings(AGENT_ID)

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") { cell(statusLabel) }
        separator()
        row("Auth Token:") {
            cell(authTokenField)
                .align(AlignX.FILL)
                .resizableColumn()
                .comment("Generate token at https://junie.jetbrains.com/cli (optional)")
                .onIsModified {
                    String(authTokenField.password) != JunieKeyStore.getAuthToken().orEmpty()
                }
                .onApply {
                    val newToken = String(authTokenField.password).trim()
                    val oldToken = JunieKeyStore.getAuthToken().orEmpty()
                    val tokenChanged = newToken != oldToken
                    JunieKeyStore.setAuthToken(newToken.ifEmpty { null })
                    if (tokenChanged) restartJunieProcesses()
                }
                .onReset {
                    authTokenField.text = JunieKeyStore.getAuthToken().orEmpty()
                }
        }
        row {
            val link = HyperlinkLabel("Generate an auth token at junie.jetbrains.com/cli")
            link.setHyperlinkTarget("https://junie.jetbrains.com/cli")
            cell(link)
        }
        row {
            val authNote = JBLabel(
                "<html><b>Authentication:</b> You can either:<br>" +
                    "1. Enter a token above (recommended for plugin use), OR<br>" +
                    "2. Run <code>junie</code> in a terminal and use <code>/account</code> " +
                    "to log in with JetBrains Account</html>"
            )
            authNote.foreground = UIUtil.getContextHelpForeground()
            cell(authNote)
        }
        separator()
        row {
            val warning = JBLabel(
                "<html><b>⚠ Tool Selection Limitation:</b> Junie ignores " +
                    "<code>excludedTools</code> and does not send <code>request_permission</code> " +
                    "for any tools. Built-in tools (Edit, View, Bash) may bypass IntelliJ's editor " +
                    "buffer. The plugin uses prompt engineering to encourage MCP tool usage, but " +
                    "compliance depends on the LLM. See <code>docs/JUNIE-TOOL-WORKAROUND.md</code> " +
                    "for details.</html>"
            )
            warning.foreground = JBColor(0xB8860B, 0xE0A030)
            cell(warning)
        }
        separator()
        row("Junie binary:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { emptyText.text = "Auto-detect (leave empty)" }
                .comment("Leave empty to auto-detect on PATH.")
                .bindText(
                    { AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID).orEmpty() },
                    { AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, it.trim()) }
                )
        }
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment("Choose a theme-aware accent color for message bubbles when using Junie.")
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)) },
                    // SonarQube S6619 falsely reports `?.` as useless: bindItem setter receives ThemeColor?
                    @Suppress("kotlin:S6619")
                    { AcpClient.saveAgentBubbleColorKey(AGENT_ID, it?.name) }
                )
        }
        row("Session history limit:") {
            spinner(0..2_000_000, 50_000)
                .comment(
                    "Maximum characters of conversation history exported to Junie's session file. " +
                        "0 = unlimited. Reduce if Junie reports context overflow. Default: 600 000."
                )
                .bindIntValue(
                    { genericSettings.getContextHistoryLimit(DEFAULT_CONTEXT_LIMIT_CHARS) },
                    { genericSettings.setContextHistoryLimit(it) }
                )
        }
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshStatusAsync()
    }

    private fun refreshStatusAsync() {
        statusLabel.text = "Checking..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val version = AcpClientBinaryResolver(AGENT_ID, "junie").detectVersion()
            SwingUtilities.invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ Junie CLI found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text =
                        "Junie CLI not found on PATH — install from junie.jetbrains.com"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    /**
     * Stops all running Junie processes across all projects so they restart with the new auth token.
     */
    private fun restartJunieProcesses() {
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                val manager = ActiveAgentManager.getInstance(project)
                if (AGENT_ID == manager.activeProfileId) {
                    LOG.info("Junie auth token changed — restarting Junie process to pick up new token")
                    manager.restart()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_LIMIT_CHARS = 600_000
        const val ID = "com.github.catatafishen.agentbridge.client.junie"
        private const val AGENT_ID = "junie"
        private val LOG = Logger.getInstance(JunieClientConfigurable::class.java)
    }
}
