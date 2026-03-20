package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.bridge.McpServerJarLocator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * OpenCode ACP client.
 * <p>
 * Tool prefix: {@code agentbridge_read_file} → strip {@code agentbridge_}
 * MCP: configured via {@code OPENCODE_CONFIG} env var pointing to a config file on disk.
 * OpenCode's {@code OPENCODE_CONFIG_CONTENT} env var uses a restricted schema that does not
 * accept {@code mcp.*} keys — only the file-based {@code OPENCODE_CONFIG} accepts MCP config.
 * References: requires inline (no ACP resource blocks)
 */
public final class OpenCodeClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(OpenCodeClient.class);
    private static final String AGENT_ID = "opencode";

    public OpenCodeClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of(AGENT_ID, "acp");
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        String configPath = writeConfigFile(mcpPort, cwd);
        if (configPath == null) {
            LOG.warn("Failed to write OpenCode config file — MCP tools will be unavailable");
            return Map.of();
        }
        return Map.of("OPENCODE_CONFIG", configPath);
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge_", "");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    protected boolean supportsAuthenticate() {
        // OpenCode returns -32603 "Authentication not implemented" — skip the call entirely.
        return false;
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // OpenCode requires mcpServers in session/new with type "http" (not "sse" or "local")
        // and needs an empty "headers" array per its Zod schema validation
        JsonObject server = new JsonObject();
        server.addProperty("name", "agentbridge");
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + mcpPort);
        server.add("headers", new JsonArray());
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    /**
     * Writes the OpenCode config JSON to {@code {cwd}/.agent-work/opencode/opencode.json}.
     * Uses {@code type: "local"} with the mcp-server.jar (stdio MCP server process).
     * This is the only MCP transport type accepted in OpenCode's file-based config schema.
     *
     * @return the absolute path to the written config file, or {@code null} on failure
     */
    @Nullable
    private String writeConfigFile(int mcpPort, String cwd) {
        String javaPath = resolveJavaPath();
        if (javaPath == null) {
            LOG.warn("Java binary not found — cannot write OpenCode MCP config");
            return null;
        }
        String jarPath = McpServerJarLocator.findMcpServerJar();
        if (jarPath == null) {
            LOG.warn("mcp-server.jar not found — cannot write OpenCode MCP config");
            return null;
        }

        String configJson = buildConfigJson(javaPath, jarPath, mcpPort);
        try {
            Path dir = Path.of(cwd, ".agent-work", AGENT_ID);
            Files.createDirectories(dir);
            Path configFile = dir.resolve("opencode.json");
            Files.writeString(configFile, configJson);
            return configFile.toAbsolutePath().toString();
        } catch (IOException e) {
            LOG.warn("Failed to write OpenCode config file", e);
            return null;
        }
    }

    private static String buildConfigJson(String javaPath, String jarPath, int mcpPort) {
        return "{\n"
            + "  \"mcp\": {\n"
            + "    \"agentbridge\": {\n"
            + "      \"type\": \"local\",\n"
            + "      \"command\": [\""
            + escapeJson(javaPath) + "\", \"-jar\", \""
            + escapeJson(jarPath) + "\", \"--port\", \"" + mcpPort + "\"],\n"
            + "      \"enabled\": true\n"
            + "    }\n"
            + "  }\n"
            + "}";
    }

    @Nullable
    private static String resolveJavaPath() {
        String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaPath = System.getProperty("java.home")
            + File.separator + "bin" + File.separator + javaExe;
        return new File(javaPath).exists() ? javaPath : null;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
