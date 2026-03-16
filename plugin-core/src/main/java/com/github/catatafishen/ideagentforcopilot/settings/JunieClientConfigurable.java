package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public final class JunieClientConfigurable implements Configurable {

    @SuppressWarnings("unused")
    public JunieClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Junie";
    }

    private JBLabel statusLabel;
    private JBTextField binaryPathField;
    private JBTextField instructionsFileField;
    private JPanel panel;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the junie binary. Leave empty to find it on PATH.");

        instructionsFileField = new JBTextField();
        instructionsFileField.getEmptyText().setText("E.g. AGENTS.md");
        instructionsFileField.setToolTipText(
            "File relative to project root. Plugin instructions are prepended here on each session start.");

        HyperlinkLabel authLink = new HyperlinkLabel("Manage your Junie account at junie.jetbrains.com/cli");
        authLink.setHyperlinkTarget("https://junie.jetbrains.com/cli");

        JBLabel authNote = new JBLabel(
            "<html>Run <code>junie</code> in a terminal and use the "
                + "<code>/account</code> command to log in with your JetBrains Account "
                + "or a <code>JUNIE_API_KEY</code> token.</html>");
        authNote.setForeground(UIUtil.getContextHelpForeground());
        authNote.setFont(JBUI.Fonts.smallFont());

        JBLabel toolWarning = new JBLabel(
            "<html><b>⚠ Tool Selection Limitation:</b> Junie ignores <code>excludedTools</code> and does not send "
                + "<code>request_permission</code> for any tools. Built-in tools (Edit, View, Bash) may bypass "
                + "IntelliJ's editor buffer. The plugin uses prompt engineering to encourage MCP tool usage, but "
                + "compliance depends on the LLM. See <code>docs/JUNIE-TOOL-WORKAROUND.md</code> for details.</html>");
        toolWarning.setForeground(new Color(184, 134, 11)); // Dark goldenrod for warning
        toolWarning.setFont(JBUI.Fonts.smallFont());

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusLabel)
            .addComponent(authNote, 2)
            .addComponent(authLink, 2)
            .addSeparator(8)
            .addComponent(toolWarning, 2)
            .addSeparator(8)
            .addLabeledComponent("Junie binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Instructions file:", instructionsFileField)
            .addTooltip("Plugin instructions are prepended here on session start (relative to project root).")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (binaryPathField == null) return false;
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.JUNIE_PROFILE_ID);
        if (p == null) return false;
        return !binaryPathField.getText().trim().equals(nullToEmpty(p.getCustomBinaryPath()))
            || !instructionsFileField.getText().trim().equals(nullToEmpty(p.getPrependInstructionsTo()));
    }

    @Override
    public void apply() {
        if (binaryPathField == null) return;
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile p = mgr.getProfile(AgentProfileManager.JUNIE_PROFILE_ID);
        if (p == null) return;
        p.setCustomBinaryPath(binaryPathField.getText().trim());
        p.setPrependInstructionsTo(instructionsFileField.getText().trim());
        mgr.updateProfile(p);
    }

    @Override
    public void reset() {
        if (binaryPathField == null) return;
        refreshStatusAsync();
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.JUNIE_PROFILE_ID);
        if (p == null) return;
        binaryPathField.setText(nullToEmpty(p.getCustomBinaryPath()));
        instructionsFileField.setText(nullToEmpty(p.getPrependInstructionsTo()));
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        binaryPathField = null;
        instructionsFileField = null;
        panel = null;
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String version = detectJunieVersion();
            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (version != null) {
                    statusLabel.setText("✓ Junie CLI found — " + version);
                    statusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    statusLabel.setText("Junie CLI not found on PATH — install from junie.jetbrains.com");
                    statusLabel.setForeground(Color.RED);
                }
            });
        });
    }

    /**
     * Runs {@code junie --version} and returns the trimmed output, or null if not installed.
     */
    @Nullable
    private static String detectJunieVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("junie", "--version");
            pb.redirectErrorStream(true);
            // Use the user's actual shell environment to ensure PATH is correct
            pb.environment().putAll(EnvironmentUtil.getEnvironmentMap());
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exit = process.waitFor();
            if (exit == 0 && !output.isEmpty()) return output;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @NotNull
    private static String nullToEmpty(@Nullable String s) {
        return s != null ? s : "";
    }
}
