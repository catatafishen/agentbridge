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
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.UIUtil
import javax.swing.SwingUtilities

@Suppress("unused")
class OpenCodeClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("OpenCode"),
    SearchableConfigurable {

    private val statusLabel = JBLabel()
    private val genericSettings = GenericSettings(AGENT_ID)

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Status:") {
            cell(statusLabel)
        }
        row {
            val installNote = JBLabel(
                "<html>Install with <code>npm i -g opencode-ai</code>. " +
                    "Ensure it's available on PATH.</html>"
            )
            installNote.foreground = UIUtil.getContextHelpForeground()
            cell(installNote)
        }
        row {
            val link = HyperlinkLabel("Install OpenCode from npmjs.com/package/opencode-ai")
            link.setHyperlinkTarget("https://www.npmjs.com/package/opencode-ai")
            cell(link)
        }
        separator()
        row("OpenCode binary:") {
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
                .comment(
                    "Choose a theme-aware accent color for message bubbles when using OpenCode."
                )
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
                    "Maximum characters of conversation history exported to OpenCode's database. " +
                        "0 = unlimited. OpenCode handles context compaction internally; set only if " +
                        "you hit overflow errors. Default: unlimited (0)."
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
            val version = AcpClientBinaryResolver(AGENT_ID, AGENT_ID).detectVersion()
            SwingUtilities.invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ OpenCode found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text = "OpenCode not found on PATH — install with npm i -g opencode-ai"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_LIMIT_CHARS = 0
        const val ID = "com.github.catatafishen.agentbridge.client.opencode"
        private const val AGENT_ID = "opencode"
    }
}
