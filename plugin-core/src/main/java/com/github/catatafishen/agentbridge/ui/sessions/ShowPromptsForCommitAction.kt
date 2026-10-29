package com.github.catatafishen.agentbridge.ui.sessions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.VcsLogDataKeys

/**
 * Git Log context menu: "Show AgentBridge Prompts" for the selected commit. Opens the prompt (turn)
 * that produced the commit, or a chooser when several did.
 *
 * Only registered when the Git integration is available (see git-features.xml).
 */
class ShowPromptsForCommitAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)
        e.presentation.isEnabledAndVisible = e.project != null && selection != null && selection.commits.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commit = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits?.singleOrNull() ?: return
        CommitPromptsPresenter.show(project, commit.hash.asString(), e.inputEvent?.component) { notify(project, it) }
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("AgentBridge Notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
