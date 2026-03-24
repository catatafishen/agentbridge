package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.ui.PermissionsPanel;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Settings page wrapping the Tool Permissions panel.
 * Appears under Settings > Tools > AgentBridge > Tool Permissions.
 */
public final class ToolPermissionsConfigurable implements Configurable {

    private final Project project;
    private PermissionsPanel permissionsPanel;

    public ToolPermissionsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Tool Permissions";
    }

    @Override
    public @NotNull JComponent createComponent() {
        var settings = ActiveAgentManager.getInstance(project).getSettings();
        permissionsPanel = new PermissionsPanel(settings, ToolRegistry.getInstance(project));
        return permissionsPanel.getComponent();
    }

    @Override
    public boolean isModified() {
        return permissionsPanel != null && permissionsPanel.isModified();
    }

    @Override
    public void apply() {
        if (permissionsPanel != null) {
            permissionsPanel.save();
        }
    }

    @Override
    public void reset() {
        if (permissionsPanel != null) {
            permissionsPanel.reload();
        }
    }

    @Override
    public void disposeUIResources() {
        permissionsPanel = null;
    }
}
