package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Generic ACP (Agent Client Protocol) client over JSON-RPC 2.0 stdin/stdout.
 * Agent-specific concerns (binary discovery, auth, model parsing) are delegated
 * to the {@link AgentConfig} strategy provided at construction time.
 */
public class AcpClient implements Closeable {
    private static final Logger LOG = Logger.getInstance(AcpClient.class);
    private static final long REQUEST_TIMEOUT_SECONDS = 30;
    private static final long INITIALIZE_TIMEOUT_SECONDS = 90;

    // JSON-RPC field names
    private static final String JSONRPC = "jsonrpc";
    private static final String METHOD = "method";
    private static final String PARAMS = "params";
    private static final String RESULT = "result";
    private static final String ERROR = "error";
    private static final String MESSAGE = "message";
    private static final String COMMAND = "command";
    private static final String SESSION_ID = "sessionId";
    private static final String DESCRIPTION = "description";
    private static final String META = "_meta";
    private static final String OPTIONS = "options";
    private static final String OPTION_ID = "optionId";
    private static final String CONTENT = "content";
    private static final String UNKNOWN = "unknown";
    private static final String RUN_COMMAND_ABUSE_PREFIX = "run_command_abuse:";
    private static final String GIT_WRITE_ABUSE_PREFIX = "git_write_abuse:";
    private static final String USER_HOME = "user.home";
    private static final String TITLE_KEY = "title";
    private static final String PARAMETERS_KEY = "parameters";
    private static final String TOOL_DENIED_DEFAULT_MSG_TEMPLATE = "⚠ Tool denied. Use tools with '%s' prefix instead.";
    private static final String PRE_REJECTION_GUIDANCE_EVENT = "PRE_REJECTION_GUIDANCE";
    private static final String SENDING_GUIDANCE_DESC = "Sending guidance before rejection";
    private static final String PERMISSION_DENIED_EVENT = "PERMISSION_DENIED";
    private static final String PERMISSION_APPROVED_EVENT = "PERMISSION_APPROVED";
    private static final String TOOL_PREFIX = " (tool=";

    // Note: DENIED_PERMISSION_KINDS was removed — permission denial is now handled by
    // per-tool ToolPermission settings in handlePermissionRequest, not a static set.

    private final Gson gson = new Gson();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonObject>> notificationListeners = new CopyOnWriteArrayList<>();

    private final Object writerLock = new Object();
    private final String projectBasePath; // Project path for config-dir
    private final AgentConfig agentConfig;
    private final AgentSettings agentSettings;
    private final int mcpPort;
    /**
     * Prefix used to strip the MCP server name from incoming tool-call names (e.g. "intellij-code-tools-").
     */
    private volatile String effectiveMcpPrefix = "intellij-code-tools-";
    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean closed = false;

    // Auto-restart state
    private final AtomicInteger restartAttempts = new AtomicInteger(0);
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final long[] RESTART_DELAYS_MS = {1000, 2000, 4000}; // Exponential backoff

    // State from the initialization response
    private boolean initialized = false;

    // Session state
    private String currentSessionId;
    private List<Model> availableModels;

    // Permission tracking: set when a built-in permission is denied during a prompt turn
    private volatile boolean builtInActionDeniedDuringTurn = false;
    private volatile String lastDeniedKind = "";

    // Sub-agent tracking: set by UI layer when a Task tool call is active
    private volatile boolean subAgentActive = false;

    private static final Set<String> GIT_WRITE_TOOLS = Set.of(
        "git_commit", "git_stage", "git_unstage", "git_branch", "git_stash", "git_push", "git_remote",
        "git_pull", "git_merge", "git_rebase", "git_cherry_pick", "git_tag", "git_reset"
    );

    private static final String SUBAGENT_WRITE_ABUSE_PREFIX = "subagent_write_abuse:";
    private static final String KIND_BASH = "bash";
    private static final String KIND_EXECUTE = "execute";

    /**
     * Built-in write/execute tools that sub-agents must not use.
     * These go through request_permission, so we can deny them.
     * Read-only built-ins (view, grep, glob) auto-execute without permission — unblockable.
     */
    private static final Set<String> BUILTIN_WRITE_TOOLS = Set.of(
        "edit", "create", KIND_BASH, "write", KIND_EXECUTE, "runInTerminal"
    );

    // Permission request listener
    private final AtomicReference<java.util.function.Consumer<PermissionRequest>> permissionRequestListener = new AtomicReference<>();
    private final java.util.Set<String> sessionAllowedTools = ConcurrentHashMap.newKeySet();

    // PermissionRequest is in separate file: PermissionRequest.java

    /**
     * Register a listener that is called when a tool with ASK permission needs user approval.
     */
    public void setPermissionRequestListener(java.util.function.Consumer<PermissionRequest> listener) {
        this.permissionRequestListener.set(listener);
    }

    // Activity tracking for inactivity-based timeout
    private volatile long lastActivityTimestamp = System.currentTimeMillis();
    private final AtomicInteger toolCallsInTurn = new AtomicInteger(0);

    /**
     * Set whether a sub-agent (Task tool) is currently active.
     * When active, git write tools are blocked via permission denial.
     * <p>
     * Note: sub-agents do NOT receive custom instructions or session/message guidance —
     * they run in their own context within the CLI. Read-only built-in tools (view, grep, glob)
     * cannot be intercepted. See CLI-BUG-556-WORKAROUND.md for details.
     */
    public void setSubAgentActive(boolean active) {
        this.subAgentActive = active;
    }

    /**
     * Fire a debug event to all listeners.
     */
    private void fireDebugEvent(String type, String message, String details) {
        // No registered debug listeners — retained for future extensibility.
    }

    /**
     * Create ACP client with an agent configuration, settings, and optional project base path.
     */
    public AcpClient(@NotNull AgentConfig agentConfig, @NotNull AgentSettings agentSettings,
                     @Nullable String projectBasePath, int mcpPort) {
        this.agentConfig = agentConfig;
        this.agentSettings = agentSettings;
        this.projectBasePath = projectBasePath;
        this.mcpPort = mcpPort;
    }

    /**
     * Start the ACP process and perform the initialization handshake.
     */
    public synchronized void start() throws AcpException {
        // Clean up the previous process if it died
        if (process != null) {
            LOG.info("Restarting ACP client (previous process died)");
            closed = false; // Reset closed flag for restart
            try {
                if (writer != null) writer.close();
            } catch (IOException e) {
                LOG.debug("Failed to close writer during restart", e);
            }
            if (process.isAlive()) process.destroyForcibly();
            failAllPendingRequests();
            availableModels = null;
            currentSessionId = null;
        }

        // Let the agent config perform pre-launch setup (e.g., ensure instruction files)
        agentConfig.prepareForLaunch(projectBasePath);

        try {
            String binaryPath = agentConfig.findAgentBinary();
            LOG.info("Starting " + agentConfig.getDisplayName() + " ACP: " + binaryPath);

            ProcessBuilder pb = agentConfig.buildAcpProcess(binaryPath, projectBasePath, mcpPort);
            effectiveMcpPrefix = agentConfig.getEffectiveMcpServerName() + "-";
            pb.redirectErrorStream(false);
            process = pb.start();

            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // Start a reader thread for responses and notifications
            readerThread = new Thread(this::readLoop, agentConfig.getDisplayName().toLowerCase() + "-acp-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Start a thread to read stderr and capture process errors
            Thread stderrReaderThread = new Thread(this::readStderrLoop, agentConfig.getDisplayName().toLowerCase() + "-acp-stderr");
            stderrReaderThread.setDaemon(true);
            stderrReaderThread.start();

            // Initialize handshake
            doInitialize();

        } catch (IOException e) {
            throw new AcpException("Failed to start " + agentConfig.getDisplayName() + " ACP process", e);
        }
    }

    /**
     * Perform the ACP initialize handshake.
     */
    private void doInitialize() throws AcpException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);
        JsonObject clientCapabilities = new JsonObject();
        params.add("clientCapabilities", clientCapabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "intellij-copilot");
        clientInfo.addProperty("version", "0.1.0");
        params.add("clientInfo", clientInfo);

        // Use a longer timeout for initialize — the CLI spawns MCP server JVMs
        // and may need >30s when the system is under load at IDE startup
        JsonObject result = sendRequest("initialize", params, INITIALIZE_TIMEOUT_SECONDS);

        JsonObject agentInfo = result.has("agentInfo") ? result.getAsJsonObject("agentInfo") : null;
        JsonObject agentCapabilities = result.has("agentCapabilities") ? result.getAsJsonObject("agentCapabilities") : null;
        agentConfig.parseInitializeResponse(result);

        initialized = true;
        LOG.info("ACP initialized: " + (agentInfo != null ? agentInfo : "unknown agent")
            + " capabilities=" + (agentCapabilities != null ? agentCapabilities : "none"));
    }

    /**
     * Create a new ACP session. Returns the session ID and populates available models.
     */
    @NotNull
    public synchronized String createSession() throws AcpException {
        return createSession(null);
    }

    /**
     * Create a new ACP session with an optional working directory.
     *
     * @param cwd The working directory for the session, or null to use user.home.
     */
    public synchronized String createSession(@Nullable String cwd) throws AcpException {
        ensureStarted();

        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd != null ? cwd : System.getProperty(USER_HOME));

        // mcpServers must be an array in session/new (agent validates this)
        // MCP servers are configured via --additional-mcp-config CLI flag
        // Empty array here because servers are loaded from the config file
        params.add("mcpServers", new JsonArray());

        // Tool filtering: for agents that support it (e.g., OpenCode), send excludedTools
        // to remove built-in tools so only IntelliJ MCP tools are available.
        // Copilot CLI ignores this (bug #556) — those tools are denied via permissions instead.
        if (agentConfig.shouldExcludeBuiltInTools()) {
            JsonArray excluded = new JsonArray();
            for (String toolId : com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.getBuiltInToolIds()) {
                excluded.add(toolId);
            }
            params.add("excludedTools", excluded);
            LOG.info("Excluding built-in tools from session: " + excluded);
        }

        LOG.info("Creating session (MCP servers configured via --additional-mcp-config)");

        JsonObject result = sendRequest("session/new", params);

        currentSessionId = result.get(SESSION_ID).getAsString();

        // Parse available models
        parseAvailableModels(result);

        LOG.info("ACP session created: " + currentSessionId + " with " +
            (availableModels != null ? availableModels.size() : 0) + " models");
        return currentSessionId;
    }

    private void parseAvailableModels(JsonObject result) {
        if (result.has("models")) {
            JsonObject modelsObj = result.getAsJsonObject("models");
            availableModels = new ArrayList<>();
            if (modelsObj.has("availableModels")) {
                for (JsonElement elem : modelsObj.getAsJsonArray("availableModels")) {
                    JsonObject m = elem.getAsJsonObject();
                    Model model = parseModel(m);
                    availableModels.add(model);
                }
            }
        }
    }

    private Model parseModel(JsonObject m) {
        Model model = new Model();
        model.setId(m.get("modelId").getAsString());
        model.setName(m.has("name") ? m.get("name").getAsString() : model.getId());
        model.setDescription(m.has(DESCRIPTION) ? m.get(DESCRIPTION).getAsString() : "");
        if (m.has(META)) {
            JsonObject meta = m.getAsJsonObject(META);
            model.setUsage(agentConfig.parseModelUsage(meta));
        }
        return model;
    }

    /**
     * Switch the model for an existing session via session/set_model.
     * This is the proper ACP mechanism for model switching (the --model CLI flag
     * only sets the default for new sessions but does not control actual model routing).
     *
     * @param sessionId The session to switch the model for.
     * @param modelId   The model ID to switch to (e.g., "gpt-4.1", "claude-opus-4.6").
     */
    public void setModel(@NotNull String sessionId, @NotNull String modelId) throws AcpException {
        ensureStarted();
        JsonObject params = new JsonObject();
        params.addProperty(SESSION_ID, sessionId);
        params.addProperty("modelId", modelId);
        LOG.info("Setting model for session " + sessionId + " to " + modelId);
        sendRequest("session/set_model", params);
        LOG.info("Model set to " + modelId + " for session " + sessionId);
    }

    /**
     * List available models. Creates a session if needed (models come from session/new).
     * Uses the project path as CWD so the CLI reads copilot-instructions.md correctly.
     */
    @NotNull
    public List<Model> listModels() throws AcpException {
        if (availableModels == null) {
            createSession(projectBasePath);
        }
        return availableModels != null ? availableModels : List.of();
    }

    /**
     * Send a prompt and collect the full response (blocking).
     * Streaming chunks are delivered via the onChunk callback.
     *
     * @param sessionId Session ID (from createSession)
     * @param prompt    The prompt text
     * @param model     Model ID (or null for default)
     * @param onChunk   Optional callback for streaming text chunks
     * @return The stop reason
     */
    @NotNull
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable Consumer<String> onChunk)
        throws AcpException {
        return sendPrompt(sessionId, prompt, model, null, onChunk);
    }

    /**
     * Send a prompt with optional file/selection context references.
     * References are included as ACP "resource" content blocks alongside the text.
     */
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable List<ResourceReference> references,
                             @Nullable Consumer<String> onChunk)
        throws AcpException {
        return sendPrompt(sessionId, prompt, model, references, onChunk, null);
    }

    /**
     * Send a prompt with full control over ACP session/update notifications.
     *
     * @param onChunk  receives text chunks for streaming display
     * @param onUpdate receives raw update JSON objects for plan events, tool calls, etc.
     */
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable List<ResourceReference> references,
                             @Nullable Consumer<String> onChunk,
                             @Nullable Consumer<JsonObject> onUpdate)
        throws AcpException {
        return sendPrompt(sessionId, prompt, model, references, onChunk, onUpdate, null);
    }

    /**
     * Send a prompt with full control over ACP session/update notifications.
     *
     * @param onChunk   receives text chunks for streaming display
     * @param onUpdate  receives raw update JSON objects for plan events, tool calls, etc.
     * @param onRequest called each time a session/prompt RPC request is sent (including retries)
     */
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable List<ResourceReference> references,
                             @Nullable Consumer<String> onChunk,
                             @Nullable Consumer<JsonObject> onUpdate,
                             @Nullable Runnable onRequest)
        throws AcpException {
        ensureStarted();

        int refCount = references != null ? references.size() : 0;
        LOG.info("sendPrompt: sessionId=" + sessionId + " model=" + model + " refs=" + refCount);

        // Register notification listener for streaming chunks
        Consumer<JsonObject> listener = createStreamingListener(sessionId, onChunk, onUpdate);
        notificationListeners.add(listener);

        try {
            String currentPrompt = prompt;
            int maxRetries = 3;
            int retryCount = 0;

            while (true) {
                JsonObject params = buildPromptParams(sessionId, currentPrompt, references);

                // Reset turn tracking
                builtInActionDeniedDuringTurn = false;
                lastActivityTimestamp = System.currentTimeMillis();
                toolCallsInTurn.set(0);

                if (onRequest != null) onRequest.run();

                LOG.info("sendPrompt: sending session/prompt request" + (retryCount > 0 ? " (retry #" + retryCount + ")" : ""));
                JsonObject result = sendPromptRequest(params);
                LOG.info("sendPrompt: got result: " + result.toString().substring(0, Math.min(200, result.toString().length())));

                String stopReason = result.has("stopReason") ? result.get("stopReason").getAsString() : UNKNOWN;

                // If tools were denied, retry with guidance (same listener, no nesting)
                if (builtInActionDeniedDuringTurn && retryCount < maxRetries) {
                    retryCount++;
                    LOG.info("Turn ended with denied tools - auto-retry #" + retryCount);
                    fireDebugEvent("AUTO_RETRY", "Retrying after tool denial #" + retryCount,
                        "Last denied: " + getLastDeniedKind());
                    currentPrompt = "The previous tool calls were denied. Please continue with the task using the correct tools with '"
                        + effectiveMcpPrefix + "' prefix.";
                    continue;
                }

                if (builtInActionDeniedDuringTurn) {
                    LOG.info("Turn ended with denied tools after " + retryCount + " retries - giving up");
                    fireDebugEvent("RETRY_EXHAUSTED", "Still denied after " + retryCount + " retries",
                        "Last denied: " + getLastDeniedKind());
                }

                return stopReason;
            }
        } finally {
            notificationListeners.remove(listener);
        }
    }

    private String getLastDeniedKind() {
        return lastDeniedKind != null ? lastDeniedKind : UNKNOWN;
    }

    /**
     * Send a session/prompt request with activity-based timeout and per-turn credit limit.
     * Instead of a fixed timeout, polls for completion and checks:
     * 1. Inactivity: no streaming chunks or tool calls for N seconds
     * 2. Credit limit: too many tool calls in this turn
     * On either condition, terminates the CLI process gracefully.
     */
    @NotNull
    private JsonObject sendPromptRequest(@NotNull JsonObject params) throws AcpException {
        if (closed) throw new AcpException("ACP client is closed", null, false);

        long id = requestIdCounter.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            JsonObject request = new JsonObject();
            request.addProperty(JSONRPC, "2.0");
            request.addProperty("id", id);
            request.addProperty(METHOD, "session/prompt");
            request.add(PARAMS, params);

            String json = gson.toJson(request);
            LOG.info("ACP request: session/prompt id=" + id + " params=" + gson.toJson(params));

            synchronized (writerLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            return pollForPromptCompletion(id, future);
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof AcpException copilotException) throw copilotException;
            throw new AcpException("ACP request failed: session/prompt - " + cause.getMessage(), e, false);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new AcpException("ACP request interrupted: session/prompt", e, true);
        } catch (IOException e) {
            pendingRequests.remove(id);
            throw new AcpException("ACP write failed: session/prompt", e, true);
        }
    }

    /**
     * Polls for prompt completion, checking for inactivity timeout and credit limit.
     */
    private JsonObject pollForPromptCompletion(long id, CompletableFuture<JsonObject> future)
        throws AcpException, ExecutionException, InterruptedException {
        int inactivityTimeoutSec = agentSettings.getPromptTimeout();
        int maxToolCalls = agentSettings.getMaxToolCallsPerTurn();
        long pollIntervalMs = 5000;

        while (true) {
            try {
                return future.get(pollIntervalMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                checkInactivityTimeout(id, inactivityTimeoutSec, e);
                checkCreditLimit(id, maxToolCalls, e);
            }
        }
    }

    private void checkInactivityTimeout(long id, int inactivityTimeoutSec, TimeoutException cause)
        throws AcpException {
        long inactiveMs = System.currentTimeMillis() - lastActivityTimestamp;
        if (inactiveMs > inactivityTimeoutSec * 1000L) {
            pendingRequests.remove(id);
            LOG.warn("Agent inactive for " + (inactiveMs / 1000) + "s, terminating");
            fireDebugEvent("INACTIVITY_TIMEOUT",
                "No activity for " + (inactiveMs / 1000) + "s (limit: " + inactivityTimeoutSec + "s)",
                "Tool calls this turn: " + toolCallsInTurn.get());
            terminateAgent();
            throw new AcpException(
                "Agent stopped: no activity for " + (inactiveMs / 1000) + " seconds", cause, true);
        }
    }

    private void checkCreditLimit(long id, int maxToolCalls, TimeoutException cause)
        throws AcpException {
        if (maxToolCalls > 0 && toolCallsInTurn.get() >= maxToolCalls) {
            pendingRequests.remove(id);
            LOG.warn("Tool call limit reached: " + toolCallsInTurn.get() + "/" + maxToolCalls);
            fireDebugEvent("CREDIT_LIMIT",
                "Tool call limit reached: " + toolCallsInTurn.get() + "/" + maxToolCalls,
                "Terminating agent to prevent excess usage");
            terminateAgent();
            throw new AcpException(
                "Agent stopped: tool call limit reached (" + toolCallsInTurn.get() + "/" + maxToolCalls + ")", cause, true);
        }
    }

    /**
     * Terminate the Copilot CLI process gracefully.
     * Called when inactivity timeout or credit limit is reached.
     */
    private void terminateAgent() {
        if (process != null && process.isAlive()) {
            LOG.info("Terminating Copilot CLI process (PID: " + process.pid() + ")");
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    LOG.warn("Force-killing Copilot CLI process");
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private Consumer<JsonObject> createStreamingListener(@NotNull String sessionId,
                                                         @Nullable Consumer<String> onChunk,
                                                         @Nullable Consumer<JsonObject> onUpdate) {
        return notification -> {
            String method = notification.has(METHOD) ? notification.get(METHOD).getAsString() : "";
            if (!"session/update".equals(method)) return;

            JsonObject params = notification.getAsJsonObject(PARAMS);
            if (params == null) return;

            String sid = params.has(SESSION_ID) ? params.get(SESSION_ID).getAsString() : "";
            if (!sessionId.equals(sid)) {
                LOG.info("sendPrompt: ignoring update for different session: " + sid + " (expected " + sessionId + ")");
                return;
            }

            if (params.has("update")) {
                JsonObject update = params.getAsJsonObject("update");
                handleSessionUpdate(update, onChunk, onUpdate);
            }
        };
    }

    private void handleSessionUpdate(JsonObject update,
                                     @Nullable Consumer<String> onChunk,
                                     @Nullable Consumer<JsonObject> onUpdate) {
        String updateType = update.has("sessionUpdate") ? update.get("sessionUpdate").getAsString() : "";
        LOG.debug("sendPrompt: received update type=" + updateType);

        // Track activity for inactivity timeout
        lastActivityTimestamp = System.currentTimeMillis();

        if ("agent_message_chunk".equals(updateType) && onChunk != null) {
            JsonObject content = update.has(CONTENT) ? update.getAsJsonObject(CONTENT) : null;
            if (content != null && "text".equals(content.has("type") ? content.get("type").getAsString() : "")) {
                onChunk.accept(content.get("text").getAsString());
            }
        }

        // Forward all updates to onUpdate listener (plan events, tool calls, etc.)
        if (onUpdate != null) {
            onUpdate.accept(update);
        }
    }

    // Note: interceptBuiltInToolCall and classifyBuiltInTool were removed — session/message
    // notifications never reach sub-agents (they run in their own CLI context). Tested with
    // 40+ guidance messages sent to a sub-agent with zero behavioral change. Read-only built-in
    // tools (view, grep, glob) auto-execute without request_permission and cannot be blocked.
    // Write/execute tools are still blocked via permission denial in handlePermissionRequest.

    private JsonObject buildPromptParams(@NotNull String sessionId, @NotNull String prompt,
                                         @Nullable List<ResourceReference> references) {
        JsonObject params = new JsonObject();
        params.addProperty(SESSION_ID, sessionId);

        JsonArray promptArray = new JsonArray();

        // Add resource references before the text prompt
        if (references != null) {
            for (ResourceReference ref : references) {
                promptArray.add(createResourceReference(ref));
            }
        }

        // Add text prompt
        JsonObject promptContent = new JsonObject();
        promptContent.addProperty("type", "text");
        promptContent.addProperty("text", prompt);
        promptArray.add(promptContent);
        params.add("prompt", promptArray);

        // Model is set per-session via session/set_model (see setModel method).
        // The model parameter is not sent in session/prompt params.

        return params;
    }

    private JsonObject createResourceReference(ResourceReference ref) {
        JsonObject resource = new JsonObject();
        resource.addProperty("type", "resource");
        JsonObject resourceData = new JsonObject();
        resourceData.addProperty("uri", ref.uri());
        if (ref.mimeType() != null) {
            resourceData.addProperty("mimeType", ref.mimeType());
        }
        resourceData.addProperty("text", ref.text());
        resource.add("resource", resourceData);
        return resource;
    }

    /**
     * Send session/cancel notification to abort the current prompt turn.
     */
    public void cancelSession(@NotNull String sessionId) {
        if (closed) return;
        try {
            JsonObject notification = new JsonObject();
            notification.addProperty(JSONRPC, "2.0");
            notification.addProperty(METHOD, "session/cancel");
            JsonObject params = new JsonObject();
            params.addProperty(SESSION_ID, sessionId);
            notification.add(PARAMS, params);
            sendRawMessage(notification);
            LOG.info("Sent session/cancel for session " + sessionId);
        } catch (RuntimeException e) {
            LOG.warn("Failed to send session/cancel", e);
        }
    }

    /**
     * Check if the ACP process is alive and initialized.
     */
    public boolean isHealthy() {
        return process != null && process.isAlive() && initialized && !closed;
    }

    /**
     * Get the auth method info from the initialization response (for the login button).
     */
    @Nullable
    public AuthMethod getAuthMethod() {
        return agentConfig.getAuthMethod();
    }

    /**
     * Whether resource reference content must be duplicated as plain text in the prompt.
     * Delegates to the underlying {@link AgentConfig}.
     *
     * @see AgentConfig#requiresResourceContentDuplication()
     */
    public boolean requiresResourceContentDuplication() {
        return agentConfig.requiresResourceContentDuplication();
    }

    /**
     * Send a JSON-RPC request and wait for the response.
     */
    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params) throws AcpException {
        return sendRequest(method, params, REQUEST_TIMEOUT_SECONDS);
    }

    /**
     * Send a JSON-RPC request and wait for the response with a custom timeout.
     */
    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params, long timeoutSeconds) throws AcpException {
        if (closed) throw new AcpException("ACP client is closed", null, false);

        long id = requestIdCounter.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            JsonObject request = new JsonObject();
            request.addProperty(JSONRPC, "2.0");
            request.addProperty("id", id);
            request.addProperty(METHOD, method);
            request.add(PARAMS, params);

            String json = gson.toJson(request);
            LOG.info("ACP request: " + method + " id=" + id + " params=" + gson.toJson(params));

            synchronized (writerLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            return future.get(timeoutSeconds, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new AcpException("ACP request timed out: " + method
                + " (waited " + timeoutSeconds + "s)", e, true);
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof AcpException copilotException) throw copilotException;
            throw new AcpException("ACP request failed: " + method + " - " + cause.getMessage(), e, false);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new AcpException("ACP request interrupted: " + method, e, true);
        } catch (IOException e) {
            pendingRequests.remove(id);
            throw new AcpException("ACP write failed: " + method, e, true);
        }
    }

    /**
     * Send a raw JSON-RPC message (used for responding to agent-to-client requests).
     */
    private void sendRawMessage(@NotNull JsonObject message) {
        try {
            String json = gson.toJson(message);
            synchronized (writerLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            LOG.warn("Failed to send raw message", e);
        }
    }

    /**
     * Background thread that reads JSON-RPC messages from the copilot process stdout.
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                processLine(line);
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("ACP reader thread ended: " + e.getMessage());
            }
        }

        // Process ended - attempt auto-restart if not intentionally closed
        if (!closed) {
            attemptAutoRestart();
        }
    }

    private void attemptAutoRestart() {
        // Immediately unblock any callers stuck in pollForPromptCompletion
        failAllPendingRequests();

        String name = agentConfig.getDisplayName();
        if (restartAttempts.get() >= MAX_RESTART_ATTEMPTS) {
            LOG.warn("ACP process terminated after " + MAX_RESTART_ATTEMPTS + " restart attempts");
            showNotification(name + " Disconnected",
                "Could not reconnect after " + MAX_RESTART_ATTEMPTS + " attempts. Please restart the IDE.",
                com.intellij.notification.NotificationType.ERROR);
            failAllPendingRequests();
            return;
        }

        int attempts = restartAttempts.incrementAndGet();
        long delayMs = RESTART_DELAYS_MS[Math.min(attempts - 1, RESTART_DELAYS_MS.length - 1)];

        LOG.info("ACP process terminated. Attempting restart " + attempts + "/" + MAX_RESTART_ATTEMPTS +
            " after " + delayMs + "ms...");
        showNotification(name + " Reconnecting...",
            "Attempt " + attempts + "/" + MAX_RESTART_ATTEMPTS,
            com.intellij.notification.NotificationType.INFORMATION);

        // Schedule restart on background thread
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                if (closed) {
                    LOG.info("ACP client was closed during restart delay — aborting restart");
                    return;
                }
                start();
                LOG.info("ACP process successfully restarted");
                showNotification(name + " Reconnected",
                    "Connection restored — please retry your last message",
                    com.intellij.notification.NotificationType.INFORMATION);
                restartAttempts.set(0); // Reset counter on successful restart
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Restart attempt interrupted", e);
            } catch (AcpException e) {
                LOG.warn("Failed to restart ACP process (attempt " + restartAttempts + ")", e);
                attemptAutoRestart(); // Try again
            }
        }, name + "ACP-Restart").start();
    }

    private void showNotification(String title, String content, com.intellij.notification.NotificationType type) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup(agentConfig.getNotificationGroupId())
            .createNotification(title, content, type)
            .notify(null);
    }

    private void failAllPendingRequests() {
        for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                new AcpException("Connection lost — please retry your message", null, false));
        }
        pendingRequests.clear();
    }

    private void processLine(String line) {
        // Any message from the CLI means it's still active
        lastActivityTimestamp = System.currentTimeMillis();
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            handleJsonRpcMessage(msg);
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            LOG.warn("Failed to parse ACP message: " + line, e);
        }
    }

    private void handleJsonRpcMessage(JsonObject msg) {
        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();
        boolean hasMethod = msg.has(METHOD);

        if (hasId && hasMethod) {
            handleAgentToClientRequest(msg);
        } else if (hasId) {
            handleResponseMessage(msg);
        } else if (hasMethod) {
            handleNotificationMessage(msg);
        }
    }

    private void handleAgentToClientRequest(JsonObject msg) {
        handleAgentRequest(msg);

        // Also forward to notification listeners for timeline tracking
        notifyListeners(msg);
    }

    private void handleResponseMessage(JsonObject msg) {
        long id = msg.get("id").getAsLong();
        LOG.info("ACP response: id=" + id + " keys=" + msg.keySet()
            + (msg.has(RESULT) ? " result_keys=" + msg.getAsJsonObject(RESULT).keySet() : ""));
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
        if (future != null) {
            if (msg.has(ERROR)) {
                handleErrorResponse(msg, id, future);
            } else if (msg.has(RESULT)) {
                future.complete(msg.getAsJsonObject(RESULT));
            } else {
                future.complete(new JsonObject());
            }
        }
    }

    private void handleErrorResponse(JsonObject msg, long id, CompletableFuture<JsonObject> future) {
        JsonObject error = msg.getAsJsonObject(ERROR);
        String errorMessage = error.has(MESSAGE) ? error.get(MESSAGE).getAsString() : "Unknown error";
        LOG.warn("ACP error response for request id=" + id + ": " + gson.toJson(error));
        if (error.has("data") && !error.get("data").isJsonNull()) {
            try {
                errorMessage = error.get("data").getAsString();
            } catch (ClassCastException | IllegalStateException e) {
                LOG.debug("Error extracting error data as string", e);
            }
        }
        future.completeExceptionally(new AcpException("ACP error: " + errorMessage, null, false));
    }

    private void handleNotificationMessage(JsonObject msg) {
        String method = msg.has(METHOD) ? msg.get(METHOD).getAsString() : UNKNOWN;
        LOG.info("ACP notification: method=" + method + " keys=" + msg.keySet());
        if (method.contains("usage") || method.contains("quota") || method.contains("billing")
            || method.contains("premium") || method.contains("stats") || method.contains("turn")) {
            LOG.info("ACP notification (quota-related) FULL: " + msg);
        }
        notifyListeners(msg);
    }

    private void notifyListeners(JsonObject msg) {
        for (Consumer<JsonObject> listener : notificationListeners) {
            try {
                listener.accept(msg);
            } catch (RuntimeException e) {
                LOG.debug("Error forwarding notification to listener", e);
            }
        }
    }

    private void readStderrLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.warn(agentConfig.getDisplayName() + " CLI stderr: " + line);
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("Stderr reader thread ended: " + e.getMessage());
            }
        }
    }

    private void ensureStarted() throws AcpException {
        if (closed) {
            throw new AcpException("ACP client is closed", null, false);
        }
        if (!initialized || process == null || !process.isAlive()) {
            initialized = false;
            start();
        }
    }

    // ---- Agent request handlers (extracted from readLoop) ----

    /**
     * Route an agent-to-client request to the appropriate handler.
     */
    private void handleAgentRequest(JsonObject msg) {
        String reqMethod = msg.get(METHOD).getAsString();
        long reqId = msg.get("id").getAsLong();
        LOG.info("ACP agent request: " + reqMethod + " id=" + reqId);

        if ("session/request_permission".equals(reqMethod)) {
            handlePermissionRequest(reqId, msg.has(PARAMS) ? msg.getAsJsonObject(PARAMS) : null);
        } else {
            sendErrorResponse(reqId, -32601, "Method not supported: " + reqMethod);
        }
    }

    /**
     * Handle permission requests from the Copilot agent.
     * Denies built-in write operations (edit, create), so the agent retries with IntelliJ MCP tools.
     * Auto-approves everything else (MCP tool calls, shell, reads).
     */
    private void handlePermissionRequest(long reqId, @Nullable JsonObject reqParams) {
        String permKind = "";
        String permTitle = "";
        JsonObject toolCall = null;

        if (reqParams != null && reqParams.has("toolCall")) {
            toolCall = reqParams.getAsJsonObject("toolCall");
            permKind = toolCall.has("kind") ? toolCall.get("kind").getAsString() : "";
            permTitle = toolCall.has(TITLE_KEY) ? toolCall.get(TITLE_KEY).getAsString() : "";
        }
        LOG.info("ACP request_permission: kind=" + permKind + " title=" + permTitle);
        String formattedPermission = formatPermissionDisplay(permKind, permTitle);
        fireDebugEvent("PERMISSION_REQUEST", formattedPermission,
            toolCall != null ? toolCall.toString() : "");
        lastActivityTimestamp = System.currentTimeMillis();
        toolCallsInTurn.incrementAndGet();

        if (checkAbuseAndDeny(reqId, reqParams, toolCall)) return;

        String toolId = resolveToolId(permKind, toolCall);
        ToolPermission perm = resolveEffectivePermission(toolId, toolCall);

        // Auto-approve: profile's usePluginPermissions=false promotes ASK → ALLOW (DENY stays DENY)
        if (perm == ToolPermission.ASK && agentSettings.isAutoApprovePermissions()) {
            LOG.info("ACP request_permission: auto-approve promoting ASK→ALLOW for " + toolId);
            perm = ToolPermission.ALLOW;
        }
        // Session-scoped allow: if user previously chose "Allow for session", skip the prompt
        if (perm == ToolPermission.ASK && sessionAllowedTools.contains(toolId)) {
            LOG.info("ACP request_permission: session-allowed for " + toolId);
            perm = ToolPermission.ALLOW;
        }

        switch (perm) {
            case DENY -> denyPermission(reqId, reqParams, permKind, toolId);
            case ASK -> handleAskPermission(reqId, reqParams, toolId, permKind, formattedPermission);
            default -> allowPermission(reqId, reqParams, permKind, toolId, formattedPermission);
        }
    }

    /**
     * Check for known abuse patterns (run_command abuse, sub-agent git/write tools) and deny the
     * request if one is detected. Returns true if the request was denied.
     * <p>
     * Note: built-in CLI read tools (view, grep, glob) bypass request_permission entirely — they
     * are handled by {@code interceptBuiltInToolCall()} in the stream handler.
     */
    private boolean checkAbuseAndDeny(long reqId, @Nullable JsonObject reqParams, @Nullable JsonObject toolCall) {
        String commandAbuse = detectCommandAbuse(toolCall);
        if (commandAbuse != null) {
            denyForAbuse(reqId, reqParams, toolCall, "run_command abuse: " + commandAbuse,
                RUN_COMMAND_ABUSE_PREFIX + commandAbuse);
            return true;
        }
        String gitWriteAbuse = detectSubAgentGitWrite(toolCall);
        if (gitWriteAbuse != null) {
            denyForAbuse(reqId, reqParams, toolCall, "Sub-agent git write: " + gitWriteAbuse,
                GIT_WRITE_ABUSE_PREFIX + gitWriteAbuse);
            return true;
        }
        String writeAbuse = detectSubAgentWriteTool(toolCall);
        if (writeAbuse != null) {
            denyForAbuse(reqId, reqParams, toolCall, "Sub-agent write tool: " + writeAbuse,
                SUBAGENT_WRITE_ABUSE_PREFIX + writeAbuse);
            return true;
        }
        return false;
    }

    /**
     * Deny a permission request for a detected abuse pattern, sending pre-rejection guidance first.
     */
    private void denyForAbuse(long reqId, @Nullable JsonObject reqParams, @Nullable JsonObject toolCall,
                              String logSuffix, String abusePrefixedKind) {
        String rejectOptionId = findRejectOption(reqParams);
        LOG.info("ACP request_permission: DENYING " + logSuffix);
        Map<String, Object> retryParams = buildRetryParams(abusePrefixedKind);
        String retryMessage = (String) retryParams.get(MESSAGE);
        fireDebugEvent(PRE_REJECTION_GUIDANCE_EVENT, SENDING_GUIDANCE_DESC, retryMessage);
        sendPromptMessage(retryMessage);
        fireDebugEvent(PERMISSION_DENIED_EVENT, logSuffix, toolCall != null ? toolCall.toString() : "");
        builtInActionDeniedDuringTurn = true;
        lastDeniedKind = abusePrefixedKind;
        sendPermissionResponse(reqId, rejectOptionId);
    }

    /**
     * Handle a DENY permission: send guidance before rejecting so the agent can retry.
     */
    private void denyPermission(long reqId, @Nullable JsonObject reqParams, String permKind, String toolId) {
        String rejectOptionId = findRejectOption(reqParams);
        LOG.info("ACP request_permission: DENYING " + permKind + TOOL_PREFIX + toolId + "), option=" + rejectOptionId);
        Map<String, Object> retryParams = buildRetryParams(permKind);
        String retryMessage = (String) retryParams.get(MESSAGE);
        fireDebugEvent(PRE_REJECTION_GUIDANCE_EVENT, SENDING_GUIDANCE_DESC, retryMessage);
        sendPromptMessage(retryMessage);
        fireDebugEvent(PERMISSION_DENIED_EVENT, "Denied: " + permKind + TOOL_PREFIX + toolId + ")",
            "Permission mode: DENY");
        builtInActionDeniedDuringTurn = true;
        lastDeniedKind = permKind;
        sendPermissionResponse(reqId, rejectOptionId);
    }

    /**
     * Handle an ASK permission: prompt the UI and block until user responds (120s timeout).
     */
    private void handleAskPermission(long reqId, @Nullable JsonObject reqParams,
                                     String toolId, String permKind, String formattedPermission) {
        var listener = permissionRequestListener.get();
        if (listener == null) {
            LOG.warn("ACP request_permission: ASK for " + toolId + " but no listener — auto-denying");
            sendPermissionResponse(reqId, findRejectOption(reqParams));
            return;
        }
        CompletableFuture<PermissionResponse> future = new CompletableFuture<>();
        ToolRegistry.ToolEntry toolEntry = ToolRegistry.findById(toolId);
        String displayName = toolEntry != null ? toolEntry.displayName : permKind;
        listener.accept(new PermissionRequest(reqId, toolId, displayName, formattedPermission, future::complete));
        PermissionResponse response;
        try {
            response = future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.info("ACP request_permission: ASK timed out / cancelled for " + toolId + " — denying");
            response = PermissionResponse.DENY;
        }
        switch (response) {
            case ALLOW_SESSION -> {
                sessionAllowedTools.add(toolId);
                LOG.info("ACP request_permission: ASK approved for session for " + toolId);
                fireDebugEvent(PERMISSION_APPROVED_EVENT, formattedPermission, "session-approved");
                sendPermissionResponse(reqId, findAllowOption(reqParams));
            }
            case ALLOW_ONCE -> {
                LOG.info("ACP request_permission: ASK approved (once) for " + toolId);
                fireDebugEvent(PERMISSION_APPROVED_EVENT, formattedPermission, "user-approved");
                sendPermissionResponse(reqId, findAllowOption(reqParams));
            }
            default -> {
                LOG.info("ACP request_permission: ASK denied by user for " + toolId);
                fireDebugEvent(PERMISSION_DENIED_EVENT, "ASK denied by user: " + toolId, "");
                builtInActionDeniedDuringTurn = true;
                lastDeniedKind = permKind;
                sendPermissionResponse(reqId, findRejectOption(reqParams));
            }
        }
    }

    /**
     * Handle an ALLOW permission: auto-approve the tool call.
     */
    private void allowPermission(long reqId, @Nullable JsonObject reqParams,
                                 String permKind, String toolId, String formattedPermission) {
        String allowOptionId = findAllowOption(reqParams);
        LOG.info("ACP request_permission: auto-approving " + permKind + TOOL_PREFIX + toolId + "), option=" + allowOptionId);
        fireDebugEvent(PERMISSION_APPROVED_EVENT, formattedPermission, "");
        sendPermissionResponse(reqId, allowOptionId);
    }

    /**
     * Strip the MCP server-name prefix and normalise the tool ID from permKind / toolCall name.
     * The prefix is determined dynamically ({@link #effectiveMcpPrefix}) so it works regardless
     * of the name the user registered the server under.
     */
    private String resolveToolId(@Nullable String permKind, @Nullable JsonObject toolCall) {
        String name = "";
        if (toolCall != null && toolCall.has("name")) {
            name = toolCall.get("name").getAsString();
        }
        if (name.startsWith(effectiveMcpPrefix)) {
            name = name.substring(effectiveMcpPrefix.length());
        }
        String fallback = permKind != null ? permKind : "";
        return name.isEmpty() ? fallback : name;
    }

    /**
     * Look up the effective ToolPermission for a tool call.
     * For file tools, checks inside/outside-project sub-permission when a path is present.
     */
    private ToolPermission resolveEffectivePermission(String toolId, @Nullable JsonObject toolCall) {
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolId);

        // Path-based sub-permissions for file tools (ceiling enforced by AgentSettings)
        if (entry != null && entry.supportsPathSubPermissions && toolCall != null) {
            String path = extractPathFromToolCall(toolCall);
            if (path != null && !path.isEmpty()) {
                boolean insideProject = isPathInsideProject(path);
                return agentSettings.resolveEffectivePermission(toolId, insideProject);
            }
        }
        return agentSettings.getToolPermission(toolId);
    }

    /**
     * Extract a file path argument from a tool call JSON (checks common arg names).
     */
    private @Nullable String extractPathFromToolCall(@Nullable JsonObject toolCall) {
        if (toolCall == null) return null;
        String direct = findPathInJson(toolCall);
        if (direct != null) return direct;
        // Also check inside a nested "arguments" / "input" object
        for (String wrapper : new String[]{"arguments", "input", PARAMS}) {
            if (toolCall.has(wrapper) && toolCall.get(wrapper).isJsonObject()) {
                String nested = findPathInJson(toolCall.getAsJsonObject(wrapper));
                if (nested != null) return nested;
            }
        }
        return null;
    }

    /**
     * Returns the first path-like value found in the given JSON object, or null if none.
     */
    private static @Nullable String findPathInJson(@NotNull JsonObject obj) {
        for (String key : new String[]{"path", "file", "file1", "file2"}) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    private boolean isPathInsideProject(String path) {
        return PsiBridgeService.isPathUnderBase(path, projectBasePath);
    }

    private String formatPermissionDisplay(String permKind, String permTitle) {
        return permKind + (permTitle.isEmpty() ? "" : " - " + permTitle);
    }

    /**
     * Detect if run_command or the bash built-in tool is being abused to do something
     * we have a dedicated tool for. Returns abuse type if detected, null otherwise.
     */
    private String detectCommandAbuse(JsonObject toolCall) {
        if (toolCall == null) return null;

        String toolName = toolCall.has("name") ? toolCall.get("name").getAsString() : "";
        String permKind = toolCall.has("kind") ? toolCall.get("kind").getAsString() : "";
        String expectedMcpName = effectiveMcpPrefix + "run_command";

        boolean isRunCommand = "run_command".equals(toolName) || expectedMcpName.equals(toolName);
        // Also intercept the bash built-in tool (kind=execute or kind=bash)
        boolean isBashTool = KIND_BASH.equals(permKind) || KIND_EXECUTE.equals(permKind)
            || KIND_BASH.equals(toolName);

        if (!isRunCommand && !isBashTool) return null;

        String command = extractCommand(toolCall);
        if (command.isEmpty()) return null;

        return detectAbusePattern(command);
    }

    private String extractCommand(JsonObject toolCall) {
        if (!toolCall.has(PARAMETERS_KEY)) return "";
        JsonObject params = toolCall.getAsJsonObject(PARAMETERS_KEY);
        if (!params.has(COMMAND)) return "";
        return params.get(COMMAND).getAsString().toLowerCase().trim();
    }

    private String detectAbusePattern(String command) {
        return com.github.catatafishen.ideagentforcopilot.psi.ToolUtils.detectCommandAbuseType(command);
    }

    // Note: detectCliToolAbuse, interceptBuiltInToolCall, classifyBuiltInTool, and
    // sendSubAgentGuidance were all removed — session/message notifications never reach
    // sub-agents or affect main agent behavior. See CLI-BUG-556-WORKAROUND.md.

    /**
     * Detect if a git write tool is being called by a sub-agent.
     * Returns the tool name (e.g. "git_commit") if blocked, null otherwise.
     */
    private String detectSubAgentGitWrite(JsonObject toolCall) {
        if (!subAgentActive || toolCall == null) return null;

        String toolName = toolCall.has("name") ? toolCall.get("name").getAsString() : "";
        // Strip MCP prefix (dynamic — matches whatever name the server is registered under)
        String shortName = toolName.startsWith(effectiveMcpPrefix)
            ? toolName.substring(effectiveMcpPrefix.length())
            : toolName;
        return GIT_WRITE_TOOLS.contains(shortName) ? shortName : null;
    }

    /**
     * Detect if a sub-agent is trying to use a built-in write/execute tool.
     * Returns the tool kind (e.g. "edit", "bash") if blocked, null otherwise.
     * <p>
     * Built-in write tools bypass IntelliJ's editor buffer, causing desync.
     * Sub-agents can't receive guidance via session/message (CLI limitation),
     * so we must deny unconditionally — the denial itself is the only signal
     * the sub-agent receives.
     */
    private String detectSubAgentWriteTool(JsonObject toolCall) {
        if (!subAgentActive || toolCall == null) return null;

        String kind = toolCall.has("kind") ? toolCall.get("kind").getAsString() : "";
        return BUILTIN_WRITE_TOOLS.contains(kind) ? kind : null;
    }

    /**
     * Build guidance message for denied actions.
     * Returns a map with "message" key containing the instruction text.
     */
    private Map<String, Object> buildRetryParams(@NotNull String deniedKind) {
        String p = effectiveMcpPrefix; // shorthand for tool name prefixing
        String instruction;

        // Specific guidance for run_command abuse
        if (deniedKind.startsWith(RUN_COMMAND_ABUSE_PREFIX)) {
            String abuseType = deniedKind.substring(RUN_COMMAND_ABUSE_PREFIX.length());
            instruction = switch (abuseType) {
                case "compile" -> "⚠ Don't run Gradle compile tasks directly. Use '" + p + "build_project' instead. "
                    + "It uses IntelliJ's incremental compiler which is faster and keeps editor buffers in sync.";
                case "test" -> "⚠ Don't run tests from the command line (including build/check/verify which "
                    + "implicitly run tests). Use '" + p + "run_tests' instead. "
                    + "Provides structured results, coverage, and failure details.";
                case "sed" -> "⚠ Don't use sed. Use '" + p + "edit_text' for surgical edits " +
                    "or '" + p + "replace_symbol_body' for replacing whole methods/classes. " +
                    "They provide proper undo/redo and live editor buffer access.";
                case "cat" -> "⚠ Don't use cat/head/tail/less/more. Use '" + p + "intellij_read_file' instead. " +
                    "It reads from the live editor buffer, not stale disk files.";
                case "grep" -> "⚠ Don't use grep. Use '" + p + "search_symbols' or " +
                    "'" + p + "find_references' instead. They search live editor buffers.";
                case "find" -> "⚠ Don't use find. Use '" + p + "list_project_files' instead.";
                case "git" -> "⚠ Don't use git commands via run_command — it desyncs IntelliJ editor buffers. " +
                    "Use dedicated git tools: git_status, git_diff, git_log, git_commit, git_stage, " +
                    "git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, " +
                    "git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset.";
                default -> String.format(TOOL_DENIED_DEFAULT_MSG_TEMPLATE, p);
            };
        } else if (deniedKind.startsWith(GIT_WRITE_ABUSE_PREFIX)) {
            instruction = "⚠ Sub-agents must not use git write commands (git_commit, git_stage, git_unstage, " +
                "git_branch, git_stash, git_push, git_remote, git_pull, git_merge, git_rebase, " +
                "git_cherry_pick, git_tag, git_reset). Only the parent agent may perform git writes. " +
                "Use read-only git tools (git_status, git_diff, git_log, git_show, git_blame, git_fetch) instead.";
        } else if (deniedKind.startsWith(SUBAGENT_WRITE_ABUSE_PREFIX)) {
            String tool = deniedKind.substring(SUBAGENT_WRITE_ABUSE_PREFIX.length());
            instruction = "⚠ Sub-agents must not use built-in '" + tool + "' — it writes to disk, bypassing " +
                "IntelliJ's editor buffer. Use '" + p + "' prefixed tools instead: " +
                "'" + p + "intellij_write_file' to write files, " +
                "'" + p + "edit_text' for surgical edits, " +
                "'" + p + "create_file' to create files, " +
                "'" + p + "run_command' for shell commands. " +
                "These tools write through IntelliJ's Document API (undo/redo, live buffers, no desync).";
        } else if (KIND_BASH.equals(deniedKind) || KIND_EXECUTE.equals(deniedKind)) {
            instruction = "⚠ Don't use bash/shell execution — it reads/writes disk directly, bypassing IntelliJ editor buffers. " +
                "Use '" + p + "run_command' for shell commands (flushes buffers first). " +
                "For file operations use intellij_read_file, edit_text, replace_symbol_body, search_text, etc. " +
                "For git use dedicated git tools: git_status, git_diff, git_log, git_commit, git_stage, " +
                "git_push, git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset.";
        } else {
            // Generic message for other denials
            instruction = String.format(TOOL_DENIED_DEFAULT_MSG_TEMPLATE, p);
        }

        return Map.of(MESSAGE, instruction);
    }

    @SuppressWarnings("SameParameterValue") // Error code is standard JSON-RPC -32_601 for "Method not found"
    private void sendErrorResponse(long reqId, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty(JSONRPC, "2.0");
        response.addProperty("id", reqId);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty(MESSAGE, message);
        response.add(ERROR, error);
        sendRawMessage(response);
    }

    // ---- End agent request handlers ----

    /**
     * Get the usage multiplier for a model ID (e.g., "1x", "3x", "0.33x").
     * Returns "1x" if the model is not found.
     */
    @NotNull
    @SuppressWarnings("unused") // Public API - may be used by external code
    public String getModelMultiplier(@NotNull String modelId) {
        if (availableModels == null) return "1x";
        return availableModels.stream()
            .filter(m -> modelId.equals(m.getId()))
            .findFirst()
            .map(m -> m.getUsage() != null ? m.getUsage() : "1x")
            .orElse("1x");
    }

    private String findAllowOption(JsonObject reqParams) {
        if (reqParams != null && reqParams.has(OPTIONS)) {
            for (JsonElement opt : reqParams.getAsJsonArray(OPTIONS)) {
                JsonObject option = opt.getAsJsonObject();
                String kind = option.has("kind") ? option.get("kind").getAsString() : "";
                if ("allow_once".equals(kind) || "allow_always".equals(kind)) {
                    return option.get(OPTION_ID).getAsString();
                }
            }
        }
        return "allow_once";
    }

    private String findRejectOption(JsonObject reqParams) {
        if (reqParams != null && reqParams.has(OPTIONS)) {
            for (JsonElement opt : reqParams.getAsJsonArray(OPTIONS)) {
                JsonObject option = opt.getAsJsonObject();
                String kind = option.has("kind") ? option.get("kind").getAsString() : "";
                if ("reject_once".equals(kind) || "reject_always".equals(kind)) {
                    return option.get(OPTION_ID).getAsString();
                }
            }
        }
        return "reject_once";
    }

    private void sendPermissionResponse(long reqId, String optionId) {
        JsonObject response = new JsonObject();
        response.addProperty(JSONRPC, "2.0");
        response.addProperty("id", reqId);
        JsonObject result = new JsonObject();
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        outcome.addProperty(OPTION_ID, optionId);
        result.add("outcome", outcome);
        response.add(RESULT, result);
        sendRawMessage(response);
    }

    /**
     * Send a prompt message to the agent without waiting for response.
     * Used to send guidance before rejecting permissions.
     */
    private void sendPromptMessage(String message) {
        LOG.info("Sending pre-rejection guidance message: " + message.substring(0, Math.min(100, message.length())));

        if (currentSessionId == null) {
            LOG.warn("Cannot send guidance — no active session");
            return;
        }

        JsonObject params = new JsonObject();
        params.addProperty(SESSION_ID, currentSessionId);
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty(CONTENT, message);
        messages.add(userMsg);
        params.add("messages", messages);

        // Fire-and-forget notification (no request ID)
        JsonObject notification = new JsonObject();
        notification.addProperty(JSONRPC, "2.0");
        notification.addProperty(METHOD, "session/message");
        notification.add(PARAMS, params);

        LOG.info("Sending session/message notification: " + notification);
        sendRawMessage(notification);
    }

    @Override
    public void close() {
        closed = true;
        sessionAllowedTools.clear();
        failAllPendingRequests();
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                LOG.debug("Failed to close writer during shutdown", e);
            }
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debug("Interrupted while waiting for process shutdown", e);
                process.destroyForcibly();
            }
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        LOG.info("ACP client closed");
    }

    // DTOs are in separate files: Model.java, ResourceReference.java, AuthMethod.java
}
