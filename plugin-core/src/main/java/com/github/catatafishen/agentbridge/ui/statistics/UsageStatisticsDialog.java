package com.github.catatafishen.agentbridge.ui.statistics;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Tabbed dialog hosting usage charts and per-tool MCP call statistics.
 */
public class UsageStatisticsDialog extends DialogWrapper {

    private final Project project;

    public UsageStatisticsDialog(@NotNull Project project) {
        super(project, false);
        this.project = project;
        setModal(false);
        setTitle("Usage Statistics");
        setOKButtonText("Close");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Charts", new UsageStatisticsPanel(project));
        tabs.addTab("Tool Statistics", new ToolStatisticsPanel(project));
        return tabs;
    }

    @Override
    protected @NotNull String getDimensionServiceKey() {
        return "AgentBridge.UsageStatistics";
    }

    @Override
    public Dimension getPreferredSize() {
        return JBUI.size(900, 600);
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }
}
