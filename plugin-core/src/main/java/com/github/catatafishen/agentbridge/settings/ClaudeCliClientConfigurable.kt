package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.acp.client.AcpClient
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.ui.ThemeColor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ui.components.JBScrollPane
import java.awt.Font

@Suppress("unused")
class ClaudeCliClientConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) :
    BoundConfigurable("Claude CLI"),
    SearchableConfigurable {

    private val customModelsArea = JBTextArea(8, 40).apply {
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)
    }

    override fun getId(): String = ID

    override fun createPanel() = panel {
        row {
            val authNote = JBLabel(
                "<html>Run <code>claude /login</code> in a terminal to authenticate. " +
                    "Authentication problems are reported by Claude itself when you send a prompt.</html>"
            )
            authNote.foreground = UIUtil.getContextHelpForeground()
            cell(authNote)
        }
        separator()
        row("Claude binary:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { (this as JBTextField).emptyText.text = "Auto-detect (leave empty)" }
                .comment("Leave empty to auto-detect on PATH.")
                .bindText(
                    { profile()?.customBinaryPath.orEmpty() },
                    { profile()?.customBinaryPath = it.trim() }
                )
        }
        row("Instructions file:") {
            textField()
                .align(AlignX.FILL)
                .resizableColumn()
                .applyToComponent { (this as JBTextField).emptyText.text = "E.g. CLAUDE.md" }
                .comment(
                    "Plugin instructions are prepended here on session start (relative to project root)."
                )
                .bindText(
                    { profile()?.prependInstructionsTo.orEmpty() },
                    { profile()?.prependInstructionsTo = it.trim() }
                )
        }
        row("Bubble color:") {
            cell(ThemeColorComboBox())
                .comment(
                    "Choose a theme-aware accent color for Claude message bubbles. " +
                        "Shared with Claude Code."
                )
                .bindItem(
                    { ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(BUBBLE_CLIENT_TYPE)) },
                    { AcpClient.saveAgentBubbleColorKey(BUBBLE_CLIENT_TYPE, it?.name) }
                )
        }
        separator()
        row {
            label("Custom models (one per line):")
                .comment(
                    "Format: <code>&lt;model-id&gt;=&lt;Display Name&gt;</code>. " +
                        "Leave empty to use the built-in model list."
                )
        }
        row {
            cell(JBScrollPane(customModelsArea))
                .align(AlignX.FILL)
                .align(AlignY.FILL)
                .resizableColumn()
                .onIsModified { parseModels() != (profile()?.customCliModels ?: emptyList()) }
                .onApply { profile()?.customCliModels = parseModels() }
                .onReset {
                    customModelsArea.text =
                        (profile()?.customCliModels ?: emptyList()).joinToString("\n")
                    customModelsArea.caretPosition = 0
                }
        }.layout(RowLayout.PARENT_GRID).resizableRow()
    }

    private fun profile() = AgentProfileManager.getInstance()
        .getProfile(AgentProfileManager.CLAUDE_CLI_PROFILE_ID)

    private fun parseModels(): List<String> =
        customModelsArea.text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.client.claudecli"

        /** CSS client type shared with Claude Code (AnthropicDirect) for bubble color. */
        private const val BUBBLE_CLIENT_TYPE = "claude"
    }
}
