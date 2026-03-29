package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient;
import com.github.catatafishen.ideagentforcopilot.ui.ThemeColor;
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
import java.io.File;

public final class CopilotClientConfigurable implements Configurable {

    private static final String AGENT_ID = "copilot";

    @SuppressWarnings("unused")
    public CopilotClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "GitHub Copilot";
    }

    private JBLabel statusLabel;
    private JBTextField binaryPathField;
    private @Nullable ThemeColorComboBox bubbleColorCombo;
    private BillingConfigurable billingConfigurable;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        JButton recheckButton = new JButton("Recheck");
        recheckButton.addActionListener(e -> refreshStatusAsync());
        recheckButton.setToolTipText("Check if the binary is available");

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.add(statusLabel);
        statusRow.add(recheckButton);

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the copilot binary. Leave empty to find it on PATH.");

        bubbleColorCombo = new ThemeColorComboBox();

        HyperlinkLabel installLink = new HyperlinkLabel("Install from github.com/github/copilot-cli");
        installLink.setHyperlinkTarget("https://github.com/github/copilot-cli#installation");

        JBLabel installNote = new JBLabel(
            "<html>Install with <code>npm install -g @github/copilot-cli</code>. Ensure it's available on PATH.</html>");
        installNote.setForeground(UIUtil.getContextHelpForeground());
        installNote.setFont(JBUI.Fonts.smallFont());

        JPanel configPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusRow)
            .addComponent(installNote, 2)
            .addComponent(installLink, 2)
            .addSeparator(8)
            .addLabeledComponent("Copilot binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Bubble color:", bubbleColorCombo)
            .addTooltip("Choose a theme-aware accent color for message bubbles when using GitHub Copilot.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        configPanel.setBorder(JBUI.Borders.empty(8));

        JBScrollPane configScroll = new JBScrollPane(configPanel);
        configScroll.setBorder(null);

        billingConfigurable = new BillingConfigurable();
        JComponent billingPanel = billingConfigurable.createComponent();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Configuration", configScroll);
        tabs.addTab("Billing Data", billingPanel);

        return tabs;
    }

    @Override
    public boolean isModified() {
        String stored = nullToEmpty(AcpClient.loadCustomBinaryPath(AGENT_ID));
        boolean binaryChanged = binaryPathField != null
            && !binaryPathField.getText().trim().equals(stored);
        if (binaryChanged) return true;
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            String key = tc != null ? tc.name() : null;
            if (!java.util.Objects.equals(key, AcpClient.loadAgentBubbleColorKey(AGENT_ID))) return true;
        }
        return billingConfigurable != null && billingConfigurable.isModified();
    }

    @Override
    public void apply() {
        if (binaryPathField != null) {
            AcpClient.saveCustomBinaryPath(AGENT_ID, binaryPathField.getText().trim());
        }
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            AcpClient.saveAgentBubbleColorKey(AGENT_ID, tc != null ? tc.name() : null);
        }
        if (billingConfigurable != null) billingConfigurable.apply();
    }

    @Override
    public void reset() {
        if (binaryPathField != null) {
            refreshStatusAsync();
            binaryPathField.setText(nullToEmpty(AcpClient.loadCustomBinaryPath(AGENT_ID)));
        }
        if (bubbleColorCombo != null) {
            bubbleColorCombo.setSelectedThemeColor(ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)));
        }
        if (billingConfigurable != null) billingConfigurable.reset();
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        binaryPathField = null;
        bubbleColorCombo = null;
        if (billingConfigurable != null) {
            billingConfigurable.disposeUIResources();
            billingConfigurable = null;
        }
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String customPath = binaryPathField != null ? binaryPathField.getText().trim() : null;
            if (customPath == null || customPath.isEmpty()) {
                customPath = AcpClient.loadCustomBinaryPath(AGENT_ID);
            }

            String version;
            if (customPath != null && !customPath.isEmpty()) {
                // Custom path provided - check if file exists first
                File file = new File(customPath);
                if (!file.exists()) {
                    String finalCustomPath = customPath;
                    SwingUtilities.invokeLater(() -> {
                        if (statusLabel == null) return;
                        statusLabel.setText("✗ File not found: " + finalCustomPath);
                        statusLabel.setForeground(Color.RED);
                    });
                    return;
                }
                if (!file.canExecute()) {
                    String finalCustomPath1 = customPath;
                    SwingUtilities.invokeLater(() -> {
                        if (statusLabel == null) return;
                        statusLabel.setText("✗ File not executable: " + finalCustomPath1);
                        statusLabel.setForeground(Color.RED);
                    });
                    return;
                }
                version = BinaryDetector.detectBinaryVersion(customPath, new String[0]);
            } else {
                // Auto-detect on PATH
                version = BinaryDetector.detectBinaryVersion(AGENT_ID, new String[]{"copilot-cli"});
            }

            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (version != null) {
                    statusLabel.setText("✓ GitHub Copilot found — " + version);
                    statusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    statusLabel.setText("GitHub Copilot not found on PATH — install from github.com/github/copilot-cli");
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
