package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.sandbox.BwrapSandbox
import com.github.catatafishen.agentbridge.sandbox.SandboxSettings
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

@Suppress("unused")
class SecurityConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    Configurable, SearchableConfigurable {

    private val bwrapStatusLabel = JBLabel()
    private var configPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getDisplayName(): String = "Security"
    override fun getId(): String = ID

    override fun createComponent(): JComponent {
        val panel = panel {
            group("Process Sandbox") {
                row("bwrap status:") {
                    cell(bwrapStatusLabel)
                    button("Recheck") { refreshBwrapStatusAsync() }
                }
                row {
                    text(
                        "When enabled, each agent process is wrapped in a bubblewrap (bwrap) " +
                            "sandbox on Linux. The sandbox blocks access to the host filesystem " +
                            "(home directory, project files) and prevents the agent from executing " +
                            "arbitrary system binaries. The agent can only communicate via the " +
                            "ACP and MCP protocol channels provided by AgentBridge.",
                        MAX_LINE_LENGTH_WORD_WRAP
                    ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
                }
                row {
                    text(
                        "Note: network traffic is <b>not</b> isolated — the agent can still reach " +
                            "its cloud AI backend. The sandbox prevents local lateral damage, " +
                            "not data exfiltration via the AI provider.",
                        MAX_LINE_LENGTH_WORD_WRAP
                    ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
                }
                separator()
                row {
                    checkBox("Run agent in sandbox (Linux only, requires bwrap)")
                        .comment(
                            "Requires <code>bwrap</code> to be installed " +
                                "(<code>sudo apt install bubblewrap</code> or equivalent). " +
                                "Has no effect on macOS or Windows — use Docker+bwrap for those platforms."
                        )
                        .bindSelected(
                            { SandboxSettings.isSandboxEnabled() },
                            { SandboxSettings.setSandboxEnabled(it) }
                        )
                }
            }
        }
        configPanel = panel
        refreshBwrapStatusAsync()
        return panel
    }

    override fun isModified(): Boolean = configPanel?.isModified() == true

    override fun apply() {
        val sandboxWasEnabled = SandboxSettings.isSandboxEnabled()
        configPanel?.apply()
        val sandboxIsNowEnabled = SandboxSettings.isSandboxEnabled()

        if (sandboxWasEnabled != sandboxIsNowEnabled) {
            offerSessionRestart()
        }
    }

    override fun reset() {
        configPanel?.reset()
        refreshBwrapStatusAsync()
    }

    override fun disposeUIResources() {
        configPanel = null
    }

    /**
     * If any agent session is currently running, asks the user whether to restart it now.
     * If the user declines, a balloon notification explains the setting takes effect on the next restart.
     */
    private fun offerSessionRestart() {
        val runningManagers = ProjectManager.getInstance().openProjects
            .asSequence()
            .map { ActiveAgentManager.getInstance(it) }
            .filter { it.clientIfRunning != null }
            .toList()

        if (runningManagers.isEmpty()) return

        val projectCount = runningManagers.size
        val message = if (projectCount == 1) {
            "The sandbox setting was changed. The active agent session must be restarted to apply it.\n\nRestart the session now?"
        } else {
            "The sandbox setting was changed. $projectCount active agent sessions must be restarted to apply it.\n\nRestart the sessions now?"
        }

        val choice = Messages.showYesNoDialog(
            message,
            "Agent Sandbox Setting Changed",
            "Restart Now",
            "Later",
            Messages.getQuestionIcon()
        )

        if (choice == Messages.YES) {
            ApplicationManager.getApplication().invokeLater {
                runningManagers.forEach { it.restart() }
            }
        }
        // If the user picks "Later", the setting is already persisted and will take effect
        // on the next session start — no extra action needed.
    }

    private fun refreshBwrapStatusAsync() {
        bwrapStatusLabel.text = "Checking..."
        bwrapStatusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            BwrapSandbox.resetDetectionCache()
            val status = SandboxSettings.getBwrapStatus()
            val available = BwrapSandbox.isAvailable()
            ApplicationManager.getApplication().invokeLater {
                bwrapStatusLabel.text = status
                bwrapStatusLabel.foreground = if (available) JBColor(0x008000, 0x4EC94E) else JBColor.GRAY
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.security"
    }
}
