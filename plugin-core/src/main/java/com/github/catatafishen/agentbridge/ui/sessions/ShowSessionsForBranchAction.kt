package com.github.catatafishen.agentbridge.ui.sessions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.vcs.log.VcsLogDataKeys

/**
 * Git Log context menu on a branch: "Show AgentBridge Sessions on This Branch". Opens the session picker
 * with the branch filter preselected.
 *
 * Only registered when the Git integration is available (see git-features.xml).
 */
class ShowSessionsForBranchAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && branchName(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branch = branchName(e) ?: return
        SessionPickerDialog(project, initialBranch = branch).show()
    }

    /** The first local branch name among the selected refs, stripped of any remote prefix. */
    private fun branchName(e: AnActionEvent): String? {
        val refs = e.getData(VcsLogDataKeys.VCS_LOG_BRANCHES) ?: return null
        val name = refs.firstOrNull()?.name ?: return null
        return stripRemote(name)
    }

    companion object {
        /** `origin/feature/x` -> `feature/x`; names without a known remote prefix are returned as is. */
        fun stripRemote(name: String): String {
            val slash = name.indexOf('/')
            return if (slash > 0 && name.substring(0, slash) in COMMON_REMOTES) name.substring(slash + 1) else name
        }

        private val COMMON_REMOTES = setOf("origin", "upstream")
    }
}
