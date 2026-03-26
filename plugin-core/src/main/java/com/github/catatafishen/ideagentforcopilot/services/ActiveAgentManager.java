package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient;
import com.github.catatafishen.ideagentforcopilot.agent.AgentRegistry;
import com.github.catatafishen.ideagentforcopilot.agent.claude.AnthropicDirectClient;
import com.github.catatafishen.ideagentforcopilot.agent.claude.ClaudeCliClient;
import com.github.catatafishen.ideagentforcopilot.agent.codex.CodexAppServerClient;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentSettings;
import com.github.catatafishen.ideagentforcopilot.bridge.GenericAgentSettings;
import com.github.catatafishen.ideagentforcopilot.bridge.ProfileBasedAgentConfig;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Project service that manages which ACP agent profile is currently active,
 * and owns the ACP client lifecycle for that agent.
 *
 * <p>Replaces the old per-agent service classes (CopilotService, ClaudeService, etc.)
 * with a single profile-driven service. The active profile is looked up from
 * {@link AgentProfileManager}.</p>
 *
 * <p>Also hosts shared UI preferences that apply regardless of which agent is active
 * (attach trigger character, follow-agent-files).</p>
 */
@Service(Service.Level.PROJECT)
public final class ActiveAgentManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(ActiveAgentManager.class);

    private static final String KEY_ACTIVE_PROFILE = "agent.activeProfileId";
    private static final String KEY_ATTACH_TRIGGER = "agent.attachTriggerChar";
    private static final String DEFAULT_ATTACH_TRIGGER = "#";
    private static final String KEY_FOLLOW_AGENT_FILES = "agent.followAgentFiles";
    private static final String KEY_INJECT_CONV_HISTORY = "agent.injectConversationHistory";
    private static final String KEY_AUTO_CONNECT = "agent.autoConnect";
    private static final String KEY_CUSTOM_ACP_COMMAND = "agent.customAcpCommand";
    private static final String KEY_SHARED_TURN_TIMEOUT_MINUTES = "agent.sharedTurnTimeoutMinutes";
    private static final String KEY_SHARED_INACTIVITY_TIMEOUT_SECONDS = "agent.sharedInactivityTimeoutSeconds";
    private static final String KEY_SHARED_MAX_TOOL_CALLS = "agent.sharedMaxToolCallsPerTurn";
    private static final int DEFAULT_TURN_TIMEOUT_MINUTES = 120;
    private static final int DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_TURN = 0;

    private final Project project;
    private volatile boolean acpConnected;

    private AbstractAgentClient acpClient;
    private AgentConfig cachedConfig;
    private GenericSettings cachedSettings;
    private GenericAgentUiSettings cachedUiSettings;
    private volatile boolean started;

    public ActiveAgentManager(@NotNull Project project) {
        this.project = project;
        LOG.info("ActiveAgentManager initialised for project: " + project.getName());
    }

    @NotNull
    public static ActiveAgentManager getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ActiveAgentManager.class);
    }

    // ── Active profile ───────────────────────────────────────────────────────

    private final java.util.List<Runnable> switchListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addSwitchListener(@NotNull Runnable listener) {
        switchListeners.add(listener);
    }

    public void removeSwitchListener(@NotNull Runnable listener) {
        switchListeners.remove(listener);
    }

    /**
     * Returns the ID of the currently active agent profile.
     */
    @NotNull
    public String getActiveProfileId() {
        String stored = PropertiesComponent.getInstance(project).getValue(KEY_ACTIVE_PROFILE);
        if (stored != null && !stored.isEmpty()
            && AgentProfileManager.getInstance().getProfile(stored) != null) {
            return stored;
        }
        return AgentProfileManager.COPILOT_PROFILE_ID;
    }

    /**
     * Returns the currently active agent profile.
     */
    @NotNull
    public AgentProfile getActiveProfile() {
        String id = getActiveProfileId();
        AgentProfile profile = AgentProfileManager.getInstance().getProfile(id);
        if (profile != null) return profile;
        // Shouldn't happen, but fall back to Copilot
        return AgentProfileManager.getInstance().getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
    }

    /**
     * Switches the active agent profile. Stops the current connection if running.
     */
    public void switchAgent(@NotNull String profileId) {
        String previousId = getActiveProfileId();
        if (previousId.equals(profileId)) return;

        LOG.info("Switching active agent from " + previousId + " to " + profileId);
        stop();
        clearCachedConfig();
        PropertiesComponent.getInstance(project).setValue(KEY_ACTIVE_PROFILE, profileId);

        for (Runnable listener : switchListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.warn("Agent switch listener failed", e);
            }
        }
    }

    // ── Agent lifecycle (absorbed from AgentService) ─────────────────────────

    /**
     * Returns the agent configuration for the active profile.
     */
    @NotNull
    public AgentConfig getConfig() {
        if (cachedConfig == null) {
            cachedConfig = new ProfileBasedAgentConfig(getActiveProfile(), ToolRegistry.getInstance(project), project);
        }
        return cachedConfig;
    }

    /**
     * Returns the UI settings for the active profile.
     */
    @NotNull
    public AgentUiSettings getSettings() {
        ensureSettingsForActiveProfile();
        return cachedUiSettings;
    }

    public int getSharedTurnTimeoutMinutes() {
        return normalizeSharedTurnTimeoutMinutes(
            PropertiesComponent.getInstance(project).getValue(KEY_SHARED_TURN_TIMEOUT_MINUTES)
        );
    }

    public int getSharedTurnTimeoutSeconds() {
        return getSharedTurnTimeoutMinutes() * 60;
    }

    public void setSharedTurnTimeoutMinutes(int minutes) {
        PropertiesComponent.getInstance(project).setValue(
            KEY_SHARED_TURN_TIMEOUT_MINUTES,
            clamp(minutes, 1, 1440),
            DEFAULT_TURN_TIMEOUT_MINUTES
        );
    }

    public int getSharedInactivityTimeoutSeconds() {
        return normalizeSharedInactivityTimeoutSeconds(
            PropertiesComponent.getInstance(project).getValue(KEY_SHARED_INACTIVITY_TIMEOUT_SECONDS)
        );
    }

    public void setSharedInactivityTimeoutSeconds(int seconds) {
        PropertiesComponent.getInstance(project).setValue(
            KEY_SHARED_INACTIVITY_TIMEOUT_SECONDS,
            clamp(seconds, 30, 86_400),
            DEFAULT_INACTIVITY_TIMEOUT_SECONDS
        );
    }

    public int getSharedMaxToolCallsPerTurn() {
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        return normalizeSharedMaxToolCallsPerTurn(
            properties.getValue(KEY_SHARED_MAX_TOOL_CALLS),
            migrateLegacyMaxToolCallsPerTurn()
        );
    }

    public void setSharedMaxToolCallsPerTurn(int count) {
        PropertiesComponent.getInstance(project).setValue(
            KEY_SHARED_MAX_TOOL_CALLS,
            Math.max(0, count),
            DEFAULT_MAX_TOOL_CALLS_PER_TURN
        );
    }

    /**
     * Returns the agent client, starting it if necessary.
     */
    @NotNull
    public AbstractAgentClient getClient() {
        if (!started || acpClient == null || !acpClient.isHealthy()) {
            start();
        }
        return acpClient;
    }

    /**
     * Checks authentication without starting the agent process.
     * Uses {@link AbstractAgentClient#checkAuthenticationPreStart()} if the client isn't running,
     * or {@link AbstractAgentClient#checkAuthentication()} if it is.
     *
     * @return {@code null} if authenticated, or a human-readable error message
     */
    @Nullable
    public String checkAuthentication() {
        if (started && acpClient != null && acpClient.isHealthy()) {
            return acpClient.checkAuthentication();
        }
        // Client not running — try a pre-start credential check without launching the process.
        // For Codex, check credentials directly; for other agents, start the client.
        String agentId = getActiveProfileId();
        if (CodexAppServerClient.PROFILE_ID.equals(agentId)) {
            return CodexAppServerClient.checkCredentials();
        }
        return getClient().checkAuthentication();
    }

    /**
     * Start the agent process for the active profile.
     */
    public synchronized void start() {
        if (started && acpClient != null && acpClient.isHealthy()) {
            LOG.debug("Agent client already running for " + getActiveProfile().getDisplayName());
            return;
        }

        try {
            String agentId = getActiveProfileId();
            AgentProfile profile = getActiveProfile();
            LOG.info("Starting agent " + agentId + " (" + profile.getDisplayName() + ") for project: " + project.getName());

            if (acpClient != null) {
                acpClient.close();
            }

            clearCachedConfig();

            if (AnthropicDirectClient.PROFILE_ID.equals(agentId)) {
                acpClient = new AnthropicDirectClient(profile, ToolRegistry.getInstance(project), project);
            } else if (ClaudeCliClient.PROFILE_ID.equals(agentId)) {
                int mcpPort = resolveMcpPort();
                AgentConfig config = resolveStartConfig();
                acpClient = new ClaudeCliClient(profile, config, ToolRegistry.getInstance(project), project, mcpPort);
            } else if (CodexAppServerClient.PROFILE_ID.equals(agentId)) {
                int mcpPort = resolveMcpPort();
                AgentConfig config = resolveStartConfig();
                acpClient = new CodexAppServerClient(profile, config, ToolRegistry.getInstance(project), project, mcpPort);
            } else {
                acpClient = createAcpClient(agentId);
            }

            // Apply persisted agent selection before start() builds the launch command.
            // Must happen before acpClient.start() since buildCommand() reads getCurrentAgentSlug().
            String savedAgent = getSettings().getSelectedAgent();
            if (!savedAgent.isEmpty()) {
                acpClient.setCurrentAgentSlug(savedAgent);
            }

            acpClient.start();
            started = true;

            LOG.info(profile.getDisplayName() + " agent client started");
        } catch (Exception e) {
            LOG.warn("Failed to start agent client", e);
            String message = e.getMessage();
            if (e.getCause() != null && e.getCause().getMessage() != null) {
                message = e.getCause().getMessage();
            }
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * Stop the ACP client.
     */
    public synchronized void stop() {
        if (!started) return;
        try {
            LOG.info("Stopping ACP client...");
            if (acpClient != null) {
                acpClient.close();
            }
            started = false;
            acpClient = null;
        } catch (Exception e) {
            LOG.error("Failed to stop ACP client", e);
        }
    }

    /**
     * Restart the agent process.
     */
    public synchronized void restart() {
        LOG.info("Restarting ACP client");
        stop();
        clearCachedConfig();
        start();
    }

    @Override
    public void dispose() {
        LOG.info("ActiveAgentManager disposed");
        stop();
    }

    // ── MCP port resolution ──────────────────────────────────────────────────

    private int resolveMcpPort() {
        McpServerControl mcpServer = McpServerControl.getInstance(project);
        if (mcpServer != null) {
            if (mcpServer.isRunning() && mcpServer.getPort() > 0) {
                LOG.info("MCP server already running on port " + mcpServer.getPort());
                return mcpServer.getPort();
            }
            try {
                mcpServer.start();
                if (mcpServer.isRunning() && mcpServer.getPort() > 0) {
                    LOG.info("Auto-started MCP server on port " + mcpServer.getPort());
                    return mcpServer.getPort();
                }
            } catch (Exception e) {
                LOG.warn("Failed to auto-start MCP server: " + e.getMessage());
            }
        }
        LOG.warn("No MCP server available — IntelliJ code tools will be unavailable for this session.");
        return 0;
    }

    /**
     * Returns the config to use for starting the ACP process.
     * If the user has customised the start command, wraps it as a command override.
     */
    @NotNull
    private AgentConfig resolveStartConfig() {
        AgentProfile profile = getActiveProfile();
        String storedCommand = getCustomAcpCommand();
        String defaultCommand = profile.getDefaultStartCommand();

        if (storedCommand.isEmpty() || storedCommand.equals(defaultCommand)) {
            AgentConfig config = new ProfileBasedAgentConfig(profile, ToolRegistry.getInstance(project), project);
            cachedConfig = config;
            return config;
        }

        // User has customised the command — use CommandOverrideAgentConfig
        LOG.info("Using custom start command for " + profile.getDisplayName() + ": " + storedCommand);
        AgentConfig realConfig = new ProfileBasedAgentConfig(profile, ToolRegistry.getInstance(project), project);
        cachedConfig = realConfig;
        return new CommandOverrideAgentConfig(realConfig, storedCommand);
    }

    @NotNull
    private AgentSettings createAgentSettings() {
        ensureSettingsForActiveProfile();
        return new GenericAgentSettings(cachedSettings, project);
    }

    private void ensureSettingsForActiveProfile() {
        String profileId = getActiveProfileId();
        if (cachedSettings == null || !cachedSettings.getPrefix().equals(profileId + ".")) {
            cachedSettings = new GenericSettings(profileId, project);
            cachedUiSettings = new GenericAgentUiSettings(cachedSettings);
        }
    }

    static int normalizeSharedTurnTimeoutMinutes(@Nullable String storedMinutes) {
        return clamp(parseIntOrDefault(storedMinutes, DEFAULT_TURN_TIMEOUT_MINUTES), 1, 1440);
    }

    static int normalizeSharedInactivityTimeoutSeconds(@Nullable String storedSeconds) {
        return clamp(parseIntOrDefault(storedSeconds, DEFAULT_INACTIVITY_TIMEOUT_SECONDS), 30, 86_400);
    }

    static int normalizeSharedMaxToolCallsPerTurn(@Nullable String storedCount, int legacyCount) {
        if (storedCount != null) {
            return Math.max(0, parseIntOrDefault(storedCount, DEFAULT_MAX_TOOL_CALLS_PER_TURN));
        }
        return Math.max(0, legacyCount);
    }

    private int migrateLegacyMaxToolCallsPerTurn() {
        return Math.max(0, findLegacySharedInt(GenericSettings::getMaxToolCallsPerTurn, DEFAULT_MAX_TOOL_CALLS_PER_TURN));
    }

    private int findLegacySharedInt(@NotNull java.util.function.ToIntFunction<GenericSettings> reader, int defaultValue) {
        GenericSettings activeSettings = new GenericSettings(getActiveProfileId(), project);
        int activeValue = reader.applyAsInt(activeSettings);
        if (activeValue != defaultValue) {
            return activeValue;
        }
        for (AgentProfile profile : AgentProfileManager.getInstance().getAllProfiles()) {
            GenericSettings settings = new GenericSettings(profile.getId(), project);
            int value = reader.applyAsInt(settings);
            if (value != defaultValue) {
                return value;
            }
        }
        return defaultValue;
    }

    private static int parseIntOrDefault(@Nullable String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @NotNull
    private AbstractAgentClient createAcpClient(@NotNull String profileId) {
        AbstractAgentClient client = AgentRegistry.create(profileId, project);
        if (client != null) {
            return client;
        }
        LOG.warn("Unknown ACP profile ID: " + profileId + " — no client registered in AgentRegistry");
        throw new IllegalArgumentException("Unknown ACP agent profile: " + profileId);
    }

    private void clearCachedConfig() {
        cachedConfig = null;
        cachedSettings = null;
        cachedUiSettings = null;
    }

    // ── Shared UI preferences (agent-agnostic) ──────────────────────────────

    @NotNull
    public static String getAttachTriggerChar() {
        return PropertiesComponent.getInstance().getValue(KEY_ATTACH_TRIGGER, DEFAULT_ATTACH_TRIGGER);
    }

    public static void setAttachTriggerChar(@NotNull String trigger) {
        PropertiesComponent.getInstance().setValue(KEY_ATTACH_TRIGGER, trigger, DEFAULT_ATTACH_TRIGGER);
    }

    public static boolean getFollowAgentFiles(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(KEY_FOLLOW_AGENT_FILES, true);
    }

    public static void setFollowAgentFiles(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(KEY_FOLLOW_AGENT_FILES, enabled, true);
    }

    public static boolean getInjectConversationHistory(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(KEY_INJECT_CONV_HISTORY, false);
    }

    public static void setInjectConversationHistory(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(KEY_INJECT_CONV_HISTORY, enabled, false);
    }

    // ── ACP connection state ─────────────────────────────────────────────────

    public boolean isConnected() {
        return acpConnected;
    }

    public void setConnected(boolean connected) {
        this.acpConnected = connected;
    }

    // ── Auto-connect on startup ──────────────────────────────────────────────

    public boolean isAutoConnect() {
        return PropertiesComponent.getInstance(project).getBoolean(KEY_AUTO_CONNECT, false);
    }

    public void setAutoConnect(boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(KEY_AUTO_CONNECT, enabled, false);
    }

    // ── Custom ACP command (per-profile) ─────────────────────────────────────

    @NotNull
    public String getCustomAcpCommand() {
        String profileId = getActiveProfileId();
        String stored = PropertiesComponent.getInstance(project)
            .getValue(KEY_CUSTOM_ACP_COMMAND + "." + profileId);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        return getActiveProfile().getDefaultStartCommand();
    }

    public void setCustomAcpCommand(@NotNull String command) {
        String profileId = getActiveProfileId();
        String defaultCommand = getActiveProfile().getDefaultStartCommand();
        String value = command.equals(defaultCommand) ? "" : command;
        PropertiesComponent.getInstance(project)
            .setValue(KEY_CUSTOM_ACP_COMMAND + "." + profileId, value, "");
    }

    @NotNull
    public String getCustomAcpCommandFor(@NotNull String profileId) {
        String stored = PropertiesComponent.getInstance(project)
            .getValue(KEY_CUSTOM_ACP_COMMAND + "." + profileId);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        AgentProfile profile = AgentProfileManager.getInstance().getProfile(profileId);
        return profile != null ? profile.getDefaultStartCommand() : "";
    }

    public void setCustomAcpCommandFor(@NotNull String profileId, @NotNull String command) {
        AgentProfile profile = AgentProfileManager.getInstance().getProfile(profileId);
        String defaultCommand = profile != null ? profile.getDefaultStartCommand() : "";
        String value = command.equals(defaultCommand) ? "" : command;
        PropertiesComponent.getInstance(project)
            .setValue(KEY_CUSTOM_ACP_COMMAND + "." + profileId, value, "");
    }

    // ── Backwards compatibility ──────────────────────────────────────────────

    /**
     * Returns a list of all available profile IDs and display names, for use
     * by UI components that need to show an agent selector.
     */
    @NotNull
    public List<AgentProfile> getAvailableProfiles() {
        return AgentProfileManager.getInstance().getAllProfiles();
    }
}
