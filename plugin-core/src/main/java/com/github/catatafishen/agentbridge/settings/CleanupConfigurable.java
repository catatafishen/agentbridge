package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.CleanupSettings;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Settings page for agent resource cleanup (scratch files and tool tabs).
 */
public final class CleanupConfigurable implements Configurable {

    private final Project project;
    private JSpinner scratchRetentionSpinner;
    private JCheckBox autoCloseTabsCheckbox;
    private JCheckBox closeRunningTerminalsCheckbox;

    public CleanupConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Cleanup";
    }

    @Override
    public @Nullable JComponent createComponent() {
        CleanupSettings settings = CleanupSettings.getInstance(project);

        scratchRetentionSpinner = new JSpinner(new SpinnerNumberModel(
            settings.getScratchRetentionHours(), 0, 8760, 1
        ));

        autoCloseTabsCheckbox = new JCheckBox("Auto-close agent tabs between turns",
            settings.isAutoCloseAgentTabs());

        closeRunningTerminalsCheckbox = new JCheckBox("Also close running terminal tabs",
            settings.isAutoCloseRunningTerminals());
        closeRunningTerminalsCheckbox.setEnabled(settings.isAutoCloseAgentTabs());

        autoCloseTabsCheckbox.addChangeListener(e ->
            closeRunningTerminalsCheckbox.setEnabled(autoCloseTabsCheckbox.isSelected()));

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Scratch file retention (hours, 0 = forever):", scratchRetentionSpinner)
            .addComponent(autoCloseTabsCheckbox)
            .addComponent(closeRunningTerminalsCheckbox)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    @Override
    public boolean isModified() {
        CleanupSettings settings = CleanupSettings.getInstance(project);
        return (int) scratchRetentionSpinner.getValue() != settings.getScratchRetentionHours()
            || autoCloseTabsCheckbox.isSelected() != settings.isAutoCloseAgentTabs()
            || closeRunningTerminalsCheckbox.isSelected() != settings.isAutoCloseRunningTerminals();
    }

    @Override
    public void apply() {
        CleanupSettings settings = CleanupSettings.getInstance(project);
        settings.setScratchRetentionHours((int) scratchRetentionSpinner.getValue());
        settings.setAutoCloseAgentTabs(autoCloseTabsCheckbox.isSelected());
        settings.setAutoCloseRunningTerminals(closeRunningTerminalsCheckbox.isSelected());
    }

    @Override
    public void reset() {
        CleanupSettings settings = CleanupSettings.getInstance(project);
        scratchRetentionSpinner.setValue(settings.getScratchRetentionHours());
        autoCloseTabsCheckbox.setSelected(settings.isAutoCloseAgentTabs());
        closeRunningTerminalsCheckbox.setSelected(settings.isAutoCloseRunningTerminals());
        closeRunningTerminalsCheckbox.setEnabled(settings.isAutoCloseAgentTabs());
    }
}
