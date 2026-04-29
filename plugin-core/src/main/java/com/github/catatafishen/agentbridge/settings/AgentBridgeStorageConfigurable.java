package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Settings page for AgentBridge plugin storage location and tool stats opt-out.
 * Addresses issue #351 — moves {@code tool-stats.db} out of the project tree
 * and into a user-configurable location.
 */
public final class AgentBridgeStorageConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.agentbridge.storage";

    private TextFieldWithBrowseButton storageRootField;
    private JBCheckBox toolStatsEnabledCb;
    private AgentBridgeStorageSettings settings;
    private JPanel mainPanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Storage";
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public @NotNull JComponent createComponent() {
        settings = AgentBridgeStorageSettings.getInstance();

        storageRootField = new TextFieldWithBrowseButton();
        storageRootField.addBrowseFolderListener(
            "Select AgentBridge Storage Directory",
            "All AgentBridge data (per-project tool-stats DB, embedding model cache, future plugin data) will be stored under this directory.",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor());
        storageRootField.getTextField().setColumns(40);

        JButton resetDefaultBtn = new JButton("Reset to default");
        resetDefaultBtn.addActionListener(e -> storageRootField.setText(""));

        JPanel pathRow = new JPanel();
        pathRow.setLayout(new BoxLayout(pathRow, BoxLayout.X_AXIS));
        pathRow.add(storageRootField);
        pathRow.add(Box.createHorizontalStrut(6));
        pathRow.add(resetDefaultBtn);

        JBLabel defaultHint = new JBLabel(
            "<html>Default: <code>" + AgentBridgeStorageSettings.getDefaultStorageRoot()
                + "</code><br/>Per-project data lives under <code>&lt;root&gt;/projects/&lt;project-name&gt;-&lt;hash&gt;/</code>.</html>");
        defaultHint.setForeground(UIUtil.getContextHelpForeground());
        defaultHint.setFont(JBUI.Fonts.smallFont());

        toolStatsEnabledCb = new JBCheckBox("Record tool call statistics");

        JBLabel toolStatsHint = new JBLabel(
            "<html>When enabled, every MCP tool call is logged to a per-project SQLite database "
                + "and surfaced in the Tool Statistics and Session Stats panels. "
                + "Disable to skip recording entirely (no data is collected).</html>");
        toolStatsHint.setForeground(UIUtil.getContextHelpForeground());
        toolStatsHint.setFont(JBUI.Fonts.smallFont());

        JBLabel migrationNote = new JBLabel(
            "<html><b>Note:</b> if a legacy <code>{project}/.agentbridge/tool-stats.db</code> "
                + "exists, it is moved to the new location automatically on first launch. "
                + "Changing the storage root takes effect on the next IDE restart.</html>");
        migrationNote.setForeground(UIUtil.getContextHelpForeground());
        migrationNote.setFont(JBUI.Fonts.smallFont());

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>Configure where AgentBridge stores per-project data files such as tool-call statistics.</html>"))
            .addSeparator(8)
            .addLabeledComponent("Storage root:", pathRow)
            .addComponent(defaultHint, 2)
            .addSeparator(12)
            .addComponent(toolStatsEnabledCb)
            .addComponent(toolStatsHint, 2)
            .addSeparator(12)
            .addComponent(migrationNote, 2)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        if (toolStatsEnabledCb.isSelected() != settings.isToolStatsEnabled()) return true;
        String fieldText = storageRootField.getText().trim();
        String currentRoot = settings.getCustomStorageRoot();
        return !fieldText.equals(currentRoot == null ? "" : currentRoot);
    }

    @Override
    public void apply() {
        settings.setToolStatsEnabled(toolStatsEnabledCb.isSelected());
        String path = storageRootField.getText().trim();
        settings.setCustomStorageRoot(path.isEmpty() ? null : path);
    }

    @Override
    public void reset() {
        toolStatsEnabledCb.setSelected(settings.isToolStatsEnabled());
        String customRoot = settings.getCustomStorageRoot();
        storageRootField.setText(customRoot != null ? customRoot : "");
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        storageRootField = null;
        toolStatsEnabledCb = null;
    }
}
