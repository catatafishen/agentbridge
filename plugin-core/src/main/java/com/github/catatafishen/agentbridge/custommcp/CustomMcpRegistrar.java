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

/**
 * Manages the lifecycle of proxy tools for all configured custom MCP servers.
 * Connects to each enabled server at startup, discovers its tools, and registers
 * {@link CustomMcpToolProxy} instances in {@link PsiBridgeService}.
 * Also handles re-sync when settings are updated.
 * <p>
 * Maintains a {@link CustomMcpClient} per server so that MCP sessions are preserved
 * across tool calls and properly terminated when servers are removed or disabled.
 */
@Service(Service.Level.PROJECT)
public final class CustomMcpRegistrar implements Disposable {

    private static final Logger LOG = Logger.getInstance(CustomMcpRegistrar.class);

    private final Project project;

    /**
     * Maps server ID → set of proxy tool IDs currently registered for that server.
     */
    private final Map<String, Set<String>> registeredByServer = new HashMap<>();

    /**
     * Maps server ID → the active MCP client for that server.
     * Used to close sessions when a server is unregistered or replaced.
     */
    private final Map<String, CustomMcpClient> clientByServer = new HashMap<>();

    public CustomMcpRegistrar(@NotNull Project project) {
        this.project = project;
    }

    public static CustomMcpRegistrar getInstance(@NotNull Project project) {
        return project.getService(CustomMcpRegistrar.class);
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

        Set<String> desiredServerIds = collectActiveServerIds(servers);

        // Unregister tools for servers no longer in the active set
        Set<String> toRemove = computeServersToRemove(registeredByServer.keySet(), desiredServerIds);
        for (String serverId : toRemove) {
            unregisterServerTools(bridge, serverId);
            registeredByServer.remove(serverId);
        }

        // Connect to each enabled server and register its tools
        for (CustomMcpServerConfig server : servers) {
            if (!server.isEnabled() || server.getUrl().isBlank()) continue;
            connectAndRegister(bridge, server);
        }
    }

    // ── Extracted pure-logic helpers (package-private for testing) ──────

    /**
     * Collects server IDs that should be active: enabled with a non-blank URL.
     */
    static Set<String> collectActiveServerIds(List<CustomMcpServerConfig> servers) {
        Set<String> ids = new HashSet<>();
        for (CustomMcpServerConfig server : servers) {
            if (server.isEnabled() && !server.getUrl().isBlank()) {
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
        for (CustomMcpClient client : clientByServer.values()) {
            client.close();
        }
        clientByServer.clear();
        registeredByServer.clear();
    }

    /**
     * Connects to one server, discovers its tools, and registers proxy instances.
     * Replaces any previously registered tools for the same server ID.
     * <p>
     * If the server returns HTTP 401, the OAuth PKCE flow is triggered automatically:
     * the user's browser opens for authentication, and on success the new tokens are stored
     * and the connection is retried. Subsequent connections reuse the stored token; expired
     * tokens are silently refreshed before connecting.
     */
    private void connectAndRegister(@NotNull PsiBridgeService bridge, @NotNull CustomMcpServerConfig server) {
        String token = resolveToken(server.getUrl());
        CustomMcpClient client = new CustomMcpClient(server.getUrl(), token);
        try {
            doConnectAndRegister(bridge, server, client);
        } catch (McpOAuthRequiredException e) {
            client.close();
            LOG.info("OAuth required for '" + server.getName() + "' — starting authentication flow");
            McpOAuthTokens tokens = runOAuthFlow(server);
            if (tokens == null) return;
            CustomMcpClient authedClient = new CustomMcpClient(server.getUrl(), tokens.accessToken());
            try {
                doConnectAndRegister(bridge, server, authedClient);
            } catch (Exception retryEx) {
                authedClient.close();
                LOG.warn(formatConnectionError(server.getName(), server.getUrl(), retryEx.getMessage()));
            }
        } catch (Exception e) {
            client.close();
            LOG.warn(formatConnectionError(server.getName(), server.getUrl(), e.getMessage()));
        }
    }

    /**
     * Core connection logic: initializes the MCP session, lists tools, and registers proxy instances.
     * Separated from {@link #connectAndRegister} so it can be called for both the initial attempt
     * and the OAuth-retry without duplicating registration logic.
     */
    private void doConnectAndRegister(
        @NotNull PsiBridgeService bridge,
        @NotNull CustomMcpServerConfig server,
        @NotNull CustomMcpClient client
    ) throws IOException {
        client.initialize();
        List<CustomMcpClient.ToolInfo> tools = client.listTools();

        if (tools.isEmpty()) {
            LOG.info("Custom MCP server '" + server.getName() + "' reported no tools");
            client.close();
            if (registeredByServer.containsKey(server.getId()) || clientByServer.containsKey(server.getId())) {
                unregisterServerTools(bridge, server.getId());
                registeredByServer.remove(server.getId());
            }
            return;
        }

        unregisterServerTools(bridge, server.getId());

        Set<String> registered = new HashSet<>();
        String prefix = server.toolPrefix();
        for (CustomMcpClient.ToolInfo toolInfo : tools) {
            CustomMcpToolProxy proxy = new CustomMcpToolProxy(
                prefix, client, toolInfo, server.getInstructions()
            );
            bridge.registerTool(proxy);
            registered.add(proxy.id());
            LOG.info("Registered custom MCP proxy: " + proxy.id() + " → " + server.getUrl());
        }
        registeredByServer.put(server.getId(), registered);
        clientByServer.put(server.getId(), client);
    }

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
            // Refresh failed — clear stale tokens so the full flow is triggered on 401
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

        CustomMcpClient oldClient = clientByServer.remove(serverId);
        if (oldClient != null) {
            oldClient.close();
        }
    }
}
