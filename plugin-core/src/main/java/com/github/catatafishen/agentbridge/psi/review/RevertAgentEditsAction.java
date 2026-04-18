package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Reverts the file in the currently active editor to its pre-session snapshot,
 * then sends an optional reason to the agent as a nudge. Enabled only when a
 * review session is active and a snapshot exists for the file.
 */
public final class RevertAgentEditsAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = project != null
            && vf != null
            && AgentEditSession.getInstance(project).isActive()
            && AgentEditSession.getInstance(project).getSnapshot(vf) != null;
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || vf == null) return;

        AgentEditSession session = AgentEditSession.getInstance(project);
        if (!session.isActive() || session.getSnapshot(vf) == null) return;

        String relativePath = toRelativePath(project, vf);
        RevertReasonDialog dialog = new RevertReasonDialog(project, vf, relativePath, session.isGateActive());
        if (!dialog.showAndGet()) {
            return;
        }
        AgentEditSession.RevertGateAction gateAction = switch (dialog.getResult()) {
            case CONTINUE_REVIEWING -> AgentEditSession.RevertGateAction.CONTINUE_REVIEWING;
            case SEND_NOW -> AgentEditSession.RevertGateAction.SEND_NOW;
            default -> AgentEditSession.RevertGateAction.DEFAULT;
        };
        session.revertFile(vf.getPath(), dialog.getReason(), gateAction);
    }

    private static @NotNull String toRelativePath(@NotNull Project project,
                                                  @NotNull VirtualFile vf) {
        String basePath = project.getBasePath();
        String filePath = vf.getPath();
        if (basePath != null && filePath.startsWith(basePath + "/")) {
            return filePath.substring(basePath.length() + 1);
        }
        return vf.getName();
    }
}
