package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.bridge.AnthropicKeyStore;
import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Settings page for managing agent profiles.
 * Settings → Tools → IDE Agent for Copilot → Agent Profiles.
 */
public final class AgentProfilesConfigurable implements Configurable {

    private static final String EMPTY_CARD = "empty";

    private JBPanel<?> mainPanel;
    private DefaultListModel<ProfileListEntry> listModel;
    private JBList<ProfileListEntry> profileList;
    private JPanel editorPanel;
    private CardLayout editorCards;

    // ── General tab ──
    private JBTextField nameField;
    private JBTextArea descriptionArea;
    private JBTextField binaryNameField;
    private JBTextField alternateNamesField;
    private JBTextField installHintField;
    private JBTextField customBinaryPathField;

    // ── ACP & Launch tab ──
    private JBTextField acpArgsField;
    private JBTextField prependInstructionsToField;
    private JBTextField bundledAgentFilesField;

    // ── MCP tab ──
    private ComboBox<McpInjectionMethod> mcpMethodCombo;
    private JBTextArea mcpConfigTemplateArea;
    private JBTextField mcpEnvVarNameField;

    // ── Advanced tab ──
    private JBCheckBox supportsModelFlagCb;
    private JBCheckBox supportsConfigDirCb;
    private JBCheckBox supportsMcpConfigFlagCb;
    private JBCheckBox requiresResourceDuplicationCb;
    private JBTextField modelUsageFieldField;
    private JBTextField agentsDirectoryField;

    // ── Permissions tab ──
    private JBCheckBox usePluginPermissionsCb;
    private JBCheckBox excludeAgentBuiltInToolsCb;

    // ── Claude Code (direct API) ──
    private JPasswordField anthropicApiKeyField;
    private JPanel anthropicApiKeySection;

    private List<AgentProfile> workingCopies;
    private int currentIndex = -1;
    private boolean loading;

    // Profiles are application-scoped; no per-project state needed.

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Agent Profiles";
    }

    @Override
    public @NotNull JComponent createComponent() {
        workingCopies = new ArrayList<>();
        for (AgentProfile p : AgentProfileManager.getInstance().getAllProfiles()) {
            workingCopies.add(p.copyForEditing());
        }

        listModel = new DefaultListModel<>();
        refreshListModel();

        profileList = new JBList<>(listModel);
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setCellRenderer(SimpleListCellRenderer.create((label, entry, index) -> {
            label.setText(entry.toString());
            label.setIcon(entry.builtIn() ? AllIcons.Nodes.Plugin : AllIcons.Nodes.Function);
        }));
        profileList.addListSelectionListener(this::onSelectionChanged);

        JPanel listPanel = buildListPanel();
        JPanel editor = buildEditorPanel();

        JBSplitter splitter = new JBSplitter(false, 0.3f);
        splitter.setShowDividerControls(true);
        splitter.setFirstComponent(listPanel);
        splitter.setSecondComponent(editor);

        mainPanel = new JBPanel<>(new BorderLayout());
        mainPanel.add(splitter, BorderLayout.CENTER);
        mainPanel.setBorder(JBUI.Borders.empty(4));

        if (!listModel.isEmpty()) {
            profileList.setSelectedIndex(0);
        }

        return mainPanel;
    }

    private JPanel buildListPanel() {
        JPanel decoratorPanel = ToolbarDecorator.createDecorator(profileList)
            .setAddAction(button -> addProfile())
            .setRemoveAction(button -> removeProfile())
            .addExtraAction(new AnAction("Duplicate", "Duplicate selected profile", AllIcons.Actions.Copy) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    duplicateProfile();
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }
            })
            .addExtraAction(new AnAction("Reset to Defaults",
                "Reset selected built-in profile to factory defaults", AllIcons.Actions.Rollback) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    resetProfile();
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }
            })
            .createPanel();

        JPanel panel = new JBPanel<>(new BorderLayout());
        panel.setBorder(IdeBorderFactory.createTitledBorder("Profiles"));
        panel.add(decoratorPanel, BorderLayout.CENTER);
        return panel;
    }

    @NotNull
    private JPanel buildEditorPanel() {
        nameField = new JBTextField();
        nameField.getEmptyText().setText("Profile display name");
        descriptionArea = new JBTextArea(3, 0);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setEditable(false);
        descriptionArea.setOpaque(false);
        descriptionArea.setBackground(UIUtil.getPanelBackground());
        descriptionArea.setFont(UIUtil.getLabelFont());
        binaryNameField = new JBTextField();
        binaryNameField.getEmptyText().setText("e.g. copilot");
        alternateNamesField = new JBTextField();
        alternateNamesField.getEmptyText().setText("Comma-separated, e.g. gh, github-copilot");
        installHintField = new JBTextField();
        installHintField.getEmptyText().setText("Shown when binary is not found");
        customBinaryPathField = new JBTextField();
        customBinaryPathField.getEmptyText().setText("Absolute path (overrides auto-discovery)");
        acpArgsField = new JBTextField();
        acpArgsField.getEmptyText().setText("e.g. --acp --stdio");

        mcpMethodCombo = new ComboBox<>(McpInjectionMethod.values());
        mcpConfigTemplateArea = new JBTextArea(6, 40);
        mcpConfigTemplateArea.setFont(JBUI.Fonts.create(Font.MONOSPACED, mcpConfigTemplateArea.getFont().getSize()));
        mcpConfigTemplateArea.setLineWrap(true);
        mcpConfigTemplateArea.setWrapStyleWord(false);
        mcpEnvVarNameField = new JBTextField();
        mcpEnvVarNameField.getEmptyText().setText("e.g. COPILOT_MCP_CONFIG");

        supportsModelFlagCb = new JBCheckBox("Supports --model flag");
        supportsConfigDirCb = new JBCheckBox("Supports --config-dir flag");
        supportsMcpConfigFlagCb = new JBCheckBox("Supports --additional-mcp-config flag");
        requiresResourceDuplicationCb = new JBCheckBox("Requires resource content duplication");
        modelUsageFieldField = new JBTextField();
        modelUsageFieldField.getEmptyText().setText("e.g. copilotUsage");
        agentsDirectoryField = new JBTextField();
        agentsDirectoryField.getEmptyText().setText("Relative path, e.g. .agents");

        prependInstructionsToField = new JBTextField();
        prependInstructionsToField.getEmptyText().setText("e.g. .copilot/copilot-instructions.md");
        bundledAgentFilesField = new JBTextField();
        bundledAgentFilesField.getEmptyText().setText("e.g. ide-explore.md,ide-task.md");

        usePluginPermissionsCb = new JBCheckBox("Use plugin-level tool permissions");
        excludeAgentBuiltInToolsCb = new JBCheckBox("Exclude agent's built-in tools at session start");

        anthropicApiKeyField = new JPasswordField();
        anthropicApiKeyField.setEchoChar('•');

        editorCards = new CardLayout();
        editorPanel = new JBPanel<>(editorCards);

        JBPanel<JBPanel<?>> emptyPanel = new JBPanel<>(new BorderLayout());
        JBLabel emptyLabel = new JBLabel("Select a profile from the list to edit it");
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        emptyPanel.add(emptyLabel, BorderLayout.CENTER);
        editorPanel.add(emptyPanel, EMPTY_CARD);

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("General", scrollTab(buildGeneralTab()));
        tabs.addTab("ACP & Launch", scrollTab(buildAcpTab()));
        tabs.addTab("MCP", scrollTab(buildMcpTab()));
        tabs.addTab("Advanced", scrollTab(buildFlagsTab()));
        tabs.addTab("Permissions", scrollTab(buildPermissionsTab()));

        editorPanel.add(tabs, "editor");
        editorCards.show(editorPanel, EMPTY_CARD);
        return editorPanel;
    }

    private static JScrollPane scrollTab(JPanel content) {
        JBScrollPane scroll = new JBScrollPane(content);
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    private JPanel buildGeneralTab() {
        anthropicApiKeySection = FormBuilder.createFormBuilder()
            .addComponent(new TitledSeparator("Claude Code API"))
            .addLabeledComponent("Anthropic API key:", anthropicApiKeyField)
            .addTooltip("Your Anthropic API key (sk-ant-...). Stored securely in the IDE keystore.")
            .getPanel();

        FormBuilder builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Display name:", nameField);
        builder.addLabeledComponent("Notes:", new JBScrollPane(descriptionArea));
        builder.addComponent(anthropicApiKeySection);
        builder.addComponent(new TitledSeparator("Binary Discovery"))
            .addLabeledComponent("Binary name:", binaryNameField)
            .addTooltip("Primary executable name to search for (e.g., \"copilot\", \"opencode\")")
            .addLabeledComponent("Alternate names (comma-separated):", alternateNamesField)
            .addTooltip("Fallback binary names if the primary is not found")
            .addLabeledComponent("Install hint:", installHintField)
            .addTooltip("Shown when the binary cannot be found")
            .addLabeledComponent("Custom binary path:", customBinaryPathField)
            .addTooltip("Override auto-discovery with an absolute path")
            .addComponentFillVertically(new JPanel(), 0);
        return builder.getPanel();
    }

    private JPanel buildAcpTab() {
        return FormBuilder.createFormBuilder()
            .addComponent(new TitledSeparator("ACP Command"))
            .addLabeledComponent("ACP args (space-separated):", acpArgsField)
            .addTooltip("Arguments to activate ACP mode (e.g., \"--acp --stdio\" or \"acp\")")
            .addComponent(new TitledSeparator("Pre-Launch Hooks"))
            .addLabeledComponent("Prepend instructions to (relative path):", prependInstructionsToField)
            .addTooltip("Relative path from project root to prepend plugin context to on launch "
                + "(e.g. \".copilot/copilot-instructions.md\" or \"CLAUDE.md\"). Leave empty to skip.")
            .addLabeledComponent("Bundled agent files (comma-separated):", bundledAgentFilesField)
            .addTooltip("Agent .md filenames to deploy to the agents directory on launch "
                + "(e.g. ide-explore.md,ide-task.md). Leave empty to skip.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    private JPanel buildMcpTab() {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("MCP injection method:", mcpMethodCombo)
            .addTooltip("How to tell the agent about the IDE's MCP server")
            .addLabeledComponent("MCP config template:", new JBScrollPane(mcpConfigTemplateArea))
            .addTooltip("JSON template. Placeholders: {mcpPort}, {mcpJarPath}, {javaPath}")
            .addLabeledComponent("Env var name (for ENV_VAR method):", mcpEnvVarNameField)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    private JPanel buildFlagsTab() {
        return FormBuilder.createFormBuilder()
            .addComponent(supportsModelFlagCb)
            .addComponent(supportsConfigDirCb)
            .addComponent(supportsMcpConfigFlagCb)
            .addComponent(requiresResourceDuplicationCb)
            .addLabeledComponent("Model usage field:", modelUsageFieldField)
            .addTooltip("JSON field name in model metadata for usage info (e.g., \"copilotUsage\")")
            .addLabeledComponent("Agents directory (relative path):", agentsDirectoryField)
            .addTooltip("Relative path from project root to a directory of agent definition files (*.md). "
                + "Leave empty for no agent selector.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    private JPanel buildPermissionsTab() {
        return FormBuilder.createFormBuilder()
            .addComponent(usePluginPermissionsCb)
            .addTooltip("When enabled, tool calls go through plugin's per-tool permission system "
                + "(allow/ask/deny). When disabled, the agent handles its own permissions.")
            .addComponent(excludeAgentBuiltInToolsCb)
            .addTooltip("Send excludedTools in session/new to remove the agent's built-in tools "
                + "(view, edit, bash, etc.). Only works with agents that honour this parameter "
                + "(e.g., OpenCode). Copilot CLI ignores it.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    // ── List management ──────────────────────────────────────────────────────

    private void refreshListModel() {
        listModel.clear();
        for (int i = 0; i < workingCopies.size(); i++) {
            AgentProfile p = workingCopies.get(i);
            listModel.addElement(new ProfileListEntry(p.getDisplayName(), p.isBuiltIn(), p.isExperimental(), i));
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
            Messages.showWarningDialog(mainPanel,
                "Built-in profiles cannot be removed. Use 'Reset' to restore defaults.",
                "Cannot Remove");
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
            Messages.showInfoMessage(mainPanel,
                "Only built-in profiles can be reset to defaults.",
                "Cannot Reset");
            return;
        }
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        mgr.resetToDefaults(p.getId());
        AgentProfile fresh = mgr.getProfile(p.getId());
        if (fresh != null) {
            workingCopies.set(idx, fresh.copyForEditing());
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
            editorCards.show(editorPanel, EMPTY_CARD);
        }
    }

    private void loadProfileIntoEditor(@NotNull AgentProfile p) {
        loading = true;
        try {
            boolean isDirect = p.getTransportType() == TransportType.ANTHROPIC_DIRECT;
            nameField.setText(p.getDisplayName());
            descriptionArea.setText(p.getDescription() != null ? p.getDescription() : "");
            descriptionArea.setEditable(!p.isBuiltIn());
            descriptionArea.setCaretPosition(0);
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
            agentsDirectoryField.setText(p.getAgentsDirectory() != null ? p.getAgentsDirectory() : "");
            prependInstructionsToField.setText(
                p.getPrependInstructionsTo() != null ? p.getPrependInstructionsTo() : "");
            bundledAgentFilesField.setText(String.join(",", p.getBundledAgentFiles()));
            usePluginPermissionsCb.setSelected(p.isUsePluginPermissions());
            excludeAgentBuiltInToolsCb.setSelected(p.isExcludeAgentBuiltInTools());

            if (isDirect) {
                String stored = AnthropicKeyStore.getApiKey(p.getId());
                anthropicApiKeyField.setText(stored != null ? stored : "");
            } else {
                anthropicApiKeyField.setText("");
            }
            anthropicApiKeySection.setVisible(isDirect);
        } finally {
            loading = false;
        }
    }

    private void saveCurrentToWorking() {
        if (currentIndex < 0 || currentIndex >= workingCopies.size() || loading) return;
        writeFormTo(workingCopies.get(currentIndex));
        if (currentIndex < listModel.size()) {
            listModel.set(currentIndex, new ProfileListEntry(
                workingCopies.get(currentIndex).getDisplayName(),
                workingCopies.get(currentIndex).isBuiltIn(),
                workingCopies.get(currentIndex).isExperimental(),
                currentIndex));
        }
    }

    /**
     * Reads all form fields into {@code target} without touching {@link #workingCopies}.
     * Safe to call from read-only polls.
     */
    private void writeFormTo(@NotNull AgentProfile target) {
        target.setDisplayName(nameField.getText().trim());
        String desc = descriptionArea.getText().trim();
        target.setDescription(desc.isEmpty() ? null : desc);
        target.setBinaryName(binaryNameField.getText().trim());
        target.setAlternateNames(splitComma(alternateNamesField.getText()));
        target.setInstallHint(installHintField.getText().trim());
        target.setCustomBinaryPath(customBinaryPathField.getText().trim());
        target.setAcpArgs(splitSpace(acpArgsField.getText()));
        McpInjectionMethod mcpMethod = (McpInjectionMethod) mcpMethodCombo.getSelectedItem();
        if (mcpMethod != null) {
            target.setMcpMethod(mcpMethod);
        }
        target.setMcpConfigTemplate(mcpConfigTemplateArea.getText().trim());
        target.setMcpEnvVarName(mcpEnvVarNameField.getText().trim());
        target.setSupportsModelFlag(supportsModelFlagCb.isSelected());
        target.setSupportsConfigDir(supportsConfigDirCb.isSelected());
        target.setSupportsMcpConfigFlag(supportsMcpConfigFlagCb.isSelected());
        target.setRequiresResourceDuplication(requiresResourceDuplicationCb.isSelected());
        String modelField = modelUsageFieldField.getText().trim();
        target.setModelUsageField(modelField.isEmpty() ? null : modelField);
        String agentsDir = agentsDirectoryField.getText().trim();
        target.setAgentsDirectory(agentsDir.isEmpty() ? null : agentsDir);
        String prependTarget = prependInstructionsToField.getText().trim();
        target.setPrependInstructionsTo(prependTarget.isEmpty() ? null : prependTarget);
        String bundledRaw = bundledAgentFilesField.getText().trim();
        target.setBundledAgentFiles(bundledRaw.isEmpty() ? List.of() :
            Arrays.stream(bundledRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        target.setUsePluginPermissions(usePluginPermissionsCb.isSelected());
        target.setExcludeAgentBuiltInTools(excludeAgentBuiltInToolsCb.isSelected());
    }

    // ── Configurable interface ───────────────────────────────────────────────

    @Override
    public boolean isModified() {
        // Read form into a transient snapshot to avoid mutating workingCopies during polling
        AgentProfile formSnapshot = (currentIndex >= 0 && currentIndex < workingCopies.size())
            ? workingCopies.get(currentIndex).copyForEditing() : null;
        if (formSnapshot != null && !loading) {
            writeFormTo(formSnapshot);
        }

        List<AgentProfile> persisted = AgentProfileManager.getInstance().getAllProfiles();
        if (workingCopies.size() != persisted.size()) return true;
        for (int i = 0; i < workingCopies.size(); i++) {
            AgentProfile toCompare = (i == currentIndex && formSnapshot != null)
                ? formSnapshot : workingCopies.get(i);
            if (!profileEquals(toCompare, persisted.get(i))) return true;
        }

        // Check if the API key field has been edited for the current ANTHROPIC_DIRECT profile
        return currentIndex >= 0 && currentIndex < workingCopies.size() && !loading
            && isApiKeyModified(workingCopies.get(currentIndex));
    }

    private boolean isApiKeyModified(@NotNull AgentProfile profile) {
        if (profile.getTransportType() != TransportType.ANTHROPIC_DIRECT) return false;
        String fieldKey = new String(anthropicApiKeyField.getPassword());
        String storedKey = AnthropicKeyStore.getApiKey(profile.getId());
        return !fieldKey.equals(storedKey != null ? storedKey : "");
    }

    @Override
    public void apply() {
        saveCurrentToWorking();
        AgentProfileManager mgr = AgentProfileManager.getInstance();

        List<String> existingIds = mgr.getAllProfiles().stream().map(AgentProfile::getId).toList();
        List<String> newIds = workingCopies.stream().map(AgentProfile::getId).toList();

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

        // Save API key for the currently-displayed ANTHROPIC_DIRECT profile
        if (currentIndex >= 0 && currentIndex < workingCopies.size()) {
            AgentProfile current = workingCopies.get(currentIndex);
            if (current.getTransportType() == TransportType.ANTHROPIC_DIRECT) {
                String key = new String(anthropicApiKeyField.getPassword()).trim();
                AnthropicKeyStore.setApiKey(current.getId(), key.isEmpty() ? null : key);
            }
        }
    }

    @Override
    public void reset() {
        workingCopies = new ArrayList<>();
        for (AgentProfile p : AgentProfileManager.getInstance().getAllProfiles()) {
            workingCopies.add(p.copyForEditing());
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
            && Objects.equals(a.getModelUsageField(), b.getModelUsageField())
            && Objects.equals(a.getAgentsDirectory(), b.getAgentsDirectory())
            && Objects.equals(a.getPrependInstructionsTo(), b.getPrependInstructionsTo())
            && a.isEnsureCopilotAgents() == b.isEnsureCopilotAgents()
            && a.isUsePluginPermissions() == b.isUsePluginPermissions()
            && a.isExcludeAgentBuiltInTools() == b.isExcludeAgentBuiltInTools();
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

    private record ProfileListEntry(String displayName, boolean builtIn, boolean experimental, int index) {
        @Override
        public @NotNull String toString() {
            String suffix = builtIn ? " (built-in)" : "";
            if (experimental) suffix += " ⚠ experimental";
            return displayName + suffix;
        }
    }
}
