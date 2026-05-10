package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.acp.client.HermesClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil

@Suppress("unused")
class HermesClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("Hermes Agent"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") { cell(statusLabel) }
        row {
            val note = JBLabel(
                "<html>Ensure <code>hermes</code> is installed and available on your PATH. " +
                    "Run <code>hermes setup</code> to configure a model and provider.</html>"
            )
            note.foreground = UIUtil.getContextHelpForeground()
            cell(note)
        }
        row {
            val link = HyperlinkLabel("Hermes Agent on GitHub (NousResearch/hermes-agent)")
            link.setHyperlinkTarget("https://github.com/NousResearch/hermes-agent")
            cell(link)
        }
        separator()
        row("Hermes binary:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { emptyText.text = "Auto-detect (leave empty)" }
                .comment("Leave empty to auto-detect on PATH. Override if hermes is not on PATH.")
                .bindText(
                    { AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID).orEmpty() },
                    { AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, it.trim()) }
                )
        }
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment("Choose a theme-aware accent color for Hermes message bubbles.")
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)) },
                    // SonarQube S6619 falsely reports `?.` as useless: bindItem setter receives ThemeColor?
                    @Suppress("kotlin:S6619")
                    { AcpClient.saveAgentBubbleColorKey(AGENT_ID, it?.name) }
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
            val version = AcpClientBinaryResolver(AGENT_ID, "hermes").detectVersion()
            ApplicationManager.getApplication().invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ Hermes found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text = "hermes not found on PATH — install from github.com/NousResearch/hermes-agent"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.client.hermes"
        private const val AGENT_ID = HermesClient.AGENT_ID
    }
}
