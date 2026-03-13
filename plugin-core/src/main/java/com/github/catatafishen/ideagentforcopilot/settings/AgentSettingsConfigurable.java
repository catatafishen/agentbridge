package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.github.catatafishen.ideagentforcopilot.services.AgentUiSettings;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Settings page for the currently active agent's configuration.
 * Adapts dynamically to whichever agent is selected in {@link ActiveAgentManager}.
 * <p>
 * Appears under Settings → Tools → IDE Agent for Copilot → ACP → Agent Settings.
 */
public final class AgentSettingsConfigurable implements Configurable {

    private final Project project;
    private JSpinner timeoutSpinner;
    private JSpinner maxToolCallsSpinner;
    private JPanel mainPanel;

    public AgentSettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Agent Settings";
    }

    @Override
    public @NotNull JComponent createComponent() {
        var agentManager = ActiveAgentManager.getInstance(project);
        String agentName = agentManager.getActiveProfile().getDisplayName();

        timeoutSpinner = new JSpinner(new SpinnerNumberModel(300, 30, 3600, 10));
        maxToolCallsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 500, 1));

        // Constrain spinners to a reasonable width so they don't stretch to fill the label column
        Dimension spinnerSize = JBUI.size(100, timeoutSpinner.getPreferredSize().height);
        timeoutSpinner.setMaximumSize(spinnerSize);
        maxToolCallsSpinner.setMaximumSize(spinnerSize);

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel("<html>Settings for the <b>" + agentName + "</b> agent. "
                + "Switch agents by disconnecting and reconnecting from the Connect screen.</html>"))
            .addSeparator()
            .addLabeledComponent("Prompt timeout (seconds):", timeoutSpinner)
            .addTooltip("Time before an inactive agent session is considered timed out (30–3600).")
            .addLabeledComponent("Max tool calls per turn:", maxToolCallsSpinner)
            .addTooltip("Limit how many tools the agent can call in a single turn. 0 = unlimited.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        AgentUiSettings settings = ActiveAgentManager.getInstance(project).getSettings();
        if ((int) timeoutSpinner.getValue() != settings.getPromptTimeout()) return true;
        return (int) maxToolCallsSpinner.getValue() != settings.getMaxToolCallsPerTurn();
    }

    @Override
    public void apply() {
        AgentUiSettings settings = ActiveAgentManager.getInstance(project).getSettings();
        settings.setPromptTimeout((int) timeoutSpinner.getValue());
        settings.setMaxToolCallsPerTurn((int) maxToolCallsSpinner.getValue());
    }

    @Override
    public void reset() {
        AgentUiSettings settings = ActiveAgentManager.getInstance(project).getSettings();
        timeoutSpinner.setValue(settings.getPromptTimeout());
        maxToolCallsSpinner.setValue(settings.getMaxToolCallsPerTurn());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        timeoutSpinner = null;
        maxToolCallsSpinner = null;
    }
}
