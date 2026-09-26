package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Shared helper for the editor context-menu attach actions: inserts a context
 * chip into the AgentBridge chat prompt, activating the tool window first if
 * it has never been opened (its content — and the prompt editor — is created
 * lazily on first activation).
 */
internal object ChatAttachHelper {
    private val LOG = Logger.getInstance(ChatAttachHelper::class.java)

    fun insertChip(project: Project, data: ContextItemData) {
        val content = ChatToolWindowContent.getInstance(project)
        if (content != null) {
            content.insertContextChip(data)
            return
        }

        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("AgentBridge")
        if (toolWindow == null) {
            LOG.warn("AgentBridge tool window not found; cannot attach ${data.name}")
            return
        }
        toolWindow.activate {
            val created = ChatToolWindowContent.getInstance(project)
            if (created != null) {
                created.insertContextChip(data)
            } else {
                LOG.warn("AgentBridge tool window content not available after activation; cannot attach ${data.name}")
            }
        }
    }
}
