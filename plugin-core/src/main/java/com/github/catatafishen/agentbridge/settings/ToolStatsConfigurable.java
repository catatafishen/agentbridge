package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Settings page controlling the tool-call statistics database. Lives under
 * the Storage parent page; the underlying file location is governed by the
 * shared storage root configured there.
 */
public final class ToolStatsConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.agentbridge.storage.toolStats";

    private JBCheckBox toolStatsEnabledCb;
    private AgentBridgeStorageSettings settings;
    private JPanel mainPanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Tool Statistics";
    }

    @Override
    public @NotNull JComponent createComponent() {
        settings = AgentBridgeStorageSettings.getInstance();

        toolStatsEnabledCb = new JBCheckBox("Record tool call statistics");

        JBLabel toolStatsHint = new JBLabel(
            "<html><body style='width: 420px'>When enabled, every MCP tool call is logged to a per-project SQLite "
                + "database and surfaced in the Tool Statistics and Session Stats panels. "
                + "Disable to skip recording entirely (no data is collected).</body></html>");
        toolStatsHint.setForeground(UIUtil.getContextHelpForeground());
        toolStatsHint.setFont(JBUI.Fonts.smallFont());

        JBLabel locationHint = new JBLabel(
            "<html><body style='width: 420px'>The database file is stored under the storage root configured "
                + "on the parent <b>Storage</b> page.</body></html>");
        locationHint.setForeground(UIUtil.getContextHelpForeground());
        locationHint.setFont(JBUI.Fonts.smallFont());

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(toolStatsEnabledCb)
            .addComponent(toolStatsHint, 2)
            .addSeparator(12)
            .addComponent(locationHint, 2)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return toolStatsEnabledCb.isSelected() != settings.isToolStatsEnabled();
    }

    @Override
    public void apply() {
        settings.setToolStatsEnabled(toolStatsEnabledCb.isSelected());
    }

    @Override
    public void reset() {
        toolStatsEnabledCb.setSelected(settings.isToolStatsEnabled());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        toolStatsEnabledCb = null;
    }
}
