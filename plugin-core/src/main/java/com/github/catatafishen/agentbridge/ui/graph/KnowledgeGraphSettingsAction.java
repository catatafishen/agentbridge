package com.github.catatafishen.agentbridge.ui.graph;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Title bar action that opens the Knowledge Graph settings dialog (⚙ cogwheel).
 */
final class KnowledgeGraphSettingsAction extends AnAction {

    private final Project project;

    KnowledgeGraphSettingsAction(@NotNull Project project) {
        super("Settings", "Open Knowledge Graph settings", AllIcons.General.GearPlain);
        this.project = project;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        new CodeGraphSettingsDialog(project).show();
    }
}
