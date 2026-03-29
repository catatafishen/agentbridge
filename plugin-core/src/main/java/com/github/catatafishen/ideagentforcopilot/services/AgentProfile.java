package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
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
 * Settings → Tools → AgentBridge → Agent Profiles.</p>
 */
public final class AgentProfile {

    // ── Identity ─────────────────────────────────────────────────────────────

    private String id;
    private String displayName;
    private boolean builtIn;
    private boolean experimental;
    private String description;

    // ── Transport ─────────────────────────────────────────────────────────────

    private TransportType transportType;

    /**
     * URL to open when the user clicks the "Install" button in the setup banner.
     * Empty string means no install URL is available (install button hidden).
     */
    private String installUrl = "";

    /**
     * Whether this agent supports the plugin's inline OAuth sign-in flow.
     * When {@code true}, a "Sign In" button is shown in the auth error banner.
     * When {@code false}, the user must authenticate externally (e.g. run a CLI command).
     */
    private boolean supportsOAuthSignIn = false;

    /**
     * Shell command to run in the embedded terminal when the user clicks "Sign In"
     * in the auth error banner. Used for agents that authenticate via a CLI command
     * (e.g. {@code codex login --device-auth}) rather than inline OAuth. Null means no
     * terminal sign-in button is shown.
     */
    @Nullable
    private String terminalSignInCommand = null;

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
    private String mcpServerName = "agentbridge";

    // ── Feature Flags ────────────────────────────────────────────────────────

    private boolean supportsModelFlag;
    private boolean supportsConfigDir;
    private boolean supportsMcpConfigFlag;
    private boolean sendResourceReferences = true;

    // ── Modes ────────────────────────────────────────────────────────────────

    // ── Agent dropdown ───────────────────────────────────────────────────────

    /**
     * Path relative to the project root where agent definition files ({@code *.md}) live.
     * When set, an agent selector dropdown is shown and the selected agent's name is
     * prepended as {@code @agent-name } to each prompt. Null/empty = no dropdown.
     */
    private String agentsDirectory;

    // ── Pre-launch hooks ─────────────────────────────────────────────────────

    // ── Permissions ────────────────────────────────────────────────────────

    private boolean usePluginPermissions = true;
    private boolean excludeAgentBuiltInTools;
    private PermissionInjectionMethod permissionInjectionMethod = PermissionInjectionMethod.NONE;

    /**
     * Whether this agent supports {@code session/message} JSON-RPC notifications.
     * When {@code true}, startup instructions are sent via {@code session/message}.
     * When {@code false}, instructions must come from config files or MCP prompt field.
     * Defaults to {@code true} for backwards compatibility (Junie, Copilot support it).
     */
    private boolean supportsSessionMessage = true;

    /**
     * Relative path (from project root) to the agent-instructions file that plugin context
     * should be prepended to on launch (e.g. {@code ".copilot/copilot-instructions.md"} or
     * {@code "CLAUDE.md"}). Empty/null means skip file injection (rely on MCP instructions field).
     */
    private String prependInstructionsTo;
    private List<String> bundledAgentFiles = new ArrayList<>();
    private String additionalInstructions = "";

    /**
     * Custom model list for CLI-mode profiles.
     *
     * <p>Each entry is {@code "model-id=Display Name"}, e.g.
     * {@code "claude-opus-4-6=Claude Opus 4.6"}.  When non-empty this list
     * is returned by {@code ClaudeCliClient.listModels()} instead of the
     * built-in defaults.  Empty means "use defaults".</p>
     *
     * <p>This field exists because the Claude CLI has no stable
     * {@code models} subcommand — {@code claude models} is treated as a
     * plain user prompt that makes a full API call and returns an LLM
     * response, so programmatic discovery is unreliable.</p>
     */
    private List<String> customCliModels = new ArrayList<>();

    public AgentProfile() {
        this.id = UUID.randomUUID().toString();
        this.displayName = "New Agent";
        this.transportType = TransportType.ACP;
        this.binaryName = "";
        this.alternateNames = new ArrayList<>();
        this.installHint = "";
        this.customBinaryPath = "";
        this.acpArgs = List.of("--acp", "--stdio");
        this.mcpMethod = McpInjectionMethod.CONFIG_FLAG;
        this.mcpConfigTemplate = "";
        this.supportsModelFlag = true;
        this.supportsConfigDir = true;
        this.supportsMcpConfigFlag = true;
        this.agentsDirectory = null;
    }

    public AgentProfile duplicate() {
        AgentProfile copy = new AgentProfile();
        copy.id = UUID.randomUUID().toString();
        copy.displayName = displayName + " (Copy)";
        copy.builtIn = false;
        copy.experimental = false;
        copy.description = description;
        copy.transportType = transportType;
        copy.installUrl = installUrl;
        copy.supportsOAuthSignIn = supportsOAuthSignIn;
        copy.terminalSignInCommand = terminalSignInCommand;
        copy.binaryName = binaryName;
        copy.alternateNames = new ArrayList<>(alternateNames);
        copy.installHint = installHint;
        copy.customBinaryPath = customBinaryPath;
        copy.acpArgs = new ArrayList<>(acpArgs);
        copy.mcpMethod = mcpMethod;
        copy.mcpConfigTemplate = mcpConfigTemplate;
        copy.mcpServerName = mcpServerName;
        copy.supportsModelFlag = supportsModelFlag;
        copy.supportsConfigDir = supportsConfigDir;
        copy.supportsMcpConfigFlag = supportsMcpConfigFlag;
        copy.sendResourceReferences = sendResourceReferences;
        copy.agentsDirectory = agentsDirectory;
        copy.usePluginPermissions = usePluginPermissions;
        copy.excludeAgentBuiltInTools = excludeAgentBuiltInTools;
        copy.permissionInjectionMethod = permissionInjectionMethod;
        copy.supportsSessionMessage = supportsSessionMessage;
        copy.prependInstructionsTo = prependInstructionsTo;
        copy.bundledAgentFiles = new ArrayList<>(bundledAgentFiles);
        copy.additionalInstructions = additionalInstructions;
        copy.customCliModels = new ArrayList<>(customCliModels);
        return copy;
    }

    public AgentProfile copyForEditing() {
        AgentProfile copy = new AgentProfile();
        copy.id = this.id;
        copy.displayName = this.displayName;
        copy.builtIn = this.builtIn;
        copy.experimental = this.experimental;
        copy.description = this.description;
        copy.transportType = this.transportType;
        copy.installUrl = this.installUrl;
        copy.supportsOAuthSignIn = this.supportsOAuthSignIn;
        copy.terminalSignInCommand = this.terminalSignInCommand;
        copy.binaryName = binaryName;
        copy.alternateNames = new ArrayList<>(alternateNames);
        copy.installHint = installHint;
        copy.customBinaryPath = customBinaryPath;
        copy.acpArgs = new ArrayList<>(acpArgs);
        copy.mcpMethod = mcpMethod;
        copy.mcpConfigTemplate = mcpConfigTemplate;
        copy.mcpServerName = mcpServerName;
        copy.supportsModelFlag = supportsModelFlag;
        copy.supportsConfigDir = supportsConfigDir;
        copy.supportsMcpConfigFlag = supportsMcpConfigFlag;
        copy.sendResourceReferences = sendResourceReferences;
        copy.agentsDirectory = agentsDirectory;
        copy.usePluginPermissions = usePluginPermissions;
        copy.excludeAgentBuiltInTools = excludeAgentBuiltInTools;
        copy.permissionInjectionMethod = permissionInjectionMethod;
        copy.supportsSessionMessage = supportsSessionMessage;
        copy.prependInstructionsTo = prependInstructionsTo;
        copy.bundledAgentFiles = new ArrayList<>(bundledAgentFiles);
        copy.additionalInstructions = additionalInstructions;
        copy.customCliModels = new ArrayList<>(customCliModels);
        return copy;
    }

    /**
     * Copies all fields from another profile into this one (preserving this profile's ID and builtIn flag).
     */
    public void copyFrom(@NotNull AgentProfile other) {
        this.displayName = other.displayName;
        this.experimental = other.experimental;
        this.description = other.description;
        this.transportType = other.transportType;
        this.installUrl = other.installUrl;
        this.supportsOAuthSignIn = other.supportsOAuthSignIn;
        this.terminalSignInCommand = other.terminalSignInCommand;
        this.binaryName = other.binaryName;
        this.alternateNames = new ArrayList<>(other.alternateNames);
        this.installHint = other.installHint;
        this.customBinaryPath = other.customBinaryPath;
        this.acpArgs = new ArrayList<>(other.acpArgs);
        this.mcpMethod = other.mcpMethod;
        this.mcpConfigTemplate = other.mcpConfigTemplate;
        this.mcpServerName = other.mcpServerName;
        this.supportsModelFlag = other.supportsModelFlag;
        this.supportsConfigDir = other.supportsConfigDir;
        this.supportsMcpConfigFlag = other.supportsMcpConfigFlag;
        this.sendResourceReferences = other.sendResourceReferences;
        this.agentsDirectory = other.agentsDirectory;
        this.usePluginPermissions = other.usePluginPermissions;
        this.excludeAgentBuiltInTools = other.excludeAgentBuiltInTools;
        this.permissionInjectionMethod = other.permissionInjectionMethod;
        this.supportsSessionMessage = other.supportsSessionMessage;
        this.prependInstructionsTo = other.prependInstructionsTo;
        this.bundledAgentFiles = new ArrayList<>(other.bundledAgentFiles);
        this.additionalInstructions = other.additionalInstructions;
        this.customCliModels = new ArrayList<>(other.customCliModels);
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

    public boolean isExperimental() {
        return experimental;
    }

    public void setExperimental(boolean experimental) {
        this.experimental = experimental;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    @NotNull
    public TransportType getTransportType() {
        return transportType != null ? transportType : TransportType.ACP;
    }

    @NotNull
    public String getInstallUrl() {
        return installUrl != null ? installUrl : "";
    }

    public void setInstallUrl(@NotNull String installUrl) {
        this.installUrl = installUrl;
    }

    public boolean isSupportsOAuthSignIn() {
        return supportsOAuthSignIn;
    }

    public void setSupportsOAuthSignIn(boolean supportsOAuthSignIn) {
        this.supportsOAuthSignIn = supportsOAuthSignIn;
    }

    @Nullable
    public String getTerminalSignInCommand() {
        return terminalSignInCommand;
    }

    public void setTerminalSignInCommand(@Nullable String terminalSignInCommand) {
        this.terminalSignInCommand = terminalSignInCommand;
    }

    public void setTransportType(@NotNull TransportType transportType) {
        this.transportType = transportType;
    }

    @NotNull
    public String getBinaryName() {
        return binaryName != null ? binaryName : "";
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
    public String getMcpServerName() {
        return mcpServerName;
    }

    public void setMcpServerName(@NotNull String mcpServerName) {
        this.mcpServerName = mcpServerName;
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

    public boolean isSendResourceReferences() {
        return sendResourceReferences;
    }

    public void setSendResourceReferences(boolean sendResourceReferences) {
        this.sendResourceReferences = sendResourceReferences;
    }

    @Nullable
    public String getAgentsDirectory() {
        return agentsDirectory;
    }

    public void setAgentsDirectory(@Nullable String agentsDirectory) {
        this.agentsDirectory = agentsDirectory;
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

    @NotNull
    public PermissionInjectionMethod getPermissionInjectionMethod() {
        return permissionInjectionMethod;
    }

    public void setPermissionInjectionMethod(@NotNull PermissionInjectionMethod permissionInjectionMethod) {
        this.permissionInjectionMethod = permissionInjectionMethod;
    }

    public boolean isSupportsSessionMessage() {
        return supportsSessionMessage;
    }

    public void setSupportsSessionMessage(boolean supportsSessionMessage) {
        this.supportsSessionMessage = supportsSessionMessage;
    }

    @Nullable
    public String getPrependInstructionsTo() {
        return prependInstructionsTo;
    }

    public void setPrependInstructionsTo(@Nullable String prependInstructionsTo) {
        this.prependInstructionsTo = prependInstructionsTo;
    }

    public boolean isEnsureCopilotAgents() {
        return !bundledAgentFiles.isEmpty();
    }

    /**
     * @deprecated Use {@link #getBundledAgentFiles()} instead.
     */
    @Deprecated
    public void setEnsureCopilotAgents(boolean ensureCopilotAgents) {
        // no-op: replaced by bundledAgentFiles
    }

    @NotNull
    public List<String> getBundledAgentFiles() {
        return bundledAgentFiles;
    }

    public void setBundledAgentFiles(@NotNull List<String> bundledAgentFiles) {
        this.bundledAgentFiles = new ArrayList<>(bundledAgentFiles);
    }

    @NotNull
    public String getAdditionalInstructions() {
        return additionalInstructions;
    }

    public void setAdditionalInstructions(@NotNull String additionalInstructions) {
        this.additionalInstructions = additionalInstructions;
    }

    @NotNull
    public List<String> getCustomCliModels() {
        return customCliModels;
    }

    public void setCustomCliModels(@NotNull List<String> customCliModels) {
        this.customCliModels = new ArrayList<>(customCliModels);
    }

    /**
     * Returns a CSS-friendly client identifier for styling agent bubbles.
     */
    @NotNull
    public String getClientCssClass() {
        String name = binaryName.toLowerCase();
        if (name.contains("copilot")) return "copilot";
        if (name.contains("claude")) return "claude";
        if (name.contains("opencode")) return "opencode";
        if (name.contains("junie")) return "junie";
        if (name.contains("kiro")) return "kiro";
        if (name.contains("codex")) return "codex";
        return "";
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
