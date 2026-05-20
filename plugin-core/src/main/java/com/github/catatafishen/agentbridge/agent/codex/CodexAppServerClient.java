package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.github.catatafishen.agentbridge.agent.AgentException;
import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.SessionOption;
import com.github.catatafishen.agentbridge.bridge.TransportType;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.McpInjectionMethod;
import com.github.catatafishen.agentbridge.services.PermissionInjectionMethod;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.settings.BinaryDetector;
import com.github.catatafishen.agentbridge.settings.ProjectFilesSettings;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Codex agent client backed by {@code codex app-server} (JSON-RPC 2.0 over stdio).
 *
 * <p>Starts a persistent {@code codex app-server} subprocess and communicates via
 * newline-delimited JSON (JSONL). Each plugin session maps to a Codex {@code thread},
 * and each user prompt starts a {@code turn} within that thread.</p>
 *
 * <p>Native tool approval requests ({@code item/commandExecution/requestApproval} and
 * {@code item/fileChange/requestApproval}) follow the same allow/ask/deny settings model
 * as the internal tools, including the permission prompt UI and session-scoped approval cache.</p>
 *
 * <p>Shell tools ({@code shell}, {@code shell_command}, {@code exec_command}, etc.) are
 * disabled at server-startup time via {@code --config features.shell_tool=false} and
 * {@code --config features.unified_exec=false}.</p>
 */
public final class CodexAppServerClient extends AbstractAgentClient implements JsonRpcTransport.MessageHandler {

    private static final Logger LOG = Logger.getInstance(CodexAppServerClient.class);

    public static final String PROFILE_ID = "codex";

    // ── JSON-RPC field names ─────────────────────────────────────────────────

    private static final String F_ID = "id";
    private static final String F_METHOD = "method";
    private static final String F_PARAMS = "params";
    private static final String F_ERROR = "error";
    private static final String F_TYPE = "type";
    private static final String F_TEXT = "text";
    private static final String F_ITEM = "item";
    private static final String F_DELTA = "delta";
    private static final String F_TURN = "turn";
    private static final String F_THREAD = "thread";
    private static final String F_STATUS = "status";
    private static final String F_USAGE = "usage";
    private static final String F_INPUT_TOKENS = "input_tokens";
    private static final String F_OUTPUT_TOKENS = "output_tokens";
    private static final String F_MESSAGE = "message";
    private static final String F_TOOL = "tool";
    private static final String F_ARGUMENTS = "arguments";
    private static final String F_COMMAND = "command";
    private static final String AGENTBRIDGE_PREFIX = "agentbridge_";
    private static final String TYPE_MCP_TOOL_CALL = "mcpToolCall";
    private static final String AGENTS_MD = "AGENTS.md";

    // ── Additional string constants ──────────────────────────────────────────

    private static final String F_MODEL = "model";
    private static final String CMD_CONFIG = "--config";

    private static final long TURN_WAIT_POLL_MILLIS = 1000;

    // ── Message classification enum ──────────────────────────────────────────

    enum MessageType {RESPONSE, SERVER_REQUEST, NOTIFICATION, UNKNOWN}

    // ── Session options ──────────────────────────────────────────────────────

    private static final SessionOption EFFORT_OPTION = new SessionOption(
        "effort", "Effort",
        List.of("", "low", "medium", "high")
    );

    // ── Profile ──────────────────────────────────────────────────────────────

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Codex");
        p.setBuiltIn(true);
        p.setExperimental(false);
        p.setTransportType(TransportType.CODEX_APP_SERVER);
        p.setDescription("""
            OpenAI Codex CLI profile — drives the locally-installed 'codex' binary as a \
            persistent app-server subprocess (JSON-RPC 2.0 over stdio). \
            Supports streaming text, graceful tool-approval denial, and multi-turn threads. \
            Requires 'codex' to be installed and authenticated via 'codex login'.""");
        p.setBinaryName(PROFILE_ID);
        p.setAlternateNames(List.of());
        p.setInstallHint("Install with: npm install -g @openai/codex, then run 'codex login'.");
        p.setInstallUrl("https://developers.openai.com/codex/cli");
        p.setSupportsOAuthSignIn(false);
        p.setTerminalSignInCommand("codex login --device-auth");
        p.setAcpArgs(List.of());
        // MCP injected via --config flags at server startup
        p.setMcpMethod(McpInjectionMethod.NONE);
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(true);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setPrependInstructionsTo(AGENTS_MD);
        return p;
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final AgentProfile profile;
    private final AgentConfig config;
    @Nullable
    private final ToolRegistry registry;
    @NotNull
    private final Project project;
    private final int mcpPort;

    private final AtomicReference<Process> appServerProcess = new AtomicReference<>();
    private final JsonRpcTransport transport;
    private final AtomicReference<List<Model>> dynamicModels = new AtomicReference<>();

    /**
     * Plugin session ID → Codex thread ID.
     */
    private final Map<String, String> sessionToThreadId = new ConcurrentHashMap<>();

    private static final String KEY_CODEX_THREAD = "codexThreadId";

    @Nullable
    private String loadCodexThreadId() {
        return PropertiesComponent.getInstance(project).getValue(PROFILE_ID + "." + KEY_CODEX_THREAD);
    }

    private void persistCodexThreadId(@Nullable String threadId) {
        if (threadId == null) {
            PropertiesComponent.getInstance(project).unsetValue(PROFILE_ID + "." + KEY_CODEX_THREAD);
        } else {
            PropertiesComponent.getInstance(project).setValue(PROFILE_ID + "." + KEY_CODEX_THREAD, threadId);
        }
    }

    /**
     * Plugin session ID → cwd (captured at createSession time).
     */
    private final Map<String, String> sessionCwd = new ConcurrentHashMap<>();
    /**
     * Plugin session ID → model override.
     */
    private final Map<String, String> sessionModels = new ConcurrentHashMap<>();
    /**
     * Plugin session ID → session options (e.g. effort).
     */
    private final Map<String, Map<String, String>> sessionOptions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> sessionCancelled = new ConcurrentHashMap<>();

    // Active turn state — one turn at a time over stdio
    private volatile String activeTurnId;
    private final AtomicReference<Consumer<SessionUpdate>> activeTurnCallback = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<String>> activeTurnResult = new AtomicReference<>();
    private volatile String activeTurnSessionId;

    /**
     * Monotonic timestamp of the last output/activity seen for the active turn.
     */
    private volatile long activeTurnLastOutputNanos;
    /**
     * True once a thinking chip has been opened for the current turn (suppresses duplicate placeholders).
     */
    private volatile boolean reasoningActive = false;

    private final CodexApprovalHandler approvalHandler;

    private String resolvedBinaryPath;

    public CodexAppServerClient(@NotNull AgentProfile profile,
                                @NotNull AgentConfig config,
                                @Nullable ToolRegistry registry,
                                @NotNull Project project,
                                int mcpPort) {
        this.profile = profile;
        this.config = config;
        this.registry = registry;
        this.project = project;
        this.mcpPort = mcpPort;
        this.transport = new JsonRpcTransport(project);
        this.approvalHandler = new CodexApprovalHandler(project, this::sendResponse);
        transport.setMessageHandler(this);
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Override
    public String agentId() {
        return PROFILE_ID;
    }

    @Override
    public String displayName() {
        return profile.getDisplayName();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void start() throws AgentException {
        resolvedBinaryPath = resolveBinary();
        launchAppServer();
        LOG.info("CodexAppServerClient started");
    }

    @Override
    public void stop() {
        transport.shutdown();
        CompletableFuture<String> turn = activeTurnResult.get();
        if (turn != null) turn.completeExceptionally(new AgentException("Client stopped", null, false));
        activeTurnResult.set(null);
        activeTurnCallback.set(null);
        Process proc = appServerProcess.get();
        if (proc != null) {
            proc.destroyForcibly();
            appServerProcess.set(null);
        }
        sessionToThreadId.clear();
        sessionCancelled.clear();
    }

    @Override
    public void clearPersistedSession() {
        persistCodexThreadId(null);
    }

    @Override
    public boolean isConnected() {
        return transport.isConnected();
    }

    @Override
    public boolean isHealthy() {
        return transport.isConnected() && appServerProcess.get() != null && appServerProcess.get().isAlive();
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    @Override
    public @NotNull String createSession(@Nullable String cwd) {
        String sessionId = UUID.randomUUID().toString();
        sessionCancelled.put(sessionId, new AtomicBoolean(false));
        if (cwd != null) sessionCwd.put(sessionId, cwd);
        return sessionId;
    }

    @Override
    public void cancelSession(@NotNull String sessionId) {
        AtomicBoolean flag = sessionCancelled.get(sessionId);
        if (flag != null) flag.set(true);
        approvalHandler.clearSessionApprovals(sessionId);
        // Interrupt the active turn if it belongs to this session
        String turnId = activeTurnId;
        if (turnId != null && sessionId.equals(activeTurnSessionId)) {
            sendInterrupt(turnId);
        }
    }

    // ── Session options ──────────────────────────────────────────────────────

    @Override
    public @NotNull List<SessionOption> listSessionOptions() {
        return List.of(EFFORT_OPTION);
    }

    @Override
    public void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        sessionOptions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public void setPermissionRequestListener(@Nullable Consumer<PermissionPrompt> listener) {
        approvalHandler.setPermissionRequestListener(listener);
    }

    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) {
        sessionModels.put(sessionId, modelId);
    }

    @Override
    public List<Model> getAvailableModels() {
        List<Model> models = dynamicModels.get();
        if (models == null) throw new IllegalStateException("Model list not loaded — agent not initialized");
        return models;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.NAME;
    }

    @Override
    @NotNull
    public List<ProjectFilesSettings.FileEntry> getDefaultProjectFiles() {
        return List.of(new ProjectFilesSettings.FileEntry(AGENTS_MD, AGENTS_MD, false, "Codex"));
    }

    // ── Prompts ──────────────────────────────────────────────────────────────

    public @NotNull PromptResponse sendPrompt(@NotNull PromptRequest request,
                                              @NotNull Consumer<SessionUpdate> onUpdate) throws Exception {
        ensureConnected();
        String sessionId = request.sessionId();
        AtomicBoolean cancelled = sessionCancelled.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        cancelled.set(false);

        String model = resolveModel(sessionId, request.modelId());
        String rawPrompt = extractPromptText(request.prompt());
        List<ContentBlock.Image> imageBlocks = extractImageBlocks(request.prompt());
        String fullPrompt = buildFullPrompt(rawPrompt, !sessionToThreadId.containsKey(sessionId)
            && loadCodexThreadId() == null);

        // Ensure a Codex thread exists for this session
        String threadId = sessionToThreadId.get(sessionId);
        if (threadId == null) {
            threadId = getOrResumeThread(sessionId, model);
        }

        // Set up the active turn future
        CompletableFuture<String> turnResult = new CompletableFuture<>();
        long turnStartNanos = System.nanoTime();
        activeTurnResult.set(turnResult);
        activeTurnCallback.set(onUpdate);
        activeTurnSessionId = sessionId;
        activeTurnLastOutputNanos = turnStartNanos;

        // Start the turn
        String turnId = startTurn(threadId, fullPrompt, imageBlocks, model, sessionId);
        activeTurnId = turnId;

        // Wait for turn completion, extending the timeout whenever Codex produces output.
        try {
            String stopReason = awaitTurnResult(turnResult, turnId, turnStartNanos);
            return new PromptResponse(stopReason, null);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AgentException ae) throw ae;
            throw new AgentException("Codex turn failed: " + e.getMessage(), e, true);
        } finally {
            activeTurnId = null;
            activeTurnResult.set(null);
            activeTurnCallback.set(null);
            activeTurnSessionId = null;
        }
    }

    // ── App-server lifecycle ──────────────────────────────────────────────────

    private int getTurnTimeoutSeconds() {
        return ActiveAgentManager.getInstance(project).getSharedTurnTimeoutSeconds();
    }

    private int getInactivityTimeoutSeconds() {
        return ActiveAgentManager.getInstance(project).getSharedInactivityTimeoutSeconds();
    }

    private String awaitTurnResult(@NotNull CompletableFuture<String> turnResult,
                                   @NotNull String turnId,
                                   long turnStartNanos)
        throws InterruptedException, java.util.concurrent.ExecutionException, AgentException {
        long turnTimeoutNanos = TimeUnit.SECONDS.toNanos(getTurnTimeoutSeconds());
        long inactivityTimeoutNanos = TimeUnit.SECONDS.toNanos(getInactivityTimeoutSeconds());
        while (true) {
            long now = System.nanoTime();
            long inactivityDeadlineNanos = activeTurnLastOutputNanos + inactivityTimeoutNanos;
            long turnDeadlineNanos = turnStartNanos + turnTimeoutNanos;
            long remainingNanos = Math.min(turnDeadlineNanos, inactivityDeadlineNanos) - now;
            if (remainingNanos <= 0) {
                sendInterrupt(turnId);
                if (turnDeadlineNanos <= inactivityDeadlineNanos) {
                    long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(now - turnStartNanos);
                    throw new AgentException("Codex turn timed out after " + elapsedSec + " seconds", null, true);
                }
                long silenceSec = TimeUnit.NANOSECONDS.toSeconds(now - activeTurnLastOutputNanos);
                throw new AgentException("Codex turn timed out after " + silenceSec + " seconds of inactivity", null, true);
            }

            long waitMillis = Math.clamp(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 1L, TURN_WAIT_POLL_MILLIS);
            try {
                return turnResult.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                if (turnResult.isDone()) {
                    return turnResult.get();
                }
            }
        }
    }

    private void launchAppServer() throws AgentException {
        List<String> cmd = buildServerCommand();
        try {
            LOG.info("Starting codex app-server: " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(
                ShellEnvironment.getEnvironment());
            if (project != null && project.getBasePath() != null) {
                pb.directory(new File(project.getBasePath()));
            }
            pb.redirectErrorStream(false);
            appServerProcess.set(pb.start());

            // Drain stderr on a daemon thread
            startStderrDrainer(appServerProcess.get());

            // Attach transport (starts reader thread)
            transport.attach(appServerProcess.get());

            // Perform JSON-RPC initialize handshake
            initialize();
            LOG.info("codex app-server ready");
        } catch (IOException e) {
            throw new AgentException("Failed to start codex app-server: " + e.getMessage(), e, true);
        }
    }

    @NotNull
    private List<String> buildServerCommand() {
        return buildServerCommandStatic(resolvedBinaryPath, mcpPort);
    }

    static List<String> buildServerCommandStatic(@NotNull String binaryPath, int mcpPort) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath);
        cmd.add("app-server");

        // Disable native shell execution tools; model must use MCP tools instead
        cmd.add(CMD_CONFIG);
        cmd.add("features.shell_tool=false");
        cmd.add(CMD_CONFIG);
        cmd.add("features.unified_exec=false");

        // Inject MCP server via --config if mcpPort is available
        if (mcpPort > 0) {
            cmd.add(CMD_CONFIG);
            cmd.add("mcp_servers.agentbridge.url=http://localhost:" + mcpPort + "/mcp");
        }

        return cmd;
    }

    // ── JSON-RPC initialize handshake ────────────────────────────────────────

    private void initialize() throws AgentException {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "intellij-copilot-plugin");
        clientInfo.addProperty("title", "IntelliJ AgentBridge");
        clientInfo.addProperty("version", "1.0.0");
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("experimentalApi", true);

        JsonObject params = new JsonObject();
        params.add("clientInfo", clientInfo);
        params.add("capabilities", capabilities);

        try {
            sendRequest("initialize", params).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("codex app-server initialize interrupted", ie, true);
        } catch (Exception e) {
            throw new AgentException("codex app-server initialize failed: " + e.getMessage(), e, true);
        }

        // Send initialized notification (no ID, no response expected)
        sendNotification("initialized", new JsonObject());

        fetchModelList();
    }

    private void fetchModelList() throws AgentException {
        JsonObject params = new JsonObject();
        params.addProperty("includeHidden", false);
        try {
            JsonObject result = sendRequest("model/list", params).get(10, TimeUnit.SECONDS);
            if (result == null || !result.has("data") || !result.get("data").isJsonArray()) {
                throw new AgentException("model/list returned unexpected format: " + result, null, true);
            }
            List<Model> models = new ArrayList<>();
            for (JsonElement el : result.getAsJsonArray("data")) {
                Model model = parseModelEntry(el);
                if (model != null) models.add(model);
            }
            if (models.isEmpty()) {
                throw new AgentException("model/list returned no models", null, true);
            }
            dynamicModels.set(Collections.unmodifiableList(models));
            LOG.info("Loaded " + models.size() + " Codex model(s) from model/list");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("model/list interrupted", ie, true);
        } catch (AgentException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AgentException("model/list failed: " + e.getMessage(), e, true);
        }
    }

    @Nullable
    private static Model parseModelEntry(@NotNull JsonElement el) {
        return CodexMessageParser.parseModelEntry(el);
    }

    // ── Thread management ─────────────────────────────────────────────────────

    /**
     * Creates a new Codex thread for a plugin session and returns its threadId.
     */
    @NotNull
    private String startThread(@NotNull String sessionId, @NotNull String model) throws AgentException {
        String cwd = sessionCwd.getOrDefault(sessionId,
            project != null && project.getBasePath() != null ? project.getBasePath() : ".");

        JsonObject params = new JsonObject();
        params.addProperty(F_MODEL, model);
        params.addProperty("cwd", cwd);
        // on-request: server sends approval notifications we can decline
        params.addProperty("approvalPolicy", "on-request");

        try {
            JsonObject result = sendRequest("thread/start", params).get(15, TimeUnit.SECONDS);
            JsonObject thread = result.getAsJsonObject(F_THREAD);
            if (thread == null || !thread.has(F_ID)) {
                throw new AgentException("thread/start response missing thread.id", null, true);
            }
            String threadId = thread.get(F_ID).getAsString();
            sessionToThreadId.put(sessionId, threadId);
            persistCodexThreadId(threadId);
            LOG.info("Created Codex thread " + threadId + " for session " + sessionId);
            return threadId;
        } catch (AgentException ae) {
            throw ae;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("thread/start interrupted", ie, true);
        } catch (Exception e) {
            throw new AgentException("thread/start failed: " + e.getMessage(), e, true);
        }
    }

    /**
     * Returns a thread ID for the session, resuming a persisted thread if available or starting a new one.
     */
    @NotNull
    private String getOrResumeThread(@NotNull String sessionId, @NotNull String model) throws AgentException {
        String savedThreadId = loadCodexThreadId();
        if (savedThreadId != null) {
            try {
                String resumed = resumeThread(savedThreadId, model, sessionId);
                LOG.info("Resumed Codex thread " + resumed + " for session " + sessionId);
                return resumed;
            } catch (AgentException e) {
                LOG.warn("thread/resume failed (thread may be expired), starting new thread: " + e.getMessage());
                persistCodexThreadId(null);
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                    com.github.catatafishen.agentbridge.psi.PlatformApiCompat.showNotification(
                        project,
                        "Codex session resume failed",
                        "Could not resume the previous Codex thread (it may have expired). "
                            + "A new thread will be started. Previous conversation context will not be restored.",
                        com.intellij.notification.NotificationType.WARNING));
            }
        }
        return startThread(sessionId, model);
    }

    /**
     * Resumes an existing Codex thread by threadId.
     */
    @NotNull
    private String resumeThread(@NotNull String threadId, @NotNull String model,
                                @NotNull String sessionId) throws AgentException {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty(F_MODEL, model);

        try {
            JsonObject result = sendRequest("thread/resume", params).get(15, TimeUnit.SECONDS);
            JsonObject thread = result.getAsJsonObject(F_THREAD);
            if (thread == null || !thread.has(F_ID)) {
                throw new AgentException("thread/resume response missing thread.id", null, true);
            }
            String resumedId = thread.get(F_ID).getAsString();
            sessionToThreadId.put(sessionId, resumedId);
            persistCodexThreadId(resumedId);
            return resumedId;
        } catch (AgentException ae) {
            throw ae;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("thread/resume interrupted", ie, true);
        } catch (Exception e) {
            throw new AgentException("thread/resume failed: " + e.getMessage(), e, true);
        }
    }

    @NotNull
    private String startTurn(@NotNull String threadId,
                             @NotNull String prompt,
                             @NotNull List<ContentBlock.Image> images,
                             @NotNull String model,
                             @NotNull String sessionId) throws AgentException {
        JsonArray input = new JsonArray();

        JsonObject textItem = new JsonObject();
        textItem.addProperty(F_TYPE, "text");
        textItem.addProperty(F_TEXT, prompt);
        input.add(textItem);

        for (ContentBlock.Image img : images) {
            JsonObject imageItem = new JsonObject();
            imageItem.addProperty(F_TYPE, "image");
            imageItem.addProperty("url", "data:" + img.mimeType() + ";base64," + img.data());
            input.add(imageItem);
        }

        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("input", input);
        params.addProperty(F_MODEL, model);

        String effort = getSessionOption(sessionId, EFFORT_OPTION.key());
        if (effort != null && !effort.isBlank()) {
            params.addProperty(EFFORT_OPTION.key(), effort);
        }

        try {
            JsonObject result = sendRequest("turn/start", params).get(15, TimeUnit.SECONDS);
            JsonObject turn = result.getAsJsonObject(F_TURN);
            if (turn == null || !turn.has(F_ID)) {
                throw new AgentException("turn/start response missing turn.id", null, true);
            }
            String turnId = turn.get(F_ID).getAsString();
            LOG.info("Started Codex turn " + turnId + " in thread " + threadId
                + (images.isEmpty() ? "" : " with " + images.size() + " image(s)"));
            return turnId;
        } catch (AgentException ae) {
            throw ae;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException("turn/start interrupted", ie, true);
        } catch (Exception e) {
            throw new AgentException("turn/start failed: " + e.getMessage(), e, true);
        }
    }

    private void sendInterrupt(@NotNull String turnId) {
        JsonObject params = new JsonObject();
        params.addProperty("turnId", turnId);
        sendNotification("turn/interrupt", params);
    }

    // ── JSON-RPC messaging ────────────────────────────────────────────────────

    @NotNull
    private CompletableFuture<JsonObject> sendRequest(@NotNull String method, @NotNull JsonObject params) {
        return transport.sendRequest(method, params);
    }

    private void sendNotification(@NotNull String method, @NotNull JsonObject params) {
        transport.sendNotification(method, params);
    }

    private void sendResponse(@NotNull JsonElement id, @NotNull JsonElement result) {
        transport.sendResponse(id, result);
    }

    private void sendErrorResponse(@NotNull JsonElement id, @NotNull JsonObject error) {
        transport.sendErrorResponse(id, error);
    }

    // ── Message classification (delegation to transport) ─────────────────────

    static MessageType classifyMessageType(@NotNull JsonObject msg) {
        JsonRpcTransport.MessageType type = JsonRpcTransport.classifyMessageType(msg);
        return switch (type) {
            case RESPONSE -> MessageType.RESPONSE;
            case SERVER_REQUEST -> MessageType.SERVER_REQUEST;
            case NOTIFICATION -> MessageType.NOTIFICATION;
            case UNKNOWN -> MessageType.UNKNOWN;
        };
    }

    static String extractJsonRpcErrorMessage(@NotNull JsonObject errorObj) {
        return JsonRpcTransport.extractJsonRpcErrorMessage(errorObj);
    }

    static boolean isCodexAuthError(@Nullable String text) {
        return JsonRpcTransport.isCodexAuthError(text);
    }

    // ── MessageHandler interface ─────────────────────────────────────────────

    @Override
    public void onServerRequest(@NotNull JsonObject msg) {
        if (activeTurnResult.get() != null && !activeTurnResult.get().isDone()) {
            activeTurnLastOutputNanos = System.nanoTime();
        }
        String method = msg.get(F_METHOD).getAsString();
        JsonElement id = msg.get(F_ID);
        JsonObject params = msg.has(F_PARAMS) ? msg.getAsJsonObject(F_PARAMS) : new JsonObject();

        LOG.info("codex app-server request: " + method);

        switch (method) {
            case "item/commandExecution/requestApproval", "item/fileChange/requestApproval" ->
                approvalHandler.handleNativeApprovalRequest(id, method, params, activeTurnSessionId, activeTurnCallback.get());
            case "item/tool/requestUserInput" -> approvalHandler.handleUserInputRequest(id, params);
            case "item/tool/call" -> {
                String toolName = params.has(F_TOOL) ? params.get(F_TOOL).getAsString() : "unknown";
                if ("request_user_input".equals(toolName) && project != null) {
                    approvalHandler.handleNativeAskUserRequest(id, params, activeTurnCallback.get());
                } else {
                    LOG.info("Declining client-side tool call: " + toolName);
                    JsonObject error = new JsonObject();
                    error.addProperty(F_TYPE, F_ERROR);
                    error.addProperty(F_TEXT, "Tool '" + toolName + "' is not available in this context.");
                    JsonArray content = new JsonArray();
                    content.add(error);
                    JsonObject resp = new JsonObject();
                    resp.add("content", content);
                    sendResponse(id, resp);
                }
            }
            default -> {
                // Unknown server request — respond with JSON-RPC error
                LOG.warn("Unknown server request method: " + method);
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty(F_MESSAGE, "Method not found: " + method);
                sendErrorResponse(id, error);
            }
        }
    }

    @Override
    public void onNotification(@NotNull JsonObject msg) {
        if (activeTurnResult.get() != null && !activeTurnResult.get().isDone()) {
            activeTurnLastOutputNanos = System.nanoTime();
        }
        String method = msg.get(F_METHOD).getAsString();
        JsonObject params = msg.has(F_PARAMS) ? msg.getAsJsonObject(F_PARAMS) : new JsonObject();

        switch (method) {
            case "item/agentMessage/delta" -> handleTextDelta(params);
            case "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> handleReasoningDelta(params);
            case "item/started" -> handleItemStarted(params);
            case "item/completed" -> handleItemCompleted(params);
            case "turn/completed" -> handleTurnCompleted(params);
            case "turn/failed" -> handleTurnFailed(params);
            // turn/started, thread/started, item/reasoning/textDelta, etc. — no action needed
            default -> LOG.debug("codex notification: " + method);
        }
    }

    // ── Notification handling ─────────────────────────────────────────────────

    private void handleTextDelta(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback.get();
        if (cb == null) return;
        JsonElement delta = params.get(F_DELTA);
        if (delta == null) return;
        String text;
        if (delta.isJsonPrimitive()) {
            text = delta.getAsString();
        } else if (delta.isJsonObject() && delta.getAsJsonObject().has(F_TEXT)) {
            text = delta.getAsJsonObject().get(F_TEXT).getAsString();
        } else {
            return;
        }
        if (!text.isEmpty()) {
            cb.accept(new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(text))));
        }
    }

    private void handleItemStarted(@NotNull JsonObject params) {
        if (!params.has(F_ITEM)) return;
        JsonObject item = params.getAsJsonObject(F_ITEM);
        String type = item.has(F_TYPE) ? item.get(F_TYPE).getAsString() : "";

        // Cache tool name for in-flight MCP calls (used by approval handler for permission checks)
        if (TYPE_MCP_TOOL_CALL.equals(type) && item.has(F_ID) && item.has(F_TOOL)) {
            String rawTool = item.get(F_TOOL).getAsString();
            String toolName = rawTool.startsWith(AGENTBRIDGE_PREFIX) ? rawTool.substring(AGENTBRIDGE_PREFIX.length()) : rawTool;
            approvalHandler.trackPendingMcpTool(item.get(F_ID).getAsString(), toolName);
        }

        Consumer<SessionUpdate> cb = activeTurnCallback.get();
        if (cb == null) return;
        if (TYPE_MCP_TOOL_CALL.equals(type)) {
            emitMcpToolCallStart(item, cb);
        } else if ("reasoning".equals(type)) {
            // Emit one thinking chip per turn, then stream any available reasoning text into it.
            if (!reasoningActive) {
                reasoningActive = true;
                cb.accept(new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Text("Thought"))));
            }
            appendReasoningContent(item, cb);
        }
    }

    private void handleItemCompleted(@NotNull JsonObject params) {
        if (!params.has(F_ITEM)) return;
        JsonObject item = params.getAsJsonObject(F_ITEM);
        String type = item.has(F_TYPE) ? item.get(F_TYPE).getAsString() : "";

        // Always update pending tool tracking, regardless of active turn callback
        if (TYPE_MCP_TOOL_CALL.equals(type) && item.has(F_ID)) {
            approvalHandler.removePendingMcpTool(item.get(F_ID).getAsString());
        }

        Consumer<SessionUpdate> cb = activeTurnCallback.get();
        if (cb == null) return;

        switch (type) {
            case TYPE_MCP_TOOL_CALL -> emitMcpToolCallEnd(item, cb);
            // Native command attempted — emit as a tool call (already declined, just for UI)
            case "commandExecution" -> emitNativeCommandItem(item, cb);
            default -> { /* reasoning is streamed via summaryTextDelta; no other types require handling */ }
        }
    }

    private void handleReasoningDelta(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback.get();
        if (cb == null) return;
        String text = extractReasoningText(params.get(F_DELTA));
        if (!text.isEmpty()) {
            cb.accept(new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Text(text))));
        }
    }

    private void appendReasoningContent(@NotNull JsonObject item, @NotNull Consumer<SessionUpdate> cb) {
        String text = extractReasoningText(item);
        if (!text.isEmpty()) {
            cb.accept(new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Text(text))));
        }
    }

    @NotNull
    private String extractReasoningText(@Nullable JsonElement el) {
        return CodexMessageParser.extractReasoningText(el);
    }

    private void handleTurnCompleted(@NotNull JsonObject params) {
        reasoningActive = false;
        Consumer<SessionUpdate> cb = activeTurnCallback.get();
        JsonObject turn = params.has(F_TURN) ? params.getAsJsonObject(F_TURN) : new JsonObject();
        String status = turn.has(F_STATUS) ? turn.get(F_STATUS).getAsString() : "completed";

        if ("failed".equals(status)) {
            // Extract and display the error to the user via the status banner
            String errorMsg = extractTurnErrorMessage(turn);
            if (cb != null) {
                cb.accept(new SessionUpdate.Banner(errorMsg, SessionUpdate.BannerLevel.ERROR, SessionUpdate.ClearOn.MANUAL));
            }
            CompletableFuture<String> f = activeTurnResult.get();
            if (f != null) f.complete(F_ERROR);
            return;
        }

        // Emit usage stats if available
        if (cb != null && turn.has(F_USAGE)) {
            emitUsageStats(turn.getAsJsonObject(F_USAGE), cb);
        }
        CompletableFuture<String> f = activeTurnResult.get();
        if (f != null) f.complete("interrupted".equals(status) ? "cancelled" : "end_turn");
    }

    @NotNull
    private static String extractTurnErrorMessage(@NotNull JsonObject turn) {
        return CodexMessageParser.extractTurnErrorMessage(turn);
    }

    private void handleTurnFailed(@NotNull JsonObject params) {
        reasoningActive = false;
        String errorMsg = "Codex turn failed";
        if (params.has(F_TURN)) {
            JsonObject turn = params.getAsJsonObject(F_TURN);
            errorMsg = extractTurnErrorMessage(turn);
        }
        Consumer<SessionUpdate> cb = activeTurnCallback.get();
        if (cb != null) {
            cb.accept(new SessionUpdate.Banner(errorMsg, SessionUpdate.BannerLevel.ERROR, SessionUpdate.ClearOn.MANUAL));
        }
        CompletableFuture<String> f = activeTurnResult.get();
        if (f != null) f.complete(F_ERROR);
    }

    // ── Tool call emission ────────────────────────────────────────────────────

    private void emitMcpToolCallStart(@NotNull JsonObject item, @NotNull Consumer<SessionUpdate> cb) {
        String id = item.has(F_ID) ? item.get(F_ID).getAsString() : UUID.randomUUID().toString();
        // MCP tool call fields: server, tool, arguments
        String rawTool = item.has(F_TOOL) ? item.get(F_TOOL).getAsString() : "tool";
        // Strip "agentbridge_" prefix if the server namespaces tool names
        String toolName = rawTool.startsWith(AGENTBRIDGE_PREFIX) ? rawTool.substring(AGENTBRIDGE_PREFIX.length()) : rawTool;
        JsonObject args = item.has(F_ARGUMENTS) && item.get(F_ARGUMENTS).isJsonObject()
            ? item.getAsJsonObject(F_ARGUMENTS) : new JsonObject();

        SessionUpdate.ToolKind kind = SessionUpdate.ToolKind.OTHER;
        if (registry != null) {
            ToolDefinition def = registry.findById(toolName);
            if (def != null) kind = SessionUpdate.ToolKind.fromCategory(def.category());
        }
        cb.accept(new SessionUpdate.ToolCall(id, toolName, toolName, kind, args.toString(), null, null, null, null, null));
    }

    private void emitMcpToolCallEnd(@NotNull JsonObject item, @NotNull Consumer<SessionUpdate> cb) {
        String id = item.has(F_ID) ? item.get(F_ID).getAsString() : "";
        boolean success = !F_ERROR.equals(item.has(F_STATUS) ? item.get(F_STATUS).getAsString() : "");
        String content = "";
        if (item.has("output")) {
            JsonElement out = item.get("output");
            content = out.isJsonPrimitive() ? out.getAsString() : out.toString();
        }
        SessionUpdate.ToolCallStatus status = success
            ? SessionUpdate.ToolCallStatus.COMPLETED
            : SessionUpdate.ToolCallStatus.FAILED;
        cb.accept(new SessionUpdate.ToolCallUpdate(id, status, success ? content : null, success ? null : content, null));
    }

    private void emitNativeCommandItem(@NotNull JsonObject item, @NotNull Consumer<SessionUpdate> cb) {
        String id = item.has(F_ID) ? item.get(F_ID).getAsString() : UUID.randomUUID().toString();
        String cmd = item.has(F_COMMAND) ? item.get(F_COMMAND).getAsString() : "shell";
        cb.accept(new SessionUpdate.ToolCall(id, "shell_command", "shell_command", SessionUpdate.ToolKind.OTHER,
            buildCommandArgsJson(cmd), null, null, null, null, null));
        cb.accept(new SessionUpdate.ToolCallUpdate(id, SessionUpdate.ToolCallStatus.FAILED,
            null, "Declined: native shell execution is not permitted. Use MCP tools instead.", null));
    }

    static String buildCommandArgsJson(@NotNull String command) {
        return "{\"command\":\"" + command.replace("\"", "\\\"") + "\"}";
    }

    private void emitUsageStats(@NotNull JsonObject usage, @NotNull Consumer<SessionUpdate> cb) {
        int inputTokens = safeGetInt(usage, F_INPUT_TOKENS);
        int outputTokens = safeGetInt(usage, F_OUTPUT_TOKENS);
        if (inputTokens == 0 && outputTokens == 0) return;
        cb.accept(new SessionUpdate.TurnUsage(inputTokens, outputTokens, 0.0));
    }

    private static int safeGetInt(@NotNull JsonObject obj, @NotNull String field) {
        return CodexMessageParser.safeGetInt(obj, field);
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    @NotNull
    private String buildFullPrompt(@NotNull String prompt, boolean isNewSession) {
        return CodexMessageParser.buildFullPrompt(prompt, isNewSession, config.getSessionInstructions());
    }

    @NotNull
    private static String extractPromptText(@NotNull List<ContentBlock> blocks) {
        return CodexMessageParser.extractPromptText(blocks);
    }

    private static List<ContentBlock.Image> extractImageBlocks(@NotNull List<ContentBlock> blocks) {
        List<ContentBlock.Image> images = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Image img) {
                images.add(img);
            }
        }
        return images;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @NotNull
    private String resolveModel(@NotNull String sessionId, @Nullable String requestModel) throws AgentException {
        if (requestModel != null && !requestModel.isEmpty()) return requestModel;
        String stored = sessionModels.get(sessionId);
        if (stored != null && !stored.isEmpty()) return stored;
        throw new AgentException("No model selected — please select a model before sending a prompt", null, false);
    }

    @Nullable
    private String getSessionOption(@NotNull String sessionId, @NotNull String key) {
        Map<String, String> opts = sessionOptions.get(sessionId);
        return opts != null ? opts.get(key) : null;
    }

    private void ensureConnected() throws AgentException {
        if (!transport.isConnected()) throw new AgentException("Codex app-server not connected", null, false);
    }

    private static void startStderrDrainer(@NotNull Process proc) {
        Thread t = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    LOG.info("codex app-server stderr: " + line);
                }
            } catch (IOException ignored) {
                // Intentionally ignored — stderr drainer cleanup, nothing to recover
            }
        }, "codex-app-server-stderr");
        t.setDaemon(true);
        t.start();
    }

    // ── Binary resolution ─────────────────────────────────────────────────────

    @NotNull
    private String resolveBinary() throws AgentException {
        String custom = profile.getCustomBinaryPath();
        if (!custom.isEmpty()) {
            if (Files.isExecutable(Path.of(custom))) return custom;
            throw new AgentException("Codex binary not found at: " + custom, null, false);
        }
        for (String name : candidateNames()) {
            // Use BinaryDetector which spawns a login shell — finds npm global installs,
            // nvm-managed Node, etc. that are not on the JVM's inherited PATH.
            String found = BinaryDetector.findBinaryPath(name);
            if (found != null) return found;
        }
        throw new AgentException(
            "Codex CLI not found. Install with: npm install -g @openai/codex, then run 'codex login'.",
            null, false);
    }

    @NotNull
    private List<String> candidateNames() {
        return CodexMessageParser.candidateNames(profile.getBinaryName(), profile.getAlternateNames());
    }
}
