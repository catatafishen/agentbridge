package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Root settings page for AgentBridge storage. Holds the shared storage root
 * directory; child pages (Tool Statistics, Memory, Chat History) configure
 * specific stores below it.
 *
 * <p>Addresses issue #351 — moves per-project AgentBridge data out of the
 * project tree and into a user-configurable location.</p>
 */
public final class AgentBridgeStorageConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.agentbridge.storage";

    private TextFieldWithBrowseButton storageRootField;
    private AgentBridgeStorageSettings settings;
    private JPanel mainPanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Storage";
    }

    @Override
    public @NotNull JComponent createComponent() {
        settings = AgentBridgeStorageSettings.getInstance();

        storageRootField = new TextFieldWithBrowseButton();
        configureBrowseButton(storageRootField);

        JButton resetDefaultBtn = new JButton("Reset to default");
        resetDefaultBtn.addActionListener(e -> storageRootField.setText(""));

        JPanel pathRow = new JPanel();
        pathRow.setLayout(new BoxLayout(pathRow, BoxLayout.X_AXIS));
        pathRow.add(storageRootField);
        pathRow.add(Box.createHorizontalStrut(6));
        pathRow.add(resetDefaultBtn);

        JBLabel defaultHint = new JBLabel(
            "<html><body style='width: 420px'>Default: <code>" + AgentBridgeStorageSettings.getDefaultStorageRoot()
                + "</code><br/>Per-project data lives under <code>&lt;root&gt;/projects/&lt;project-name&gt;-&lt;hash&gt;/</code>.</body></html>");
        defaultHint.setForeground(UIUtil.getContextHelpForeground());
        defaultHint.setFont(JBUI.Fonts.smallFont());

        JBLabel migrationNote = new JBLabel(
            "<html><body style='width: 420px'><b>Note:</b> if legacy data exists under "
                + "<code>{project}/.agentbridge/tool-stats.db</code> or <code>{project}/.agent-work/memory/</code>, "
                + "it is moved to the new location automatically on first use. "
                + "Changing the storage root takes effect on the next IDE restart.</body></html>");
        migrationNote.setForeground(UIUtil.getContextHelpForeground());
        migrationNote.setFont(JBUI.Fonts.smallFont());

        JBLabel childPagesHint = new JBLabel(
            "<html><body style='width: 420px'>Configure individual stores below: "
                + "<b>Tool Statistics</b>, <b>Memory</b>, and <b>Chat History</b>.</body></html>");
        childPagesHint.setForeground(UIUtil.getContextHelpForeground());
        childPagesHint.setFont(JBUI.Fonts.smallFont());

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html><body style='width: 420px'>Configure where AgentBridge stores per-project data files "
                    + "such as tool-call statistics and semantic memory.</body></html>"))
            .addSeparator(8)
            .addLabeledComponent("Storage root:", pathRow)
            .addComponent(defaultHint, 2)
            .addSeparator(12)
            .addComponent(migrationNote, 2)
            .addSeparator(12)
            .addComponent(childPagesHint, 2)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    private static void configureBrowseButton(@NotNull TextFieldWithBrowseButton field) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select AgentBridge Storage Directory");
        descriptor.setDescription(
            "AgentBridge per-project data files such as tool-call statistics and semantic memory will be stored under this directory.");
        field.addBrowseFolderListener(new TextBrowseFolderListener(descriptor));
    }

    @Override
    public boolean isModified() {
        String fieldText = storageRootField.getText().trim();
        String currentRoot = settings.getCustomStorageRoot();
        return !fieldText.equals(currentRoot == null ? "" : currentRoot);
    }

    @Override
    public void apply() {
        String path = storageRootField.getText().trim();
        settings.setCustomStorageRoot(path.isEmpty() ? null : path);
    }

    @Override
    public void reset() {
        String customRoot = settings.getCustomStorageRoot();
        storageRootField.setText(customRoot != null ? customRoot : "");
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        storageRootField = null;
    }
}
