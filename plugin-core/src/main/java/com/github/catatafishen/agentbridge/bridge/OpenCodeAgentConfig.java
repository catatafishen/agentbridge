package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OpenCode-specific {@link AgentConfig} implementation.
 *
 * <p>Extends the generic {@link ProfileBasedAgentConfig} with OpenCode's unique requirements:
 * <ul>
 *   <li>Writes a custom {@code opencode.json} config file (MCP + permissions)</li>
 *   <li>Uses {@code "mcp"} as the JSON key for server definitions (not {@code "mcpServers"})</li>
 *   <li>Denies OpenCode's native built-in tools so the model uses agentbridge MCP tools</li>
 *   <li>Checks {@code ~/.config/opencode/opencode.json} for existing MCP registrations</li>
 * </ul>
 */
final class OpenCodeAgentConfig extends ProfileBasedAgentConfig {

    private static final Logger LOG = Logger.getInstance(OpenCodeAgentConfig.class);

    static final String PROFILE_ID = "opencode";
    private static final String AGENT_WORK_DIR = ".agent-work";
    private static final String CONFIG_FILE = "opencode.json";

    /**
     * OpenCode's native built-in tool names. Denied in the generated config so the model
     * uses agentbridge MCP tools instead of OpenCode's own file/search/shell tools.
     */
    private static final List<String> NATIVE_TOOLS = List.of(
        "grep", "glob", "ls", "read", "write", "edit", "patch",
        "bash", "webfetch", "task", "todoread", "todowrite",
        "lsp", "websearch", "codesearch"
    );

    OpenCodeAgentConfig(@NotNull AgentProfile profile,
                        @Nullable ToolRegistry registry,
                        @Nullable Project project) {
        super(profile, registry, project);
    }

    @Override
    protected void configureProcess(@NotNull ProcessBuilder pb,
                                    @Nullable String projectBasePath,
                                    int mcpPort) {
        if (mcpPort <= 0 || projectBasePath == null) return;

        writeConfigFile(projectBasePath, mcpPort);
        String configPath = Path.of(projectBasePath, AGENT_WORK_DIR, PROFILE_ID, CONFIG_FILE).toString();
        pb.environment().put("OPENCODE_CONFIG", configPath);
    }

    @Override
    protected @NotNull List<Path> getAdditionalMcpConfigPaths() {
        String userHome = System.getProperty("user.home", "");
        return List.of(Path.of(userHome, ".config", PROFILE_ID, CONFIG_FILE));
    }

    @Override
    protected @NotNull String getMcpContainerKey() {
        return "mcp";
    }

    @Override
    protected @NotNull List<String> getNativeToolDenyList() {
        return NATIVE_TOOLS;
    }

    /**
     * Writes the OpenCode config file to disk so OpenCode can read it via
     * {@code OPENCODE_CONFIG} env var. Includes MCP server config and tool permissions.
     */
    private void writeConfigFile(@NotNull String projectBasePath, int mcpPort) {
        try {
            Path dir = Path.of(projectBasePath, AGENT_WORK_DIR, PROFILE_ID);
            Path configPath = dir.resolve(CONFIG_FILE);

            Files.createDirectories(dir);

            String resolved = resolveMcpTemplate(mcpPort);
            if (resolved == null || resolved.isEmpty()) {
                LOG.warn("Failed to resolve MCP config template for OpenCode (null or empty)");
                return;
            }

            String configWithPermissions = mergePermissionsIntoConfig(resolved);
            String finalConfig = convertMcpServersToObject(configWithPermissions);
            String formatted = formatJsonSafely(finalConfig);

            Files.writeString(configPath, formatted, StandardCharsets.UTF_8);
            LOG.info("OpenCode config written to " + configPath + " (length: " + formatted.length() + ")");
        } catch (Exception e) {
            LOG.warn("Failed to write OpenCode config file", e);
        }
    }

    /**
     * Converts {@code "mcpServers"} array to {@code "mcp"} object for OpenCode's expected format.
     */
    @NotNull
    private static String convertMcpServersToObject(@NotNull String configJson) {
        try {
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
            if (!root.has("mcpServers") || !root.get("mcpServers").isJsonArray()) {
                return configJson;
            }
            JsonArray servers = root.getAsJsonArray("mcpServers");
            JsonObject mcp = new JsonObject();
            for (JsonElement el : servers) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                String name = s.has("name") ? s.get("name").getAsString() : "agentbridge";
                JsonObject entry = s.deepCopy();
                entry.remove("name");
                mcp.add(name, entry);
            }
            root.remove("mcpServers");
            root.add("mcp", mcp);
            return new Gson().toJson(root);
        } catch (Exception e) {
            LOG.warn("Failed to convert mcpServers to mcp object", e);
            return configJson;
        }
    }
}
