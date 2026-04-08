package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.CleanupSettings;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class ChatInputConfigurable implements Configurable {

    private final Project project;

    private JBCheckBox showHintsCheckBox;
    private JBCheckBox smartPasteCheckBox;
    private JBCheckBox softWrapsCheckBox;
    private JSpinner smartPasteMinLinesSpinner;
    private JSpinner smartPasteMinCharsSpinner;
    private JComboBox<String> triggerCharCombo;
    private JBCheckBox followModeCheckbox;
    private JBCheckBox smoothScrollCheckbox;
    private JBCheckBox showTurnStatsCheckbox;

    private JSpinner scratchRetentionSpinner;
    private JCheckBox autoCloseTabsCheckbox;
    private JCheckBox closeRunningTerminalsCheckbox;

    public ChatInputConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "UI/UX";
    }

    @Override
    public @NotNull JComponent createComponent() {
        McpServerSettings mcpSettings = McpServerSettings.getInstance(project);
        CleanupSettings cleanupSettings = CleanupSettings.getInstance(project);

        showHintsCheckBox = new JBCheckBox("Show keyboard shortcut hints in prompt placeholder");
        smartPasteCheckBox = new JBCheckBox("Enable smart paste");
        softWrapsCheckBox = new JBCheckBox("Enable soft wraps in chat input");
        smartPasteMinLinesSpinner = new JSpinner(new SpinnerNumberModel(
            ChatInputSettings.DEFAULT_SMART_PASTE_MIN_LINES, 1, 100, 1));
        smartPasteMinCharsSpinner = new JSpinner(new SpinnerNumberModel(
            ChatInputSettings.DEFAULT_SMART_PASTE_MIN_CHARS, 50, 10_000, 50));
        triggerCharCombo = new JComboBox<>(new String[]{
            "# (VS Code style)", "@ (AI Assistant style)", "Disabled"
        });

        followModeCheckbox = new JBCheckBox(
            "Follow Agent — open files and highlight regions as the agent reads or edits them",
            ActiveAgentManager.getFollowAgentFiles(project));
        followModeCheckbox.setToolTipText(
            "Works independently of the connected agent — any external agent accessing "
                + "the MCP server will trigger follow-mode when this is enabled.");

        smoothScrollCheckbox = new JBCheckBox(
            "Enable smooth scrolling in chat panel",
            mcpSettings.isSmoothScrollEnabled());
        smoothScrollCheckbox.setToolTipText(
            "⚠ May cause screen tearing on some systems. Disable if you see visual artifacts while scrolling.");

        showTurnStatsCheckbox = new JBCheckBox(
            "Show turn stats below messages (duration, tokens, lines changed)",
            mcpSettings.isShowTurnStats());
        showTurnStatsCheckbox.setToolTipText(
            "Displays a summary footer below the last message of each agent turn. Disabling saves vertical space.");

        scratchRetentionSpinner = new JSpinner(new SpinnerNumberModel(
            cleanupSettings.getScratchRetentionHours(), 0, 8760, 1));

        autoCloseTabsCheckbox = new JCheckBox("Auto-close agent tabs between turns",
            cleanupSettings.isAutoCloseAgentTabs());

        closeRunningTerminalsCheckbox = new JCheckBox("Also close running terminal tabs",
            cleanupSettings.isAutoCloseRunningTerminals());
        closeRunningTerminalsCheckbox.setEnabled(cleanupSettings.isAutoCloseAgentTabs());

        autoCloseTabsCheckbox.addChangeListener(e ->
            closeRunningTerminalsCheckbox.setEnabled(autoCloseTabsCheckbox.isSelected()));

        JBLabel descLabel = new JBLabel(
            "<html>Appearance and interaction settings for the chat panel, "
                + "input area, and editor integration.</html>");
        descLabel.setForeground(UIUtil.getContextHelpForeground());

        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(descLabel)
            .addVerticalGap(8)
            .addSeparator(8)
            .addComponent(showHintsCheckBox)
            .addTooltip("Display Enter/Shift+Enter/Ctrl+Enter hints in the prompt placeholder text.")
            .addVerticalGap(4)
            .addSeparator(8)
            .addComponent(softWrapsCheckBox)
            .addTooltip("Wrap long lines in the chat input instead of scrolling horizontally.")
            .addVerticalGap(4)
            .addSeparator(8)
            .addComponent(smartPasteCheckBox)
            .addTooltip("Intercept large clipboard pastes to create scratch files or inline file references.")
            .addLabeledComponent("Min lines to trigger:", smartPasteMinLinesSpinner)
            .addTooltip("Clipboard content with more lines than this triggers Smart Paste.")
            .addLabeledComponent("Min characters to trigger:", smartPasteMinCharsSpinner)
            .addTooltip("Clipboard content with more characters than this triggers Smart Paste.")
            .addVerticalGap(4)
            .addSeparator(8)
            .addLabeledComponent("File search trigger:", triggerCharCombo)
            .addTooltip("Character that opens the file search popup in the chat input.")
            .addVerticalGap(4)
            .addSeparator(8)
            .addComponent(followModeCheckbox)
            .addVerticalGap(4)
            .addComponent(smoothScrollCheckbox)
            .addTooltip("⚠ May cause screen tearing on some systems")
            .addVerticalGap(4)
            .addComponent(showTurnStatsCheckbox)
            .addVerticalGap(4)
            .addSeparator(8)
            .addLabeledComponent("Scratch file retention (hours, 0 = forever):", scratchRetentionSpinner)
            .addComponent(autoCloseTabsCheckbox)
            .addComponent(closeRunningTerminalsCheckbox)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));

        smartPasteCheckBox.addActionListener(e -> updateSmartPasteSpinnerState());

        reset();
        return panel;
    }

    private void updateSmartPasteSpinnerState() {
        boolean enabled = smartPasteCheckBox.isSelected();
        smartPasteMinLinesSpinner.setEnabled(enabled);
        smartPasteMinCharsSpinner.setEnabled(enabled);
    }

    @Override
    public boolean isModified() {
        if (showHintsCheckBox == null) return false;
        ChatInputSettings s = ChatInputSettings.getInstance();
        if (showHintsCheckBox.isSelected() != s.isShowShortcutHints()) return true;
        if (softWrapsCheckBox.isSelected() != s.isSoftWrapsEnabled()) return true;
        if (smartPasteCheckBox.isSelected() != s.isSmartPasteEnabled()) return true;
        if ((int) smartPasteMinLinesSpinner.getValue() != s.getSmartPasteMinLines()) return true;
        if ((int) smartPasteMinCharsSpinner.getValue() != s.getSmartPasteMinChars()) return true;
        if (!selectedTriggerChar().equals(s.getFileSearchTrigger())) return true;
        if (followModeCheckbox.isSelected() != ActiveAgentManager.getFollowAgentFiles(project)) return true;
        McpServerSettings mcpSettings = McpServerSettings.getInstance(project);
        if (smoothScrollCheckbox.isSelected() != mcpSettings.isSmoothScrollEnabled()) return true;
        if (showTurnStatsCheckbox.isSelected() != mcpSettings.isShowTurnStats()) return true;
        CleanupSettings cleanupSettings = CleanupSettings.getInstance(project);
        if ((int) scratchRetentionSpinner.getValue() != cleanupSettings.getScratchRetentionHours()) return true;
        if (autoCloseTabsCheckbox.isSelected() != cleanupSettings.isAutoCloseAgentTabs()) return true;
        return closeRunningTerminalsCheckbox.isSelected() != cleanupSettings.isAutoCloseRunningTerminals();
    }

    @Override
    public void apply() {
        ChatInputSettings s = ChatInputSettings.getInstance();
        s.setShowShortcutHints(showHintsCheckBox.isSelected());
        s.setSoftWrapsEnabled(softWrapsCheckBox.isSelected());
        s.setSmartPasteEnabled(smartPasteCheckBox.isSelected());
        s.setSmartPasteMinLines((int) smartPasteMinLinesSpinner.getValue());
        s.setSmartPasteMinChars((int) smartPasteMinCharsSpinner.getValue());
        s.setFileSearchTrigger(selectedTriggerChar());

        ActiveAgentManager.setFollowAgentFiles(project, followModeCheckbox.isSelected());

        McpServerSettings mcpSettings = McpServerSettings.getInstance(project);
        mcpSettings.setSmoothScrollEnabled(smoothScrollCheckbox.isSelected());
        mcpSettings.setShowTurnStats(showTurnStatsCheckbox.isSelected());
        var chatPanel = ChatConsolePanel.Companion.getInstance(project);
        if (chatPanel != null) {
            chatPanel.setSmoothScroll(smoothScrollCheckbox.isSelected());
            chatPanel.setShowTurnStats(showTurnStatsCheckbox.isSelected());
        }
        var chatContent = com.github.catatafishen.agentbridge.ui.ChatToolWindowContent.Companion.getInstance(project);
        if (chatContent != null) {
            chatContent.setSoftWrapsEnabled(softWrapsCheckBox.isSelected());
        }

        CleanupSettings cleanupSettings = CleanupSettings.getInstance(project);
        cleanupSettings.setScratchRetentionHours((int) scratchRetentionSpinner.getValue());
        cleanupSettings.setAutoCloseAgentTabs(autoCloseTabsCheckbox.isSelected());
        cleanupSettings.setAutoCloseRunningTerminals(closeRunningTerminalsCheckbox.isSelected());
    }

    @Override
    public void reset() {
        ChatInputSettings s = ChatInputSettings.getInstance();
        showHintsCheckBox.setSelected(s.isShowShortcutHints());
        softWrapsCheckBox.setSelected(s.isSoftWrapsEnabled());
        smartPasteCheckBox.setSelected(s.isSmartPasteEnabled());
        smartPasteMinLinesSpinner.setValue(s.getSmartPasteMinLines());
        smartPasteMinCharsSpinner.setValue(s.getSmartPasteMinChars());
        selectTriggerChar(s.getFileSearchTrigger());
        updateSmartPasteSpinnerState();

        followModeCheckbox.setSelected(ActiveAgentManager.getFollowAgentFiles(project));
        McpServerSettings mcpSettings = McpServerSettings.getInstance(project);
        smoothScrollCheckbox.setSelected(mcpSettings.isSmoothScrollEnabled());
        showTurnStatsCheckbox.setSelected(mcpSettings.isShowTurnStats());

        CleanupSettings cleanupSettings = CleanupSettings.getInstance(project);
        scratchRetentionSpinner.setValue(cleanupSettings.getScratchRetentionHours());
        autoCloseTabsCheckbox.setSelected(cleanupSettings.isAutoCloseAgentTabs());
        closeRunningTerminalsCheckbox.setSelected(cleanupSettings.isAutoCloseRunningTerminals());
        closeRunningTerminalsCheckbox.setEnabled(cleanupSettings.isAutoCloseAgentTabs());
    }

    @Override
    public void disposeUIResources() {
        showHintsCheckBox = null;
        smartPasteCheckBox = null;
        softWrapsCheckBox = null;
        smartPasteMinLinesSpinner = null;
        smartPasteMinCharsSpinner = null;
        triggerCharCombo = null;
        followModeCheckbox = null;
        smoothScrollCheckbox = null;
        showTurnStatsCheckbox = null;
        scratchRetentionSpinner = null;
        autoCloseTabsCheckbox = null;
        closeRunningTerminalsCheckbox = null;
    }

    private String selectedTriggerChar() {
        int idx = triggerCharCombo == null ? 0 : triggerCharCombo.getSelectedIndex();
        return switch (idx) {
            case 1 -> "@";
            case 2 -> "";
            default -> "#";
        };
    }

    private void selectTriggerChar(String value) {
        if (triggerCharCombo == null) return;
        triggerCharCombo.setSelectedIndex(switch (value) {
            case "@" -> 1;
            case "" -> 2;
            default -> 0;
        });
    }
}
