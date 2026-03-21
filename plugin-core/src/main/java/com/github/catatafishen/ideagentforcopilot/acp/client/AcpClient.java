package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlockSerializer;
import com.github.catatafishen.ideagentforcopilot.acp.model.InitializeRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.InitializeResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.Location;
import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.NewSessionResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.NewSessionResponseDeserializer;
import com.github.catatafishen.ideagentforcopilot.acp.model.PlanEntry;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.acp.model.ToolCallContent;
import com.github.catatafishen.ideagentforcopilot.acp.model.ToolCallStatus;
import com.github.catatafishen.ideagentforcopilot.acp.model.ToolKind;
import com.github.catatafishen.ideagentforcopilot.acp.transport.JsonRpcErrorCodes;
import com.github.catatafishen.ideagentforcopilot.acp.transport.JsonRpcException;
import com.github.catatafishen.ideagentforcopilot.acp.transport.JsonRpcTransport;
import com.github.catatafishen.ideagentforcopilot.agent.AgentConnector;
import com.github.catatafishen.ideagentforcopilot.agent.AgentPromptException;
import com.github.catatafishen.ideagentforcopilot.agent.AgentSessionException;
import com.github.catatafishen.ideagentforcopilot.agent.AgentStartException;
import com.github.catatafishen.ideagentforcopilot.bridge.McpServerJarLocator;
import com.github.catatafishen.ideagentforcopilot.services.McpServerControl;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Abstract ACP protocol client.
 * <p>
 * Manages: process lifecycle, initialization, authentication, sessions, prompt streaming.
 * Agent-specific behavior is provided by abstract/overridable methods in subclasses.
 * Each concrete subclass IS the agent definition — no separate profile needed.
 *
 * @see <a href="https://agentclientprotocol.com">Agent Client Protocol</a>
 */
public abstract class AcpClient implements AgentConnector {

    private static final Logger LOG = Logger.getInstance(AcpClient.class);

    private static final long INITIALIZE_TIMEOUT_SECONDS = 90;
    private static final long SESSION_TIMEOUT_SECONDS = 30;
    private static final long PROMPT_TIMEOUT_SECONDS = 600;
    private static final long AUTH_TIMEOUT_SECONDS = 30;
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private static final int PROTOCOL_VERSION = 1;
    private static final String CLIENT_NAME = "AgentBridge";
    private static final String CLIENT_TITLE = "AgentBridge for IntelliJ";
    private static final String CLIENT_VERSION = "2.0.0";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_SESSION_UPDATE = "sessionUpdate";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_STATUS = "status";
    private static final String KEY_RESULT = "result";

    protected final Gson gson = new GsonBuilder()
            .registerTypeAdapter(NewSessionResponse.class, new NewSessionResponseDeserializer())
            .registerTypeHierarchyAdapter(ContentBlock.class, new ContentBlockSerializer())
            .create();
    protected final JsonRpcTransport transport = new JsonRpcTransport();
    protected final Project project;

    private @Nullable Process agentProcess;
    private @Nullable InitializeResponse capabilities;
    private @Nullable String currentSessionId;
    private @Nullable String launchCwd;
    private final List<Model> availableModels = new ArrayList<>();
    private volatile @Nullable Consumer<SessionUpdate> updateConsumer;

    protected AcpClient(Project project) {
        this.project = project;
    }

    // ═══════════════════════════════════════════════════
    // Final protocol methods — subclasses cannot override
    // ═══════════════════════════════════════════════════

    @Override
    public final void start() throws AgentStartException {
        try {
            int mcpPort = resolveMcpPort();
            agentProcess = launchProcess(mcpPort);
            transport.start(agentProcess);
            registerHandlers();
            capabilities = initialize();
            authenticate();
            eagerFetchModels();
            LOG.info(displayName() + " agent started successfully");
        } catch (Exception e) {
            stop();
            throw new AgentStartException("Failed to start " + displayName(), e);
        }
    }

    @Override
    public final void stop() {
        transport.stop();
        destroyProcess();
        agentProcess = null;
        capabilities = null;
        currentSessionId = null;
        launchCwd = null;
        availableModels.clear();
        updateConsumer = null;
    }

    @Override
    public final boolean isConnected() {
        return transport.isAlive() && agentProcess != null && agentProcess.isAlive();
    }

    @Override
    public final String createSession(String cwd) throws AgentSessionException {
        try {
            beforeCreateSession(cwd);
            int mcpPort = resolveMcpPort();

            JsonObject params = new JsonObject();
            params.addProperty("cwd", cwd);
            customizeNewSession(cwd, mcpPort, params);

            CompletableFuture<JsonElement> future = transport.sendRequest("session/new", params);
            JsonElement result = future.get(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            NewSessionResponse response = gson.fromJson(result, NewSessionResponse.class);
            LOG.info(displayName() + ": session/new: " + (response.models() != null ? response.models().size() : 0) + " model(s), "
                + (response.modes() != null ? response.modes().size() : 0) + " mode(s)");
            currentSessionId = response.sessionId();

            if (response.models() != null) {
                availableModels.clear();
                availableModels.addAll(response.models());
            }

            onSessionCreated(currentSessionId);
            return currentSessionId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentSessionException("Session creation interrupted for " + displayName(), e);
        } catch (Exception e) {
            throw new AgentSessionException("Failed to create session for " + displayName(), e);
        }
    }

    @Override
    public final void cancelSession(String sessionId) {
        JsonObject params = new JsonObject();
        params.addProperty(KEY_SESSION_ID, sessionId);
        transport.sendNotification("session/cancel", params);
    }

    @Override
    public final PromptResponse sendPrompt(PromptRequest request,
                                           Consumer<SessionUpdate> onUpdate) throws AgentPromptException {
        try {
            updateConsumer = onUpdate;
            JsonObject params = gson.toJsonTree(request).getAsJsonObject();
            LOG.info(displayName() + ": sending session/prompt, sessionId=" + request.sessionId());
            CompletableFuture<JsonElement> future = transport.sendRequest(
                "session/prompt", params, PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS
            );
            JsonElement result = future.get(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return gson.fromJson(result, PromptResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentPromptException("Prompt interrupted for " + displayName(), e);
        } catch (Exception e) {
            PromptResponse recovery = tryRecoverPromptException(e);
            if (recovery != null) return recovery;
            throw new AgentPromptException("Prompt failed for " + displayName(), e);
        } finally {
            updateConsumer = null;
        }
    }

    /**
     * Called when {@code sendPrompt} catches an exception.
     * Override to return a synthetic {@link PromptResponse} if the failure is recoverable
     * (e.g. a known agent-side deserialization bug where streaming updates already rendered
     * the response). Returning {@code null} causes the default exception to be thrown.
     */
    protected @Nullable PromptResponse tryRecoverPromptException(Exception cause) {
        return null;
    }

    /**
     * Called at the very start of {@code createSession}, before the {@code session/new} RPC.
     * Override to perform per-session setup, e.g. restarting a poisoned process.
     */
    protected void beforeCreateSession(String cwd) throws Exception {
        // default: no-op
    }

    @Override
    public final List<Model> getAvailableModels() {
        return Collections.unmodifiableList(availableModels);
    }

    @Override
    public final void setModel(String sessionId, String modelId) {
        JsonObject params = new JsonObject();
        params.addProperty(KEY_SESSION_ID, sessionId);
        params.addProperty("modelId", modelId);
        transport.sendRequest("session/set_model", params);
    }

    /**
     * Send a session/message notification to inject context (e.g. instructions).
     */
    protected final void sendSessionMessage(String sessionId, String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty(KEY_CONTENT, text);

        JsonArray messages = new JsonArray();
        messages.add(msg);

        JsonObject params = new JsonObject();
        params.addProperty(KEY_SESSION_ID, sessionId);
        params.add("messages", messages);

        transport.sendNotification("session/message", params);
    }

    // ═══════════════════════════════════════════════════
    // Abstract/overridable methods — subclass hooks
    // ═══════════════════════════════════════════════════

    /**
     * Build the ClientCapabilities to send in the initialize request.
     * <p>
     * Default: empty (no declared capabilities). Override to advertise fs/terminal support.
     */
    protected InitializeRequest.ClientCapabilities buildClientCapabilities() {
        return InitializeRequest.ClientCapabilities.empty();
    }

    /**
     * Build the command line to launch this agent process.
     */
    protected abstract List<String> buildCommand(String cwd, int mcpPort);

    /**
     * Extra environment variables for the agent process.
     *
     * @param mcpPort the MCP server port for this session
     * @param cwd     working directory for the agent process
     */
    @SuppressWarnings("unused")
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        return Map.of();
    }

    /**
     * Customize the session/new request parameters.
     *
     * @param cwd     working directory for the session
     * @param mcpPort the MCP server port
     * @param params  the JSON params object to modify
     */
    @SuppressWarnings("unused")
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Default: no customization
    }

    /**
     * Called after a session is successfully created.
     *
     * @param sessionId the created session ID
     */
    @SuppressWarnings("unused")
    protected void onSessionCreated(String sessionId) {
        // Default: no post-session setup
    }

    /**
     * Extract canonical tool ID from the agent's protocol tool call title.
     */
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle;
    }

    /**
     * Whether context references must be inlined as text.
     */
    public boolean requiresInlineReferences() {
        return false;
    }

    /**
     * Post-process a session update before delivering to UI.
     */
    protected SessionUpdate processUpdate(SessionUpdate update) {
        return update;
    }

    // ═══════════════════════════════════════════════════
    // MCP port resolution
    // ═══════════════════════════════════════════════════

    /**
     * Resolve the MCP server port, starting the server if needed.
     */
    protected int resolveMcpPort() {
        McpServerControl mcpServer = McpServerControl.getInstance(project);
        if (mcpServer != null) {
            if (mcpServer.isRunning() && mcpServer.getPort() > 0) {
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
        LOG.warn("No MCP server available — IntelliJ tools will be unavailable.");
        return 0;
    }

    // ═══════════════════════════════════════════════════
    // Private protocol implementation
    // ═══════════════════════════════════════════════════

    private Process launchProcess(int mcpPort) throws IOException {
        String cwd = project.getBasePath();
        if (cwd == null) {
            cwd = System.getProperty("user.home");
        }
        launchCwd = cwd;

        List<String> command = buildCommand(cwd, mcpPort);

        // Resolve the binary to an absolute path so ProcessBuilder can find it even when
        // it's installed via nvm/sdkman/homebrew in a non-standard location. Java's exec()
        // does not search PATH the same way a shell does.
        List<String> resolvedCommand = resolveCommand(command);

        ProcessBuilder pb = new ProcessBuilder(resolvedCommand);
        pb.directory(new File(cwd));
        pb.redirectErrorStream(false);

        // Inject shell environment so CLIs installed via nvm/sdkman/homebrew are found
        pb.environment().putAll(com.github.catatafishen.ideagentforcopilot.settings.ShellEnvironment.getEnvironment());

        Map<String, String> env = buildEnvironment(mcpPort, cwd);
        if (!env.isEmpty()) {
            pb.environment().putAll(env);
        }

        LOG.info("Launching " + displayName() + ": " + String.join(" ", resolvedCommand));
        return pb.start();
    }

    private List<String> resolveCommand(List<String> command) {
        if (command.isEmpty()) {
            return command;
        }
        String binaryName = command.getFirst();
        // Already absolute — no resolution needed
        if (binaryName.startsWith("/") || binaryName.startsWith("./")) {
            return command;
        }
        String absolutePath = com.github.catatafishen.ideagentforcopilot.settings.BinaryDetector.findBinaryPath(binaryName);
        if (absolutePath != null && !absolutePath.isEmpty()) {
            List<String> resolved = new java.util.ArrayList<>(command);
            resolved.set(0, absolutePath);
            return resolved;
        }
        // Fall back to original name; will fail at exec with a helpful error
        LOG.warn("Could not resolve absolute path for '" + binaryName + "'; attempting launch with unresolved name");
        return command;
    }

    private InitializeResponse initialize() throws Exception {
        InitializeRequest request = new InitializeRequest(
            PROTOCOL_VERSION,
            new InitializeRequest.ClientInfo(CLIENT_NAME, CLIENT_TITLE, CLIENT_VERSION),
            buildClientCapabilities()
        );

        JsonObject params = gson.toJsonTree(request).getAsJsonObject();
        CompletableFuture<JsonElement> future = transport.sendRequest(
            "initialize", params, INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS
        );

        JsonElement result = future.get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        InitializeResponse response = gson.fromJson(result, InitializeResponse.class);

        if (response.agentInfo() != null) {
            LOG.info(displayName() + " initialized: " + response.agentInfo().name()
                + " v" + response.agentInfo().version());
        } else {
            LOG.info(displayName() + " initialized (no agentInfo in response)");
        }
        return response;
    }

    private void authenticate() throws Exception {
        if (!supportsAuthenticate()) {
            LOG.info(displayName() + " does not support authenticate — skipping");
            return;
        }
        if (capabilities == null || capabilities.authMethods() == null
            || capabilities.authMethods().isEmpty()) {
            return;
        }

        String methodId = capabilities.authMethods().getFirst().id();
        JsonObject params = new JsonObject();
        params.addProperty("methodId", methodId);

        try {
            CompletableFuture<JsonElement> future = transport.sendRequest(
                "authenticate", params, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS
            );
            future.get(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.info(displayName() + " authenticated with method: " + methodId);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof JsonRpcException jre
                && jre.getCode() == JsonRpcErrorCodes.METHOD_NOT_FOUND) {
                // Agent does not implement the authenticate method — treat as already authenticated.
                LOG.info(displayName() + " does not support authenticate (method not found) — skipping");
            } else {
                throw e;
            }
        }
    }

    /**
     * Whether this agent supports the {@code authenticate} ACP method.
     * Override to return {@code false} for agents that handle auth internally
     * and respond with an error when authenticate is called.
     */
    protected boolean supportsAuthenticate() {
        return true;
    }

    /**
     * Builds a JsonObject for a stdio MCP server entry for use in the {@code mcpServers} array
     * of {@code session/new} params. Uses separate {@code command} (string) and {@code args}
     * (array) fields as required by Junie and Kiro.
     *
     * @return the server entry, or {@code null} if the jar or Java binary cannot be located
     */
    @Nullable
    protected final JsonObject buildMcpStdioServer(String serverName, int mcpPort) {
        String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + javaExe;
        if (!new File(javaPath).exists()) {
            LOG.warn("Java binary not found at " + javaPath + " — cannot build stdio MCP server config");
            return null;
        }
        String jarPath = McpServerJarLocator.findMcpServerJar();
        if (jarPath == null) {
            LOG.warn("mcp-server.jar not found — cannot build stdio MCP server config");
            return null;
        }

        JsonArray args = new JsonArray();
        args.add("-jar");
        args.add(jarPath);
        args.add("--port");
        args.add(String.valueOf(mcpPort));

        JsonObject server = new JsonObject();
        server.addProperty("name", serverName);
        server.addProperty("command", javaPath);
        server.add("args", args);
        server.add("env", new JsonArray());
        return server;
    }

    /**
     * Creates a temporary session immediately after startup to populate the available-models
     * list. The session is cancelled right after so it does not interfere with the first
     * real user session. If the call fails, the failure is logged and swallowed — the agent
     * is still usable, just without pre-loaded models.
     */
    private void eagerFetchModels() {
        String cwd = launchCwd != null ? launchCwd : project.getBasePath();
        if (cwd == null) return;
        try {
            String sessionId = createSession(cwd);
            cancelSession(sessionId);
            currentSessionId = null;
            LOG.info(displayName() + ": eagerly loaded " + availableModels.size() + " model(s)");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.warn(displayName() + ": eager model fetch failed (models will be empty): "
                + e.getMessage() + (cause != e ? " — caused by: " + cause.getMessage() : ""));
        }
    }

    private void registerHandlers() {
        transport.onNotification(notification -> {
            if ("session/update".equals(notification.method())) {
                handleSessionUpdate(notification.params());
            }
        });

        transport.onRequest(this::handleAgentRequest);

        transport.onStderr(line ->
            LOG.info("[" + agentId() + " stderr] " + line));
    }

    private void handleSessionUpdate(@Nullable JsonObject params) {
        if (params == null) return;

        JsonObject updateObj = normalizeSessionUpdateParams(params);

        Consumer<SessionUpdate> consumer = updateConsumer;
        if (consumer == null) {
            LOG.debug("Session update received but no consumer registered");
            return;
        }

        SessionUpdate update = parseSessionUpdate(updateObj);
        if (update != null) {
            update = processUpdate(update);
            consumer.accept(update);
        }
    }

    /**
     * Normalize the raw {@code session/update} notification params before parsing.
     * Both Copilot and Junie wrap the actual payload in a nested {@code update} sub-object:
     * {@code {sessionId, update: {sessionUpdate, content, ...}}}
     * The base implementation unwraps that envelope. Override only if an agent uses a
     * genuinely different structure.
     */
    protected JsonObject normalizeSessionUpdateParams(JsonObject params) {
        if (params.has("update") && params.get("update").isJsonObject()) {
            return params.getAsJsonObject("update");
        }
        return params;
    }

    /**
     * Parse a session/update notification into a typed SessionUpdate.
     */
    private @Nullable SessionUpdate parseSessionUpdate(JsonObject params) {
        String type = params.has(KEY_SESSION_UPDATE)
            ? params.get(KEY_SESSION_UPDATE).getAsString() : null;
        if (type == null) {
            LOG.debug(displayName() + ": session/update has no '" + KEY_SESSION_UPDATE + "' field after normalization");
            return null;
        }

        return switch (type) {
            case "agent_message_chunk" -> parseMessageChunk(params);
            case "agent_thought_chunk" -> parseThoughtChunk(params);
            case "tool_call" -> parseToolCall(params);
            case "tool_call_update" -> parseToolCallUpdate(params);
            case "plan" -> parsePlan(params);
            case "turn_usage" -> parseTurnUsage(params);
            case "banner" -> parseBanner(params);
            default -> {
                LOG.debug(displayName() + ": unknown session update type: '" + type + "'");
                yield null;
            }
        };
    }

    private SessionUpdate.AgentMessageChunk parseMessageChunk(JsonObject params) {
        List<ContentBlock> blocks = parseContentBlocks(params);
        return new SessionUpdate.AgentMessageChunk(blocks);
    }

    private SessionUpdate.AgentThoughtChunk parseThoughtChunk(JsonObject params) {
        List<ContentBlock> blocks = parseContentBlocks(params);
        return new SessionUpdate.AgentThoughtChunk(blocks);
    }

    private SessionUpdate.ToolCall parseToolCall(JsonObject params) {
        String toolCallId = getStringOrEmpty(params, "toolCallId");
        String title = getStringOrEmpty(params, "title");
        String resolvedTitle = resolveToolId(title);

        ToolKind kind = null;
        if (params.has("kind")) {
            try {
                kind = ToolKind.valueOf(params.get("kind").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                kind = ToolKind.OTHER;
            }
        }

        String arguments = params.has("arguments") ? params.get("arguments").toString() : null;

        List<Location> locations = null;
        if (params.has("locations")) {
            locations = new ArrayList<>();
            for (JsonElement locEl : params.getAsJsonArray("locations")) {
                JsonObject locObj = locEl.getAsJsonObject();
                String uri = getStringOrEmpty(locObj, "uri");
                if (uri.isEmpty()) uri = getStringOrEmpty(locObj, "path");
                locations.add(new Location(uri, null));
            }
        }

        return new SessionUpdate.ToolCall(toolCallId, resolvedTitle, kind, arguments, locations);
    }

    private SessionUpdate.ToolCallUpdate parseToolCallUpdate(JsonObject params) {
        String toolCallId = getStringOrEmpty(params, "toolCallId");

        ToolCallStatus status = ToolCallStatus.COMPLETED;
        if (params.has(KEY_STATUS)) {
            try {
                status = ToolCallStatus.valueOf(params.get(KEY_STATUS).getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                // keep default
            }
        }

        String error = params.has("error") ? params.get("error").getAsString() : null;
        List<ToolCallContent> content = extractToolCallContent(params);

        return new SessionUpdate.ToolCallUpdate(toolCallId, status, content, error);
    }

    private List<ToolCallContent> extractToolCallContent(JsonObject params) {
        if (params.has(KEY_CONTENT)) {
            return List.of(new ToolCallContent.Content(parseContentBlocks(params)));
        }
        if (params.has(KEY_RESULT)) {
            String resultText = params.get(KEY_RESULT).isJsonPrimitive()
                ? params.get(KEY_RESULT).getAsString()
                : params.get(KEY_RESULT).toString();
            return List.of(new ToolCallContent.Content(
                List.of(new ContentBlock.Text(resultText))
            ));
        }
        return List.of();
    }

    private SessionUpdate.Plan parsePlan(JsonObject params) {
        List<PlanEntry> entries = new ArrayList<>();
        if (params.has("entries")) {
            for (JsonElement entryEl : params.getAsJsonArray("entries")) {
                JsonObject entryObj = entryEl.getAsJsonObject();
                String content = getStringOrEmpty(entryObj, KEY_CONTENT);
                String status = entryObj.has(KEY_STATUS) ? entryObj.get(KEY_STATUS).getAsString() : null;
                entries.add(new PlanEntry(content, status));
            }
        }
        return new SessionUpdate.Plan(entries);
    }

    private SessionUpdate.TurnUsage parseTurnUsage(JsonObject params) {
        int inputTokens = params.has("inputTokens") ? params.get("inputTokens").getAsInt() : 0;
        int outputTokens = params.has("outputTokens") ? params.get("outputTokens").getAsInt() : 0;
        double costUsd = params.has("costUsd") ? params.get("costUsd").getAsDouble() : 0.0;
        return new SessionUpdate.TurnUsage(inputTokens, outputTokens, costUsd);
    }

    private SessionUpdate.Banner parseBanner(JsonObject params) {
        String message = getStringOrEmpty(params, "message");
        String level = params.has("level") ? params.get("level").getAsString() : "warning";
        String clearOn = params.has("clearOn") ? params.get("clearOn").getAsString() : null;
        return new SessionUpdate.Banner(message, level, clearOn);
    }

    private List<ContentBlock> parseContentBlocks(JsonObject params) {
        if (params.has(KEY_CONTENT) && params.get(KEY_CONTENT).isJsonArray()) {
            return parseContentArray(params.getAsJsonArray(KEY_CONTENT));
        }
        if (params.has(KEY_CONTENT) && params.get(KEY_CONTENT).isJsonObject()) {
            // Single content object: {"type":"text","text":"..."} — treat as one-element array
            JsonArray arr = new JsonArray();
            arr.add(params.get(KEY_CONTENT));
            return parseContentArray(arr);
        }
        if (params.has(KEY_CONTENT) && params.get(KEY_CONTENT).isJsonPrimitive()) {
            return List.of(new ContentBlock.Text(params.get(KEY_CONTENT).getAsString()));
        }
        if (params.has("text")) {
            return List.of(new ContentBlock.Text(params.get("text").getAsString()));
        }
        return List.of();
    }

    private List<ContentBlock> parseContentArray(JsonArray array) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonElement el : array) {
            if (el.isJsonObject()) {
                JsonObject block = el.getAsJsonObject();
                String blockType = block.has("type") ? block.get("type").getAsString() : "text";
                if ("text".equals(blockType) && block.has("text")) {
                    blocks.add(new ContentBlock.Text(block.get("text").getAsString()));
                } else if ("content".equals(blockType) && block.has("content")) {
                    // Spec: tool_call_update content items wrap blocks as {type:"content", content:{type,text}}
                    JsonElement inner = block.get("content");
                    if (inner.isJsonObject() && inner.getAsJsonObject().has("text")) {
                        blocks.add(new ContentBlock.Text(inner.getAsJsonObject().get("text").getAsString()));
                    }
                }
            } else if (el.isJsonPrimitive()) {
                blocks.add(new ContentBlock.Text(el.getAsString()));
            }
        }
        return blocks;
    }

    private static String getStringOrEmpty(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive()
            ? obj.get(key).getAsString() : "";
    }

    private void handleAgentRequest(JsonElement id, JsonRpcTransport.IncomingRequest request) {
        switch (request.method()) {
            case "session/request_permission" -> handlePermissionRequest(id, request.params());
            case "fs/read_text_file", "fs/write_text_file",
                 "terminal/create", "terminal/output" ->
                transport.sendError(id, JsonRpcErrorCodes.INTERNAL_ERROR, request.method() + " not yet implemented");
            default -> {
                LOG.warn("Unknown agent request: " + request.method());
                transport.sendError(id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: " + request.method());
            }
        }
    }

    private void handlePermissionRequest(JsonElement id, @Nullable JsonObject params) {
        // Prefer the "allow_once" option by kind; fall back to the first available option.
        JsonObject chosenOption = findOptionByKind(params, "allow_once");
        if (chosenOption == null) {
            chosenOption = findFirstOption(params);
        }
        String optionId = chosenOption != null && chosenOption.has("optionId")
            ? chosenOption.get("optionId").getAsString()
            : "allow_once";
        JsonObject result = new JsonObject();
        result.add("outcome", buildPermissionOutcome(optionId, chosenOption));
        transport.sendResponse(id, result);
    }

    /**
     * Build the outcome object sent back in the {@code session/request_permission} response.
     * <p>
     * Per ACP spec the outcome is {@code {outcome: "selected", optionId: "<chosen-id>"}}.
     * Override to add agent-specific fields.
     *
     * @param optionId     the chosen option ID (echoed from the request's {@code options} array)
     * @param chosenOption the matching option object from the request params, or {@code null} if not found
     */
    protected JsonObject buildPermissionOutcome(String optionId, @Nullable JsonObject chosenOption) {
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        outcome.addProperty("optionId", optionId);
        return outcome;
    }

    @Nullable
    private static JsonObject findOptionByKind(@Nullable JsonObject params, String kind) {
        if (params == null || !params.has("options")) return null;
        JsonElement options = params.get("options");
        if (!options.isJsonArray()) return null;
        for (JsonElement el : options.getAsJsonArray()) {
            if (el.isJsonObject()) {
                JsonObject opt = el.getAsJsonObject();
                if (opt.has("kind") && kind.equals(opt.get("kind").getAsString())) {
                    return opt;
                }
            }
        }
        return null;
    }

    @Nullable
    private static JsonObject findFirstOption(@Nullable JsonObject params) {
        if (params == null || !params.has("options")) return null;
        JsonElement options = params.get("options");
        if (!options.isJsonArray()) return null;
        JsonArray arr = options.getAsJsonArray();
        return (!arr.isEmpty() && arr.get(0).isJsonObject()) ? arr.get(0).getAsJsonObject() : null;
    }

    protected void destroyProcess() {
        if (agentProcess == null || !agentProcess.isAlive()) {
            return;
        }
        agentProcess.destroy();
        try {
            if (!agentProcess.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                agentProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            agentProcess.destroyForcibly();
        }
    }
}
