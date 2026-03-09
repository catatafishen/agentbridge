package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AgentMode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-level service that manages all agent profiles.
 * Persists user-created and modified profiles; ships built-in defaults
 * for known agents (Copilot, OpenCode).
 *
 * <p>Thread-safe: all mutations are synchronized on this instance.</p>
 */
@Service(Service.Level.APP)
@State(name = "AgentProfiles", storages = @Storage("agentProfiles.xml"))
public final class AgentProfileManager implements PersistentStateComponent<AgentProfileManager.ProfileState> {

    private static final Logger LOG = Logger.getInstance(AgentProfileManager.class);

    public static final String COPILOT_PROFILE_ID = "copilot";
    public static final String OPENCODE_PROFILE_ID = "opencode";

    private final Map<String, AgentProfile> profiles = new LinkedHashMap<>();
    private volatile boolean initialized;

    public AgentProfileManager() {
        ensureDefaults();
    }

    @NotNull
    public static AgentProfileManager getInstance() {
        return ApplicationManager.getApplication().getService(AgentProfileManager.class);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @NotNull
    public synchronized List<AgentProfile> getAllProfiles() {
        ensureDefaults();
        return new ArrayList<>(profiles.values());
    }

    @Nullable
    public synchronized AgentProfile getProfile(@NotNull String id) {
        ensureDefaults();
        return profiles.get(id);
    }

    public synchronized void addProfile(@NotNull AgentProfile profile) {
        profiles.put(profile.getId(), profile);
        LOG.info("Added agent profile: " + profile.getDisplayName() + " (" + profile.getId() + ")");
    }

    public synchronized void updateProfile(@NotNull AgentProfile profile) {
        if (!profiles.containsKey(profile.getId())) {
            LOG.warn("Cannot update non-existent profile: " + profile.getId());
            return;
        }
        profiles.put(profile.getId(), profile);
        LOG.info("Updated agent profile: " + profile.getDisplayName());
    }

    public synchronized void removeProfile(@NotNull String id) {
        AgentProfile profile = profiles.get(id);
        if (profile != null && profile.isBuiltIn()) {
            LOG.warn("Cannot remove built-in profile: " + id);
            return;
        }
        profiles.remove(id);
        LOG.info("Removed agent profile: " + id);
    }

    /**
     * Resets a built-in profile to its factory defaults.
     */
    public synchronized void resetToDefaults(@NotNull String id) {
        AgentProfile defaults = createDefaultProfile(id);
        if (defaults != null) {
            profiles.put(id, defaults);
            LOG.info("Reset profile to defaults: " + id);
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Override
    public @Nullable ProfileState getState() {
        ProfileState state = new ProfileState();
        synchronized (this) {
            for (AgentProfile profile : profiles.values()) {
                state.profiles.add(ProfileEntry.fromProfile(profile));
            }
        }
        return state;
    }

    @Override
    public void loadState(@NotNull ProfileState state) {
        synchronized (this) {
            profiles.clear();
            for (ProfileEntry entry : state.profiles) {
                AgentProfile profile = entry.toProfile();
                profiles.put(profile.getId(), profile);
            }
            initialized = true;
            ensureDefaults();
        }
    }

    private void ensureDefaults() {
        if (!profiles.containsKey(COPILOT_PROFILE_ID)) {
            profiles.put(COPILOT_PROFILE_ID, createCopilotProfile());
        } else {
            refreshBuiltInProfile(COPILOT_PROFILE_ID);
        }
        if (!profiles.containsKey(OPENCODE_PROFILE_ID)) {
            profiles.put(OPENCODE_PROFILE_ID, createOpenCodeProfile());
        } else {
            refreshBuiltInProfile(OPENCODE_PROFILE_ID);
        }
        initialized = true;
    }

    /**
     * Refreshes code-managed fields on a built-in profile that was loaded from
     * persisted state. User-customisable fields (custom binary path, model selection)
     * are preserved; fields that track internal transport details (MCP config template,
     * ACP args, install hint) are overwritten from the code-defined defaults so that
     * bug-fixes (e.g. changing the MCP server type from "streamable-http" to "http")
     * take effect without requiring the user to manually reset the profile.
     */
    private void refreshBuiltInProfile(@NotNull String id) {
        AgentProfile stored = profiles.get(id);
        AgentProfile defaults = createDefaultProfile(id);
        if (stored == null || defaults == null || !stored.isBuiltIn()) return;

        stored.setAcpArgs(defaults.getAcpArgs());
        stored.setMcpConfigTemplate(defaults.getMcpConfigTemplate());
        stored.setMcpMethod(defaults.getMcpMethod());
        stored.setMcpEnvVarName(defaults.getMcpEnvVarName());
        stored.setInstallHint(defaults.getInstallHint());
        stored.setSupportsMcpConfigFlag(defaults.isSupportsMcpConfigFlag());
        stored.setSupportsModelFlag(defaults.isSupportsModelFlag());
        stored.setSupportsConfigDir(defaults.isSupportsConfigDir());
        stored.setSupportedModes(defaults.getSupportedModes());
        stored.setRequiresResourceDuplication(defaults.isRequiresResourceDuplication());
    }

    @Nullable
    private AgentProfile createDefaultProfile(@NotNull String id) {
        return switch (id) {
            case COPILOT_PROFILE_ID -> createCopilotProfile();
            case OPENCODE_PROFILE_ID -> createOpenCodeProfile();
            default -> null;
        };
    }

    // ── Default Profiles ─────────────────────────────────────────────────────

    /**
     * Creates the default Copilot profile. Public for use in tests.
     */
    @NotNull
    public static AgentProfile createDefaultCopilotProfile() {
        return createCopilotProfile();
    }

    /**
     * Creates the default OpenCode profile. Public for use in tests.
     */
    @NotNull
    public static AgentProfile createDefaultOpenCodeProfile() {
        return createOpenCodeProfile();
    }

    @NotNull
    private static AgentProfile createCopilotProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(COPILOT_PROFILE_ID);
        p.setDisplayName("GitHub Copilot");
        p.setBuiltIn(true);
        p.setBinaryName("copilot");
        p.setAlternateNames(List.of("copilot-cli"));
        p.setInstallHint("Install with: npm install -g @anthropic-ai/copilot-cli");
        p.setAcpArgs(List.of("--acp", "--stdio"));
        p.setMcpMethod(McpInjectionMethod.CONFIG_FLAG);
        p.setSupportsMcpConfigFlag(true);
        p.setMcpConfigTemplate(
            "{\"mcpServers\":{\"intellij-code-tools\":"
                + "{\"type\":\"http\","
                + "\"url\":\"http://localhost:{mcpPort}/mcp\"}}}");
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(true);
        p.setRequiresResourceDuplication(true);
        p.setModelUsageField("copilotUsage");
        p.setSupportedModes(List.of(
            new AgentMode("agent", "Agent"),
            new AgentMode("plan", "Plan")
        ));
        p.setEnsureCopilotInstructions(true);
        p.setEnsureCopilotAgents(true);
        return p;
    }

    @NotNull
    private static AgentProfile createOpenCodeProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(OPENCODE_PROFILE_ID);
        p.setDisplayName("OpenCode");
        p.setBuiltIn(true);
        p.setBinaryName("opencode");
        p.setInstallHint("Install with: npm i -g opencode-ai");
        p.setAcpArgs(List.of("acp"));
        p.setMcpMethod(McpInjectionMethod.ENV_VAR);
        p.setMcpEnvVarName("OPENCODE_CONFIG_CONTENT");
        p.setMcpConfigTemplate(
            "{\"mcp\":{\"intellij-code-tools\":"
                + "{\"type\":\"local\","
                + "\"command\":[\"{javaPath}\",\"-jar\",\"{mcpJarPath}\","
                + "\"--port\",\"{mcpPort}\"]}}}");
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(false);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(false);
        return p;
    }

    // ── Serialization model ──────────────────────────────────────────────────

    /**
     * Serializable state wrapper for {@link PersistentStateComponent}.
     */
    public static final class ProfileState {
        public List<ProfileEntry> profiles = new ArrayList<>();
    }

    /**
     * Flat serializable representation of an {@link AgentProfile}.
     * Uses primitive types and strings for XML serialization compatibility.
     */
    public static final class ProfileEntry {
        public String id = "";
        public String displayName = "";
        public boolean builtIn;
        public String binaryName = "";
        public String alternateNames = "";
        public String installHint = "";
        public String customBinaryPath = "";
        public String acpArgs = "";
        public String mcpMethod = "CONFIG_FLAG";
        public String mcpConfigTemplate = "";
        public String mcpEnvVarName = "";
        public boolean supportsModelFlag = true;
        public boolean supportsConfigDir = true;
        public boolean supportsMcpConfigFlag = true;
        public boolean requiresResourceDuplication;
        public String modelUsageField = "";
        public String supportedModes = "";
        public boolean ensureCopilotInstructions;
        public boolean ensureCopilotAgents;
        public boolean ensureClaudeInstructions;
        public boolean usePluginPermissions = true;
        public boolean excludeAgentBuiltInTools;

        @NotNull
        static ProfileEntry fromProfile(@NotNull AgentProfile p) {
            ProfileEntry e = new ProfileEntry();
            e.id = p.getId();
            e.displayName = p.getDisplayName();
            e.builtIn = p.isBuiltIn();
            e.binaryName = p.getBinaryName();
            e.alternateNames = String.join(",", p.getAlternateNames());
            e.installHint = p.getInstallHint();
            e.customBinaryPath = p.getCustomBinaryPath();
            e.acpArgs = String.join(" ", p.getAcpArgs());
            e.mcpMethod = p.getMcpMethod().name();
            e.mcpConfigTemplate = p.getMcpConfigTemplate();
            e.mcpEnvVarName = p.getMcpEnvVarName();
            e.supportsModelFlag = p.isSupportsModelFlag();
            e.supportsConfigDir = p.isSupportsConfigDir();
            e.supportsMcpConfigFlag = p.isSupportsMcpConfigFlag();
            e.requiresResourceDuplication = p.isRequiresResourceDuplication();
            e.modelUsageField = p.getModelUsageField() != null ? p.getModelUsageField() : "";
            e.supportedModes = serializeModes(p.getSupportedModes());
            e.ensureCopilotInstructions = p.isEnsureCopilotInstructions();
            e.ensureCopilotAgents = p.isEnsureCopilotAgents();
            e.ensureClaudeInstructions = p.isEnsureClaudeInstructions();
            e.usePluginPermissions = p.isUsePluginPermissions();
            e.excludeAgentBuiltInTools = p.isExcludeAgentBuiltInTools();
            return e;
        }

        @NotNull
        AgentProfile toProfile() {
            AgentProfile p = new AgentProfile();
            p.setId(id);
            p.setDisplayName(displayName);
            p.setBuiltIn(builtIn);
            p.setBinaryName(binaryName);
            p.setAlternateNames(splitComma(alternateNames));
            p.setInstallHint(installHint);
            p.setCustomBinaryPath(customBinaryPath);
            p.setAcpArgs(splitSpace(acpArgs));
            try {
                p.setMcpMethod(McpInjectionMethod.valueOf(mcpMethod));
            } catch (IllegalArgumentException e) {
                p.setMcpMethod(McpInjectionMethod.CONFIG_FLAG);
            }
            p.setMcpConfigTemplate(mcpConfigTemplate);
            p.setMcpEnvVarName(mcpEnvVarName);
            p.setSupportsModelFlag(supportsModelFlag);
            p.setSupportsConfigDir(supportsConfigDir);
            p.setSupportsMcpConfigFlag(supportsMcpConfigFlag);
            p.setRequiresResourceDuplication(requiresResourceDuplication);
            p.setModelUsageField(modelUsageField);
            p.setSupportedModes(deserializeModes(supportedModes));
            p.setEnsureCopilotInstructions(ensureCopilotInstructions);
            p.setEnsureCopilotAgents(ensureCopilotAgents);
            p.setEnsureClaudeInstructions(ensureClaudeInstructions);
            p.setUsePluginPermissions(usePluginPermissions);
            p.setExcludeAgentBuiltInTools(excludeAgentBuiltInTools);
            return p;
        }

        @NotNull
        private static List<String> splitComma(@NotNull String s) {
            if (s.isEmpty()) return new ArrayList<>();
            List<String> result = new ArrayList<>();
            for (String part : s.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        }

        @NotNull
        private static List<String> splitSpace(@NotNull String s) {
            if (s.isEmpty()) return new ArrayList<>();
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
            if (s.isEmpty()) return new ArrayList<>();
            List<AgentMode> result = new ArrayList<>();
            for (String entry : s.split(";")) {
                String[] parts = entry.split(":", 2);
                if (parts.length == 2 && !parts[0].isEmpty()) {
                    result.add(new AgentMode(parts[0].trim(), parts[1].trim()));
                }
            }
            return result;
        }
    }
}
