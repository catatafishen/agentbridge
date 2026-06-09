package com.github.catatafishen.agentbridge.ui.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphIndexer;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Title bar action that triggers a full Knowledge Graph rebuild (↻).
 */
final class KnowledgeGraphRebuildAction extends AnAction {

    private final Project project;
    private final KnowledgeGraphDashboardPanel dashboard;

    KnowledgeGraphRebuildAction(@NotNull Project project, @NotNull KnowledgeGraphDashboardPanel dashboard) {
        super("Rebuild", "Rebuild the knowledge graph index", AllIcons.Actions.Refresh);
        this.project = project;
        this.dashboard = dashboard;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        CodeGraphIndexer.getInstance(project).rebuildAll(dashboard::onRebuildFinished);
    }
}
