package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public final class CopilotClientConfigurable implements Configurable {

    private static final int SECTIONS =
        AcpProfileForm.SECTION_BINARY | AcpProfileForm.SECTION_ACP_ARGS
            | AcpProfileForm.SECTION_MCP | AcpProfileForm.SECTION_PRE_LAUNCH
            | AcpProfileForm.SECTION_AGENT_DIR | AcpProfileForm.SECTION_PERMISSIONS
            | AcpProfileForm.SECTION_FLAGS;

    @SuppressWarnings("unused")
    public CopilotClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "GitHub Copilot";
    }

    private JBLabel statusLabel;
    private AcpProfileForm form;
    private BillingConfigurable billingConfigurable;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        HyperlinkLabel installLink = new HyperlinkLabel("Install from github.com/github/copilot-cli");
        installLink.setHyperlinkTarget("https://github.com/github/copilot-cli#installation");

        JBLabel installNote = new JBLabel(
            "<html>Install with <code>npm install -g @github/copilot-cli</code>. Ensure it's available on PATH.</html>");
        installNote.setForeground(UIUtil.getContextHelpForeground());
        installNote.setFont(JBUI.Fonts.smallFont());

        JPanel statusPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusLabel)
            .addComponent(installNote, 2)
            .addComponent(installLink, 2)
            .addSeparator(8)
            .getPanel();

        form = new AcpProfileForm(SECTIONS);
        JPanel configPanel = form.buildPanel();

        JPanel configWithStatus = new JPanel(new BorderLayout(0, 8));
        configWithStatus.add(statusPanel, BorderLayout.NORTH);
        configWithStatus.add(configPanel, BorderLayout.CENTER);
        configWithStatus.setBorder(JBUI.Borders.empty(8));

        JBScrollPane configScroll = new JBScrollPane(configWithStatus);
        configScroll.setBorder(null);

        billingConfigurable = new BillingConfigurable();
        JComponent billingPanel = billingConfigurable.createComponent();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Configuration", configScroll);
        tabs.addTab("Billing Data", billingPanel);

        reset();
        return tabs;
    }

    @Override
    public boolean isModified() {
        if (form == null) return false;
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
        return (p != null && form.isModified(p))
            || (billingConfigurable != null && billingConfigurable.isModified());
    }

    @Override
    public void apply() {
        if (form != null) {
            AgentProfileManager mgr = AgentProfileManager.getInstance();
            AgentProfile p = mgr.getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
            if (p != null) {
                form.save(p);
                mgr.updateProfile(p);
            }
        }
        if (billingConfigurable != null) billingConfigurable.apply();
    }

    @Override
    public void reset() {
        if (form != null) {
            refreshStatusAsync();
            AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
            if (p != null) form.load(p);
        }
        if (billingConfigurable != null) billingConfigurable.reset();
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        form = null;
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
            String version = detectCopilotVersion();
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

    /**
     * Runs {@code copilot --version} or {@code copilot-cli --version} and returns the trimmed output,
     * or null if not installed.
     */
    @Nullable
    private static String detectCopilotVersion() {
        // Try copilot first, then copilot-cli
        for (String binary : new String[]{"copilot", "copilot-cli"}) {
            try {
                // Run through login shell to ensure PATH is fully loaded from shell profile
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd", "/c", binary + " --version")
                    : new ProcessBuilder("bash", "-c", binary + " --version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes()).trim();
                int exit = process.waitFor();
                if (exit == 0 && !output.isEmpty()) return output;
            } catch (IOException e) {
                // Try next binary
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
