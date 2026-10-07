package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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
    /**
     * Primary OpenCode agent defined by this plugin. Its own {@code prompt} replaces OpenCode's
     * model-specific base prompt (which describes OpenCode's built-in tools, all of which are
     * denied here), so the model is only told about the IDE tools it actually has.
     */
    static final String STRICT_AGENT = "agentbridge";
    /** Name the AgentBridge MCP server is registered under in {@code session/new}; unrelated to the agent slug. */
    private static final String MCP_SERVER_NAME = "agentbridge";
    private static final String STRICT_AGENT_PROMPT_RESOURCE = "/agents/opencode/agentbridge-system-prompt.md";
    private static final String BUILD_AGENT = "build";
    private static final String PLAN_AGENT = "plan";
    private static final String GENERAL_AGENT = "general";
    private static final String EXPLORE_AGENT = "explore";
    private static final String SCOUT_AGENT = "scout";
    private static final String PROJECT_AGENT_DIR = ".opencode/agent";
    private static final String PROJECT_AGENTS_DIR = ".opencode/agents";
    private static final String DEPLOYED_AGENT_DIR = ".agent-work/opencode/agent";

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
        return STRICT_AGENT;
    }

    @Override
    public boolean supportsModelGrouping() {
        return true;
    }

    @Override
    public List<AbstractClient.AgentMode> getAvailableAgents() {
        List<AbstractClient.AgentMode> agents = new ArrayList<>(builtInAgents());
        String basePath = project.getBasePath();
        if (basePath != null) {
            agents.addAll(ProjectAgentScanner.scanAgentDirectories(
                Path.of(basePath),
                Set.of(STRICT_AGENT, BUILD_AGENT, PLAN_AGENT, GENERAL_AGENT, EXPLORE_AGENT, SCOUT_AGENT),
                PROJECT_AGENT_DIR,
                PROJECT_AGENTS_DIR,
                DEPLOYED_AGENT_DIR
            ));
        }
        return agents;
    }

    static List<AbstractClient.AgentMode> builtInAgents() {
        return List.of(
            new AbstractClient.AgentMode(STRICT_AGENT, "AgentBridge",
                "IDE tools only, with an AgentBridge system prompt instead of OpenCode's (default)"),
            new AbstractClient.AgentMode(BUILD_AGENT, "Build", "OpenCode's own primary agent and system prompt"),
            new AbstractClient.AgentMode(PLAN_AGENT, "Plan", "Read-only planning mode with guarded edits and bash"),
            new AbstractClient.AgentMode(GENERAL_AGENT, "General", "General-purpose subagent for complex tasks"),
            new AbstractClient.AgentMode(EXPLORE_AGENT, "Explore", "Fast read-only subagent for codebase exploration"),
            new AbstractClient.AgentMode(SCOUT_AGENT, "Scout", "Read-only subagent for external docs and dependency research")
        );
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        // On Windows, opencode is installed via npm and the native binary is not on PATH.
        // Probe the project-local node_modules path as a fallback.
        String windowsPath = resolveWindowsOpenCodePath(cwd);
        List<String> cmd = new java.util.ArrayList<>(List.of(windowsPath != null ? windowsPath : AGENT_ID, "acp"));
        AgentProfile profile = AgentProfileManager.getInstance().getProfile(AGENT_ID);
        if (profile != null) {
            cmd.addAll(profile.parsedExtraCliArgs());
        }
        return cmd;
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
        if (!SystemInfo.isWindows) {
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
        return buildPermissionConfig();
    }

    /**
     * Builds the OPENCODE_CONFIG_CONTENT environment variable: denies native tools globally and
     * defines the {@link #STRICT_AGENT} primary agent with the AgentBridge system prompt.
     *
     * <p>NOTE: We do NOT set {@code "default_agent"} here. OpenCode v1.4.10+ rejects
     * subagent slugs (like "build", "plan", "explore") as the {@code default_agent} value,
     * causing {@code session/new} to fail with
     * {@code "default agent \"build\" is a subagent"}. OpenCode selects its own default
     * agent internally — the plugin selects the agent with {@code session/set_mode} once
     * the session exists (see {@link #onSessionCreated}).</p>
     *
     * @throws IllegalStateException if the bundled system prompt resource is missing
     */
    static Map<String, String> buildPermissionConfig() {
        JsonObject permission = new JsonObject();
        for (String tool : NATIVE_TOOLS_TO_DENY) {
            permission.addProperty(tool, "deny");
        }
        JsonObject strictAgent = new JsonObject();
        strictAgent.addProperty("description", "IDE tools only, with an AgentBridge system prompt");
        strictAgent.addProperty("mode", "primary");
        strictAgent.addProperty("prompt", loadStrictAgentPrompt());
        strictAgent.add("permission", permission.deepCopy());
        JsonObject agents = new JsonObject();
        agents.add(STRICT_AGENT, strictAgent);

        JsonObject config = new JsonObject();
        config.add("permission", permission);
        config.add("agent", agents);
        return Map.of("OPENCODE_CONFIG_CONTENT", new Gson().toJson(config));
    }

    /**
     * Reads the bundled system prompt for {@link #STRICT_AGENT}. Fails loudly when the resource is
     * missing: silently starting the agent without its prompt would put OpenCode's own prompt
     * back, which is exactly what this agent exists to avoid.
     */
    static String loadStrictAgentPrompt() {
        try (java.io.InputStream in = OpenCodeClient.class.getResourceAsStream(STRICT_AGENT_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled resource missing: " + STRICT_AGENT_PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read bundled resource " + STRICT_AGENT_PROMPT_RESOURCE, e);
        }
    }

    /**
     * Applies the selected agent to the new session. OpenCode starts every session in its own
     * default agent ({@code build}) and only switches on {@code session/set_mode}, so without this
     * the {@link #STRICT_AGENT} (or any agent chosen in the dropdown) would never be used.
     * Waits for the switch so the first prompt cannot overtake it.
     */
    @Override
    protected void onSessionCreated(String sessionId) {
        String slug = getCurrentAgentSlug();
        if (isSelectableMode(slug) && !slug.equals(getCurrentModeSlug())) {
            awaitSetMode(sessionId, slug);
        }
    }

    /**
     * Pushes an agent selection made while a session is live. Without a session the selection is
     * applied by {@link #onSessionCreated} instead. Does not wait for the reply: this runs from the
     * UI selection handler and must not block it.
     */
    @Override
    protected void onAgentSlugChanged(@Nullable String slug) {
        String sessionId = getActiveSessionId();
        if (isSelectableMode(slug) && sessionId != null && !sessionId.isBlank()) {
            sendSetMode(sessionId, slug);
        }
    }

    /**
     * Only primary agents advertised in {@code session/new} are valid {@code session/set_mode}
     * targets; OpenCode rejects subagents (general, explore, scout) and unknown names.
     */
    private boolean isSelectableMode(@Nullable String slug) {
        return slug != null && !slug.isBlank()
            && getAvailableModes().stream().anyMatch(m -> slug.equals(m.slug()));
    }

    private void awaitSetMode(String sessionId, String modeId) {
        try {
            sendSetMode(sessionId, modeId).join();
        } catch (java.util.concurrent.CompletionException ignored) {
            // Already logged by sendSetMode; the agent stays in the mode it started with.
        }
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
        return GENERAL_AGENT;
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
    public @Nullable java.nio.file.Path getSessionDirectory() {
        java.io.File dir = com.github.catatafishen.agentbridge.session.exporters.ExportUtils.sessionsDir(project);
        return dir.isDirectory() ? dir.toPath() : null;
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws InterruptedException, ExecutionException, TimeoutException {
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
        server.addProperty("name", MCP_SERVER_NAME);
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray());
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }
}
