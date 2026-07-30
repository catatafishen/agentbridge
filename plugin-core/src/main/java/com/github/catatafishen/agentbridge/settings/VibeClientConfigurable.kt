package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.client.acp.AcpClient
import com.github.catatafishen.agentbridge.client.acp.VibeClient
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
class VibeClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("Mistral Vibe"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()
    private var binaryPathField: javax.swing.JTextField? = null
    private val sandboxSection = SandboxSettingsSection(
        agentId = AGENT_ID,
        displayName = "Vibe",
        binaryPathProvider = {
            binaryPathField?.text?.takeIf { it.isNotBlank() }
                ?: AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID)
        },
        binaryNameProvider = { "vibe-acp" },
    )

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") { cell(statusLabel) }
        row {
            val note = JBLabel(
                "<html>Ensure <code>vibe-acp</code> is installed and available on your PATH. " +
                    "Install with <code>pip install mistral-vibe</code> or " +
                    "<code>uv tool install mistral-vibe</code> (Python 3.12+). " +
                    "Run <code>vibe</code> once to authenticate with your Mistral API key.</html>"
            )
            note.foreground = UIUtil.getContextHelpForeground()
            cell(note)
        }
        row {
            val link = HyperlinkLabel("Mistral Vibe documentation")
            link.setHyperlinkTarget("https://docs.mistral.ai/vibe/code/use-vibe-in-other-ides")
            cell(link)
        }
        separator()
        row("Vibe binary:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent {
                    emptyText.text = "Auto-detect (leave empty)"
                    binaryPathField = this
                    sandboxSection.wireBinaryPathField(this)
                }
                .comment("Leave empty to auto-detect on PATH. Override if vibe-acp is not on PATH.")
                .bindText(
                    { AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID).orEmpty() },
                    { AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, it.trim()) }
                )
        }
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment("Choose a theme-aware accent color for Vibe message bubbles.")
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)) },
                    // SonarQube S6619 falsely reports `?.` as useless: bindItem setter receives ThemeColor?
                    @Suppress("kotlin:S6619")
                    { AcpClient.saveAgentBubbleColorKey(AGENT_ID, it?.name) }
                )
        }
        sandboxSection.render(this@panel)
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshStatusAsync()
        sandboxSection.reset()
    }

    private fun refreshStatusAsync() {
        statusLabel.text = "Checking..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val version = AcpClientBinaryResolver(AGENT_ID, "vibe-acp", "vibe").detectVersion()
            ApplicationManager.getApplication().invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ Vibe found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text = "vibe-acp not found on PATH — install with: pip install mistral-vibe"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.client.vibe"
        private const val AGENT_ID = VibeClient.AGENT_ID
    }
}
