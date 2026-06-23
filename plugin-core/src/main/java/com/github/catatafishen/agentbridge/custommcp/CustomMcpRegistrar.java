package com.github.catatafishen.agentbridge.custommcp;

import com.github.catatafishen.agentbridge.custommcp.oauth.McpOAuthFlow;
import com.github.catatafishen.agentbridge.custommcp.oauth.McpOAuthRequiredException;
import com.github.catatafishen.agentbridge.custommcp.oauth.McpOAuthTokenStore;
import com.github.catatafishen.agentbridge.custommcp.oauth.McpOAuthTokens;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the lifecycle of proxy tools for all configured custom MCP servers.
 * Connects to each enabled server at startup, discovers its tools, and registers
 * {@link CustomMcpToolProxy} instances in {@link PsiBridgeService}.
 * Also handles re-sync when settings are updated.
 * <p>
 * Maintains clients ({@link CustomMcpClient} for HTTP, {@link CustomMcpStdioClient}
 * for stdio) per server so that MCP sessions are preserved across tool calls
 * and properly terminated when servers are removed or disabled.
 */
@Service(Service.Level.PROJECT)
public final class CustomMcpRegistrar implements Disposable {

    private static final Logger LOG = Logger.getInstance(CustomMcpRegistrar.class);

    private final Project project;
    private final List<ServerStatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final List<ServerStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final CustomMcpSettings.SettingsListener settingsListener;

    /**
     * Maps server ID → set of proxy tool IDs currently registered for that server.
     */
    private final Map<String, Set<String>> registeredByServer = new HashMap<>();

    /**
     * Maps server ID → the AutoCloseable client for that server (either
     * {@link CustomMcpClient} or {@link CustomMcpStdioClient}).
     */
    private final Map<String, AutoCloseable> clientByServer = new HashMap<>();

    /**
     * Maps server ID → last known status for the UI panel.
     */
    private final Map<String, ServerStatus> statusByServer = new HashMap<>();

    /**
     * Maps server ID → optional status detail shown in the settings table.
     */
    private final Map<String, String> statusDetailByServer = new HashMap<>();

    public CustomMcpRegistrar(@NotNull Project project) {
        this.project = project;
        this.settingsListener = this::notifyAllStateListeners;
        CustomMcpSettings.getInstance(project).addListener(settingsListener);
    }

    public static CustomMcpRegistrar getInstance(@NotNull Project project) {
        return project.getService(CustomMcpRegistrar.class);
    }

    // ── Status listener API (for UI panel) ───────────────────────────

    public void addStatusListener(@NotNull ServerStatusListener listener) {
        statusListeners.add(listener);
    }

    public void removeStatusListener(@NotNull ServerStatusListener listener) {
        statusListeners.remove(listener);
    }

    public void addStateListener(@NotNull ServerStateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(@NotNull ServerStateListener listener) {
        stateListeners.remove(listener);
    }

    @NotNull
    public Map<String, ServerStatus> getStatusSnapshot() {
        synchronized (this) {
            return new HashMap<>(statusByServer);
        }
    }

    @NotNull
    public Map<String, String> getStatusDetailSnapshot() {
        synchronized (this) {
            return new HashMap<>(statusDetailByServer);
        }
    }

    @NotNull
    public Map<String, ServerState> getStateSnapshot() {
        CustomMcpSettings settings = CustomMcpSettings.getInstance(project);
        Map<String, Boolean> enabledByServer = new HashMap<>();
        for (CustomMcpServerConfig server : settings.getServers()) {
            enabledByServer.put(server.getId(), server.isEnabled());
        }

        synchronized (this) {
            Map<String, ServerState> snapshot = new HashMap<>();
            for (Map.Entry<String, Boolean> entry : enabledByServer.entrySet()) {
                String serverId = entry.getKey();
                ServerStatus status = statusByServer.getOrDefault(serverId, ServerStatus.UNKNOWN);
                String detail = statusDetailByServer.getOrDefault(serverId, defaultStatusDetail(status));
                snapshot.put(serverId, new ServerState(entry.getValue(), status, detail));
            }
            return snapshot;
        }
    }

    /**
     * Checks all configured servers' statuses asynchronously and notifies listeners.
     */
    public void checkAllStatuses() {
        CustomMcpSettings settings = CustomMcpSettings.getInstance(project);
        List<CustomMcpServerConfig> servers = settings.getServers();

        for (CustomMcpServerConfig server : servers) {
            if (!server.isEnabled() || !server.isConfigured()) {
                updateStatus(server.getId(), ServerStatus.DISABLED, "Disabled");
            } else {
                updateStatus(server.getId(), ServerStatus.LOADING, "Checking...");
            }
        }

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (CustomMcpServerConfig server : servers) {
                if (!server.isEnabled() || !server.isConfigured()) continue;
                ProbeResult result = probeServer(server);
                updateStatus(server.getId(), result.status(), result.detail());
            }
        });
    }

    @NotNull
    private ProbeResult probeServer(@NotNull CustomMcpServerConfig server) {
        try {
            if (server.isStdio()) {
                CustomMcpStdioClient client = new CustomMcpStdioClient(
                    server.getEffectiveCommand(), server.getEffectiveArgs(), server.getEnvironmentMap()
                );
                try {
                    client.initialize();
                    return new ProbeResult(ServerStatus.CONNECTED, "Running");
                } finally {
                    client.close();
                }
            } else {
                String url = server.getEffectiveUrl();
                if (url.isBlank()) return new ProbeResult(ServerStatus.DISABLED, "Disabled");
                CustomMcpClient client = new CustomMcpClient(url, null, server.getHeadersMap());
                try {
                    client.initialize();
                    return new ProbeResult(ServerStatus.CONNECTED, "Running");
                } finally {
                    client.close();
                }
            }
        } catch (Exception e) {
            LOG.debug("Status check failed for '" + server.getName() + "': " + e.getMessage());
            return new ProbeResult(ServerStatus.ERROR, e.getMessage() != null ? e.getMessage() : "Connection failed");
        }
    }

    private void updateStatus(@NotNull String serverId, @NotNull ServerStatus status, @Nullable String detail) {
        synchronized (this) {
            statusByServer.put(serverId, status);
            statusDetailByServer.put(serverId, detail != null ? detail : defaultStatusDetail(status));
        }
        for (ServerStatusListener listener : statusListeners) {
            listener.statusChanged(serverId, status);
        }
        notifyStateListeners(serverId);
    }

    private void notifyStateListeners(@NotNull String serverId) {
        ServerState state = getStateSnapshot().get(serverId);
        if (state == null) {
            state = new ServerState(false, ServerStatus.UNKNOWN, defaultStatusDetail(ServerStatus.UNKNOWN));
        }
        for (ServerStateListener listener : stateListeners) {
            listener.stateChanged(serverId, state);
        }
    }

    private void notifyAllStateListeners() {
        for (ServerStateListener listener : stateListeners) {
            listener.structureChanged();
        }
        for (String serverId : getStateSnapshot().keySet()) {
            notifyStateListeners(serverId);
        }
    }

    @NotNull
    private static String defaultStatusDetail(@NotNull ServerStatus status) {
        return switch (status) {
            case CONNECTED -> "Running";
            case LOADING -> "Checking...";
            case ERROR -> "Connection failed";
            case DISABLED -> "Disabled";
            case UNKNOWN -> "Unknown";
        };
    }

    /**
     * Reads current settings and synchronises proxy tool registrations.
     * Unregisters tools for removed/disabled servers, then connects to
     * newly enabled servers and registers their tools.
     * <p>
     * Safe to call on any thread (uses pooled HTTP connections, no EDT usage).
     * Synchronized to prevent concurrent modification of internal maps
     * when called from both startup and settings-apply threads.
     */
    public synchronized void syncRegistrations() {
        PsiBridgeService bridge = PsiBridgeService.getInstance(project);
        CustomMcpSettings settings = CustomMcpSettings.getInstance(project);
        List<CustomMcpServerConfig> servers = settings.getServers();

        for (CustomMcpServerConfig server : servers) {
            if (!server.isEnabled() || !server.isConfigured()) {
                updateStatus(server.getId(), ServerStatus.DISABLED, server.isEnabled() ? "Incomplete configuration" : "Disabled");
            }
        }

        Set<String> desiredServerIds = collectActiveServerIds(servers);

        // Unregister tools for servers no longer in the active set
        Set<String> toRemove = computeServersToRemove(registeredByServer.keySet(), desiredServerIds);
        for (String serverId : toRemove) {
            unregisterServerTools(bridge, serverId);
            registeredByServer.remove(serverId);
            updateStatus(serverId, ServerStatus.DISABLED, "Disabled");
        }

        // Connect to each enabled server and register its tools
        for (CustomMcpServerConfig server : servers) {
            if (!server.isEnabled() || !server.isConfigured()) continue;
            updateStatus(server.getId(), ServerStatus.LOADING, "Connecting...");
            if (server.isHttp()) {
                connectAndRegisterHttp(bridge, server);
            } else {
                connectAndRegisterStdio(bridge, server);
            }
        }
    }

    // ── Extracted pure-logic helpers (package-private for testing) ──────

    /**
     * Collects server IDs that should be active: enabled, with either a non-blank URL
     * (HTTP) or a non-blank command (stdio).
     */
    static Set<String> collectActiveServerIds(List<CustomMcpServerConfig> servers) {
        Set<String> ids = new HashSet<>();
        for (CustomMcpServerConfig server : servers) {
            if (server.isEnabled() && server.isConfigured()) {
                ids.add(server.getId());
            }
        }
        return ids;
    }

    /**
     * Computes which server IDs should be removed: present in current but not in desired.
     */
    static Set<String> computeServersToRemove(Set<String> currentServerIds, Set<String> desiredServerIds) {
        Set<String> toRemove = new HashSet<>();
        for (String serverId : currentServerIds) {
            if (!desiredServerIds.contains(serverId)) {
                toRemove.add(serverId);
            }
        }
        return toRemove;
    }

    /**
     * Formats a connection-failure warning message for logging.
     */
    static String formatConnectionError(String serverName, String serverUrl, String errorMessage) {
        return "Failed to connect to custom MCP server '" + serverName
            + "' at " + serverUrl + ": " + errorMessage;
    }

    /**
     * Closes all tracked MCP client sessions when the project/service is disposed.
     * Called by IntelliJ on project close or IDE shutdown.
     */
    @Override
    public synchronized void dispose() {
        CustomMcpSettings.getInstance(project).removeListener(settingsListener);
        for (AutoCloseable client : clientByServer.values()) {
            try { client.close(); } catch (Exception ignored) {}
        }
        clientByServer.clear();
        registeredByServer.clear();
        statusByServer.clear();
        statusDetailByServer.clear();
        statusListeners.clear();
        stateListeners.clear();
    }

    // ── HTTP server connection ───────────────────────────────────────

    /**
     * Connects to one HTTP server, discovers its tools, and registers proxy instances.
     * Replaces any previously registered tools for the same server ID.
     * <p>
     * If the server returns HTTP 401, the OAuth PKCE flow is triggered automatically:
     * the user's browser opens for authentication, and on success the new tokens are stored
     * and the connection is retried. Subsequent connections reuse the stored token; expired
     * tokens are silently refreshed before connecting.
     */
    private void connectAndRegisterHttp(@NotNull PsiBridgeService bridge, @NotNull CustomMcpServerConfig server) {
        String token = resolveToken(server.getEffectiveUrl());
        CustomMcpClient client = new CustomMcpClient(server.getEffectiveUrl(), token, server.getHeadersMap());
        try {
            doConnectAndRegisterHttp(bridge, server, client);
        } catch (McpOAuthRequiredException e) {
            client.close();
            LOG.info("OAuth required for '" + server.getName() + "' — starting authentication flow");
            McpOAuthTokens tokens = runOAuthFlow(server);
            if (tokens == null) return;
            CustomMcpClient authedClient = new CustomMcpClient(server.getEffectiveUrl(), tokens.accessToken(), server.getHeadersMap());
            try {
                doConnectAndRegisterHttp(bridge, server, authedClient);
            } catch (Exception retryEx) {
                authedClient.close();
                updateStatus(server.getId(), ServerStatus.ERROR, retryEx.getMessage());
                LOG.warn(formatConnectionError(server.getName(), server.getEffectiveUrl(), retryEx.getMessage()));
            }
        } catch (Exception e) {
            client.close();
            updateStatus(server.getId(), ServerStatus.ERROR, e.getMessage());
            LOG.warn(formatConnectionError(server.getName(), server.getEffectiveUrl(), e.getMessage()));
        }
    }

    /**
     * Core connection logic for HTTP servers: initializes the MCP session, lists tools,
     * and registers proxy instances.
     */
    private void doConnectAndRegisterHttp(
        @NotNull PsiBridgeService bridge,
        @NotNull CustomMcpServerConfig server,
        @NotNull CustomMcpClient client
    ) throws IOException {
        client.initialize();
        List<CustomMcpClient.ToolInfo> tools = client.listTools();
        finishRegistration(bridge, server, client, tools);
    }

    // ── Stdio server connection ──────────────────────────────────────

    /**
     * Connects to one stdio server (spawns process), discovers its tools,
     * and registers proxy instances.
     */
    private void connectAndRegisterStdio(@NotNull PsiBridgeService bridge, @NotNull CustomMcpServerConfig server) {
        CustomMcpStdioClient client = new CustomMcpStdioClient(
            server.getEffectiveCommand(), server.getEffectiveArgs(), server.getEnvironmentMap()
        );
        try {
            doConnectAndRegisterStdio(bridge, server, client);
        } catch (Exception e) {
            client.close();
            updateStatus(server.getId(), ServerStatus.ERROR, e.getMessage());
            LOG.warn(formatConnectionError(server.getName(), server.getCommand(), e.getMessage()));
        }
    }

    private void doConnectAndRegisterStdio(
        @NotNull PsiBridgeService bridge,
        @NotNull CustomMcpServerConfig server,
        @NotNull CustomMcpStdioClient client
    ) throws IOException {
        client.initialize();
        List<CustomMcpClient.ToolInfo> tools = client.listTools();
        finishRegistration(bridge, server, client, tools);
    }

    // ── Shared registration logic ────────────────────────────────────

    /**
     * Shared logic for both HTTP and stdio: if tools are empty, clean up;
     * otherwise register proxy tools under the server's prefix.
     */
    private void finishRegistration(
        @NotNull PsiBridgeService bridge,
        @NotNull CustomMcpServerConfig server,
        @NotNull Object rawClient,
        @NotNull List<CustomMcpClient.ToolInfo> tools
    ) {
        if (tools.isEmpty()) {
            LOG.info("Custom MCP server '" + server.getName() + "' reported no tools");
            closeClient(rawClient);
            updateStatus(server.getId(), ServerStatus.ERROR, "Server reported no tools");
            if (registeredByServer.containsKey(server.getId()) || clientByServer.containsKey(server.getId())) {
                unregisterServerTools(bridge, server.getId());
                registeredByServer.remove(server.getId());
            }
            return;
        }

        unregisterServerTools(bridge, server.getId());

        String endpoint = server.isHttp() ? server.getUrl() : server.getCommand();
        Set<String> registered = new HashSet<>();
        String prefix = server.toolPrefix();
        McpToolCaller caller = (McpToolCaller) rawClient;

        for (CustomMcpClient.ToolInfo toolInfo : tools) {
            CustomMcpToolProxy proxy = new CustomMcpToolProxy(
                prefix, caller, toolInfo, server.getInstructions()
            );
            bridge.registerTool(proxy);
            registered.add(proxy.id());
            LOG.info("Registered custom MCP proxy: " + proxy.id() + " → " + endpoint);
        }
        registeredByServer.put(server.getId(), registered);
        clientByServer.put(server.getId(), (AutoCloseable) rawClient);
        updateStatus(server.getId(), ServerStatus.CONNECTED, "Running");
    }

    private static void closeClient(@NotNull Object client) {
        try {
            if (client instanceof AutoCloseable c) c.close();
        } catch (Exception ignored) {
        }
    }

    // ── OAuth helpers ────────────────────────────────────────────────

    /**
     * Loads a stored bearer token for {@code serverUrl}, refreshing it first if it has expired.
     *
     * @return the access token string, or {@code null} if no token is stored
     */
    @Nullable
    private static String resolveToken(@NotNull String serverUrl) {
        McpOAuthTokens stored = McpOAuthTokenStore.load(serverUrl);
        if (stored == null) return null;
        if (stored.isExpired() && stored.refreshToken() != null) {
            McpOAuthTokens refreshed = McpOAuthFlow.refreshAccessToken(serverUrl, stored.refreshToken());
            if (refreshed != null) {
                McpOAuthTokenStore.store(serverUrl, refreshed);
                return refreshed.accessToken();
            }
            McpOAuthTokenStore.clear(serverUrl);
            return null;
        }
        return stored.isExpired() ? null : stored.accessToken();
    }

    /**
     * Runs the OAuth PKCE flow for the given server, stores the obtained tokens, and
     * returns them. Returns {@code null} and logs a warning if authentication fails.
     */
    @Nullable
    private static McpOAuthTokens runOAuthFlow(@NotNull CustomMcpServerConfig server) {
        try {
            McpOAuthTokens tokens = McpOAuthFlow.authenticate(server.getUrl());
            McpOAuthTokenStore.store(server.getUrl(), tokens);
            LOG.info("OAuth authentication succeeded for '" + server.getName() + "'");
            return tokens;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("OAuth authentication interrupted for '" + server.getName() + "'");
            return null;
        } catch (Exception e) {
            LOG.warn("OAuth authentication failed for '" + server.getName() + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Unregisters proxy tools for a server and terminates the MCP session.
     */
    private void unregisterServerTools(@NotNull PsiBridgeService bridge, @NotNull String serverId) {
        Set<String> toolIds = registeredByServer.get(serverId);
        if (toolIds != null) {
            for (String toolId : toolIds) {
                bridge.unregisterTool(toolId);
                LOG.info("Unregistered custom MCP proxy: " + toolId);
            }
        }

        AutoCloseable oldClient = clientByServer.remove(serverId);
        if (oldClient != null) {
            try { oldClient.close(); } catch (Exception ignored) {}
        }
    }

    // ── Status model ─────────────────────────────────────────────────

    public enum ServerStatus {
        UNKNOWN,
        LOADING,
        CONNECTED,
        ERROR,
        DISABLED
    }

    @FunctionalInterface
    public interface ServerStatusListener {
        void statusChanged(@NotNull String serverId, @NotNull ServerStatus status);
    }

    public interface ServerStateListener {
        void stateChanged(@NotNull String serverId, @NotNull ServerState state);

        void structureChanged();
    }

    public record ServerState(boolean enabled, @NotNull ServerStatus status, @NotNull String detail) {
    }

    private record ProbeResult(@NotNull ServerStatus status, @NotNull String detail) {}
}
