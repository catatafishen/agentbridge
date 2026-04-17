package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Explicitly ends the current {@link AgentEditSession}, clearing all diff
 * highlights and forgetting all before-snapshots. Any reverts after this are
 * impossible until a new session starts (on the next agent edit).
 */
public final class EndReviewSessionAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean active = project != null && AgentEditSession.getInstance(project).isActive();
        e.getPresentation().setEnabledAndVisible(active);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        AgentEditSession.getInstance(project).endSession();
    }
}
