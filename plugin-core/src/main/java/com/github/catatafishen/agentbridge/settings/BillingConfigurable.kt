package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.ui.CopilotBillingClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.SwingUtilities

class BillingConfigurable :
    BoundConfigurable("Billing Data"),
    SearchableConfigurable {

    private val settings = BillingSettings.getInstance()
    private val statusLabel = JBLabel()

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row {
            comment("Configure how billing and usage data is displayed in the IDE.")
        }
        separator()
        row("GitHub CLI Status:") {
            cell(statusLabel)
            button("Recheck") { refreshGhCliStatusAsync() }
        }
        row("GitHub CLI binary:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { emptyText.text = "Auto-detect (leave empty)" }
                .comment("Absolute path to gh CLI binary. Leave empty to auto-detect on PATH.")
                .bindText(
                    { settings.ghBinaryPath.orEmpty() },
                    { settings.ghBinaryPath = it.trim().ifEmpty { null } }
                )
        }
        row {
            text(
                "<b>Why GitHub CLI is needed:</b><br/>" +
                    "The Copilot ACP (Agent Communication Protocol) mode does not expose billing " +
                    "or usage data. To view Copilot premium request usage, the plugin uses the " +
                    "GitHub CLI (<code>gh</code>) to query GitHub's internal API endpoint.",
                MAX_LINE_LENGTH_WORD_WRAP
            ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
        }
        row {
            text(
                "Install from <a href='https://cli.github.com/'>cli.github.com</a>, " +
                    "then authenticate with <code>gh auth login</code>.",
                MAX_LINE_LENGTH_WORD_WRAP
            ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
        }
        row {
            val link = HyperlinkLabel("Install GitHub CLI")
            link.setHyperlinkTarget("https://cli.github.com/")
            cell(link)
        }
        separator()
        row {
            checkBox("Show Copilot usage graph in toolbar")
                .comment(
                    "Shows a usage graph icon in the main toolbar when Copilot usage data is available."
                )
                .bindSelected(
                    { settings.isShowCopilotUsage },
                    { settings.isShowCopilotUsage = it }
                )
        }
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshGhCliStatusAsync()
    }

    private fun refreshGhCliStatusAsync() {
        statusLabel.text = "Checking..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = CopilotBillingClient()
            val ghCli = client.findGhCli()
            val authenticated = ghCli != null && client.isGhAuthenticated(ghCli)
            SwingUtilities.invokeLater {
                when {
                    ghCli == null -> {
                        statusLabel.text = "GitHub CLI not found — install from cli.github.com"
                        statusLabel.foreground = JBColor.RED
                    }
                    !authenticated -> {
                        statusLabel.text =
                            "GitHub CLI found but not authenticated — run 'gh auth login'"
                        statusLabel.foreground = JBColor(0xFF8C00, 0xFFA040)
                    }
                    else -> {
                        statusLabel.text = "✓ GitHub CLI authenticated"
                        statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                    }
                }
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.billing"
    }
}
