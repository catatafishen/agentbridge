package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentClient;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentSettings;
import com.github.catatafishen.ideagentforcopilot.bridge.AnthropicDirectClient;
import com.github.catatafishen.ideagentforcopilot.bridge.ClaudeCliClient;
import com.github.catatafishen.ideagentforcopilot.bridge.CopilotAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.GenericAgentSettings;
import com.github.catatafishen.ideagentforcopilot.bridge.JunieAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.KiroAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.OpenCodeAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.ProfileBasedAgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
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
    private static final String KEY_AUTO_CONNECT = "agent.autoConnect";
    private static final String KEY_CUSTOM_ACP_COMMAND = "agent.customAcpCommand";

    private final Project project;
    private volatile boolean acpConnected;

    private AgentClient acpClient;
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
            cachedConfig = new ProfileBasedAgentConfig(getActiveProfile(), ToolRegistry.getInstance(project));
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

    /**
     * Returns the agent client, starting it if necessary.
     */
    @NotNull
    public AgentClient getClient() {
        if (!started || acpClient == null || !acpClient.isHealthy()) {
            start();
        }
        return acpClient;
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
            AgentProfile profile = getActiveProfile();
            LOG.info("Starting " + profile.getDisplayName() + " agent client for project: " + project.getName());

            if (acpClient != null) {
                acpClient.close();
            }

            clearCachedConfig();

            if (profile.getTransportType() == TransportType.ANTHROPIC_DIRECT) {
                acpClient = new AnthropicDirectClient(profile, ToolRegistry.getInstance(project), project);
            } else if (profile.getTransportType() == TransportType.CLAUDE_CLI) {
                int mcpPort = resolveMcpPort();
                acpClient = new ClaudeCliClient(profile, ToolRegistry.getInstance(project), project, mcpPort);
            } else {
                String projectPath = project.getBasePath();
                int mcpPort = resolveMcpPort();
                AgentConfig config = resolveStartConfig();
                AgentSettings agentSettings = createAgentSettings();
                ToolRegistry registry = ToolRegistry.getInstance(project);
                acpClient = createAcpClient(profile.getId(), config, agentSettings, registry, projectPath, mcpPort);
            }

            acpClient.start();
            started = true;

            LOG.info(profile.getDisplayName() + " agent client started");
        } catch (Exception e) {
            LOG.warn("Failed to start agent client", e);
            throw new IllegalStateException(e.getMessage(), e);
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
            AgentConfig config = new ProfileBasedAgentConfig(profile, ToolRegistry.getInstance(project));
            cachedConfig = config;
            return config;
        }

        // User has customised the command — use CommandOverrideAgentConfig
        LOG.info("Using custom start command for " + profile.getDisplayName() + ": " + storedCommand);
        AgentConfig realConfig = new ProfileBasedAgentConfig(profile, ToolRegistry.getInstance(project));
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
            cachedSettings = new GenericSettings(profileId);
            cachedUiSettings = new GenericAgentUiSettings(cachedSettings);
        }
    }

    /**
     * Instantiates the appropriate {@link AcpClient} subclass for the given profile ID.
     * Each built-in ACP profile has a dedicated subclass that encapsulates any
     * profile-specific {@link com.github.catatafishen.ideagentforcopilot.bridge.AgentClient}
     * overrides. User-created profiles fall back to the generic {@link AcpClient}.
     */
    @NotNull
    private static AcpClient createAcpClient(@NotNull String profileId,
                                              @NotNull AgentConfig config,
                                              @NotNull AgentSettings settings,
                                              @NotNull ToolRegistry registry,
                                              @Nullable String projectBasePath,
                                              int mcpPort) {
        return switch (profileId) {
            case AgentProfileManager.COPILOT_PROFILE_ID ->
                new CopilotAcpClient(config, settings, registry, projectBasePath, mcpPort);
            case AgentProfileManager.OPENCODE_PROFILE_ID ->
                new OpenCodeAcpClient(config, settings, registry, projectBasePath, mcpPort);
            case AgentProfileManager.JUNIE_PROFILE_ID ->
                new JunieAcpClient(config, settings, registry, projectBasePath, mcpPort);
            case AgentProfileManager.KIRO_PROFILE_ID ->
                new KiroAcpClient(config, settings, registry, projectBasePath, mcpPort);
            default ->
                new AcpClient(config, settings, registry, projectBasePath, mcpPort);
        };
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
