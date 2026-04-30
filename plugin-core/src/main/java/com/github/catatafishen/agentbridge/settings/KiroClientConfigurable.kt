package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.services.GenericSettings
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.SwingUtilities

@Suppress("unused")
class KiroClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("Kiro"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()
    private val genericSettings = GenericSettings(AGENT_ID)

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") { cell(statusLabel) }
        row {
            val note = JBLabel(
                "<html>Ensure <code>kiro-cli</code> is installed and available on your PATH.</html>"
            )
            note.foreground = UIUtil.getContextHelpForeground()
            cell(note)
        }
        row {
            val link = HyperlinkLabel("Kiro CLI documentation at kiro.dev/docs/cli/acp")
            link.setHyperlinkTarget("https://kiro.dev/docs/cli/acp/")
            cell(link)
        }
        separator()
        row("Kiro binary:") {
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
                .comment("Choose a theme-aware accent color for message bubbles when using Kiro.")
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)) },
                    { AcpClient.saveAgentBubbleColorKey(AGENT_ID, it?.name) }
                )
        }
        row("Session history limit:") {
            spinner(0..2_000_000, 50_000)
                .comment(
                    "Maximum characters of conversation history exported to Kiro's session file. " +
                        "0 = unlimited. Reduce if Kiro reports context overflow. Default: 300 000."
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
            val version = AcpClientBinaryResolver(AGENT_ID, "kiro-cli", "kiro").detectVersion()
            SwingUtilities.invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ Kiro CLI found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text = "Kiro CLI not found on PATH — install from kiro.dev"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_LIMIT_CHARS = 300_000
        const val ID = "com.github.catatafishen.agentbridge.client.kiro"
        private const val AGENT_ID = "kiro"
    }
}
