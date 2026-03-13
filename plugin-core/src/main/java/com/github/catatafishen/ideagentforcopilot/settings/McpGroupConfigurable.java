package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Group node: Settings → Tools → IDE Agent for Copilot → MCP.
 * Contains child pages for Server and Tools.
 */
public final class McpGroupConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.mcp";

    public McpGroupConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "MCP";
    }

    @Override
    public @NotNull JComponent createComponent() {
        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>"
                    + "<b>Model Context Protocol (MCP)</b><br><br>"
                    + "Configure the MCP server that exposes IDE tools to the agent.<br><br>"
                    + "Use the sub-pages to manage:<br>"
                    + "&#8226; <b>Server</b> — port, transport mode, auto-start<br>"
                    + "&#8226; <b>Tools</b> — enable or disable individual tools"
                    + "</html>"))
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));
        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
        // group page — no settings
    }
}
