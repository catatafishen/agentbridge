package com.github.catatafishen.agentbridge.ui.graph;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the <b>Knowledge Graph</b> sidebar tool window with multiple tabs:
 * <ul>
 *   <li><b>Dashboard</b> — stats cards, hotspots, activity feed, settings</li>
 *   <li><b>Explorer</b> — searchable table of files with dependency/commit counts</li>
 * </ul>
 */
public final class KnowledgeGraphToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory cf = ContentFactory.getInstance();

        KnowledgeGraphDashboardPanel dashboard = new KnowledgeGraphDashboardPanel(project);
        Content dashboardContent = cf.createContent(dashboard.getComponent(), "Dashboard", false);
        Disposer.register(dashboardContent, dashboard);
        toolWindow.getContentManager().addContent(dashboardContent);

        KnowledgeGraphExplorerPanel explorer = new KnowledgeGraphExplorerPanel(project);
        Content explorerContent = cf.createContent(explorer.getComponent(), "Explorer", false);
        Disposer.register(explorerContent, explorer);
        toolWindow.getContentManager().addContent(explorerContent);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
