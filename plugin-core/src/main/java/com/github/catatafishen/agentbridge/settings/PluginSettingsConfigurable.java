package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class PluginSettingsConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.agentbridge.settings";
    public static final String DISPLAY_NAME = "AgentBridge";

    @SuppressWarnings("unused")
    public PluginSettingsConfigurable(@NotNull Project ignoredProject) {
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
    public @NotNull JComponent createComponent() {
        String version = com.github.catatafishen.agentbridge.BuildInfo.getVersion();
        String hash = com.github.catatafishen.agentbridge.BuildInfo.getGitHash();

        JBLabel descLabel = new JBLabel(
            "<html>"
                + "AgentBridge connects IntelliJ IDE with AI coding agents via the "
                + "<b>Agent Coding Protocol (ACP)</b>. Agents gain live access to code intelligence, "
                + "refactoring, search, file editing, and build tools through the MCP server built "
                + "into the IDE.<br><br>"
                + "Supported clients: <b>GitHub Copilot</b>, <b>OpenCode</b>, <b>Claude Code</b>, "
                + "<b>Claude CLI</b>, <b>Junie</b>, <b>Kiro</b>."
                + "</html>");
        descLabel.setForeground(UIUtil.getContextHelpForeground());

        JBLabel versionLabel = new JBLabel("Version " + version + "  ·  " + hash);
        versionLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        versionLabel.setFont(JBUI.Fonts.smallFont());

        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(descLabel)
            .addVerticalGap(8)
            .addComponent(versionLabel)
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
        // Root page has no settings — sub-pages handle their own apply()
    }

    @Override
    public void reset() {
        // Root page has no settings — sub-pages handle their own reset()
    }
}
