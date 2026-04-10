package com.github.catatafishen.agentbridge.ui.statistics;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Dialog that hosts a {@link UsageStatisticsPanel} for viewing aggregated usage
 * statistics across all agent sessions.
 */
public class UsageStatisticsDialog extends DialogWrapper {

    private final Project project;

    public UsageStatisticsDialog(@NotNull Project project) {
        super(project, false);
        this.project = project;
        setTitle("Usage Statistics");
        setOKButtonText("Close");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return new UsageStatisticsPanel(project);
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
