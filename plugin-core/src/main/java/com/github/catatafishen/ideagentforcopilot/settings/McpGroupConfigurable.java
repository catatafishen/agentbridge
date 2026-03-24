package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Group node: Settings → Tools → AgentBridge → MCP.
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
        JBLabel descLabel = new JBLabel(
            "<html>Configure the <b>MCP server</b> that exposes IDE tools to agents, "
                + "and manage which tools are available to them.</html>");
        descLabel.setForeground(UIUtil.getContextHelpForeground());

        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(descLabel)
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
