package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

public final class ClaudeCliClientConfigurable implements Configurable {

    @SuppressWarnings("unused")
    public ClaudeCliClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Claude CLI";
    }

    private JBLabel authStatusLabel;
    private JBTextField binaryPathField;
    private JBTextField instructionsFileField;
    private JBTextArea customModelsArea;
    private JPanel panel;

    @Override
    public @NotNull JComponent createComponent() {
        authStatusLabel = new JBLabel();

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the claude binary. Leave empty to find it on PATH.");

        instructionsFileField = new JBTextField();
        instructionsFileField.getEmptyText().setText("E.g. CLAUDE.md");
        instructionsFileField.setToolTipText(
            "Path relative to project root. Plugin instructions are prepended to this file on each session start.");

        customModelsArea = new JBTextArea(4, 40);
        customModelsArea.setLineWrap(false);
        customModelsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().getSize()));
        JBScrollPane modelsScroll = new JBScrollPane(customModelsArea);
        modelsScroll.setPreferredSize(JBUI.size(400, 90));

        JBLabel authNote = new JBLabel(
            "<html>Run <code>claude auth login</code> in a terminal to authenticate.</html>");
        authNote.setForeground(UIUtil.getContextHelpForeground());
        authNote.setFont(JBUI.Fonts.smallFont());

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", authStatusLabel)
            .addComponent(authNote, 2)
            .addSeparator(8)
            .addLabeledComponent("Claude binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Instructions file:", instructionsFileField)
            .addTooltip("Plugin instructions are prepended here on session start (relative to project root).")
            .addSeparator(8)
            .addComponent(new JBLabel("Custom models (one per line):"))
            .addTooltip("Format: <model-id>=<Display Name>. Leave empty to use the built-in model list.")
            .addComponentFillVertically(modelsScroll, 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (customModelsArea == null) return false;
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.CLAUDE_CLI_PROFILE_ID);
        if (p == null) return false;
        return !parseModels().equals(p.getCustomCliModels())
            || !binaryPathField.getText().trim().equals(p.getCustomBinaryPath())
            || !instructionsFileField.getText().trim().equals(nullToEmpty(p.getPrependInstructionsTo()));
    }

    @Override
    public void apply() {
        if (customModelsArea == null) return;
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile p = mgr.getProfile(AgentProfileManager.CLAUDE_CLI_PROFILE_ID);
        if (p == null) return;
        p.setCustomCliModels(parseModels());
        p.setCustomBinaryPath(binaryPathField.getText().trim());
        p.setPrependInstructionsTo(instructionsFileField.getText().trim());
    }

    @Override
    public void reset() {
        if (customModelsArea == null) return;
        refreshAuthStatusAsync();
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.CLAUDE_CLI_PROFILE_ID);
        if (p == null) return;
        binaryPathField.setText(p.getCustomBinaryPath());
        instructionsFileField.setText(nullToEmpty(p.getPrependInstructionsTo()));
        customModelsArea.setText(String.join("\n", p.getCustomCliModels()));
        customModelsArea.setCaretPosition(0);
    }

    @Override
    public void disposeUIResources() {
        authStatusLabel = null;
        binaryPathField = null;
        instructionsFileField = null;
        customModelsArea = null;
        panel = null;
    }

    private void refreshAuthStatusAsync() {
        if (authStatusLabel == null) return;
        authStatusLabel.setText("Checking...");
        authStatusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String status = AgentProfileManager.getClaudeCliAuthStatus();
            SwingUtilities.invokeLater(() -> {
                if (authStatusLabel == null) return;
                if (status != null) {
                    authStatusLabel.setText("✓ Logged in — " + status);
                    authStatusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    authStatusLabel.setText("Not logged in — run 'claude auth login' in a terminal");
                    authStatusLabel.setForeground(Color.RED);
                }
            });
        });
    }

    @NotNull
    private List<String> parseModels() {
        if (customModelsArea == null) return List.of();
        return Arrays.stream(customModelsArea.getText().split("\n"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    @NotNull
    private static String nullToEmpty(@Nullable String s) {
        return s != null ? s : "";
    }
}
