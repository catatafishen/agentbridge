package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.bridge.AgentConfig
import com.github.catatafishen.agentbridge.sandbox.BwrapSandbox
import com.github.catatafishen.agentbridge.sandbox.SandboxSettings
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.nio.file.Path
import javax.swing.JTextArea

/**
 * Shared helper that renders the per-agent "Sandbox (Experimental)" section inside a client
 * settings page.
 *
 * <p>The section shows the bwrap availability status, a checkbox to enable sandboxing for
 * <em>this specific agent</em>, and a read-only preview of the exact command the plugin
 * will invoke when launching the agent. The same widget is reused by every client
 * configurable so behaviour and wording stay consistent.</p>
 *
 * <p>The section also offers to restart the active agent session when the toggle changes,
 * mirroring the behaviour that used to live in the now-removed global "Security" page.</p>
 */
internal class SandboxSettingsSection(
    /**
     * Stable agent id (e.g. {@code "copilot"}, {@code "codex"}, {@code "claude-cli"}). Used as
     * the storage key for {@link SandboxSettings} and as the project id for
     * {@link ActiveAgentManager} restarts.
     */
    private val agentId: String,
    /** Display name used in the section title and restart prompt (e.g. "GitHub Copilot"). */
    private val displayName: String,
    /**
     * Supplier returning the user-configured binary path (typically the contents of the
     * "Binary" text field). Empty/null means "auto-detect on PATH" — the preview will
     * show {@code <binary on PATH>} as a placeholder.
     */
    private val binaryPathProvider: () -> String? = { null },
    /**
     * Optional supplier for the binary name used as a placeholder when no path is configured.
     * Defaults to {@link #agentId}.
     */
    private val binaryNameProvider: () -> String = { agentId },
    /**
     * Optional supplier for additional arguments the plugin appends after the binary.
     * Empty by default.
     */
    private val extraArgsProvider: () -> List<String> = { emptyList() },
) {

    private val bwrapStatusLabel = JBLabel()
    private val commandPreviewArea = JTextArea(6, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4)
    }
    private var initialEnabled: Boolean = SandboxSettings.isSandboxEnabled(agentId)

    /** Adds the sandbox group to the given Kotlin DSL panel. */
    fun render(panel: Panel) = with(panel) {
        group("Sandbox (Experimental)") {
            row {
                text(
                    "⚠ <b>Experimental:</b> Wraps the agent process in a " +
                        "<a href='https://github.com/containers/bubblewrap'>bubblewrap (bwrap)</a> " +
                        "sandbox on Linux so it can only see the project directory and its own " +
                        "config/auth files — the rest of your home directory and system binaries " +
                        "(git, gh, curl, …) are hidden. Network traffic is <i>not</i> isolated.",
                    MAX_LINE_LENGTH_WORD_WRAP
                ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
            }
            row("bwrap status:") {
                cell(bwrapStatusLabel)
                button("Recheck") { refreshBwrapStatusAsync() }
            }
            row {
                checkBox("Run $displayName in bwrap sandbox (Linux only)")
                    .comment(
                        "Requires <code>bwrap</code> on the host " +
                            "(<code>sudo apt install bubblewrap</code> or equivalent). " +
                            "Has no effect on macOS or Windows."
                    )
                    .bindSelected(
                        { SandboxSettings.isSandboxEnabled(agentId) },
                        { newValue ->
                            val wasEnabled = SandboxSettings.isSandboxEnabled(agentId)
                            SandboxSettings.setSandboxEnabled(agentId, newValue)
                            refreshCommandPreview()
                            if (wasEnabled != newValue) offerSessionRestart()
                        }
                    )
            }
            row {
                label("Exact command:")
                    .comment(
                        "The full command the plugin will run when launching $displayName. " +
                            "Shown so you can see exactly which paths are exposed to the agent."
                    )
            }
            row {
                cell(JBScrollPane(commandPreviewArea))
                    .align(AlignX.FILL)
                    .align(AlignY.FILL)
                    .resizableColumn()
            }.layout(RowLayout.PARENT_GRID).resizableRow()
        }

        refreshBwrapStatusAsync()
        refreshCommandPreview()
    }

    /** Refresh the preview when the user changes the binary path field. */
    fun refreshCommandPreview() {
        ApplicationManager.getApplication().invokeLater {
            commandPreviewArea.text = buildPreviewText()
            commandPreviewArea.caretPosition = 0
        }
    }

    /**
     * Attaches a {@link javax.swing.event.DocumentListener} to the given text field so the
     * command preview refreshes live as the user edits the binary path. Each client
     * configurable calls this on its binary-path text field so users see the impact of
     * changing the path immediately in the sandbox preview.
     */
    fun wireBinaryPathField(field: javax.swing.text.JTextComponent) {
        field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshCommandPreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshCommandPreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshCommandPreview()
        })
    }

    /** Called by the configurable's {@code reset()} to refresh status + preview. */
    fun reset() {
        initialEnabled = SandboxSettings.isSandboxEnabled(agentId)
        refreshBwrapStatusAsync()
        refreshCommandPreview()
    }

    private fun buildPreviewText(): String {
        val configBinds = AgentConfig.sandboxConfigBindsForAgentId(
            agentId,
            Path.of(System.getProperty("user.home"))
        )
        val binaryPath = binaryPathProvider().orEmpty().trim()
            .ifEmpty { "<${binaryNameProvider()} on PATH>" }
        val args = extraArgsProvider()
        val baseCommand = listOf(binaryPath) + args
        val projectDir = "<project directory>"

        return if (SandboxSettings.isSandboxEnabled(agentId)) {
            val cmd = try {
                BwrapSandbox.previewCommand(binaryPath, configBinds, projectDir, baseCommand)
            } catch (e: Exception) {
                return "Unable to build preview: ${e.message}"
            }
            formatCommandLines(cmd)
        } else {
            "Sandbox disabled — agent runs directly:\n\n${formatCommandLines(baseCommand)}"
        }
    }

    private fun formatCommandLines(cmd: List<String>): String {
        // Render one argument per line for readability, but join short flag+value pairs
        // (e.g. "--bind /a /b") onto a single line so the preview is grep-friendly.
        val builder = StringBuilder()
        var i = 0
        while (i < cmd.size) {
            val arg = cmd[i]
            when {
                arg == "--" -> {
                    builder.append("--\n")
                    i++
                }

                arg.startsWith("--") && i + 2 < cmd.size && isBindLikeFlag(arg) -> {
                    builder.append("  ").append(arg)
                        .append(' ').append(cmd[i + 1])
                        .append(' ').append(cmd[i + 2]).append('\n')
                    i += 3
                }

                arg.startsWith("--") && i + 1 < cmd.size && isSingleArgFlag(arg) -> {
                    builder.append("  ").append(arg).append(' ').append(cmd[i + 1]).append('\n')
                    i += 2
                }

                else -> {
                    builder.append("  ").append(arg).append('\n')
                    i++
                }
            }
        }
        return builder.toString().trimEnd()
    }

    private fun isBindLikeFlag(flag: String): Boolean = flag in BIND_FLAGS_WITH_TWO_ARGS

    private fun isSingleArgFlag(flag: String): Boolean = flag in FLAGS_WITH_ONE_ARG

    private fun refreshBwrapStatusAsync() {
        bwrapStatusLabel.text = "Checking..."
        bwrapStatusLabel.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            BwrapSandbox.forceRecheck()
            val status = SandboxSettings.getBwrapStatus()
            val available = BwrapSandbox.isAvailable()
            ApplicationManager.getApplication().invokeLater {
                bwrapStatusLabel.text = if (SystemInfo.isLinux) status else "$status (current OS)"
                bwrapStatusLabel.foreground = if (available) JBColor(0x008000, 0x4EC94E) else JBColor.GRAY
            }
        }
    }

    /**
     * If any agent session is currently running, asks the user whether to restart it now
     * so the new sandbox setting takes effect. Declining leaves the setting persisted; it
     * applies to the next session start.
     */
    private fun offerSessionRestart() {
        val runningManagers = ProjectManager.getInstance().openProjects
            .asSequence()
            .map { ActiveAgentManager.getInstance(it) }
            .filter { it.clientIfRunning != null && it.activeProfileId == agentId }
            .toList()

        if (runningManagers.isEmpty()) return

        val projectCount = runningManagers.size
        val message = if (projectCount == 1) {
            "The sandbox setting for $displayName was changed. The active session must be " +
                "restarted to apply it.\n\nRestart the session now?"
        } else {
            "The sandbox setting for $displayName was changed. $projectCount active sessions " +
                "must be restarted to apply it.\n\nRestart the sessions now?"
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
    }

    companion object {
        /** bwrap flags that take two positional arguments (src + dest). */
        private val BIND_FLAGS_WITH_TWO_ARGS = setOf(
            "--bind", "--bind-try",
            "--ro-bind", "--ro-bind-try",
            "--dev-bind", "--dev-bind-try",
            "--setenv",
        )

        /** bwrap flags that take a single positional argument. */
        private val FLAGS_WITH_ONE_ARG = setOf(
            "--tmpfs", "--proc", "--dev", "--chdir",
            "--unsetenv", "--clearenv-keep",
        )
    }
}
