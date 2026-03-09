package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AgentMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Data-driven configuration for an ACP agent.
 * Each profile holds everything needed to discover, launch, and manage an agent —
 * replacing the old per-agent config/service/settings classes.
 *
 * <p>Profiles are persisted via {@link AgentProfileManager} and editable in
 * Settings → Tools → IDE Agent for Copilot → Agent Profiles.</p>
 */
public final class AgentProfile {

    // ── Identity ─────────────────────────────────────────────────────────────

    private String id;
    private String displayName;
    private boolean builtIn;

    // ── Binary Discovery ─────────────────────────────────────────────────────

    private String binaryName;
    private List<String> alternateNames;
    private String installHint;
    private String customBinaryPath;

    // ── Command Building ─────────────────────────────────────────────────────

    private List<String> acpArgs;

    // ── MCP Configuration ────────────────────────────────────────────────────

    private McpInjectionMethod mcpMethod;
    private String mcpConfigTemplate;
    private String mcpEnvVarName;

    // ── Feature Flags ────────────────────────────────────────────────────────

    private boolean supportsModelFlag;
    private boolean supportsConfigDir;
    private boolean supportsMcpConfigFlag;
    private boolean requiresResourceDuplication;
    private String modelUsageField;

    // ── Modes ────────────────────────────────────────────────────────────────

    private List<AgentMode> supportedModes;

    // ── Pre-launch hooks ─────────────────────────────────────────────────────

    // ── Permissions ────────────────────────────────────────────────────────

    private boolean usePluginPermissions = true;
    private boolean excludeAgentBuiltInTools;

    private boolean ensureCopilotInstructions;
    private boolean ensureCopilotAgents;
    private boolean ensureClaudeInstructions;

    public AgentProfile() {
        this.id = UUID.randomUUID().toString();
        this.displayName = "New Agent";
        this.binaryName = "";
        this.alternateNames = new ArrayList<>();
        this.installHint = "";
        this.customBinaryPath = "";
        this.acpArgs = List.of("--acp", "--stdio");
        this.mcpMethod = McpInjectionMethod.CONFIG_FLAG;
        this.mcpConfigTemplate = "";
        this.mcpEnvVarName = "";
        this.supportsModelFlag = true;
        this.supportsConfigDir = true;
        this.supportsMcpConfigFlag = true;
        this.requiresResourceDuplication = false;
        this.modelUsageField = "";
        this.supportedModes = new ArrayList<>();
    }

    /**
     * Creates a deep copy of this profile with a new ID.
     */
    @NotNull
    public AgentProfile duplicate() {
        AgentProfile copy = new AgentProfile();
        copy.id = UUID.randomUUID().toString();
        copy.displayName = displayName + " (Copy)";
        copy.builtIn = false;
        copy.binaryName = binaryName;
        copy.alternateNames = new ArrayList<>(alternateNames);
        copy.installHint = installHint;
        copy.customBinaryPath = customBinaryPath;
        copy.acpArgs = new ArrayList<>(acpArgs);
        copy.mcpMethod = mcpMethod;
        copy.mcpConfigTemplate = mcpConfigTemplate;
        copy.mcpEnvVarName = mcpEnvVarName;
        copy.supportsModelFlag = supportsModelFlag;
        copy.supportsConfigDir = supportsConfigDir;
        copy.supportsMcpConfigFlag = supportsMcpConfigFlag;
        copy.requiresResourceDuplication = requiresResourceDuplication;
        copy.modelUsageField = modelUsageField;
        copy.supportedModes = new ArrayList<>(supportedModes);
        copy.usePluginPermissions = usePluginPermissions;
        copy.excludeAgentBuiltInTools = excludeAgentBuiltInTools;
        copy.ensureCopilotInstructions = ensureCopilotInstructions;
        copy.ensureCopilotAgents = ensureCopilotAgents;
        copy.ensureClaudeInstructions = ensureClaudeInstructions;
        return copy;
    }

    /**
     * Copies all fields from another profile into this one (preserving this profile's ID and builtIn flag).
     */
    public void copyFrom(@NotNull AgentProfile other) {
        this.displayName = other.displayName;
        this.binaryName = other.binaryName;
        this.alternateNames = new ArrayList<>(other.alternateNames);
        this.installHint = other.installHint;
        this.customBinaryPath = other.customBinaryPath;
        this.acpArgs = new ArrayList<>(other.acpArgs);
        this.mcpMethod = other.mcpMethod;
        this.mcpConfigTemplate = other.mcpConfigTemplate;
        this.mcpEnvVarName = other.mcpEnvVarName;
        this.supportsModelFlag = other.supportsModelFlag;
        this.supportsConfigDir = other.supportsConfigDir;
        this.supportsMcpConfigFlag = other.supportsMcpConfigFlag;
        this.requiresResourceDuplication = other.requiresResourceDuplication;
        this.modelUsageField = other.modelUsageField;
        this.supportedModes = new ArrayList<>(other.supportedModes);
        this.usePluginPermissions = other.usePluginPermissions;
        this.excludeAgentBuiltInTools = other.excludeAgentBuiltInTools;
        this.ensureCopilotInstructions = other.ensureCopilotInstructions;
        this.ensureCopilotAgents = other.ensureCopilotAgents;
        this.ensureClaudeInstructions = other.ensureClaudeInstructions;
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    @NotNull
    public String getId() {
        return id;
    }

    public void setId(@NotNull String id) {
        this.id = id;
    }

    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(@NotNull String displayName) {
        this.displayName = displayName;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    @NotNull
    public String getBinaryName() {
        return binaryName;
    }

    public void setBinaryName(@NotNull String binaryName) {
        this.binaryName = binaryName;
    }

    @NotNull
    public List<String> getAlternateNames() {
        return alternateNames;
    }

    public void setAlternateNames(@NotNull List<String> alternateNames) {
        this.alternateNames = new ArrayList<>(alternateNames);
    }

    @NotNull
    public String getInstallHint() {
        return installHint;
    }

    public void setInstallHint(@NotNull String installHint) {
        this.installHint = installHint;
    }

    @NotNull
    public String getCustomBinaryPath() {
        return customBinaryPath;
    }

    public void setCustomBinaryPath(@NotNull String customBinaryPath) {
        this.customBinaryPath = customBinaryPath;
    }

    @NotNull
    public List<String> getAcpArgs() {
        return acpArgs;
    }

    public void setAcpArgs(@NotNull List<String> acpArgs) {
        this.acpArgs = new ArrayList<>(acpArgs);
    }

    @NotNull
    public McpInjectionMethod getMcpMethod() {
        return mcpMethod;
    }

    public void setMcpMethod(@NotNull McpInjectionMethod mcpMethod) {
        this.mcpMethod = mcpMethod;
    }

    @NotNull
    public String getMcpConfigTemplate() {
        return mcpConfigTemplate;
    }

    public void setMcpConfigTemplate(@NotNull String mcpConfigTemplate) {
        this.mcpConfigTemplate = mcpConfigTemplate;
    }

    @NotNull
    public String getMcpEnvVarName() {
        return mcpEnvVarName;
    }

    public void setMcpEnvVarName(@NotNull String mcpEnvVarName) {
        this.mcpEnvVarName = mcpEnvVarName;
    }

    public boolean isSupportsModelFlag() {
        return supportsModelFlag;
    }

    public void setSupportsModelFlag(boolean supportsModelFlag) {
        this.supportsModelFlag = supportsModelFlag;
    }

    public boolean isSupportsConfigDir() {
        return supportsConfigDir;
    }

    public void setSupportsConfigDir(boolean supportsConfigDir) {
        this.supportsConfigDir = supportsConfigDir;
    }

    public boolean isSupportsMcpConfigFlag() {
        return supportsMcpConfigFlag;
    }

    public void setSupportsMcpConfigFlag(boolean supportsMcpConfigFlag) {
        this.supportsMcpConfigFlag = supportsMcpConfigFlag;
    }

    public boolean isRequiresResourceDuplication() {
        return requiresResourceDuplication;
    }

    public void setRequiresResourceDuplication(boolean requiresResourceDuplication) {
        this.requiresResourceDuplication = requiresResourceDuplication;
    }

    @Nullable
    public String getModelUsageField() {
        return modelUsageField;
    }

    public void setModelUsageField(@Nullable String modelUsageField) {
        this.modelUsageField = modelUsageField != null ? modelUsageField : "";
    }

    @NotNull
    public List<AgentMode> getSupportedModes() {
        return supportedModes;
    }

    public void setSupportedModes(@NotNull List<AgentMode> supportedModes) {
        this.supportedModes = new ArrayList<>(supportedModes);
    }

    public boolean isUsePluginPermissions() {
        return usePluginPermissions;
    }

    public void setUsePluginPermissions(boolean usePluginPermissions) {
        this.usePluginPermissions = usePluginPermissions;
    }

    public boolean isExcludeAgentBuiltInTools() {
        return excludeAgentBuiltInTools;
    }

    public void setExcludeAgentBuiltInTools(boolean excludeAgentBuiltInTools) {
        this.excludeAgentBuiltInTools = excludeAgentBuiltInTools;
    }

    public boolean isEnsureCopilotInstructions() {
        return ensureCopilotInstructions;
    }

    public void setEnsureCopilotInstructions(boolean ensureCopilotInstructions) {
        this.ensureCopilotInstructions = ensureCopilotInstructions;
    }

    public boolean isEnsureCopilotAgents() {
        return ensureCopilotAgents;
    }

    public void setEnsureCopilotAgents(boolean ensureCopilotAgents) {
        this.ensureCopilotAgents = ensureCopilotAgents;
    }

    public boolean isEnsureClaudeInstructions() {
        return ensureClaudeInstructions;
    }

    public void setEnsureClaudeInstructions(boolean ensureClaudeInstructions) {
        this.ensureClaudeInstructions = ensureClaudeInstructions;
    }

    /**
     * Builds the default start command string for display in the connect panel.
     */
    @NotNull
    public String getDefaultStartCommand() {
        if (binaryName.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(binaryName);
        for (String arg : acpArgs) {
            sb.append(' ').append(arg);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentProfile that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}
