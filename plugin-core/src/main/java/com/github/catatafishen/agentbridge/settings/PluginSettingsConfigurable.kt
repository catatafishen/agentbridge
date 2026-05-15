package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.BuildInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Root settings page for AgentBridge. Holds no settings of its own — child
 * pages (UI/UX, Storage, MCP, Agents, etc.) configure the various subsystems.
 *
 * Implements [SearchableConfigurable.Parent] so that IntelliJ treats this node
 * as a group in the settings tree and expands it when selected, saving the user
 * one click to reach child pages. [hasOwnContent] returns true so the description
 * page is still shown.
 *
 * Built with the official IntelliJ Platform Kotlin UI DSL v2.
 */
class PluginSettingsConfigurable @Suppress("unused") constructor(
    @Suppress("UNUSED_PARAMETER") project: Project
) : SearchableConfigurable.Parent {

    override fun getId(): String = "com.github.catatafishen.agentbridge.settings"

    override fun getDisplayName(): String = "AgentBridge"

    /** Keep the root description page visible when this node is selected. */
    override fun hasOwnContent(): Boolean = true

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

    // No mutable state on this page; child Configurables own all persisted settings.
    override fun apply() = Unit
    override fun reset() = Unit
}

/**
 * Opens the AgentBridge root settings page programmatically. Because
 * [PluginSettingsConfigurable] implements [SearchableConfigurable.Parent], IntelliJ
 * treats it as a group node and expands its children in the tree when it is selected.
 *
 * Defers the dialog to the next EDT cycle to avoid a BufferStrategy NPE that can occur
 * when a modal dialog is shown synchronously during mouse-event processing
 * (a JDK Swing repaint race).
 */
fun openAgentBridgeSettings(project: Project) {
    ApplicationManager.getApplication().invokeLater {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, PluginSettingsConfigurable::class.java)
    }
}
