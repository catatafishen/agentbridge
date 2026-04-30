package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.BuildInfo
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Root settings page for AgentBridge. Holds no settings of its own — child
 * pages (UI/UX, Storage, MCP, Agents, etc.) configure the various subsystems.
 *
 * Built with the official IntelliJ Platform Kotlin UI DSL v2.
 */
class PluginSettingsConfigurable @Suppress("unused") constructor(
    @Suppress("UNUSED_PARAMETER") project: Project
) : Configurable {

    override fun getDisplayName(): String = "AgentBridge"

    override fun createComponent(): JComponent = panel {
        row {
            comment(
                "AgentBridge connects IntelliJ IDE with AI coding agents via the " +
                    "<b>Agent Coding Protocol (ACP)</b>. Agents gain live access to code " +
                    "intelligence, refactoring, search, file editing, and build tools through " +
                    "the MCP server built into the IDE.<br><br>" +
                    "Supported clients: <b>GitHub Copilot</b>, <b>OpenCode</b>, " +
                    "<b>Claude Code</b>, <b>Claude CLI</b>, <b>Junie</b>, <b>Kiro</b>."
            )
        }
        separator()
        row {
            comment("Version ${BuildInfo.getVersion()}  ·  ${BuildInfo.getGitHash()}")
        }
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}
}
