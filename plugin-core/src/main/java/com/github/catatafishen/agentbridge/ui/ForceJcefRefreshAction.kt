package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Diagnostic action that forces the chat panel's JCEF OSR renderer to recover.
 *
 * Provides a user-facing recovery path for issue #237 (JCEF chat panel freezes
 * or renders at wrong DPI after monitor switch / MacBook lid close). The
 * automatic [MonitorSwitchRecovery] should handle this, but if a listener
 * misses the event the user can invoke this action from *Find Action* instead
 * of restarting the IDE.
 *
 * Also serves as an A/B diagnostic: if this fixes the hang, our handler works
 * but its triggers are missing; if it does not, the handler itself is
 * insufficient.
 */
class ForceJcefRefreshAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatConsolePanel.getInstance(project)?.forceJcefRefresh()
    }
}
