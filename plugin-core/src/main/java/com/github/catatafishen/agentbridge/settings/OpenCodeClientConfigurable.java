package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.acp.client.AcpClient;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.github.catatafishen.agentbridge.services.GenericSettings;
import com.github.catatafishen.agentbridge.ui.ThemeColor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings page for the OpenCode client (ACP transport).
 * Shows binary discovery status and optional binary path override.
 */
public final class OpenCodeClientConfigurable implements Configurable {

    public static final int DEFAULT_CONTEXT_LIMIT_CHARS = 0;

    private static final String AGENT_ID = "opencode";

    @SuppressWarnings("unused")
    public OpenCodeClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "OpenCode";
    }

    private JBLabel statusLabel;
    private JBTextField binaryPathField;
    private @Nullable ThemeColorComboBox bubbleColorCombo;
    private JSpinner contextLimitSpinner;
    private JPanel mainPanel;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the opencode binary. Leave empty to find it on PATH.");

        bubbleColorCombo = new ThemeColorComboBox();

        contextLimitSpinner = new JSpinner(new SpinnerNumberModel(
            new GenericSettings("opencode").getContextHistoryLimit(DEFAULT_CONTEXT_LIMIT_CHARS),
            0, 2_000_000, 50_000));
        contextLimitSpinner.setToolTipText(
            "<html>Maximum characters of conversation history exported to OpenCode's database.<br>"
                + "0 = unlimited. OpenCode handles context compaction internally;<br>"
                + "set only if you hit overflow errors. Default: unlimited (0).</html>");

        HyperlinkLabel installLink = new HyperlinkLabel("Install OpenCode from npmjs.com/package/opencode-ai");
        installLink.setHyperlinkTarget("https://www.npmjs.com/package/opencode-ai");

        JBLabel installNote = new JBLabel(
            "<html>Install with <code>npm i -g opencode-ai</code>. Ensure it's available on PATH.</html>");
        installNote.setForeground(UIUtil.getContextHelpForeground());
        installNote.setFont(JBUI.Fonts.smallFont());

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusLabel)
            .addComponent(installNote, 2)
            .addComponent(installLink, 2)
            .addSeparator(8)
            .addLabeledComponent("OpenCode binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Bubble color:", bubbleColorCombo)
            .addTooltip("Choose a theme-aware accent color for message bubbles when using OpenCode.")
            .addLabeledComponent("Session history limit:", contextLimitSpinner)
            .addTooltip("<html>0 = unlimited. OpenCode handles context compaction internally; set only if you hit overflow errors.</html>")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        JBScrollPane scroll = new JBScrollPane(mainPanel);
        scroll.setBorder(null);
        return scroll;
    }

    @Override
    public boolean isModified() {
        if (binaryPathField == null) return false;
        String stored = nullToEmpty(AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID));
        if (!binaryPathField.getText().trim().equals(stored)) return true;
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            String key = tc != null ? tc.name() : null;
            if (!java.util.Objects.equals(key, AcpClient.loadAgentBubbleColorKey(AGENT_ID))) return true;
        }
        if (contextLimitSpinner != null) {
            int storedLimit = new GenericSettings(AGENT_ID).getContextHistoryLimit(DEFAULT_CONTEXT_LIMIT_CHARS);
            if (!contextLimitSpinner.getValue().equals(storedLimit)) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        if (binaryPathField == null) return;
        AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, binaryPathField.getText().trim());
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            AcpClient.saveAgentBubbleColorKey(AGENT_ID, tc != null ? tc.name() : null);
        }
        if (contextLimitSpinner != null) {
            new GenericSettings(AGENT_ID).setContextHistoryLimit((Integer) contextLimitSpinner.getValue());
        }
    }

    @Override
    public void reset() {
        if (binaryPathField == null) return;
        refreshStatusAsync();
        binaryPathField.setText(nullToEmpty(AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID)));
        if (bubbleColorCombo != null) {
            bubbleColorCombo.setSelectedThemeColor(ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)));
        }
        if (contextLimitSpinner != null) {
            contextLimitSpinner.setValue(new GenericSettings(AGENT_ID).getContextHistoryLimit(DEFAULT_CONTEXT_LIMIT_CHARS));
        }
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        binaryPathField = null;
        bubbleColorCombo = null;
        contextLimitSpinner = null;
        mainPanel = null;
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String version = new AcpClientBinaryResolver(AGENT_ID, AGENT_ID).detectVersion();
            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (version != null) {
                    statusLabel.setText("✓ OpenCode found — " + version);
                    statusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    statusLabel.setText("OpenCode not found on PATH — install with npm i -g opencode-ai");
                    statusLabel.setForeground(Color.RED);
                }
            });
        });
    }

    @NotNull
    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
