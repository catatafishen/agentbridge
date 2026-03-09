package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.bridge.AgentMode;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings page for managing agent profiles.
 * Settings → Tools → IDE Agent for Copilot → Agent Profiles.
 */
public final class AgentProfilesConfigurable implements Configurable {

    private final Project project;

    private JPanel mainPanel;
    private DefaultListModel<ProfileListEntry> listModel;
    private JBList<ProfileListEntry> profileList;
    private JPanel editorPanel;
    private CardLayout editorCards;

    private JBTextField nameField;
    private JBTextField binaryNameField;
    private JBTextField alternateNamesField;
    private JBTextField installHintField;
    private JBTextField customBinaryPathField;
    private JBTextField acpArgsField;
    private JComboBox<McpInjectionMethod> mcpMethodCombo;
    private JBTextArea mcpConfigTemplateArea;
    private JBTextField mcpEnvVarNameField;
    private JBCheckBox supportsModelFlagCb;
    private JBCheckBox supportsConfigDirCb;
    private JBCheckBox supportsMcpConfigFlagCb;
    private JBCheckBox requiresResourceDuplicationCb;
    private JBTextField modelUsageFieldField;
    private JBTextField supportedModesField;
    private JBCheckBox ensureCopilotInstructionsCb;
    private JBCheckBox ensureCopilotAgentsCb;
    private JBCheckBox ensureClaudeInstructionsCb;

    private List<AgentProfile> workingCopies;
    private int currentIndex = -1;
    private boolean loading;

    public AgentProfilesConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Agent Profiles";
    }

    @Override
    public @Nullable JComponent createComponent() {
        workingCopies = new ArrayList<>();
        for (AgentProfile p : AgentProfileManager.getInstance().getAllProfiles()) {
            AgentProfile copy = p.duplicate();
            copy.setId(p.getId());
            copy.setBuiltIn(p.isBuiltIn());
            workingCopies.add(copy);
        }

        listModel = new DefaultListModel<>();
        refreshListModel();

        profileList = new JBList<>(listModel);
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.addListSelectionListener(this::onSelectionChanged);

        JPanel listPanel = buildListPanel();
        JPanel editor = buildEditorPanel();

        JBSplitter splitter = new JBSplitter(false, 0.3f);
        splitter.setFirstComponent(listPanel);
        splitter.setSecondComponent(editor);

        mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(splitter, BorderLayout.CENTER);
        mainPanel.setBorder(JBUI.Borders.empty(4));

        if (!listModel.isEmpty()) {
            profileList.setSelectedIndex(0);
        }

        return mainPanel;
    }

    private JPanel buildListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Profiles"));

        JBScrollPane scrollPane = new JBScrollPane(profileList);
        panel.add(scrollPane, BorderLayout.CENTER);

        JButton addBtn = new JButton("Add");
        addBtn.addActionListener(e -> addProfile());

        JButton duplicateBtn = new JButton("Duplicate");
        duplicateBtn.addActionListener(e -> duplicateProfile());

        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> removeProfile());

        JButton resetBtn = new JButton("Reset");
        resetBtn.setToolTipText("Reset selected built-in profile to factory defaults");
        resetBtn.addActionListener(e -> resetProfile());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(addBtn);
        buttons.add(duplicateBtn);
        buttons.add(removeBtn);
        buttons.add(resetBtn);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

    @NotNull
    private JPanel buildEditorPanel() {
        nameField = new JBTextField();
        binaryNameField = new JBTextField();
        alternateNamesField = new JBTextField();
        installHintField = new JBTextField();
        customBinaryPathField = new JBTextField();
        acpArgsField = new JBTextField();

        mcpMethodCombo = new JComboBox<>(McpInjectionMethod.values());
        mcpConfigTemplateArea = new JBTextArea(4, 40);
        mcpConfigTemplateArea.setLineWrap(true);
        mcpConfigTemplateArea.setWrapStyleWord(true);
        mcpEnvVarNameField = new JBTextField();

        supportsModelFlagCb = new JBCheckBox("Supports --model flag");
        supportsConfigDirCb = new JBCheckBox("Supports --config-dir flag");
        supportsMcpConfigFlagCb = new JBCheckBox("Supports --additional-mcp-config flag");
        requiresResourceDuplicationCb = new JBCheckBox("Requires resource content duplication");
        modelUsageFieldField = new JBTextField();
        supportedModesField = new JBTextField();

        ensureCopilotInstructionsCb = new JBCheckBox("Ensure Copilot instruction files on launch");
        ensureCopilotAgentsCb = new JBCheckBox("Ensure Copilot agents config on launch");
        ensureClaudeInstructionsCb = new JBCheckBox("Ensure Claude instruction files on launch");

        editorCards = new CardLayout();
        editorPanel = new JPanel(editorCards);

        JPanel emptyPanel = new JPanel(new BorderLayout());
        emptyPanel.add(new JBLabel("Select a profile to edit"), BorderLayout.CENTER);
        editorPanel.add(emptyPanel, "empty");

        JPanel form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Display name:", nameField)
            .addSeparator()
            .addComponent(new JBLabel("<html><b>Binary Discovery</b></html>"))
            .addLabeledComponent("Binary name:", binaryNameField)
            .addTooltip("Primary executable name to search for (e.g., \"copilot\", \"opencode\")")
            .addLabeledComponent("Alternate names (comma-separated):", alternateNamesField)
            .addTooltip("Fallback binary names if the primary is not found")
            .addLabeledComponent("Install hint:", installHintField)
            .addTooltip("Shown when the binary cannot be found")
            .addLabeledComponent("Custom binary path:", customBinaryPathField)
            .addTooltip("Override auto-discovery with an absolute path")
            .addSeparator()
            .addComponent(new JBLabel("<html><b>ACP Command</b></html>"))
            .addLabeledComponent("ACP args (space-separated):", acpArgsField)
            .addTooltip("Arguments to activate ACP mode (e.g., \"--acp --stdio\" or \"acp\")")
            .addSeparator()
            .addComponent(new JBLabel("<html><b>MCP Configuration</b></html>"))
            .addLabeledComponent("MCP injection method:", mcpMethodCombo)
            .addTooltip("How to tell the agent about the IDE's MCP server")
            .addLabeledComponent("MCP config template:", new JBScrollPane(mcpConfigTemplateArea))
            .addTooltip("JSON template. Placeholders: {mcpPort}, {mcpJarPath}, {javaPath}")
            .addLabeledComponent("Env var name (for ENV_VAR method):", mcpEnvVarNameField)
            .addSeparator()
            .addComponent(new JBLabel("<html><b>Feature Flags</b></html>"))
            .addComponent(supportsModelFlagCb)
            .addComponent(supportsConfigDirCb)
            .addComponent(supportsMcpConfigFlagCb)
            .addComponent(requiresResourceDuplicationCb)
            .addLabeledComponent("Model usage field:", modelUsageFieldField)
            .addTooltip("JSON field name in model metadata for usage info (e.g., \"copilotUsage\")")
            .addLabeledComponent("Supported modes (id:label;...):", supportedModesField)
            .addTooltip("Session modes like \"agent:Agent;plan:Plan\". Leave empty for no mode selector.")
            .addSeparator()
            .addComponent(new JBLabel("<html><b>Pre-launch Hooks</b></html>"))
            .addComponent(ensureCopilotInstructionsCb)
            .addComponent(ensureCopilotAgentsCb)
            .addComponent(ensureClaudeInstructionsCb)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

        JBScrollPane formScroll = new JBScrollPane(form);
        formScroll.setBorder(JBUI.Borders.empty());
        editorPanel.add(formScroll, "editor");

        editorCards.show(editorPanel, "empty");
        return editorPanel;
    }

    // ── List management ──────────────────────────────────────────────────────

    private void refreshListModel() {
        listModel.clear();
        for (int i = 0; i < workingCopies.size(); i++) {
            AgentProfile p = workingCopies.get(i);
            listModel.addElement(new ProfileListEntry(p.getDisplayName(), p.isBuiltIn(), i));
        }
    }

    private void addProfile() {
        AgentProfile newProfile = new AgentProfile();
        newProfile.setDisplayName("New Agent");
        workingCopies.add(newProfile);
        refreshListModel();
        profileList.setSelectedIndex(workingCopies.size() - 1);
    }

    private void duplicateProfile() {
        int idx = profileList.getSelectedIndex();
        if (idx < 0) return;
        AgentProfile source = workingCopies.get(idx);
        AgentProfile copy = source.duplicate();
        workingCopies.add(copy);
        refreshListModel();
        profileList.setSelectedIndex(workingCopies.size() - 1);
    }

    private void removeProfile() {
        int idx = profileList.getSelectedIndex();
        if (idx < 0) return;
        AgentProfile p = workingCopies.get(idx);
        if (p.isBuiltIn()) {
            JOptionPane.showMessageDialog(mainPanel,
                "Built-in profiles cannot be removed. Use 'Reset' to restore defaults.",
                "Cannot Remove", JOptionPane.WARNING_MESSAGE);
            return;
        }
        workingCopies.remove(idx);
        refreshListModel();
        if (!listModel.isEmpty()) {
            profileList.setSelectedIndex(Math.min(idx, listModel.size() - 1));
        }
    }

    private void resetProfile() {
        int idx = profileList.getSelectedIndex();
        if (idx < 0) return;
        AgentProfile p = workingCopies.get(idx);
        if (!p.isBuiltIn()) {
            JOptionPane.showMessageDialog(mainPanel,
                "Only built-in profiles can be reset to defaults.",
                "Cannot Reset", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        mgr.resetToDefaults(p.getId());
        AgentProfile fresh = mgr.getProfile(p.getId());
        if (fresh != null) {
            AgentProfile copy = fresh.duplicate();
            copy.setId(fresh.getId());
            copy.setBuiltIn(fresh.isBuiltIn());
            workingCopies.set(idx, copy);
            refreshListModel();
            profileList.setSelectedIndex(idx);
        }
    }

    // ── Editor load/save ─────────────────────────────────────────────────────

    private void onSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        saveCurrentToWorking();
        int idx = profileList.getSelectedIndex();
        if (idx >= 0 && idx < workingCopies.size()) {
            currentIndex = idx;
            loadProfileIntoEditor(workingCopies.get(idx));
            editorCards.show(editorPanel, "editor");
        } else {
            currentIndex = -1;
            editorCards.show(editorPanel, "empty");
        }
    }

    private void loadProfileIntoEditor(@NotNull AgentProfile p) {
        loading = true;
        try {
            nameField.setText(p.getDisplayName());
            binaryNameField.setText(p.getBinaryName());
            alternateNamesField.setText(String.join(", ", p.getAlternateNames()));
            installHintField.setText(p.getInstallHint());
            customBinaryPathField.setText(p.getCustomBinaryPath());
            acpArgsField.setText(String.join(" ", p.getAcpArgs()));
            mcpMethodCombo.setSelectedItem(p.getMcpMethod());
            mcpConfigTemplateArea.setText(p.getMcpConfigTemplate());
            mcpEnvVarNameField.setText(p.getMcpEnvVarName());
            supportsModelFlagCb.setSelected(p.isSupportsModelFlag());
            supportsConfigDirCb.setSelected(p.isSupportsConfigDir());
            supportsMcpConfigFlagCb.setSelected(p.isSupportsMcpConfigFlag());
            requiresResourceDuplicationCb.setSelected(p.isRequiresResourceDuplication());
            modelUsageFieldField.setText(p.getModelUsageField() != null ? p.getModelUsageField() : "");
            supportedModesField.setText(serializeModes(p.getSupportedModes()));
            ensureCopilotInstructionsCb.setSelected(p.isEnsureCopilotInstructions());
            ensureCopilotAgentsCb.setSelected(p.isEnsureCopilotAgents());
            ensureClaudeInstructionsCb.setSelected(p.isEnsureClaudeInstructions());
        } finally {
            loading = false;
        }
    }

    private void saveCurrentToWorking() {
        if (currentIndex < 0 || currentIndex >= workingCopies.size() || loading) return;
        AgentProfile p = workingCopies.get(currentIndex);
        p.setDisplayName(nameField.getText().trim());
        p.setBinaryName(binaryNameField.getText().trim());
        p.setAlternateNames(splitComma(alternateNamesField.getText()));
        p.setInstallHint(installHintField.getText().trim());
        p.setCustomBinaryPath(customBinaryPathField.getText().trim());
        p.setAcpArgs(splitSpace(acpArgsField.getText()));
        p.setMcpMethod((McpInjectionMethod) mcpMethodCombo.getSelectedItem());
        p.setMcpConfigTemplate(mcpConfigTemplateArea.getText().trim());
        p.setMcpEnvVarName(mcpEnvVarNameField.getText().trim());
        p.setSupportsModelFlag(supportsModelFlagCb.isSelected());
        p.setSupportsConfigDir(supportsConfigDirCb.isSelected());
        p.setSupportsMcpConfigFlag(supportsMcpConfigFlagCb.isSelected());
        p.setRequiresResourceDuplication(requiresResourceDuplicationCb.isSelected());
        String modelField = modelUsageFieldField.getText().trim();
        p.setModelUsageField(modelField.isEmpty() ? null : modelField);
        p.setSupportedModes(deserializeModes(supportedModesField.getText()));
        p.setEnsureCopilotInstructions(ensureCopilotInstructionsCb.isSelected());
        p.setEnsureCopilotAgents(ensureCopilotAgentsCb.isSelected());
        p.setEnsureClaudeInstructions(ensureClaudeInstructionsCb.isSelected());

        // Update list display name
        if (currentIndex < listModel.size()) {
            listModel.set(currentIndex,
                new ProfileListEntry(p.getDisplayName(), p.isBuiltIn(), currentIndex));
        }
    }

    // ── Configurable interface ───────────────────────────────────────────────

    @Override
    public boolean isModified() {
        saveCurrentToWorking();
        List<AgentProfile> persisted = AgentProfileManager.getInstance().getAllProfiles();
        if (workingCopies.size() != persisted.size()) return true;
        for (int i = 0; i < workingCopies.size(); i++) {
            if (!profileEquals(workingCopies.get(i), persisted.get(i))) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        saveCurrentToWorking();
        AgentProfileManager mgr = AgentProfileManager.getInstance();

        // Collect IDs to remove
        List<String> existingIds = mgr.getAllProfiles().stream()
            .map(AgentProfile::getId).toList();
        List<String> newIds = workingCopies.stream()
            .map(AgentProfile::getId).toList();

        for (String id : existingIds) {
            if (!newIds.contains(id)) {
                mgr.removeProfile(id);
            }
        }

        for (AgentProfile working : workingCopies) {
            AgentProfile existing = mgr.getProfile(working.getId());
            if (existing != null) {
                existing.copyFrom(working);
                mgr.updateProfile(existing);
            } else {
                AgentProfile fresh = new AgentProfile();
                fresh.setId(working.getId());
                fresh.copyFrom(working);
                mgr.addProfile(fresh);
            }
        }
    }

    @Override
    public void reset() {
        workingCopies = new ArrayList<>();
        for (AgentProfile p : AgentProfileManager.getInstance().getAllProfiles()) {
            AgentProfile copy = p.duplicate();
            copy.setId(p.getId());
            copy.setBuiltIn(p.isBuiltIn());
            workingCopies.add(copy);
        }
        refreshListModel();
        if (!listModel.isEmpty()) {
            profileList.setSelectedIndex(0);
        }
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        profileList = null;
        editorPanel = null;
        workingCopies = null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean profileEquals(@NotNull AgentProfile a, @NotNull AgentProfile b) {
        return a.getId().equals(b.getId())
            && a.getDisplayName().equals(b.getDisplayName())
            && a.getBinaryName().equals(b.getBinaryName())
            && a.getAlternateNames().equals(b.getAlternateNames())
            && a.getInstallHint().equals(b.getInstallHint())
            && a.getCustomBinaryPath().equals(b.getCustomBinaryPath())
            && a.getAcpArgs().equals(b.getAcpArgs())
            && a.getMcpMethod() == b.getMcpMethod()
            && a.getMcpConfigTemplate().equals(b.getMcpConfigTemplate())
            && a.getMcpEnvVarName().equals(b.getMcpEnvVarName())
            && a.isSupportsModelFlag() == b.isSupportsModelFlag()
            && a.isSupportsConfigDir() == b.isSupportsConfigDir()
            && a.isSupportsMcpConfigFlag() == b.isSupportsMcpConfigFlag()
            && a.isRequiresResourceDuplication() == b.isRequiresResourceDuplication()
            && java.util.Objects.equals(a.getModelUsageField(), b.getModelUsageField())
            && a.getSupportedModes().equals(b.getSupportedModes())
            && a.isEnsureCopilotInstructions() == b.isEnsureCopilotInstructions()
            && a.isEnsureCopilotAgents() == b.isEnsureCopilotAgents()
            && a.isEnsureClaudeInstructions() == b.isEnsureClaudeInstructions();
    }

    @NotNull
    private static List<String> splitComma(@NotNull String s) {
        List<String> result = new ArrayList<>();
        for (String part : s.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    @NotNull
    private static List<String> splitSpace(@NotNull String s) {
        List<String> result = new ArrayList<>();
        for (String part : s.split("\\s+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    @NotNull
    private static String serializeModes(@NotNull List<AgentMode> modes) {
        StringBuilder sb = new StringBuilder();
        for (AgentMode m : modes) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(m.id()).append(':').append(m.displayName());
        }
        return sb.toString();
    }

    @NotNull
    private static List<AgentMode> deserializeModes(@NotNull String s) {
        if (s.trim().isEmpty()) return new ArrayList<>();
        List<AgentMode> result = new ArrayList<>();
        for (String entry : s.split(";")) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                result.add(new AgentMode(parts[0].trim(), parts[1].trim()));
            }
        }
        return result;
    }

    private record ProfileListEntry(String displayName, boolean builtIn, int index) {
        @Override
        public String toString() {
            return builtIn ? displayName + " (built-in)" : displayName;
        }
    }
}
