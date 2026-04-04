package com.github.catatafishen.ideagentforcopilot.custommcp;

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
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
 */
@Service(Service.Level.PROJECT)
public final class CustomMcpRegistrar {

    private static final Logger LOG = Logger.getInstance(CustomMcpRegistrar.class);

    private final Project project;
    /** Maps server ID → set of proxy tool IDs currently registered for that server. */
    private final Map<String, Set<String>> registeredByServer = new HashMap<>();

    public CustomMcpRegistrar(@NotNull Project project) {
        this.project = project;
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static CustomMcpRegistrar getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(CustomMcpRegistrar.class);
    }

    /**
     * Reads current settings and synchronises proxy tool registrations.
     * Unregisters tools for removed/disabled servers, then connects to
     * newly enabled servers and registers their tools.
     * Safe to call on any thread (uses pooled HTTP connections, no EDT usage).
     */
    public void syncRegistrations() {
        PsiBridgeService bridge = PsiBridgeService.getInstance(project);
        CustomMcpSettings settings = CustomMcpSettings.getInstance(project);
        List<CustomMcpServerConfig> servers = settings.getServers();

        Set<String> desiredServerIds = new HashSet<>();
        for (CustomMcpServerConfig server : servers) {
            if (server.isEnabled() && !server.getUrl().isBlank()) {
                desiredServerIds.add(server.getId());
            }
        }

        // Unregister tools for servers no longer in the active set
        List<String> toRemove = new ArrayList<>();
        for (String serverId : registeredByServer.keySet()) {
            if (!desiredServerIds.contains(serverId)) {
                unregisterServerTools(bridge, serverId);
                toRemove.add(serverId);
            }
        }
        toRemove.forEach(registeredByServer::remove);

        // Connect to each enabled server and register its tools
        for (CustomMcpServerConfig server : servers) {
            if (!server.isEnabled() || server.getUrl().isBlank()) continue;
            connectAndRegister(bridge, server);
        }
    }

    /**
     * Connects to one server, discovers its tools, and registers proxy instances.
     * Replaces any previously registered tools for the same server ID.
     * Logs a warning and skips silently on connection failure.
     */
    private void connectAndRegister(@NotNull PsiBridgeService bridge, @NotNull CustomMcpServerConfig server) {
        CustomMcpClient client = new CustomMcpClient(server.getUrl());
        try {
            client.initialize();
            List<CustomMcpClient.ToolInfo> tools = client.listTools();

            if (tools.isEmpty()) {
                LOG.info("Custom MCP server '" + server.getName() + "' reported no tools");
                return;
            }

            // Replace any stale registrations for this server
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

        } catch (IOException e) {
            LOG.warn("Failed to connect to custom MCP server '" + server.getName()
                + "' at " + server.getUrl() + ": " + e.getMessage());
        }
    }

    private void unregisterServerTools(@NotNull PsiBridgeService bridge, @NotNull String serverId) {
        Set<String> toolIds = registeredByServer.get(serverId);
        if (toolIds == null) return;
        for (String toolId : toolIds) {
            bridge.unregisterTool(toolId);
            LOG.info("Unregistered custom MCP proxy: " + toolId);
        }
    }
}
