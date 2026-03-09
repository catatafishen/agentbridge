package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.GenericSettings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * Agent configuration for OpenCode CLI.
 * OpenCode uses subcommand-style ACP: {@code opencode acp}.
 */
public class OpenCodeAgentConfig implements AgentConfig {

    private static final Logger LOG = Logger.getInstance(OpenCodeAgentConfig.class);
    private static final Gson GSON = new Gson();

    private final GenericSettings settings;
    private String resolvedBinaryPath;
    private JsonArray authMethods;

    public OpenCodeAgentConfig(@NotNull GenericSettings settings) {
        this.settings = settings;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "OpenCode";
    }

    @Override
    public @NotNull String getNotificationGroupId() {
        return "Copilot Notifications";
    }

    @Override
    public void prepareForLaunch(@Nullable String projectBasePath) {
        // OpenCode does not require pre-launch instruction files.
    }

    @Override
    public @NotNull String findAgentBinary() throws AcpException {
        String path = GenericCliLocator.findBinary("opencode", "OpenCode",
            "Install with: npm i -g opencode-ai");
        resolvedBinaryPath = path;
        return path;
    }

    @Override
    public @NotNull ProcessBuilder buildAcpProcess(@NotNull String binaryPath,
                                                   @Nullable String projectBasePath,
                                                   int mcpPort) {
        resolvedBinaryPath = binaryPath;
        // OpenCode uses subcommand "acp". It does NOT support --model, --additional-mcp-config,
        // or --config-dir at the ACP level — passing unknown flags causes an immediate exit.
        // Instead, MCP servers are injected via the OPENCODE_CONFIG_CONTENT env var, which
        // OpenCode merges as the highest-priority user config on startup.
        ProcessBuilder pb = GenericCliLocator.buildAcpCommand(
            binaryPath, "opencode",
            List.of("acp"),
            null,          // no --model flag (unsupported)
            projectBasePath,
            mcpPort,
            false, false); // no --config-dir, no --additional-mcp-config (both unsupported)

        String mcpConfigContent = buildMcpConfigContent(mcpPort);
        if (mcpConfigContent != null) {
            pb.environment().put("OPENCODE_CONFIG_CONTENT", mcpConfigContent);
        }
        return pb;
    }

    /**
     * Builds an {@code OPENCODE_CONFIG_CONTENT} JSON value that registers the IntelliJ MCP server
     * inside OpenCode's native {@code mcp} config block.
     *
     * <p>OpenCode reads {@code OPENCODE_CONFIG_CONTENT} as the highest-priority user config,
     * merging it on top of global and project configs without touching persistent files.</p>
     *
     * @param mcpPort the port that the plugin's PSI bridge is listening on
     * @return JSON string, or {@code null} if the MCP JAR or Java binary cannot be located
     */
    @Nullable
    private String buildMcpConfigContent(int mcpPort) {
        if (mcpPort <= 0) return null;

        String mcpJarPath = CopilotCliLocator.findMcpServerJar();
        if (mcpJarPath == null) {
            LOG.warn("MCP server JAR not found — IntelliJ tools will be unavailable in OpenCode.");
            return null;
        }

        String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + javaExe;
        if (!new File(javaPath).exists()) {
            LOG.warn("Java not found at: " + javaPath + " — IntelliJ tools will be unavailable in OpenCode.");
            return null;
        }

        // OpenCode McpLocal schema: { type: "local", command: string[] }
        JsonObject server = new JsonObject();
        server.addProperty("type", "local");
        JsonArray command = new JsonArray();
        command.add(javaPath);
        command.add("-jar");
        command.add(mcpJarPath);
        command.add("--port");
        command.add(String.valueOf(mcpPort));
        server.add("command", command);

        JsonObject mcp = new JsonObject();
        mcp.add("intellij-code-tools", server);

        JsonObject config = new JsonObject();
        config.add("mcp", mcp);

        LOG.info("OpenCode MCP config built for port " + mcpPort);
        return GSON.toJson(config);
    }

    @Override
    public void parseInitializeResponse(@NotNull JsonObject result) {
        authMethods = result.has("authMethods") ? result.getAsJsonArray("authMethods") : null;
    }

    @Override
    public @Nullable String parseModelUsage(@Nullable JsonObject modelMeta) {
        return null;
    }

    @Override
    public @Nullable AuthMethod getAuthMethod() {
        return KiroAgentConfig.parseStandardAuthMethod(authMethods);
    }

    @Override
    public @Nullable String getAgentBinaryPath() {
        return resolvedBinaryPath;
    }
}
