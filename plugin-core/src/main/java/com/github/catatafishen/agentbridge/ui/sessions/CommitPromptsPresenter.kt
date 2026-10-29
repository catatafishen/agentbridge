package com.github.catatafishen.agentbridge.ui.sessions

import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.session.db.SessionSearch
import com.github.catatafishen.agentbridge.ui.side.HistoryContextWindow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component

/**
 * Shows the prompts (turns) behind a git commit and opens the one the user picks in the conversation
 * history. Used by the Git Log "Show AgentBridge Prompts" action.
 */
object CommitPromptsPresenter {

    /** Label for one matching turn in the popup. Pure, so it can be unit tested. */
    fun label(turn: SessionSearch.CommitTurn): String {
        val title = SessionFormat.truncate(turn.prompt().ifBlank { "(empty prompt)" }, 80)
        val session = turn.sessionName().ifBlank { turn.agentName() }
        return "$title  \u2014  ${
            SessionFormat.truncate(
                session,
                30
            )
        } \u00b7 ${SessionFormat.relativeTime(turn.startedAtMs())}"
    }

    /**
     * Looks up the turns for [commitHash] off the EDT and then either opens the single match directly,
     * shows a chooser for several, or tells the user none were recorded.
     */
    fun show(project: Project, commitHash: String, anchor: Component?, onNone: (String) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            val turns = ConversationService.getInstance(project).turnsForCommit(commitHash)
            ApplicationManager.getApplication().invokeLater {
                when (turns.size) {
                    0 -> onNone("No AgentBridge prompt is recorded for commit ${SessionFormat.shortHash(commitHash)}")
                    1 -> open(project, turns.first())
                    else -> choose(project, turns, anchor)
                }
            }
        }
    }

    private fun open(project: Project, turn: SessionSearch.CommitTurn) {
        HistoryContextWindow.open(project, turn.sessionId(), turn.turnId())
    }

    private fun choose(project: Project, turns: List<SessionSearch.CommitTurn>, anchor: Component?) {
        val step = object : BaseListPopupStep<SessionSearch.CommitTurn>("Prompts for this Commit", turns) {
            override fun getTextFor(value: SessionSearch.CommitTurn): String = label(value)

            override fun onChosen(selected: SessionSearch.CommitTurn, finalChoice: Boolean): PopupStep<*>? {
                open(project, selected)
                return FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        if (anchor != null) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
    }
}
