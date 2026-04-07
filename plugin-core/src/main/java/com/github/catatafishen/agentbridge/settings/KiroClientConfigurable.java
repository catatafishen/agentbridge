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
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class KiroClientConfigurable implements Configurable {

    public static final int DEFAULT_CONTEXT_LIMIT_CHARS = 600_000;

    private static final String AGENT_ID = "kiro";

    @SuppressWarnings("unused")
    public KiroClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Kiro";
    }

    private JBLabel statusLabel;
    private JBTextField binaryPathField;
    private @Nullable ThemeColorComboBox bubbleColorCombo;
    private JSpinner contextLimitSpinner;
    private JPanel panel;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the kiro-cli binary. Leave empty to find it on PATH.");

        bubbleColorCombo = new ThemeColorComboBox();

        contextLimitSpinner = new JSpinner(new SpinnerNumberModel(
            new GenericSettings("kiro").getContextHistoryLimit(DEFAULT_CONTEXT_LIMIT_CHARS),
            0, 2_000_000, 50_000));
        contextLimitSpinner.setToolTipText(
            "<html>Maximum characters of conversation history exported to Kiro's session file.<br>"
                + "0 = unlimited. Reduce if Kiro reports context overflow. Default: 600 000.</html>");

        HyperlinkLabel docsLink = new HyperlinkLabel("Kiro CLI documentation at kiro.dev/docs/cli/acp");
        docsLink.setHyperlinkTarget("https://kiro.dev/docs/cli/acp/");

        JBLabel authNote = new JBLabel(
            "<html>Ensure <code>kiro-cli</code> is installed and available on your PATH.</html>");
        authNote.setForeground(UIUtil.getContextHelpForeground());
        authNote.setFont(JBUI.Fonts.smallFont());

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusLabel)
            .addComponent(authNote, 2)
            .addComponent(docsLink, 2)
            .addSeparator(8)
            .addLabeledComponent("Kiro binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Bubble color:", bubbleColorCombo)
            .addTooltip("Choose a theme-aware accent color for message bubbles when using Kiro.")
            .addLabeledComponent("Session history limit:", contextLimitSpinner)
            .addTooltip("<html>Maximum characters of conversation history exported to Kiro's session file.<br>0 = unlimited. Reduce if Kiro reports context overflow. Default: 600 000.</html>")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));
        return panel;
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
        panel = null;
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String version = new AcpClientBinaryResolver(AGENT_ID, "kiro-cli", "kiro").detectVersion();
            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (version != null) {
                    statusLabel.setText("✓ Kiro CLI found — " + version);
                    statusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    statusLabel.setText("Kiro CLI not found on PATH — install from kiro.dev");
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
