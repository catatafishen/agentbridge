package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.github.catatafishen.agentbridge.agent.AgentException;
import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.PermissionResponse;
import com.github.catatafishen.agentbridge.bridge.SessionOption;
import com.github.catatafishen.agentbridge.bridge.TransportType;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.AskUserTool;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.McpInjectionMethod;
import com.github.catatafishen.agentbridge.services.PermissionInjectionMethod;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.settings.ProjectFilesSettings;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicInteger;
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
public final class CodexAppServerClient extends AbstractAgentClient {

    private static final Logger LOG = Logger.getInstance(CodexAppServerClient.class);

    public static final String PROFILE_ID = "codex";

    // ── JSON-RPC field names ─────────────────────────────────────────────────

    private static final String F_ID = "id";
    private static final String F_METHOD = "method";
    private static final String F_PARAMS = "params";
    private static final String F_RESULT = "result";
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
    private static final String MCP_TOOL_APPROVAL_QUESTION_ID_PREFIX = "mcp_tool_call_approval_";
    private static final String AGENTS_MD = "AGENTS.md";

    private static final long TURN_WAIT_POLL_MILLIS = 1000;

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
        p.setBinaryName("codex");
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

    private volatile Process appServerProcess;
    private volatile OutputStream stdin;
    private volatile boolean connected = false;
    private volatile List<Model> dynamicModels = null;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

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
    private final Map<String, java.util.Set<String>> sessionApprovalAllows = new ConcurrentHashMap<>();

    /**
     * Tracks in-flight MCP tool calls: {@code callId → toolName}.
     * Populated from {@code item/started} notifications, consumed by {@code item/tool/requestUserInput}
     * to resolve tool names for permission checks, cleaned up on {@code item/completed}.
     */
    private final Map<String, String> pendingMcpToolNames = new ConcurrentHashMap<>();

    // Active turn state — one turn at a time over stdio
    private volatile String activeTurnId;
    private volatile Consumer<SessionUpdate> activeTurnCallback;
    private volatile CompletableFuture<String> activeTurnResult;
    private volatile String activeTurnSessionId;

    /**
     * Monotonic timestamp of the last output/activity seen for the active turn.
     */
    private volatile long activeTurnLastOutputNanos;
    /**
     * True once a thinking chip has been opened for the current turn (suppresses duplicate placeholders).
     */
    private volatile boolean reasoningActive = false;

    @Nullable
    private volatile Consumer<PermissionPrompt> permissionRequestListener;

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
        CodexCredentials creds = CodexCredentials.read();
        if (!creds.isLoggedIn()) {
            throw new AgentException(
                "Not authenticated with Codex. Run 'codex login --device-auth' in a terminal, then retry.",
                null, false);
        }
        launchAppServer();
        LOG.info("CodexAppServerClient started" +
            (creds.getDisplayName() != null ? " (account: " + creds.getDisplayName() + ")" : ""));
    }

    @Override
    public void stop() {
        connected = false;
        pendingRequests.forEach((id, f) -> f.completeExceptionally(new AgentException("Client stopped", null, false)));
        pendingRequests.clear();
        CompletableFuture<String> turn = activeTurnResult;
        if (turn != null) turn.completeExceptionally(new AgentException("Client stopped", null, false));
        activeTurnResult = null;
        activeTurnCallback = null;
        closeQuietly(stdin);
        stdin = null;
        if (appServerProcess != null) {
            appServerProcess.destroyForcibly();
            appServerProcess = null;
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
        return connected;
    }

    @Override
    public boolean isHealthy() {
        return isConnected() && appServerProcess != null && appServerProcess.isAlive();
    }

    @Override
    public @Nullable String checkAuthentication() {
        return checkAuthenticationPreStart();
    }

    @Override
    public @Nullable String checkAuthenticationPreStart() {
        return checkCredentials();
    }

    /**
     * Static credential check — usable without instantiating the client.
     * Reads {@code ~/.codex/auth.json} to determine login state.
     */
    @Nullable
    public static String checkCredentials() {
        CodexCredentials creds = CodexCredentials.read();
        return creds.isLoggedIn() ? null
            : "Not authenticated with Codex. Run 'codex login --device-auth' in a terminal, then retry.";
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
        sessionApprovalAllows.remove(sessionId);
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
        this.permissionRequestListener = listener;
    }

    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) {
        sessionModels.put(sessionId, modelId);
    }

    @Override
    public List<Model> getAvailableModels() {
        List<Model> models = dynamicModels;
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
        activeTurnResult = turnResult;
        activeTurnCallback = onUpdate;
        activeTurnSessionId = sessionId;
        activeTurnLastOutputNanos = turnStartNanos;

        // Start the turn
        String turnId = startTurn(threadId, fullPrompt, model, sessionId);
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
            activeTurnResult = null;
            activeTurnCallback = null;
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

            long waitMillis = Math.max(1L, Math.min(TURN_WAIT_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
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
                com.github.catatafishen.agentbridge.settings.ShellEnvironment.getEnvironment());
            if (project != null && project.getBasePath() != null) {
                pb.directory(new File(project.getBasePath()));
            }
            pb.redirectErrorStream(false);
            appServerProcess = pb.start();
            stdin = appServerProcess.getOutputStream();

            // Drain stderr on a daemon thread
            startStderrDrainer(appServerProcess);

            // Start reader thread
            startReaderThread(appServerProcess);

            // Perform JSON-RPC initialize handshake
            initialize();
            connected = true;
            LOG.info("codex app-server ready");
        } catch (IOException e) {
            throw new AgentException("Failed to start codex app-server: " + e.getMessage(), e, true);
        }
    }

    @NotNull
    private List<String> buildServerCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedBinaryPath);
        cmd.add("app-server");

        // Disable native shell execution tools; model must use MCP tools instead
        cmd.add("--config");
        cmd.add("features.shell_tool=false");
        cmd.add("--config");
        cmd.add("features.unified_exec=false");

        // Inject MCP server via --config if mcpPort is available
        if (mcpPort > 0) {
            cmd.add("--config");
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
            dynamicModels = Collections.unmodifiableList(models);
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
        if (!el.isJsonObject()) return null;
        JsonObject m = el.getAsJsonObject();
        String id = m.has("id") ? m.get("id").getAsString() : null;
        if (id == null || id.isEmpty()) return null;
        String name = m.has("name") ? m.get("name").getAsString() : id;
        return new Model(id, name, null, null);
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
        params.addProperty("model", model);
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
        params.addProperty("model", model);

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

    /**
     * Starts a turn within a thread and returns the turnId.
     */
    @NotNull
    private String startTurn(@NotNull String threadId,
                             @NotNull String prompt,
                             @NotNull String model,
                             @NotNull String sessionId) throws AgentException {
        JsonObject textItem = new JsonObject();
        textItem.addProperty(F_TYPE, "text");
        textItem.addProperty(F_TEXT, prompt);
        JsonArray input = new JsonArray();
        input.add(textItem);

        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("input", input);
        params.addProperty("model", model);

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
            LOG.info("Started Codex turn " + turnId + " in thread " + threadId);
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

    /**
     * Sends a JSON-RPC request and returns a CompletableFuture resolved by the reader thread.
     */
    @NotNull
    private CompletableFuture<JsonObject> sendRequest(@NotNull String method, @NotNull JsonObject params) {
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        JsonObject msg = new JsonObject();
        msg.addProperty(F_ID, id);
        msg.addProperty(F_METHOD, method);
        msg.add(F_PARAMS, params);

        if (!writeMessage(msg)) {
            pendingRequests.remove(id);
            future.completeExceptionally(new AgentException("Failed to write to codex app-server", null, true));
        }
        return future;
    }

    /**
     * Sends a JSON-RPC notification (no ID, no response expected).
     */
    private void sendNotification(@NotNull String method, @NotNull JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty(F_METHOD, method);
        msg.add(F_PARAMS, params);
        writeMessage(msg);
    }

    /**
     * Sends a JSON-RPC response to a server-initiated request.
     */
    private void sendResponse(@NotNull JsonElement id, @NotNull JsonElement result) {
        JsonObject msg = new JsonObject();
        msg.add(F_ID, id);
        msg.add(F_RESULT, result);
        writeMessage(msg);
    }

    private boolean writeMessage(@NotNull JsonObject msg) {
        OutputStream out = stdin;
        if (out == null) return false;
        try {
            String json = msg.toString();
            if (isDebugLoggingEnabled()) {
                LOG.info("[Codex] >>> " + json);
            }
            synchronized (out) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            }
            return true;
        } catch (IOException e) {
            LOG.debug("codex app-server: write failed: " + e.getMessage());
            return false;
        }
    }

    private boolean isDebugLoggingEnabled() {
        return project != null && McpServerSettings.getInstance(project).isDebugLoggingEnabled();
    }

    // ── Reader thread ─────────────────────────────────────────────────────────

    private void startReaderThread(@NotNull Process proc) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    processLine(line);
                }
            } catch (IOException e) {
                if (connected) LOG.warn("codex app-server reader ended: " + e.getMessage());
            } finally {
                connected = false;
                CompletableFuture<String> turn = activeTurnResult;
                if (turn != null && !turn.isDone()) {
                    turn.completeExceptionally(new AgentException("codex app-server disconnected", null, true));
                }
                pendingRequests.forEach((id, f) ->
                    f.completeExceptionally(new AgentException("codex app-server disconnected", null, true)));
                pendingRequests.clear();
            }
        }, "codex-app-server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Parses a single JSONL line from the app-server and dispatches it.
     */
    private void processLine(@NotNull String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            if (isDebugLoggingEnabled()) {
                LOG.info("[Codex] <<< " + line);
            }
            if (activeTurnResult != null && !activeTurnResult.isDone()) {
                activeTurnLastOutputNanos = System.nanoTime();
            }
            dispatchMessage(msg);
        } catch (RuntimeException e) {
            LOG.warn("codex app-server: could not parse line: " + line, e);
        }
    }

    /**
     * Routes an incoming JSONL message to the appropriate handler.
     *
     * <p>Three categories:
     * <ol>
     *   <li>Response to our request: has numeric {@code id} and {@code result}/{@code error} fields.</li>
     *   <li>Notification from server: has {@code method} but no {@code id} (or null {@code id}).</li>
     *   <li>Server-initiated request: has both {@code method} and a non-null {@code id} — e.g. approval requests.</li>
     * </ol>
     */
    private void dispatchMessage(@NotNull JsonObject msg) {
        // id is "present and non-null" — some implementations send "id": null in notifications
        boolean hasId = msg.has(F_ID) && !msg.get(F_ID).isJsonNull();
        boolean hasMethod = msg.has(F_METHOD);
        boolean hasResult = msg.has(F_RESULT) || msg.has(F_ERROR);

        if (hasId && hasResult && !hasMethod) {
            handleResponse(msg);
        } else if (hasMethod && hasId) {
            // Server-initiated request (approval or tool call) — has an id we must echo back
            handleServerRequest(msg);
        } else if (hasMethod) {
            // Notification from server
            handleNotification(msg);
        }
    }

    private void handleResponse(@NotNull JsonObject msg) {
        JsonElement idEl = msg.get(F_ID);
        if (idEl.isJsonPrimitive() && idEl.getAsJsonPrimitive().isNumber()) {
            int id = idEl.getAsInt();
            CompletableFuture<JsonObject> f = pendingRequests.remove(id);
            if (f != null) {
                completeResponseFuture(f, msg);
            }
        }
    }

    /**
     * Resolves a pending-request future from the given JSON-RPC result/error message.
     */
    private void completeResponseFuture(@NotNull CompletableFuture<JsonObject> f,
                                        @NotNull JsonObject msg) {
        if (msg.has(F_ERROR)) {
            JsonObject err = msg.getAsJsonObject(F_ERROR);
            String errMsg = err.has(F_MESSAGE) ? err.get(F_MESSAGE).getAsString() : err.toString();
            f.completeExceptionally(new AgentException("JSON-RPC error: " + errMsg, null, false));
        } else {
            JsonElement result = msg.get(F_RESULT);
            f.complete(result.isJsonObject() ? result.getAsJsonObject() : new JsonObject());
        }
    }

    // ── Server-initiated request handling ────────────────────────────────────

    private void handleServerRequest(@NotNull JsonObject msg) {
        String method = msg.get(F_METHOD).getAsString();
        JsonElement id = msg.get(F_ID);
        JsonObject params = msg.has(F_PARAMS) ? msg.getAsJsonObject(F_PARAMS) : new JsonObject();

        LOG.info("codex app-server request: " + method);

        switch (method) {
            case "item/commandExecution/requestApproval", "item/fileChange/requestApproval" ->
                handleNativeApprovalRequest(id, method, params);
            case "item/tool/requestUserInput" -> handleUserInputRequest(id, params);
            case "item/tool/call" -> {
                String toolName = params.has(F_TOOL) ? params.get(F_TOOL).getAsString() : "unknown";
                if ("request_user_input".equals(toolName) && project != null) {
                    handleNativeAskUserRequest(id, params);
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
                // Unknown server request — send a generic error response
                LOG.warn("Unknown server request method: " + method);
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty(F_MESSAGE, "Method not found: " + method);
                JsonObject errResp = new JsonObject();
                errResp.add(F_ID, id);
                errResp.add(F_ERROR, error);
                writeMessage(errResp);
            }
        }
    }

    /**
     * Handles {@code item/tool/requestUserInput} — a generic user-input mechanism that
     * Codex uses both for MCP tool call approvals and potentially other question types.
     *
     * <p>MCP tool approval questions are identified by ID prefix {@code mcp_tool_call_approval_}
     * (matching Codex's {@code MCP_TOOL_APPROVAL_QUESTION_ID_PREFIX}). These are auto-approved
     * at the protocol level for ALLOW/ASK tools (the real permission enforcement happens in
     * {@link com.github.catatafishen.agentbridge.psi.PsiBridgeService#callTool}),
     * and declined early for DENY tools to avoid unnecessary MCP round-trips.</p>
     *
     * <p>Non-approval questions are forwarded to the user via {@link AskUserTool}.</p>
     *
     * <p><b>Wire format</b> (per Codex app-server protocol spec):</p>
     * <pre>
     * Response: { answers: { questionId: { answers: ["label"] } } }
     * </pre>
     *
     * @see <a href="https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/schema/typescript/v2/ToolRequestUserInputResponse.ts">ToolRequestUserInputResponse</a>
     * @see <a href="https://github.com/openai/codex/blob/main/codex-rs/core/src/mcp_tool_call.rs">mcp_tool_call.rs</a>
     */
    private void handleUserInputRequest(@NotNull JsonElement id, @NotNull JsonObject params) {
        JsonArray questions = params.has("questions") ? params.getAsJsonArray("questions") : new JsonArray();
        String itemId = params.has("itemId") ? params.get("itemId").getAsString() : "";

        JsonObject answers = new JsonObject();
        for (JsonElement elem : questions) {
            if (!elem.isJsonObject()) continue;
            JsonObject q = elem.getAsJsonObject();
            String qId = q.has(F_ID) ? q.get(F_ID).getAsString() : "";

            String answerLabel;
            if (isMcpToolApprovalQuestion(qId)) {
                answerLabel = resolveMcpToolApprovalAnswer(itemId, qId);
            } else {
                answerLabel = askUserForQuestionAnswer(q);
            }

            JsonObject answerObj = new JsonObject();
            JsonArray answerArr = new JsonArray();
            answerArr.add(answerLabel);
            answerObj.add("answers", answerArr);
            answers.add(qId, answerObj);
        }
        JsonObject result = new JsonObject();
        result.add("answers", answers);
        sendResponse(id, result);
    }

    /**
     * Matches Codex's {@code MCP_TOOL_APPROVAL_QUESTION_ID_PREFIX}: questions whose ID
     * starts with {@code mcp_tool_call_approval_} are MCP tool call approval prompts.
     */
    private static boolean isMcpToolApprovalQuestion(@NotNull String questionId) {
        return questionId.startsWith(MCP_TOOL_APPROVAL_QUESTION_ID_PREFIX);
    }

    /**
     * Resolves an MCP tool approval answer using the plugin's shared permission infrastructure.
     *
     * <p>Looks up the tool name from the {@link #pendingMcpToolNames} cache (populated by
     * {@code item/started} notifications) and checks {@link ToolLayerSettings#getToolPermission}.
     * DENY tools are declined at this level to avoid an unnecessary MCP round-trip.
     * ALLOW and ASK tools are auto-approved here — matching the Copilot CLI approach where real
     * permission enforcement happens at the MCP server layer
     * ({@link com.github.catatafishen.agentbridge.psi.PsiBridgeService#callTool}).</p>
     *
     * @see <a href="https://github.com/openai/codex/blob/main/codex-rs/core/src/mcp_tool_call.rs">
     * Codex MCP_TOOL_APPROVAL_ACCEPT / CANCEL constants</a>
     */
    @NotNull
    private String resolveMcpToolApprovalAnswer(@NotNull String itemId, @NotNull String questionId) {
        String toolName = pendingMcpToolNames.get(itemId);
        if (toolName != null) {
            ToolPermission permission = resolveNativeApprovalPermission(toolName);
            if (permission == ToolPermission.DENY) {
                LOG.info("Declining MCP tool at Codex protocol level: " + toolName + " (DENY in settings)");
                return "Cancel";
            }
        }
        LOG.info("Auto-approving MCP tool at Codex protocol level: " + questionId
            + (toolName != null ? " (tool=" + toolName + ")" : " (tool name not cached)"));
        return "Allow for this session";
    }

    /**
     * Forwards a non-approval {@code requestUserInput} question to the user via {@link AskUserTool}.
     * Extracts the question text and options from the Codex protocol format and translates the
     * user's response back to an answer label.
     */
    @NotNull
    private String askUserForQuestionAnswer(@NotNull JsonObject question) {
        String questionText = question.has("question") ? question.get("question").getAsString() : "";
        List<String> optionLabels = new ArrayList<>();
        if (question.has("options") && question.get("options").isJsonArray()) {
            for (JsonElement opt : question.getAsJsonArray("options")) {
                if (opt.isJsonObject() && opt.getAsJsonObject().has("label")) {
                    optionLabels.add(opt.getAsJsonObject().get("label").getAsString());
                }
            }
        }

        JsonObject toolArgs = new JsonObject();
        toolArgs.addProperty("question", questionText);
        if (!optionLabels.isEmpty()) {
            JsonArray opts = new JsonArray();
            optionLabels.forEach(opts::add);
            toolArgs.add("options", opts);
        }

        try {
            String response = new AskUserTool(project).execute(toolArgs);
            // If the response matches an option label, use it directly
            for (String label : optionLabels) {
                if (label.equalsIgnoreCase(response.trim())) return label;
            }
            return response.trim();
        } catch (Exception e) {
            LOG.warn("AskUserTool failed for Codex requestUserInput question", e);
            // Return the last option (typically "Cancel") as a safe fallback
            return optionLabels.isEmpty() ? "Cancel" : optionLabels.getLast();
        }
    }

    // ── Notification handling ─────────────────────────────────────────────────

    private void handleNotification(@NotNull JsonObject msg) {
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

    private void handleTextDelta(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback;
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

        // Cache tool name for in-flight MCP calls (used by handleUserInputRequest for permission checks)
        if (TYPE_MCP_TOOL_CALL.equals(type) && item.has(F_ID) && item.has(F_TOOL)) {
            String rawTool = item.get(F_TOOL).getAsString();
            String toolName = rawTool.startsWith(AGENTBRIDGE_PREFIX) ? rawTool.substring(AGENTBRIDGE_PREFIX.length()) : rawTool;
            pendingMcpToolNames.put(item.get(F_ID).getAsString(), toolName);
        }

        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb == null) return;
        switch (type) {
            case TYPE_MCP_TOOL_CALL -> emitMcpToolCallStart(item, cb);
            case "reasoning" -> {
                // Emit one thinking chip per turn, then stream any available reasoning text into it.
                if (!reasoningActive) {
                    reasoningActive = true;
                    cb.accept(new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Text("Thought"))));
                }
                appendReasoningContent(item, cb);
            }
        }
    }

    private void handleItemCompleted(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (!params.has(F_ITEM)) return;
        JsonObject item = params.getAsJsonObject(F_ITEM);
        String type = item.has(F_TYPE) ? item.get(F_TYPE).getAsString() : "";

        switch (type) {
            case TYPE_MCP_TOOL_CALL -> {
                if (item.has(F_ID)) pendingMcpToolNames.remove(item.get(F_ID).getAsString());
                if (cb != null) emitMcpToolCallEnd(item, cb);
            }
            case "commandExecution" -> {
                // Native command attempted — emit as a tool call (already declined, just for UI)
                if (cb != null) emitNativeCommandItem(item, cb);
            }
            default -> { /* reasoning is streamed via summaryTextDelta; no other types require handling */ }
        }
    }

    private void handleReasoningDelta(@NotNull JsonObject params) {
        Consumer<SessionUpdate> cb = activeTurnCallback;
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
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement child : el.getAsJsonArray()) {
                String childText = extractReasoningText(child);
                if (!childText.isEmpty()) sb.append(childText);
            }
            return sb.toString();
        }
        if (!el.isJsonObject()) return "";

        JsonObject obj = el.getAsJsonObject();
        if (obj.has(F_TEXT) && obj.get(F_TEXT).isJsonPrimitive()) return obj.get(F_TEXT).getAsString();
        if (obj.has("thinking") && obj.get("thinking").isJsonPrimitive()) return obj.get("thinking").getAsString();
        if (obj.has("summary")) return extractReasoningText(obj.get("summary"));
        if (obj.has(F_DELTA)) return extractReasoningText(obj.get(F_DELTA));
        if (obj.has("content")) return extractReasoningText(obj.get("content"));
        return "";
    }

    private void handleTurnCompleted(@NotNull JsonObject params) {
        reasoningActive = false;
        Consumer<SessionUpdate> cb = activeTurnCallback;
        JsonObject turn = params.has(F_TURN) ? params.getAsJsonObject(F_TURN) : new JsonObject();
        String status = turn.has(F_STATUS) ? turn.get(F_STATUS).getAsString() : "completed";

        if ("failed".equals(status)) {
            // Extract and display the error to the user via the status banner
            String errorMsg = extractTurnErrorMessage(turn);
            if (cb != null) {
                cb.accept(new SessionUpdate.Banner(errorMsg, SessionUpdate.BannerLevel.ERROR, SessionUpdate.ClearOn.MANUAL));
            }
            CompletableFuture<String> f = activeTurnResult;
            if (f != null) f.complete(F_ERROR);
            return;
        }

        // Emit usage stats if available
        if (cb != null && turn.has(F_USAGE)) {
            emitUsageStats(turn.getAsJsonObject(F_USAGE), cb);
        }
        CompletableFuture<String> f = activeTurnResult;
        if (f != null) f.complete("interrupted".equals(status) ? "cancelled" : "end_turn");
    }

    /**
     * Extracts a human-readable error message from a failed turn's error object.
     * The {@code message} field may itself be a JSON string (nested error envelope),
     * in which case we try to unwrap the inner {@code error.message}.
     */
    @NotNull
    private static String extractTurnErrorMessage(@NotNull JsonObject turn) {
        if (!turn.has(F_ERROR)) return "Codex turn failed";
        JsonElement errEl = turn.get(F_ERROR);
        if (!errEl.isJsonObject()) return errEl.isJsonNull() ? "Codex turn failed" : errEl.getAsString();
        JsonObject err = errEl.getAsJsonObject();
        String raw = err.has(F_MESSAGE) ? err.get(F_MESSAGE).getAsString() : err.toString();
        // The message field is sometimes a JSON string itself; try to unwrap it
        if (raw.startsWith("{")) {
            try {
                JsonObject nested = JsonParser.parseString(raw).getAsJsonObject();
                if (nested.has(F_ERROR) && nested.getAsJsonObject(F_ERROR).has(F_MESSAGE)) {
                    return nested.getAsJsonObject(F_ERROR).get(F_MESSAGE).getAsString();
                }
            } catch (RuntimeException ignored) {
                // Fall through to returning the raw string
            }
        }
        return raw;
    }

    private void handleTurnFailed(@NotNull JsonObject params) {
        reasoningActive = false;
        String errorMsg = "Codex turn failed";
        if (params.has(F_TURN)) {
            JsonObject turn = params.getAsJsonObject(F_TURN);
            errorMsg = extractTurnErrorMessage(turn);
        }
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb != null) {
            cb.accept(new SessionUpdate.Banner(errorMsg, SessionUpdate.BannerLevel.ERROR, SessionUpdate.ClearOn.MANUAL));
        }
        CompletableFuture<String> f = activeTurnResult;
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
        cb.accept(new SessionUpdate.ToolCall(id, toolName, kind, args.toString(), null, null, null, null, null));
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
        cb.accept(new SessionUpdate.ToolCall(id, "shell_command", SessionUpdate.ToolKind.OTHER,
            "{\"command\":\"" + cmd.replace("\"", "\\\"") + "\"}", null, null, null, null, null));
        cb.accept(new SessionUpdate.ToolCallUpdate(id, SessionUpdate.ToolCallStatus.FAILED,
            null, "Declined: native shell execution is not permitted. Use MCP tools instead.", null));
    }

    /**
     * Routes Codex's native {@code request_user_input} tool call to our {@link AskUserTool},
     * then returns the user's response to Codex. Blocks the reader thread until the user replies
     * (same pattern as {@link #requestNativeApproval}).
     */
    private void handleNativeAskUserRequest(@NotNull JsonElement id, @NotNull JsonObject params) {
        JsonObject arguments = params.has(F_ARGUMENTS) && params.get(F_ARGUMENTS).isJsonObject()
            ? params.getAsJsonObject(F_ARGUMENTS) : new JsonObject();

        // Codex uses "question" or "prompt" for the question text
        String question = null;
        for (String key : List.of("question", "prompt", "message", "text")) {
            if (arguments.has(key) && arguments.get(key).isJsonPrimitive()) {
                question = arguments.get(key).getAsString().trim();
                if (!question.isEmpty()) break;
            }
        }
        if (question == null || question.isEmpty()) {
            question = "The agent has a question for you. Please provide your response.";
        }

        // Build args matching AskUserTool's expected schema
        JsonObject toolArgs = new JsonObject();
        toolArgs.addProperty("question", question);
        JsonArray options = new JsonArray();
        if (arguments.has("options") && arguments.get("options").isJsonArray()) {
            options = arguments.getAsJsonArray("options");
        }
        if (options.isEmpty()) {
            options.add("Continue");
        }
        toolArgs.add("options", options);

        // Emit a ToolCall chip so the user sees the ask_user tool being invoked
        String chipId = UUID.randomUUID().toString();
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb != null) {
            cb.accept(new SessionUpdate.ToolCall(chipId, "ask_user", SessionUpdate.ToolKind.OTHER,
                toolArgs.toString(), null, null, null, null, null));
        }

        String userResponse;
        try {
            userResponse = new AskUserTool(project).execute(toolArgs);
        } catch (Exception e) {
            LOG.warn("AskUserTool failed during Codex native request_user_input", e);
            userResponse = "Error: failed to get user input";
        }

        if (cb != null) {
            boolean success = !userResponse.startsWith("Error:");
            cb.accept(new SessionUpdate.ToolCallUpdate(chipId,
                success ? SessionUpdate.ToolCallStatus.COMPLETED : SessionUpdate.ToolCallStatus.FAILED,
                success ? userResponse : null, success ? null : userResponse, null));
        }

        // Send response back to Codex
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty(F_TYPE, "text");
        textBlock.addProperty(F_TEXT, userResponse);
        JsonArray content = new JsonArray();
        content.add(textBlock);
        JsonObject resp = new JsonObject();
        resp.add("content", content);
        sendResponse(id, resp);
    }

    private void handleNativeApprovalRequest(@NotNull JsonElement id, @NotNull String method, @NotNull JsonObject params) {
        String sessionId = activeTurnSessionId;
        String permissionKey = method.contains("commandExecution") ? "run_command" : "write_file";
        ToolPermission permission = resolveNativeApprovalPermission(permissionKey);

        if (sessionId != null && isSessionApprovalAllowed(sessionId, permissionKey)) {
            LOG.info("Allowing native approval from session cache: " + method + " -> " + permissionKey);
            sendNativeApprovalDecision(id, "accept");
            return;
        }

        switch (permission) {
            case ALLOW -> {
                LOG.info("Allowing native approval from settings: " + method + " -> " + permissionKey);
                sendNativeApprovalDecision(id, "accept");
                if (sessionId != null) allowSessionApproval(sessionId, permissionKey);
            }
            case DENY -> {
                LOG.info("Denying native approval from settings: " + method + " -> " + permissionKey);
                sendNativeApprovalDecision(id, "decline");
                emitToolDeclinedBanner(method, params);
            }
            case ASK -> {
                PermissionResponse response = requestNativeApproval(method, permissionKey, params);
                switch (response) {
                    case ALLOW_SESSION -> {
                        if (sessionId != null) allowSessionApproval(sessionId, permissionKey);
                        sendNativeApprovalDecision(id, "acceptForSession");
                    }
                    case ALLOW_ONCE -> sendNativeApprovalDecision(id, "accept");
                    case DENY -> {
                        sendNativeApprovalDecision(id, "decline");
                        emitToolDeclinedBanner(method, params);
                    }
                }
            }
        }
    }

    private ToolPermission resolveNativeApprovalPermission(@NotNull String permissionKey) {
        if (project == null) {
            return ToolPermission.DENY;
        }
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        return settings.getToolPermission(permissionKey);
    }

    private boolean isSessionApprovalAllowed(@NotNull String sessionId, @NotNull String permissionKey) {
        java.util.Set<String> allowed = sessionApprovalAllows.get(sessionId);
        return allowed != null && allowed.contains(permissionKey);
    }

    private void allowSessionApproval(@NotNull String sessionId, @NotNull String permissionKey) {
        sessionApprovalAllows.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(permissionKey);
    }

    private PermissionResponse requestNativeApproval(@NotNull String method,
                                                     @NotNull String permissionKey,
                                                     @NotNull JsonObject params) {
        String displayName = "item/commandExecution/requestApproval".equals(method)
            ? "Run command"
            : "Edit file";
        String description = buildNativeApprovalDescription(method, params);
        CompletableFuture<PermissionResponse> future = new CompletableFuture<>();

        String promptId = method + ":" + permissionKey + ":" + UUID.randomUUID();
        PermissionPrompt prompt = new PermissionPrompt() {
            @Override
            public String toolCallId() {
                return promptId;
            }

            @Override
            public String toolName() {
                return displayName;
            }

            @Override
            public @Nullable String arguments() {
                return description;
            }

            @Override
            public List<String> options() {
                return List.of("allow_once", "allow_session", "deny");
            }

            @Override
            public void allow(String optionId) {
                if (optionId != null && optionId.contains("session")) {
                    future.complete(PermissionResponse.ALLOW_SESSION);
                } else {
                    future.complete(PermissionResponse.ALLOW_ONCE);
                }
            }

            @Override
            public void deny(String reason) {
                future.complete(PermissionResponse.DENY);
            }
        };

        Consumer<PermissionPrompt> listener = permissionRequestListener;
        if (listener != null) {
            listener.accept(prompt);
        } else if (project != null) {
            ChatConsolePanel chatPanel = ChatConsolePanel.Companion.getInstance(project);
            if (chatPanel != null) {
                String reqId = UUID.randomUUID().toString();
                chatPanel.showPermissionRequest(reqId, displayName, description, response -> {
                    future.complete(response);
                    return kotlin.Unit.INSTANCE;
                });
            }
        }

        try {
            PermissionResponse response = future.get(120, TimeUnit.SECONDS);
            return response != null ? response : PermissionResponse.DENY;
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.info("Native approval timed out for " + method);
            return PermissionResponse.DENY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PermissionResponse.DENY;
        } catch (java.util.concurrent.ExecutionException e) {
            LOG.warn("Native approval failed for " + method, e);
            return PermissionResponse.DENY;
        }
    }

    private static String buildNativeApprovalDescription(@NotNull String method, @NotNull JsonObject params) {
        String detail = extractNativeApprovalDetail(params);
        if (detail.isEmpty()) {
            return method;
        }
        return method + "\n" + detail;
    }

    private static String extractNativeApprovalDetail(@NotNull JsonObject params) {
        for (String key : List.of(F_COMMAND, "path", "filePath", "reason")) {
            if (params.has(key) && !params.get(key).isJsonNull()) {
                JsonElement value = params.get(key);
                if (value.isJsonPrimitive()) {
                    return value.getAsString();
                }
                return value.toString();
            }
        }
        if (params.entrySet().isEmpty()) {
            return "";
        }
        return params.toString();
    }

    private void sendNativeApprovalDecision(@NotNull JsonElement id, @NotNull String decision) {
        JsonObject result = new JsonObject();
        result.addProperty("decision", decision);
        sendResponse(id, result);
    }

    private void emitToolDeclinedBanner(@NotNull String method, @NotNull JsonObject params) {
        // Surface a soft warning if the model is trying to use native tools
        Consumer<SessionUpdate> cb = activeTurnCallback;
        if (cb == null) return;
        String detail;
        if (params.has(F_COMMAND) && !params.get(F_COMMAND).isJsonNull()) {
            JsonElement command = params.get(F_COMMAND);
            detail = command.isJsonPrimitive() ? command.getAsString() : command.toString();
        } else if (params.has("reason") && !params.get("reason").isJsonNull()) {
            JsonElement reason = params.get("reason");
            detail = reason.isJsonPrimitive() ? reason.getAsString() : reason.toString();
        } else {
            detail = method;
        }
        cb.accept(new SessionUpdate.Banner(
            "Native tool declined: " + detail + ". Use MCP tools instead.",
            SessionUpdate.BannerLevel.WARNING,
            SessionUpdate.ClearOn.NEXT_SUCCESS));
    }

    private void emitUsageStats(@NotNull JsonObject usage, @NotNull Consumer<SessionUpdate> cb) {
        int inputTokens = safeGetInt(usage, F_INPUT_TOKENS);
        int outputTokens = safeGetInt(usage, F_OUTPUT_TOKENS);
        if (inputTokens == 0 && outputTokens == 0) return;
        cb.accept(new SessionUpdate.TurnUsage(inputTokens, outputTokens, 0.0));
    }

    private static int safeGetInt(@NotNull JsonObject obj, @NotNull String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) return 0;
        return obj.get(field).getAsInt();
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    @NotNull
    private String buildFullPrompt(@NotNull String prompt, boolean isNewSession) {
        if (!isNewSession) return prompt;
        StringBuilder sb = new StringBuilder();
        String instructions = config.getSessionInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            sb.append("<system-reminder>\n").append(instructions).append("\n</system-reminder>\n\n");
        }
        sb.append(prompt);
        return sb.toString();
    }

    @NotNull
    private static String extractPromptText(@NotNull List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Text t) {
                sb.append(t.text());
            } else if (block instanceof ContentBlock.Resource r) {
                ContentBlock.ResourceLink rl = r.resource();
                if (rl.text() != null && !rl.text().isEmpty()) {
                    sb.append("File: ").append(rl.uri()).append("\n```\n").append(rl.text()).append("\n```\n\n");
                }
            }
        }
        return sb.toString();
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
        if (!connected) throw new AgentException("Codex app-server not connected", null, false);
    }

    private static void closeQuietly(@Nullable OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Intentionally ignored — cleanup path, nothing to recover
        }
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
            String found = com.github.catatafishen.agentbridge.settings.BinaryDetector.findBinaryPath(name);
            if (found != null) return found;
        }
        throw new AgentException(
            "Codex CLI not found. Install with: npm install -g @openai/codex, then run 'codex login'.",
            null, false);
    }

    @NotNull
    private List<String> candidateNames() {
        List<String> names = new ArrayList<>();
        String primary = profile.getBinaryName();
        if (!primary.isEmpty()) names.add(primary);
        names.addAll(profile.getAlternateNames());
        if (!names.contains("codex")) names.add("codex");
        return names;
    }
}
