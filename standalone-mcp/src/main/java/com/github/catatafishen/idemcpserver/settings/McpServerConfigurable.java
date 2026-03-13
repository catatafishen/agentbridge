package com.github.catatafishen.idemcpserver.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Parent settings group: Settings → Tools → IDE Agent for Copilot → MCP Server.
 * Children: General, Tools.
 */
public final class McpServerConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.idemcpserver";

    // S1172: The Project parameter is required by IntelliJ's <projectConfigurable> instantiation mechanism.
    @SuppressWarnings("java:S1172")
    public McpServerConfigurable(@NotNull Project project) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "MCP Server";
    }

    @Override
    public @NotNull JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout());
        JBLabel label = new JBLabel(
            "<html><b>MCP Server</b><br><br>"
                + "Configure the MCP server using the sub-pages:<br>"
                + "• <b>General</b> — port, auto-start, follow agent, server controls<br>"
                + "• <b>Tools</b> — enable/disable individual MCP tools</html>");
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
        // No mutable settings on this parent page — sub-pages handle their own apply()
    }
}
