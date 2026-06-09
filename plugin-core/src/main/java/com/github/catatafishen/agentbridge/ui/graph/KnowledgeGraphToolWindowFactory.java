package com.github.catatafishen.agentbridge.ui.graph;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the <b>Knowledge Graph</b> sidebar tool window. Hosts the
 * {@link KnowledgeGraphPanel} where users enable the feature, see graph stats,
 * trigger rebuilds, and export the graph to JSON.
 */
public final class KnowledgeGraphToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        KnowledgeGraphPanel panel = new KnowledgeGraphPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "", false);
        Disposer.register(content, panel);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
