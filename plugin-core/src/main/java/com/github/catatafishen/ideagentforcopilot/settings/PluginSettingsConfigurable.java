package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Parent settings group: Settings > Tools > IDE Agent for Copilot.
 * Child pages (Tool Permissions, Macro Tools, MCP Server) appear as sub-nodes.
 */
public final class PluginSettingsConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.settings";
    public static final String DISPLAY_NAME = "IDE Agent for Copilot";

    private final Project project;

    public PluginSettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Opens this settings page programmatically.
     */
    public static void open(@NotNull Project project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable.class);
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout());
        JBLabel label = new JBLabel(
            "<html><b>" + DISPLAY_NAME + "</b><br><br>"
                + "Configure the plugin using the sections in the tree on the left:<br><br>"
                + "<b>MCP</b> — MCP server, PSI Bridge, and tool registration<br>"
                + "<b>ACP</b> — agent settings, profiles, and tool permissions<br>"
                + "<b>Other</b> — scratch file types and project file shortcuts</html>");
        label.setBorder(JBUI.Borders.empty(12));
        panel.add(label, BorderLayout.NORTH);
        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
        // No settings on the parent page itself
    }
}
