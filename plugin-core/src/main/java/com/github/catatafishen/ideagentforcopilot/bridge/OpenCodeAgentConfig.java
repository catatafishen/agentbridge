package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.GenericSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Agent configuration for OpenCode CLI.
 * OpenCode uses subcommand-style ACP: {@code opencode acp}.
 */
public class OpenCodeAgentConfig implements AgentConfig {

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
            "Install with: go install github.com/sst/opencode@latest");
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

        String mcpConfigContent = CopilotCliLocator.buildOpenCodeMcpConfigContent(mcpPort);
        if (mcpConfigContent != null) {
            pb.environment().put("OPENCODE_CONFIG_CONTENT", mcpConfigContent);
        }
        return pb;
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
