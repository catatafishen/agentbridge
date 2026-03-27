package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.agent.claude.ClaudeCliClient
import com.github.catatafishen.ideagentforcopilot.agent.codex.CodexAppServerClient
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AgentIconProvider {
    private val defaultIcon: Icon = loadIcon("agentbridge.svg")
    private val claudeIcon: Icon = loadIcon("claude.svg")
    private val copilotIcon: Icon = loadIcon("copilot.svg")
    private val opencodeIcon: Icon = loadIcon("opencode.svg")
    private val junieIcon: Icon = loadIcon("junie.svg")
    private val kiroIcon: Icon = loadIcon("kiro.svg")
    private val codexIcon: Icon = loadIcon("codex.svg")

    fun getDefaultIcon(): Icon = defaultIcon

    fun getIconForProfile(profileId: String?): Icon {
        val icon = when (profileId) {
            ClaudeCliClient.PROFILE_ID -> claudeIcon
            AgentProfileManager.COPILOT_PROFILE_ID -> copilotIcon
            AgentProfileManager.OPENCODE_PROFILE_ID -> opencodeIcon
            AgentProfileManager.JUNIE_PROFILE_ID -> junieIcon
            AgentProfileManager.KIRO_PROFILE_ID -> kiroIcon
            CodexAppServerClient.PROFILE_ID -> codexIcon
            else -> null
        }
        return icon ?: defaultIcon
    }

    private fun loadIcon(filename: String): Icon {
        return IconLoader.getIcon("/icons/$filename", AgentIconProvider::class.java)
    }
}
