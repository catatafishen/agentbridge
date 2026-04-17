package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenCode ACP client.
 * <p>
 * Command: {@code opencode acp}
 * Tool prefix: {@code agentbridge_read_file} → strip {@code agentbridge_}
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 * References: requires inline (no ACP resource blocks)
 */
public final class OpenCodeClient extends AcpClient {

    private static final String AGENT_ID = "opencode";
    private static final String BUILD_AGENT = "build";
    private static final String PLAN_AGENT = "plan";
    private static final String GENERAL_AGENT = "general";
    private static final String EXPLORE_AGENT = "explore";
    private static final String PROJECT_AGENT_DIR = ".opencode/agent";
    private static final String PROJECT_AGENTS_DIR = ".opencode/agents";

    private static final String KEY_RAW_INPUT = "rawInput";
    private static final List<String> NATIVE_TOOLS_TO_DENY = List.of(
        "grep", "glob", "ls", "read", "write", "edit", "patch", "bash",
        "lsp", "websearch", "webfetch", "codesearch", "todoread", "todowrite"
    );

    static List<String> nativeToolsToDeny() {
        return NATIVE_TOOLS_TO_DENY;
    }

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
    public @Nullable String defaultAgentSlug() {
        return BUILD_AGENT;
    }

    @Override
    public List<AbstractAgentClient.AgentMode> getAvailableAgents() {
        List<AbstractAgentClient.AgentMode> agents = new ArrayList<>(builtInAgents());
        String basePath = project.getBasePath();
        if (basePath != null) {
            agents.addAll(ProjectAgentScanner.scanAgentDirectories(
                Path.of(basePath),
                Set.of(BUILD_AGENT, PLAN_AGENT, GENERAL_AGENT, EXPLORE_AGENT),
                PROJECT_AGENT_DIR,
                PROJECT_AGENTS_DIR
            ));
        }
        return agents;
    }

    static List<AbstractAgentClient.AgentMode> builtInAgents() {
        return List.of(
            new AbstractAgentClient.AgentMode(BUILD_AGENT, "Build", "Default primary agent with full tool access"),
            new AbstractAgentClient.AgentMode(PLAN_AGENT, "Plan", "Read-only planning mode with guarded edits and bash"),
            new AbstractAgentClient.AgentMode(GENERAL_AGENT, "General", "General-purpose subagent for complex tasks"),
            new AbstractAgentClient.AgentMode(EXPLORE_AGENT, "Explore", "Fast read-only subagent for codebase exploration")
        );
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        // On Windows, opencode is installed via npm and the native binary is not on PATH.
        // Probe the project-local node_modules path as a fallback.
        String windowsPath = resolveWindowsOpenCodePath(cwd);
        return List.of(windowsPath != null ? windowsPath : AGENT_ID, "acp");
    }

    /**
     * On Windows, opencode is shipped as a native binary inside its npm package and is not
     * added to PATH by default. Probes the project-local {@code node_modules} tree for the
     * {@code opencode-windows-x64} binary bundled by {@code opencode-ai}.
     *
     * <p>Package-private and static so unit tests can call it directly without an
     * IntelliJ application context.</p>
     *
     * @param projectBasePath the project root directory, or {@code null} if unavailable
     * @return absolute path to {@code opencode.exe}, or {@code null} if not found or not on Windows
     */
    @Nullable
    static String resolveWindowsOpenCodePath(@Nullable String projectBasePath) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        if (projectBasePath == null || projectBasePath.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(projectBasePath,
            "node_modules", "opencode-ai", "node_modules", "opencode-windows-x64", "bin", "opencode.exe");
        if (Files.isRegularFile(candidate)) {
            return candidate.toString();
        }
        return null;
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        return buildPermissionConfig(resolveDefaultPrimaryAgent());
    }

    /**
     * Builds the OPENCODE_CONFIG_CONTENT environment variable denying native tools.
     */
    static Map<String, String> buildPermissionConfig() {
        return buildPermissionConfig(BUILD_AGENT);
    }

    static Map<String, String> buildPermissionConfig(@Nullable String defaultAgentSlug) {
        JsonObject permission = new JsonObject();
        for (String tool : NATIVE_TOOLS_TO_DENY) {
            permission.addProperty(tool, "deny");
        }
        JsonObject config = new JsonObject();
        config.add("permission", permission);
        if (defaultAgentSlug != null && !defaultAgentSlug.isBlank()) {
            config.addProperty("default_agent", defaultAgentSlug);
        }
        return Map.of("OPENCODE_CONFIG_CONTENT", new Gson().toJson(config));
    }

    private @Nullable String resolveDefaultPrimaryAgent() {
        String current = getCurrentAgentSlug();
        if (PLAN_AGENT.equals(current)) return PLAN_AGENT;
        return BUILD_AGENT;
    }

    @Override
    protected String extractSubAgentType(@NotNull JsonObject params, @NotNull String resolvedTitle,
                                         @Nullable JsonObject argumentsObj) {
        if ("task".equals(resolvedTitle)) {
            return extractTaskSubAgentType(params);
        }
        return super.extractSubAgentType(params, resolvedTitle, argumentsObj);
    }

    /**
     * Extracts the sub-agent type from a "task" tool call's rawInput.
     * Returns the {@code subagent_type} value if present, otherwise {@code "general"}.
     */
    static String extractTaskSubAgentType(JsonObject params) {
        JsonObject raw = params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()
            ? params.getAsJsonObject(KEY_RAW_INPUT) : null;
        if (raw != null && raw.has("subagent_type")) {
            return raw.get("subagent_type").getAsString();
        }
        return "general";
    }

    @Override
    @Nullable
    protected JsonObject parseToolCallArguments(@NotNull JsonObject params) {
        JsonObject fromRawInput = extractRawInputArgs(params);
        return fromRawInput != null ? fromRawInput : super.parseToolCallArguments(params);
    }

    /**
     * Extracts tool call arguments from the {@code rawInput} field.
     * Returns {@code null} if rawInput is absent or empty.
     */
    @Nullable
    static JsonObject extractRawInputArgs(JsonObject params) {
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            JsonObject raw = params.getAsJsonObject(KEY_RAW_INPUT);
            if (!raw.entrySet().isEmpty()) {
                return raw;
            }
        }
        return null;
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return stripToolPrefix(protocolTitle);
    }

    /**
     * Strips the {@code agentbridge_} prefix from an OpenCode tool title.
     */
    static String stripToolPrefix(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge_", "");
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return hasToolPrefix(protocolTitle);
    }

    /**
     * Returns {@code true} if the title starts with the OpenCode MCP tool prefix.
     */
    static boolean hasToolPrefix(String protocolTitle) {
        return protocolTitle.startsWith("agentbridge_");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    protected boolean supportsAuthenticate() {
        return false;
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws Exception {
        String result = sendLoadSessionRequest("session/resume", cwd, sessionId);
        markSessionHistoryLoadedInternally();
        return result;
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        addMcpServerConfig(mcpPort, params);
    }

    /**
     * Adds the {@code mcpServers} block to session/new params with type "http".
     */
    static void addMcpServerConfig(int mcpPort, JsonObject params) {
        JsonObject server = new JsonObject();
        server.addProperty("name", "agentbridge");
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray());
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }
}
