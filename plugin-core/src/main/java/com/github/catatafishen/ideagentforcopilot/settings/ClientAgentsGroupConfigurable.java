package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class ClientAgentsGroupConfigurable implements Configurable, Configurable.Composite {

    private final Project project;

    private JSpinner turnTimeoutSpinner;
    private JSpinner inactivityTimeoutSpinner;
    private JSpinner maxToolCallsSpinner;
    private JCheckBox branchSessionCheckbox;
    private JPanel panel;

    public ClientAgentsGroupConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Agents";
    }

    @Override
    public @Nullable JComponent createComponent() {
        turnTimeoutSpinner = new JSpinner(new SpinnerNumberModel(120, 1, 1440, 1));
        inactivityTimeoutSpinner = new JSpinner(new SpinnerNumberModel(300, 30, 86400, 30));
        maxToolCallsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 500, 1));

        Dimension spinnerSize = JBUI.size(100, turnTimeoutSpinner.getPreferredSize().height);
        turnTimeoutSpinner.setMaximumSize(spinnerSize);
        inactivityTimeoutSpinner.setMaximumSize(spinnerSize);
        maxToolCallsSpinner.setMaximumSize(spinnerSize);

        branchSessionCheckbox = new JCheckBox("Branch session at startup");

        JBLabel introLabel = new JBLabel(
            "<html>Global behavior settings for all agent sessions. "
                + "Configure individual agent clients in the sub-pages below.</html>");
        introLabel.setForeground(UIUtil.getContextHelpForeground());

        panel = FormBuilder.createFormBuilder()
            .addComponent(introLabel)
            .addSeparator(8)
            .addLabeledComponent("Turn timeout (minutes):", turnTimeoutSpinner)
            .addTooltip("Maximum wall-clock time allowed for a turn (1–1440 minutes).")
            .addLabeledComponent("Inactivity timeout (seconds):", inactivityTimeoutSpinner)
            .addTooltip("Maximum silence before a turn is considered stalled (30–86400 seconds).")
            .addLabeledComponent("Max tool calls per turn:", maxToolCallsSpinner)
            .addTooltip("Limit how many tools the agent can call in a single turn. 0 = unlimited.")
            .addSeparator(8)
            .addComponent(branchSessionCheckbox)
            .addTooltip("Snapshot the current session before each new session starts, "
                + "so you can restore it from the session history picker.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (turnTimeoutSpinner == null) return false;
        ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
        if ((int) turnTimeoutSpinner.getValue() != manager.getSharedTurnTimeoutMinutes()) return true;
        if ((int) inactivityTimeoutSpinner.getValue() != manager.getSharedInactivityTimeoutSeconds()) return true;
        if ((int) maxToolCallsSpinner.getValue() != manager.getSharedMaxToolCallsPerTurn()) return true;
        return branchSessionCheckbox.isSelected() != manager.isBranchSessionAtStartup();
    }

    @Override
    public void apply() {
        if (turnTimeoutSpinner == null) return;
        ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
        manager.setSharedTurnTimeoutMinutes((int) turnTimeoutSpinner.getValue());
        manager.setSharedInactivityTimeoutSeconds((int) inactivityTimeoutSpinner.getValue());
        manager.setSharedMaxToolCallsPerTurn((int) maxToolCallsSpinner.getValue());
        manager.setBranchSessionAtStartup(branchSessionCheckbox.isSelected());
    }

    @Override
    public void reset() {
        if (turnTimeoutSpinner == null) return;
        ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
        turnTimeoutSpinner.setValue(manager.getSharedTurnTimeoutMinutes());
        inactivityTimeoutSpinner.setValue(manager.getSharedInactivityTimeoutSeconds());
        maxToolCallsSpinner.setValue(manager.getSharedMaxToolCallsPerTurn());
        branchSessionCheckbox.setSelected(manager.isBranchSessionAtStartup());
    }

    @Override
    public void disposeUIResources() {
        turnTimeoutSpinner = null;
        inactivityTimeoutSpinner = null;
        maxToolCallsSpinner = null;
        branchSessionCheckbox = null;
        panel = null;
    }

    /**
     * Built-in agent pages are registered statically in plugin.xml — no dynamic children needed.
     */
    @Override
    public Configurable @NotNull [] getConfigurables() {
        return new Configurable[0];
    }
}
