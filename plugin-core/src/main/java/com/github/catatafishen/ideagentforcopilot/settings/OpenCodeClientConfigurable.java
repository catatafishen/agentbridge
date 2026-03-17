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

/**
 * Settings page for the OpenCode client (ACP transport).
 * Shows binary discovery, ACP args, MCP injection, permissions, and feature flags.
 */
public final class OpenCodeClientConfigurable implements Configurable {

    private static final int SECTIONS =
        AcpProfileForm.SECTION_BINARY | AcpProfileForm.SECTION_ACP_ARGS
            | AcpProfileForm.SECTION_MCP | AcpProfileForm.SECTION_PERMISSIONS
            | AcpProfileForm.SECTION_FLAGS;

    @SuppressWarnings("unused")
    public OpenCodeClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "OpenCode";
    }

    private JBLabel statusLabel;
    private AcpProfileForm form;
    private JPanel mainPanel;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        HyperlinkLabel installLink = new HyperlinkLabel("Install OpenCode from npmjs.com/package/opencode-ai");
        installLink.setHyperlinkTarget("https://www.npmjs.com/package/opencode-ai");

        JBLabel installNote = new JBLabel(
            "<html>Install with <code>npm i -g opencode-ai</code>. Ensure it's available on PATH.</html>");
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

        mainPanel = new JPanel(new BorderLayout(0, 8));
        mainPanel.add(statusPanel, BorderLayout.NORTH);
        mainPanel.add(configPanel, BorderLayout.CENTER);
        mainPanel.setBorder(JBUI.Borders.empty(8));

        JBScrollPane scroll = new JBScrollPane(mainPanel);
        scroll.setBorder(null);
        reset();
        return scroll;
    }

    @Override
    public boolean isModified() {
        if (form == null) return false;
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.OPENCODE_PROFILE_ID);
        return p != null && form.isModified(p);
    }

    @Override
    public void apply() {
        if (form == null) return;
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile p = mgr.getProfile(AgentProfileManager.OPENCODE_PROFILE_ID);
        if (p == null) return;
        form.save(p);
        mgr.updateProfile(p);
    }

    @Override
    public void reset() {
        if (form == null) return;
        refreshStatusAsync();
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.OPENCODE_PROFILE_ID);
        if (p != null) form.load(p);
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        form = null;
        mainPanel = null;
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String version = detectOpenCodeVersion();
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

    /**
     * Runs {@code opencode --version} and returns the trimmed output, or null if not installed.
     */
    @Nullable
    private static String detectOpenCodeVersion() {
        try {
            // Run through login shell to ensure PATH is fully loaded from shell profile
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd", "/c", "opencode --version")
                : new ProcessBuilder("bash", "-c", "opencode --version 2>/dev/null");

            processBuilderRedirectError(pb);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exit = process.waitFor();
            if (exit == 0 && !output.isEmpty()) {
                // Filter out any remaining shell noise
                return output.lines()
                    .filter(l -> !l.toLowerCase().contains("bash:"))
                    .findFirst()
                    .orElse(output)
                    .trim();
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static void processBuilderRedirectError(ProcessBuilder pb) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            pb.redirectErrorStream(true);
        } else {
            // On Linux/Mac, we want to keep stderr separate to avoid bash noise
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
    }
}
