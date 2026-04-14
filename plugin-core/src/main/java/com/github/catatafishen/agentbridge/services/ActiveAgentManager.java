package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.acp.client.CopilotClient;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.github.catatafishen.agentbridge.agent.AgentRegistry;
import com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient;
import com.github.catatafishen.agentbridge.agent.codex.CodexAppServerClient;
import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.ProfileBasedAgentConfig;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.session.SessionSwitchService;
import com.github.catatafishen.agentbridge.settings.ChatInputSettings;
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
    private static final String KEY_FOLLOW_AGENT_FILES = "agent.followAgentFiles";
    private static final String KEY_INJECT_CONV_HISTORY = "agent.injectConversationHistory";
    private static final String KEY_AUTO_CONNECT = "agent.autoConnect";
    private static final String KEY_CUSTOM_ACP_COMMAND = "agent.customAcpCommand";
    private static final String KEY_SHARED_TURN_TIMEOUT_MINUTES = "agent.sharedTurnTimeoutMinutes";
    private static final String KEY_SHARED_INACTIVITY_TIMEOUT_SECONDS = "agent.sharedInactivityTimeoutSeconds";
    private static final String KEY_SHARED_MAX_TOOL_CALLS = "agent.sharedMaxToolCallsPerTurn";
    static final int DEFAULT_TURN_TIMEOUT_MINUTES = 120;
    static final int DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 3000;
    static final int DEFAULT_MAX_TOOL_CALLS_PER_TURN = 0;

    private final Project project;
    private volatile boolean acpConnected;

    private volatile AbstractAgentClient acpClient;
    private AgentConfig cachedConfig;
    private GenericSettings cachedSettings;
    private GenericAgentUiSettings cachedUiSettings;
    private volatile boolean started;

    /**
     * Whether the next {@link #start()} should launch Copilot in remote control mode
     * ({@code --remote} flag). Only applies to {@link CopilotClient} instances.
     */
    private boolean remoteModeNextStart = false;

    /**
     * URL listener to attach to the next {@link CopilotClient} when remote mode is active.
     * Called once the CLI outputs the remote session URL to stderr; cleared after firing.
     */
    private @Nullable java.util.function.Consumer<String> pendingRemoteUrlListener = null;

    /**
     * Error listener to attach to the next {@link CopilotClient} when remote mode is active.
     * Called if the CLI outputs a "remote sessions not enabled" error to stderr; cleared after firing.
     */
    private @Nullable java.util.function.Consumer<String> pendingRemoteErrorListener = null;

    public ActiveAgentManager(@NotNull Project project) {
        this.project = project;
        LOG.info("ActiveAgentManager initialised for project: " + project.getName());
    }

    @NotNull
    public static ActiveAgentManager getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ActiveAgentManager.class);
    }

    // ── Remote mode ──────────────────────────────────────────────────────────

    /**
     * Enables or disables Copilot remote control mode for the next {@link #start()}.
     * When {@code true}, the Copilot CLI will be launched with {@code --remote} and
     * the session URL extracted from stderr will be forwarded to any registered listener.
     * Automatically reset to {@code false} after each connect to avoid stale state.
     */
    public synchronized void setRemoteMode(boolean remote) {
        this.remoteModeNextStart = remote;
    }

    /**
     * Returns {@code true} if Copilot remote mode is enabled for the next {@link #start()}.
     */
    public synchronized boolean isRemoteMode() {
        return remoteModeNextStart;
    }

    /**
     * Registers a listener that will be called with the Copilot remote session URL once
     * the CLI outputs it to stderr. Set to {@code null} to clear. The listener is
     * automatically cleared after firing (fire-once semantics managed by {@link CopilotClient}).
     */
    public synchronized void setRemoteUrlListener(@Nullable java.util.function.Consumer<String> listener) {
        this.pendingRemoteUrlListener = listener;
    }

    /**
     * Registers a listener that will be called with the error message if the CLI reports
     * that remote sessions are not enabled for this repository. Set to {@code null} to clear.
     * Cleared after firing (fire-once semantics managed by {@link CopilotClient}).
     */
    public synchronized void setRemoteErrorListener(@Nullable java.util.function.Consumer<String> listener) {
        this.pendingRemoteErrorListener = listener;
    }

    void configureCopilotClientForStart(@NotNull CopilotClient copilotClient) {
        if (remoteModeNextStart) {
            copilotClient.setRemoteMode(true);
        }
        java.util.function.Consumer<String> urlListener = pendingRemoteUrlListener;
        if (urlListener != null) {
            copilotClient.setRemoteUrlListener(urlListener);
        }
        java.util.function.Consumer<String> errorListener = pendingRemoteErrorListener;
        if (errorListener != null) {
            copilotClient.setRemoteErrorListener(errorListener);
        }
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

        // Kick off the session export. onAgentSwitch dispatches work to a pooled thread
        // and stores a CompletableFuture so the new agent can wait before createSession().
        try {
            SessionSwitchService
                .getInstance(project)
                .onAgentSwitch(previousId, profileId);
        } catch (Exception e) {
            LOG.warn("SessionSwitchService.onAgentSwitch failed", e);
        }
    }

    // ── Agent lifecycle (absorbed from AgentService) ─────────────────────────

    /**
     * Returns the agent configuration for the active profile.
     */
    @NotNull
    public AgentConfig getConfig() {
        if (cachedConfig == null) {
            cachedConfig = ProfileBasedAgentConfig.create(getActiveProfile(), ToolRegistry.getInstance(project), project);
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

    private static final String KEY_BRANCH_SESSION_AT_STARTUP = "agent.branchSessionAtStartup";

    /**
     * Whether to snapshot the current session before each new session starts.
     * When {@code true}, a copy of the current session JSONL is saved with a timestamp label
     * so the user can revert to the state captured at that point via the session history picker.
     */
    public boolean isBranchSessionAtStartup() {
        return PropertiesComponent.getInstance(project).getBoolean(KEY_BRANCH_SESSION_AT_STARTUP, false);
    }

    public void setBranchSessionAtStartup(boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(KEY_BRANCH_SESSION_AT_STARTUP, enabled, false);
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

    @Nullable
    public String checkAuthentication() {
        if (started && acpClient != null && acpClient.isHealthy()) {
            return acpClient.checkAuthentication();
        }
        return resolvePreStartAuth(getActiveProfileId());
    }

    /**
     * Returns an auth error for pre-start credential checks, or {@code null} when no check is
     * possible without a running process. Only Codex has a synchronous credential check; all other
     * agent types defer auth errors until the agent is actually started.
     * <p>Static and package-private so it can be exercised in unit tests without a live platform.
     */
    static @Nullable String resolvePreStartAuth(@Nullable String agentId) {
        if (CodexAppServerClient.PROFILE_ID.equals(agentId)) {
            return CodexAppServerClient.checkCredentials();
        }
        return null;
    }

    /**
     * Start the agent process for the active profile.
     */
    public synchronized void start() {
        if (started && acpClient != null && acpClient.isHealthy()) {
            LOG.debug("Agent client already running for " + getActiveProfile().getDisplayName());
            return;
        }

        // Wait for any pending session export to finish before the ACP client starts.
        // CopilotClient.buildCommand() reads resumeSessionId to build the --resume CLI
        // flag, so the export must complete before the process is launched.
        SessionSwitchService.getInstance(project).awaitPendingExport(10_000);

        try {
            String agentId = getActiveProfileId();
            AgentProfile profile = getActiveProfile();
            LOG.info("Starting agent " + agentId + " (" + profile.getDisplayName() + ") for project: " + project.getName());

            if (acpClient != null) {
                acpClient.close();
            }

            clearCachedConfig();

            switch (profile.getTransportType()) {
                case CLAUDE_CLI -> {
                    int mcpPort = resolveMcpPort();
                    AgentConfig config = resolveStartConfig();
                    acpClient = new ClaudeCliClient(profile, config, ToolRegistry.getInstance(project), project, mcpPort);
                }
                case CODEX_APP_SERVER -> {
                    int mcpPort = resolveMcpPort();
                    AgentConfig config = resolveStartConfig();
                    acpClient = new CodexAppServerClient(profile, config, ToolRegistry.getInstance(project), project, mcpPort);
                }
                case ACP -> acpClient = createAcpClient(agentId);
            }

            // Apply persisted agent selection before start() builds the launch command.
            // Must happen before acpClient.start() since buildCommand() reads getCurrentAgentSlug().
            String savedAgent = getSettings().getSelectedAgent();
            if (!savedAgent.isEmpty()) {
                acpClient.setCurrentAgentSlug(savedAgent);
            }

            // Wire remote mode before start() so buildCommand() picks up the --remote flag.
            if (acpClient instanceof CopilotClient copilotClient) {
                configureCopilotClientForStart(copilotClient);
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

    public synchronized void stop() {
        if (!started) return;
        started = false;
        AbstractAgentClient clientToStop = acpClient;
        acpClient = null;
        if (clientToStop != null) {
            try {
                clientToStop.close();
            } catch (Exception e) {
                LOG.error("Failed to stop ACP client", e);
            }
        }
    }

    /**
     * Restart the agent process.
     *
     * <p>Before stopping the CLI, exports the current v2 session to the agent's native
     * format. This ensures the native session directory has a valid {@code events.jsonl}
     * (or equivalent) so the CLI can resume on restart — even if the previous CLI process
     * was killed before flushing its event log to disk.</p>
     */
    public synchronized void restart() {
        LOG.info("Restarting ACP client");

        // Export the v2 session to native format before stopping the CLI.
        // start() calls awaitPendingExport() to wait for completion.
        SessionSwitchService.getInstance(project).exportForRestart(getActiveProfileId());

        stop();
        clearCachedConfig();
        start();
    }

    @Override
    public void dispose() {
        LOG.info("ActiveAgentManager disposed");

        // Export the v2 session before shutting down, so that the next IDE startup
        // can resume via --resume. This must be synchronous (best-effort with timeout)
        // because async tasks may not complete during IDE shutdown.
        try {
            SessionSwitchService.getInstance(project).exportForRestart(getActiveProfileId());
            SessionSwitchService.getInstance(project).awaitPendingExport(3000);
        } catch (Exception e) {
            LOG.warn("Failed to export session during dispose: " + e.getMessage());
        }

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
            AgentConfig config = ProfileBasedAgentConfig.create(profile, ToolRegistry.getInstance(project), project);
            cachedConfig = config;
            return config;
        }

        // User has customised the command — use CommandOverrideAgentConfig
        LOG.info("Using custom start command for " + profile.getDisplayName() + ": " + storedCommand);
        AgentConfig realConfig = ProfileBasedAgentConfig.create(profile, ToolRegistry.getInstance(project), project);
        cachedConfig = realConfig;
        return new CommandOverrideAgentConfig(realConfig, storedCommand);
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

    static int parseIntOrDefault(@Nullable String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static int clamp(int value, int min, int max) {
        return Math.clamp(value, min, max);
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
        return ChatInputSettings.getInstance().getFileSearchTrigger();
    }

    public static void setAttachTriggerChar(@NotNull String trigger) {
        ChatInputSettings.getInstance().setFileSearchTrigger(trigger);
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

    /**
     * Returns {@code true} if the current agent client is alive and healthy,
     * without triggering an automatic restart. Safe to call from error handlers
     * where auto-starting a new process would be undesirable.
     */
    public boolean isClientHealthy() {
        return started && acpClient != null && acpClient.isHealthy();
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
