package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.agent.codex.CodexAppServerClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.ui.AuthTerminalHelperKt
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
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

@Suppress("unused")
class CodexClientConfigurable(private val project: Project) :
    BoundConfigurable("Codex"),
    SearchableConfigurable {

    private val statusLabel = JBLabel(CHECKING)

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row("Binary:") { cell(statusLabel) }
        row {
            val installNote = JBLabel(
                "<html>Install with <code>npm install -g @openai/codex</code>, then run " +
                    "<code>codex login</code>. Authentication problems are reported by Codex " +
                    "itself when you send a prompt.</html>"
            )
            installNote.foreground = UIUtil.getContextHelpForeground()
            cell(installNote)
        }
        row {
            val link = HyperlinkLabel("Install Codex CLI — npmjs.com/@openai/codex")
            link.setHyperlinkTarget("https://www.npmjs.com/package/@openai/codex")
            cell(link)
        }
        separator()
        row("Codex binary path:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { emptyText.text = "Auto-detect (leave empty)" }
                .comment("Leave empty to auto-detect on PATH.")
                .bindText(
                    { AgentProfileManager.getInstance().loadBinaryPath(PROFILE_ID).orEmpty() },
                    { AgentProfileManager.getInstance().saveBinaryPath(PROFILE_ID, it.trim()) }
                )
        }
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment("Choose a theme-aware accent color for message bubbles when using Codex.")
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(PROFILE_ID)) },
                    { AcpClient.saveAgentBubbleColorKey(PROFILE_ID, it?.name) }
                )
        }
        separator()
        row { button("Sign In (codex login)") { openSignInTerminal(false) } }
        row { button("Sign In — headless (codex login --device-auth)") { openSignInTerminal(true) } }
        row {
            val note = JBLabel(
                "<html>Use <i>headless</i> sign-in on remote/SSH machines where a browser cannot " +
                    "open automatically.</html>"
            )
            note.foreground = UIUtil.getContextHelpForeground()
            cell(note)
        }
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        refreshStatusAsync()
    }

    private fun refreshStatusAsync() {
        statusLabel.text = CHECKING
        statusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val version = AcpClientBinaryResolver(PROFILE_ID, "codex").detectVersion()
            SwingUtilities.invokeLater {
                if (version != null) {
                    statusLabel.text = "✓ Codex CLI found — $version"
                    statusLabel.foreground = JBColor(0x008000, 0x4EC94E)
                } else {
                    statusLabel.text =
                        "Codex CLI not found on PATH — install with npm install -g @openai/codex"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    private fun openSignInTerminal(deviceAuth: Boolean) {
        val customPath = AgentProfileManager.getInstance().loadBinaryPath(PROFILE_ID)
        val binary = if (!customPath.isNullOrEmpty()) customPath else "codex"
        val cmd = if (deviceAuth) "$binary login --device-auth" else "$binary login"
        AuthTerminalHelperKt.runAuthInEmbeddedTerminal(project, cmd, "Codex Sign In") {
            openExternalTerminal(cmd)
            kotlin.Unit
        }
    }

    private fun openExternalTerminal(cmd: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val os = System.getProperty("os.name", "").lowercase()
                when {
                    os.contains("win") ->
                        ProcessBuilder("cmd", "/c", "start", "cmd", "/k", cmd).start()
                    os.contains("mac") ->
                        ProcessBuilder(
                            "osascript", "-e",
                            "tell application \"Terminal\" to do script \"$cmd\""
                        ).start()
                    else -> ProcessBuilder(
                        "sh", "-c",
                        "x-terminal-emulator -e '$cmd' || gnome-terminal -- bash -c '$cmd' " +
                            "|| xterm -e bash -c '$cmd'"
                    ).start()
                }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        null,
                        "Could not open a terminal. Run manually: $cmd",
                        "Codex Sign In",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
            }
        }
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.client.codex"
        private const val CHECKING = "Checking…"
        private val PROFILE_ID = CodexAppServerClient.PROFILE_ID
    }
}
