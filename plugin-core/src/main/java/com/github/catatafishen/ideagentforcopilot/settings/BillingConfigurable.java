package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.ui.CopilotBillingClient;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public final class BillingConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.billing";

    private JBCheckBox showCopilotUsageCb;
    private BillingSettings settings;
    private JBLabel statusLabel;
    private JPanel mainPanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Billing Data";
    }

    @Override
    public @NotNull JComponent createComponent() {
        settings = BillingSettings.getInstance();

        statusLabel = new JBLabel();

        JBLabel explanationLabel = new JBLabel(
            "<html><b>Why GitHub CLI is needed:</b><br/>" +
                "The Copilot ACP (Agent Communication Protocol) mode does not expose billing or usage data. " +
                "To view Copilot premium request usage, the plugin uses the GitHub CLI (<code>gh</code>) " +
                "to query GitHub's internal API endpoint.</html>");
        explanationLabel.setForeground(UIUtil.getContextHelpForeground());
        explanationLabel.setFont(JBUI.Fonts.smallFont());
        explanationLabel.setBorder(JBUI.Borders.emptyTop(4));

        HyperlinkLabel installLink = new HyperlinkLabel("Install GitHub CLI");
        installLink.setHyperlinkTarget("https://cli.github.com/");

        JBLabel installNote = new JBLabel(
            "<html>Install from <a href='https://cli.github.com/'>cli.github.com</a>, then authenticate with <code>gh auth login</code>.</html>");
        installNote.setForeground(UIUtil.getContextHelpForeground());
        installNote.setFont(JBUI.Fonts.smallFont());

        showCopilotUsageCb = new JBCheckBox("Show Copilot usage graph in toolbar");

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>Configure how billing and usage data is displayed in the IDE.</html>"))
            .addSeparator(8)
            .addLabeledComponent("GitHub CLI Status:", statusLabel)
            .addComponent(explanationLabel, 2)
            .addComponent(installNote, 2)
            .addComponent(installLink, 2)
            .addSeparator(12)
            .addComponent(showCopilotUsageCb)
            .addTooltip("Shows a usage graph icon in the main toolbar when Copilot usage data is available.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return showCopilotUsageCb.isSelected() != settings.isShowCopilotUsage();
    }

    @Override
    public void apply() {
        settings.setShowCopilotUsage(showCopilotUsageCb.isSelected());
    }

    @Override
    public void reset() {
        showCopilotUsageCb.setSelected(settings.isShowCopilotUsage());
        refreshGhCliStatusAsync();
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        showCopilotUsageCb = null;
        statusLabel = null;
    }

    private void refreshGhCliStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CopilotBillingClient client = new CopilotBillingClient();
            String ghCli = client.findGhCli();
            boolean authenticated = ghCli != null && client.isGhAuthenticated(ghCli);

            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (ghCli == null) {
                    statusLabel.setText("GitHub CLI not found — install from cli.github.com");
                    statusLabel.setForeground(Color.RED);
                } else if (!authenticated) {
                    statusLabel.setText("GitHub CLI found but not authenticated — run 'gh auth login'");
                    statusLabel.setForeground(new Color(255, 140, 0)); // Orange
                } else {
                    statusLabel.setText("✓ GitHub CLI authenticated");
                    statusLabel.setForeground(new Color(0, 128, 0)); // Green
                }
            });
        });
    }
}
