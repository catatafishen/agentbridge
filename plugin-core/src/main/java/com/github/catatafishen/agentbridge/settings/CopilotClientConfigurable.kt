package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import java.io.File
import javax.swing.JComponent
import javax.swing.SwingUtilities

@Suppress("unused")
class CopilotClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    Configurable, SearchableConfigurable {

    private val statusLabel = JBLabel()
    private val billing = BillingConfigurable()
    private var configPanel: com.intellij.openapi.ui.DialogPanel? = null

    private var liveBinaryFieldText: () -> String = { "" }

    override fun getDisplayName(): String = "GitHub Copilot"
    override fun getId(): String = ID

    override fun createComponent(): JComponent {
        val panel = buildConfigPanel()
        configPanel = panel
        val scroll = JBScrollPane(panel).apply { border = null }
        val tabs = JBTabbedPane()
        tabs.addTab("Configuration", scroll)
        tabs.addTab("Billing Data", billing.createComponent())
        return tabs
    }

    private fun buildConfigPanel() = panel {
        row("Status:") {
            cell(statusLabel)
            button("Recheck") { refreshStatusAsync() }
        }
        row {
            text(
                "Install with <code>npm install -g @github/copilot-cli</code>. " +
                    "Ensure it's available on PATH.",
                MAX_LINE_LENGTH_WORD_WRAP
            ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
        }
        row {
            val link = HyperlinkLabel("Install from github.com/github/copilot-cli")
            link.setHyperlinkTarget("https://github.com/github/copilot-cli#installation")
            cell(link)
        }
        separator()
        row("Copilot binary:") {
            val cell = textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { emptyText.text = "Auto-detect (leave empty)" }
                .comment("Leave empty to auto-detect on PATH.")
                .bindText(
                    { AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID).orEmpty() },
                    { AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, it.trim()) }
                )
            liveBinaryFieldText = { cell.component.text }
        }
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment(
                    "Choose a theme-aware accent color for message bubbles when using GitHub Copilot."
                )
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)) },
                    // SonarQube S6619 falsely reports `?.` as useless: bindItem setter receives ThemeColor?
                    @Suppress("kotlin:S6619")
                    { AcpClient.saveAgentBubbleColorKey(AGENT_ID, it?.name) }
                )
        }
    }

    override fun isModified(): Boolean =
        (configPanel?.isModified() == true) || billing.isModified

    override fun apply() {
        configPanel?.apply()
        billing.apply()
    }

    override fun reset() {
        configPanel?.reset()
        billing.reset()
        refreshStatusAsync()
    }

    override fun disposeUIResources() {
        configPanel = null
        billing.disposeUIResources()
        liveBinaryFieldText = { "" }
    }

    private fun refreshStatusAsync() {
        statusLabel.text = "Checking..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        val liveCustomPath = liveBinaryFieldText().trim()
        ApplicationManager.getApplication().executeOnPooledThread {
            var resolver: AgentBinaryResolver = AcpClientBinaryResolver(AGENT_ID, AGENT_ID, "copilot-cli")
            if (liveCustomPath.isNotEmpty()) {
                val file = File(liveCustomPath)
                if (!file.exists()) {
                    setStatus("✗ File not found: $liveCustomPath", JBColor.RED); return@executeOnPooledThread
                }
                if (!file.canExecute()) {
                    setStatus("✗ File not executable: $liveCustomPath", JBColor.RED); return@executeOnPooledThread
                }
                resolver = resolver.withCustomPath(liveCustomPath)
            }
            val version = resolver.detectVersion()
            if (version != null) {
                setStatus("✓ GitHub Copilot found — $version", JBColor(0x008000, 0x4EC94E))
            } else {
                setStatus(
                    "GitHub Copilot not found on PATH — install from github.com/github/copilot-cli",
                    JBColor.RED
                )
            }
        }
    }

    private fun setStatus(text: String, color: JBColor) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
            statusLabel.foreground = color
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.client.copilot"
        private const val AGENT_ID = "copilot"
    }
}
