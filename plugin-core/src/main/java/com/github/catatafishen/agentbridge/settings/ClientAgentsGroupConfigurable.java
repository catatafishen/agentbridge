package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings page: Settings → Tools → AgentBridge → Agents.
 * <p>
 * Global behavior settings for all agent sessions (timeouts, tool-call limits,
 * branch-at-startup) and custom startup instructions that are injected into
 * every new session.
 */
public final class ClientAgentsGroupConfigurable implements Configurable {

    private final Project project;

    private JSpinner turnTimeoutSpinner;
    private JSpinner inactivityTimeoutSpinner;
    private JSpinner maxToolCallsSpinner;
    private JCheckBox branchSessionCheckbox;

    private JBCheckBox useCustomInstructionsCheckbox;
    private JBTextArea instructionsArea;

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

        // Build the top section (session behavior)
        JPanel behaviorPanel = FormBuilder.createFormBuilder()
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
            .getPanel();

        // Build the instructions section
        JPanel instructionsPanel = createInstructionsPanel();

        panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.add(behaviorPanel, BorderLayout.NORTH);
        panel.add(instructionsPanel, BorderLayout.CENTER);

        reset();
        return panel;
    }

    private JPanel createInstructionsPanel() {
        useCustomInstructionsCheckbox = new JBCheckBox("Use custom startup instructions");
        useCustomInstructionsCheckbox.addActionListener(e -> updateInstructionsState());

        JBLabel infoLabel = new JBLabel(
            "<html><i>Sent to all agents at the start of each session. "
                + "Changes take effect for new sessions only.</i></html>");
        infoLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());

        instructionsArea = new JBTextArea();
        instructionsArea.setFont(JBUI.Fonts.create("monospace", 12));
        instructionsArea.setRows(12);
        instructionsArea.setWrapStyleWord(true);
        instructionsArea.setLineWrap(true);

        JBScrollPane scrollPane = new JBScrollPane(instructionsArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JButton resetButton = new JButton("Reset to Default");
        resetButton.addActionListener(e -> resetInstructionsToDefault());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        buttonPanel.add(resetButton);

        JPanel instructionsContent = new JPanel(new BorderLayout(0, 4));
        instructionsContent.add(useCustomInstructionsCheckbox, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 4));
        centerPanel.add(infoLabel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        centerPanel.add(buttonPanel, BorderLayout.SOUTH);
        instructionsContent.add(centerPanel, BorderLayout.CENTER);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(IdeBorderFactory.createTitledBorder("Agent Instructions"));
        wrapper.add(instructionsContent, BorderLayout.CENTER);
        return wrapper;
    }

    private void updateInstructionsState() {
        StartupInstructionsSettings settings = StartupInstructionsSettings.getInstance();
        boolean useCustom = useCustomInstructionsCheckbox.isSelected();
        instructionsArea.setEnabled(useCustom);

        if (!useCustom) {
            instructionsArea.setText(settings.getDefaultTemplate());
            instructionsArea.setEditable(false);
            instructionsArea.setBackground(UIManager.getColor("TextField.disabledBackground"));
        } else {
            instructionsArea.setEditable(true);
            instructionsArea.setBackground(UIManager.getColor("TextField.background"));
        }
    }

    private void resetInstructionsToDefault() {
        instructionsArea.setText(StartupInstructionsSettings.getInstance().getDefaultTemplate());
        useCustomInstructionsCheckbox.setSelected(false);
        updateInstructionsState();
    }

    @Override
    public boolean isModified() {
        if (turnTimeoutSpinner == null) return false;
        ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
        if ((int) turnTimeoutSpinner.getValue() != manager.getSharedTurnTimeoutMinutes()) return true;
        if ((int) inactivityTimeoutSpinner.getValue() != manager.getSharedInactivityTimeoutSeconds()) return true;
        if ((int) maxToolCallsSpinner.getValue() != manager.getSharedMaxToolCallsPerTurn()) return true;
        if (branchSessionCheckbox.isSelected() != manager.isBranchSessionAtStartup()) return true;

        StartupInstructionsSettings instrSettings = StartupInstructionsSettings.getInstance();
        if (useCustomInstructionsCheckbox.isSelected() != instrSettings.isUsingCustomInstructions()) return true;
        if (!useCustomInstructionsCheckbox.isSelected()) return false;

        String current = instructionsArea.getText().trim();
        String stored = instrSettings.getCustomInstructions();
        stored = stored != null ? stored.trim() : "";
        return !current.equals(stored);
    }

    @Override
    public void apply() {
        if (turnTimeoutSpinner == null) return;
        ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
        manager.setSharedTurnTimeoutMinutes((int) turnTimeoutSpinner.getValue());
        manager.setSharedInactivityTimeoutSeconds((int) inactivityTimeoutSpinner.getValue());
        manager.setSharedMaxToolCallsPerTurn((int) maxToolCallsSpinner.getValue());
        manager.setBranchSessionAtStartup(branchSessionCheckbox.isSelected());

        StartupInstructionsSettings instrSettings = StartupInstructionsSettings.getInstance();
        if (useCustomInstructionsCheckbox.isSelected()) {
            String text = instructionsArea.getText();
            instrSettings.setCustomInstructions(text.trim().isEmpty() ? null : text);
        } else {
            instrSettings.setCustomInstructions(null);
        }
    }

    @Override
    public void reset() {
        if (turnTimeoutSpinner == null) return;
        ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
        turnTimeoutSpinner.setValue(manager.getSharedTurnTimeoutMinutes());
        inactivityTimeoutSpinner.setValue(manager.getSharedInactivityTimeoutSeconds());
        maxToolCallsSpinner.setValue(manager.getSharedMaxToolCallsPerTurn());
        branchSessionCheckbox.setSelected(manager.isBranchSessionAtStartup());

        StartupInstructionsSettings instrSettings = StartupInstructionsSettings.getInstance();
        boolean usingCustom = instrSettings.isUsingCustomInstructions();
        useCustomInstructionsCheckbox.setSelected(usingCustom);
        if (usingCustom) {
            instructionsArea.setText(instrSettings.getCustomInstructions());
        } else {
            instructionsArea.setText(instrSettings.getDefaultTemplate());
        }
        updateInstructionsState();
    }

    @Override
    public void disposeUIResources() {
        turnTimeoutSpinner = null;
        inactivityTimeoutSpinner = null;
        maxToolCallsSpinner = null;
        branchSessionCheckbox = null;
        useCustomInstructionsCheckbox = null;
        instructionsArea = null;
        panel = null;
    }
}
