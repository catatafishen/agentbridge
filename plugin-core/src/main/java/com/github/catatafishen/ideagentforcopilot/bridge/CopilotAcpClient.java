package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings;
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
import java.util.function.Consumer;

/**
 * Client for GitHub Copilot CLI via the Agent Client Protocol (ACP).
 * Spawns "copilot --acp --stdio" and communicates via JSON-RPC 2.0 over stdin/stdout.
 */
public class CopilotAcpClient implements Closeable {
    private static final Logger LOG = Logger.getInstance(CopilotAcpClient.class);
    private static final long REQUEST_TIMEOUT_SECONDS = 30;

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
    private static final String CLI_TOOL_ABUSE_PREFIX = "cli_tool_abuse:";
    private static final String GIT_WRITE_ABUSE_PREFIX = "git_write_abuse:";
    private static final String USER_HOME = "user.home";
    private static final String TITLE_KEY = "title";
    private static final String PARAMETERS_KEY = "parameters";
    private static final String CREATE_KIND = "create";
    private static final String TOOL_DENIED_DEFAULT_MSG = "⚠ Tool denied. Use tools with 'intellij-code-tools-' prefix instead.";
    private static final String BUILT_IN_TOOL_WARNING_PREFIX = "⚠ You used the built-in '";
    private static final String PRE_REJECTION_GUIDANCE_EVENT = "PRE_REJECTION_GUIDANCE";
    private static final String SENDING_GUIDANCE_DESC = "Sending guidance before rejection";
    private static final String PERMISSION_DENIED_EVENT = "PERMISSION_DENIED";

    /**
     * Permission kinds that are denied, so the agent uses IntelliJ MCP tools instead.
     * <p>
     * WORKAROUND for GitHub Copilot CLI bug #556:
     * <a href="https://github.com/github/copilot-cli/issues/556">Issue #556</a>
     * <p>
     * Bug: Tool filtering (--available-tools, --excluded-tools, session params) doesn't work
     * in --acp mode. Agent sees ALL tools regardless of filtering attempts.
     * <p>
     * Until fixed, we deny permissions for CLI built-in tools at runtime to force
     * agent to use IntelliJ MCP tools (which read live editor buffers, not stale disk files).
     */
    private static final Set<String> DENIED_PERMISSION_KINDS = Set.of(
        "edit",          // CLI built-in view tool - deny to force intellij_write_file
        CREATE_KIND,     // CLI built-in create tool - deny to force intellij_write_file
        "read",          // CLI built-in view tool - deny to force intellij_read_file
        "execute",       // Generic execute - doesn't exist, agent invents it
        "runInTerminal"  // Generic name - actual tool is run_in_terminal
    );

    private final Gson gson = new Gson();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonObject>> notificationListeners = new CopyOnWriteArrayList<>();

    private final Object writerLock = new Object();
    private final String projectBasePath; // Project path for config-dir
    private String resolvedCopilotPath; // Resolved path to copilot CLI binary
    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean closed = false;

    // Auto-restart state
    private final AtomicInteger restartAttempts = new AtomicInteger(0);
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final long[] RESTART_DELAYS_MS = {1000, 2000, 4000}; // Exponential backoff

    // State from the initialization response
    private JsonArray authMethods;
    private boolean initialized = false;

    // Session state
    private String currentSessionId;
    private List<Model> availableModels;

    // Permission tracking: set when a built-in permission is denied during a prompt turn
    private volatile boolean builtInActionDeniedDuringTurn = false;
    private volatile String lastDeniedKind = "";

    // Sub-agent tracking: set by UI layer when a Task tool call is active
    private volatile boolean subAgentActive = false;

    // Git write tools that sub-agents must not use
    private static final Set<String> GIT_WRITE_TOOLS = Set.of(
        "git_commit", "git_stage", "git_unstage", "git_branch", "git_stash", "git_push", "git_remote"
    );

    // Permission request listener and pending ASK map
    private volatile java.util.function.Consumer<PermissionRequest> permissionRequestListener;
    private final ConcurrentHashMap<Long, CompletableFuture<Boolean>> pendingPermissionAsks = new ConcurrentHashMap<>();

    /**
     * A permission request surfaced to the UI when a tool has ASK permission mode.
     * Call {@link #respond} with true to allow, false to deny.
     */
    public static class PermissionRequest {
        public final long reqId;
        public final String toolId;
        public final String displayName;
        public final String description;
        private final java.util.function.Consumer<Boolean> respondFn;

        public PermissionRequest(long reqId, String toolId, String displayName, String description,
                                 java.util.function.Consumer<Boolean> respondFn) {
            this.reqId = reqId;
            this.toolId = toolId;
            this.displayName = displayName;
            this.description = description;
            this.respondFn = respondFn;
        }

        public void respond(boolean allowed) {
            respondFn.accept(allowed);
        }
    }

    /**
     * Register a listener that is called when a tool with ASK permission needs user approval.
     */
    public void setPermissionRequestListener(java.util.function.Consumer<PermissionRequest> listener) {
        this.permissionRequestListener = listener;
    }

    // Activity tracking for inactivity-based timeout
    private volatile long lastActivityTimestamp = System.currentTimeMillis();
    private final AtomicInteger toolCallsInTurn = new AtomicInteger(0);

    // Debug event listeners for UI debug tab
    private final CopyOnWriteArrayList<Consumer<DebugEvent>> debugListeners = new CopyOnWriteArrayList<>();

    /**
     * Debug event for UI debug tab showing permission requests, denials, tool calls, etc.
     */
    public static class DebugEvent {
        public final String timestamp;
        public final String type;  // "PERMISSION_REQUEST", "PERMISSION_DENIED", "RETRY_SENT", "TOOL_CALL", etc.
        public final String message;
        public final String details; // JSON or additional info

        public DebugEvent(String type, String message, String details) {
            this.timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            this.type = type;
            this.message = message;
            this.details = details;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s", timestamp, type, message);
        }
    }

    /**
     * Register a debug event listener for the UI debug tab.
     */
    public void addDebugListener(Consumer<DebugEvent> listener) {
        debugListeners.add(listener);
    }

    /**
     * Set whether a sub-agent (Task tool) is currently active.
     * When active, git write tools (commit, stage, unstage, branch, stash) are blocked.
     */
    public void setSubAgentActive(boolean active) {
        this.subAgentActive = active;
    }

    /**
     * Fire a debug event to all listeners.
     */
    private void fireDebugEvent(String type, String message, String details) {
        DebugEvent event = new DebugEvent(type, message, details);
        for (Consumer<DebugEvent> listener : debugListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOG.warn("Debug listener threw exception", e);
            }
        }
    }

    /**
     * Create ACP client with optional project base path for config-dir.
     */
    public CopilotAcpClient(@Nullable String projectBasePath) {
        this.projectBasePath = projectBasePath;
    }

    /**
     * Start the copilot ACP process and perform the initialization handshake.
     */
    public synchronized void start() throws CopilotException {
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
            pendingRequests.clear();
            availableModels = null;
            currentSessionId = null;
        }

        try {
            String copilotPath = CopilotCliLocator.findCopilotCli();
            resolvedCopilotPath = copilotPath;
            LOG.info("Starting Copilot ACP: " + copilotPath);

            ProcessBuilder pb = CopilotCliLocator.buildAcpCommand(copilotPath, projectBasePath);
            pb.redirectErrorStream(false);
            process = pb.start();

            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // Start a reader thread for responses and notifications
            readerThread = new Thread(this::readLoop, "copilot-acp-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Start a thread to read stderr and capture process errors
            Thread stderrReaderThread = new Thread(this::readStderrLoop, "copilot-acp-stderr");
            stderrReaderThread.setDaemon(true);
            stderrReaderThread.start();

            // Initialize handshake
            doInitialize();

        } catch (IOException e) {
            throw new CopilotException("Failed to start Copilot ACP process", e);
        }
    }

    /**
     * Perform the ACP initialize handshake.
     */
    private void doInitialize() throws CopilotException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);
        JsonObject clientCapabilities = new JsonObject();
        params.add("clientCapabilities", clientCapabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "intellij-copilot");
        clientInfo.addProperty("version", "0.1.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);

        JsonObject agentInfo = result.has("agentInfo") ? result.getAsJsonObject("agentInfo") : null;
        JsonObject agentCapabilities = result.has("agentCapabilities") ? result.getAsJsonObject("agentCapabilities") : null;
        authMethods = result.has("authMethods") ? result.getAsJsonArray("authMethods") : null;

        initialized = true;
        LOG.info("ACP initialized: " + (agentInfo != null ? agentInfo : "unknown agent")
            + " capabilities=" + (agentCapabilities != null ? agentCapabilities : "none"));
    }

    /**
     * Create a new ACP session. Returns the session ID and populates available models.
     */
    @NotNull
    public synchronized String createSession() throws CopilotException {
        return createSession(null);
    }

    /**
     * Create a new ACP session with an optional working directory.
     *
     * @param cwd The working directory for the session, or null to use user.home.
     */
    public synchronized String createSession(@Nullable String cwd) throws CopilotException {
        ensureStarted();

        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd != null ? cwd : System.getProperty(USER_HOME));

        // mcpServers must be an array in session/new (agent validates this)
        // MCP servers are configured via --additional-mcp-config CLI flag
        // Empty array here because servers are loaded from the config file
        params.add("mcpServers", new JsonArray());

        // Do NOT send availableTools param - it would filter out MCP tools!
        // We want the agent to see both CLI tools AND MCP tools.
        // Tool filtering doesn't work properly in ACP mode (CLI bug #556),
        // so we handle it via permission denial (see handlePermissionRequest).

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
            model.setUsage(meta.has("copilotUsage") ? meta.get("copilotUsage").getAsString() : null);
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
    public void setModel(@NotNull String sessionId, @NotNull String modelId) throws CopilotException {
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
     */
    @NotNull
    public List<Model> listModels() throws CopilotException {
        if (availableModels == null) {
            createSession();
        }
        return availableModels != null ? availableModels : List.of();
    }

    /**
     * Force-refresh the model list by creating a new session.
     * Useful after authentication changes to pick up fresh tokens.
     */
    @NotNull
    public List<Model> refreshModels() throws CopilotException {
        availableModels = null;
        currentSessionId = null;
        createSession();
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
        throws CopilotException {
        return sendPrompt(sessionId, prompt, model, null, onChunk);
    }

    /**
     * Send a prompt with optional file/selection context references.
     * References are included as ACP "resource" content blocks alongside the text.
     */
    public String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                             @Nullable String model, @Nullable List<ResourceReference> references,
                             @Nullable Consumer<String> onChunk)
        throws CopilotException {
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
        throws CopilotException {
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
        throws CopilotException {
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
                    currentPrompt = "The previous tool calls were denied. Please continue with the task using the correct tools with 'intellij-code-tools-' prefix.";
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
    private JsonObject sendPromptRequest(@NotNull JsonObject params) throws CopilotException {
        if (closed) throw new CopilotException("ACP client is closed", null, false);

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
            if (cause instanceof CopilotException copilotException) throw copilotException;
            throw new CopilotException("ACP request failed: session/prompt - " + cause.getMessage(), e, false);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new CopilotException("ACP request interrupted: session/prompt", e, true);
        } catch (IOException e) {
            pendingRequests.remove(id);
            throw new CopilotException("ACP write failed: session/prompt", e, true);
        }
    }

    /**
     * Polls for prompt completion, checking for inactivity timeout and credit limit.
     */
    private JsonObject pollForPromptCompletion(long id, CompletableFuture<JsonObject> future)
        throws CopilotException, ExecutionException, InterruptedException {
        int inactivityTimeoutSec = CopilotSettings.getPromptTimeout();
        int maxToolCalls = CopilotSettings.getMaxToolCallsPerTurn();
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
        throws CopilotException {
        long inactiveMs = System.currentTimeMillis() - lastActivityTimestamp;
        if (inactiveMs > inactivityTimeoutSec * 1000L) {
            pendingRequests.remove(id);
            LOG.warn("Agent inactive for " + (inactiveMs / 1000) + "s, terminating");
            fireDebugEvent("INACTIVITY_TIMEOUT",
                "No activity for " + (inactiveMs / 1000) + "s (limit: " + inactivityTimeoutSec + "s)",
                "Tool calls this turn: " + toolCallsInTurn.get());
            terminateAgent();
            throw new CopilotException(
                "Agent stopped: no activity for " + (inactiveMs / 1000) + " seconds", cause, true);
        }
    }

    private void checkCreditLimit(long id, int maxToolCalls, TimeoutException cause)
        throws CopilotException {
        if (maxToolCalls > 0 && toolCallsInTurn.get() >= maxToolCalls) {
            pendingRequests.remove(id);
            LOG.warn("Tool call limit reached: " + toolCallsInTurn.get() + "/" + maxToolCalls);
            fireDebugEvent("CREDIT_LIMIT",
                "Tool call limit reached: " + toolCallsInTurn.get() + "/" + maxToolCalls,
                "Terminating agent to prevent excess usage");
            terminateAgent();
            throw new CopilotException(
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

        // Intercept built-in read-only tool calls (view, grep, glob) that bypass request_permission.
        // We can't block them (they auto-execute), but we send corrective guidance so the agent
        // switches to IntelliJ MCP tools for subsequent calls in the same turn.
        if ("tool_call".equals(updateType)) {
            interceptBuiltInToolCall(update);
        }

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

    /**
     * Built-in read-only tools (view, grep, glob) bypass request_permission entirely —
     * they auto-execute without asking. We can't block them, but we CAN detect them via
     * tool_call notifications and send corrective guidance so the agent uses IntelliJ tools
     * for subsequent calls in the same turn.
     * <p>
     * Detection uses a negative check: if the tool is NOT one of our known MCP tools or
     * Copilot meta-tools, it must be a built-in CLI tool that bypasses IDE buffers.
     */
    private void interceptBuiltInToolCall(JsonObject update) {
        String title = update.has(TITLE_KEY) ? update.get(TITLE_KEY).getAsString() : "";

        // Skip our MCP tools and GitHub MCP tools
        if (title.startsWith("intellij-code-tools-") || title.startsWith("github-mcp-server-")) return;

        // Skip Copilot meta-tools (these aren't file operations)
        String kind = update.has("kind") ? update.get("kind").getAsString() : "";
        if ("think".equals(kind)) return;

        // Everything else is a built-in CLI tool — classify and send guidance
        String guidance = classifyBuiltInTool(title);
        LOG.info("interceptBuiltInToolCall: detected built-in tool '" + title +
            "' (kind=" + kind + "), sending corrective guidance");
        sendPromptMessage(guidance);
    }

    /**
     * Classify a built-in tool call by its title and return corrective guidance.
     * Copilot CLI uses descriptive titles like "Viewing ...", "Searching for '...'"
     * rather than simple tool names.
     */
    private @NotNull String classifyBuiltInTool(String title) {
        String lower = title.toLowerCase();

        // View/read: "Viewing ...", "view", "read", "read file", "view file"
        if (lower.startsWith("viewing ") || lower.equals("view") || lower.equals("read")
            || lower.equals("read file") || lower.equals("view file")) {
            return BUILT_IN_TOOL_WARNING_PREFIX + title + "' tool which reads from disk (may be stale). " +
                "Use 'intellij-code-tools-intellij_read_file' instead — it reads live editor buffers.";
        }

        // Grep/search: "Searching for '...'", "grep", "search", "ripgrep"
        if (lower.startsWith("searching for ") || lower.equals("grep") || lower.equals("search")
            || lower.equals("ripgrep")) {
            return BUILT_IN_TOOL_WARNING_PREFIX + title + "' tool which reads from disk. " +
                "Use 'intellij-code-tools-search_text' instead — it searches live editor buffers. " +
                "For symbol search, use 'intellij-code-tools-search_symbols'.";
        }

        // Glob/find: "Finding files matching ...", "glob", "find files", "list files"
        if (lower.startsWith("finding files ") || lower.equals("glob") || lower.equals("find files")
            || lower.equals("list files")) {
            return BUILT_IN_TOOL_WARNING_PREFIX + title + "' tool. " +
                "Use 'intellij-code-tools-list_project_files' instead — it uses IntelliJ's project index.";
        }

        // Create: "Creating ...", "create", "create file"
        if (lower.startsWith("creating ") || lower.equals(CREATE_KIND) || lower.equals("create file")) {
            return BUILT_IN_TOOL_WARNING_PREFIX + title + "' tool. " +
                "Use 'intellij-code-tools-create_file' instead — it integrates with IntelliJ's project index.";
        }

        // Edit: "Editing ...", "edit", "edit file"
        if (lower.startsWith("editing ") || lower.equals("edit") || lower.equals("edit file")) {
            return BUILT_IN_TOOL_WARNING_PREFIX + title + "' tool which writes to disk, bypassing the editor. " +
                "Use 'intellij-code-tools-intellij_write_file' instead — it writes to live editor buffers.";
        }

        // Unknown non-MCP tool — send generic guidance
        LOG.info("interceptBuiltInToolCall: unknown tool '" + title + "', sending generic guidance");
        return BUILT_IN_TOOL_WARNING_PREFIX + title + "' (a Copilot CLI built-in tool). " +
            "Prefer 'intellij-code-tools-*' MCP tools instead — they operate on live editor buffers.";
    }

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
     * Get the resolved path to the copilot CLI binary (for logout, auth commands).
     */
    @Nullable
    public String getCopilotCliPath() {
        return resolvedCopilotPath;
    }

    /**
     * Get the auth method info from the initialization response (for the login button).
     */
    @Nullable
    public AuthMethod getAuthMethod() {
        if (authMethods == null || authMethods.isEmpty()) return null;
        JsonObject first = authMethods.get(0).getAsJsonObject();
        AuthMethod method = new AuthMethod();
        method.setId(first.has("id") ? first.get("id").getAsString() : "");
        method.setName(first.has("name") ? first.get("name").getAsString() : "");
        method.setDescription(first.has(DESCRIPTION) ? first.get(DESCRIPTION).getAsString() : "");
        parseTerminalAuth(first, method);
        return method;
    }

    private void parseTerminalAuth(JsonObject jsonObject, AuthMethod method) {
        if (jsonObject.has(META)) {
            JsonObject meta = jsonObject.getAsJsonObject(META);
            if (meta.has("terminal-auth")) {
                JsonObject termAuth = meta.getAsJsonObject("terminal-auth");
                method.setCommand(termAuth.has(COMMAND) ? termAuth.get(COMMAND).getAsString() : null);
                if (termAuth.has("args")) {
                    List<String> args = new ArrayList<>();
                    for (JsonElement a : termAuth.getAsJsonArray("args")) {
                        args.add(a.getAsString());
                    }
                    method.setArgs(args);
                }
            }
        }
    }

    /**
     * Send a JSON-RPC request and wait for the response.
     */
    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params) throws CopilotException {
        if (closed) throw new CopilotException("ACP client is closed", null, false);

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

            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new CopilotException("ACP request timed out: " + method, e, true);
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof CopilotException copilotException) throw copilotException;
            throw new CopilotException("ACP request failed: " + method + " - " + cause.getMessage(), e, false);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new CopilotException("ACP request interrupted: " + method, e, true);
        } catch (IOException e) {
            pendingRequests.remove(id);
            throw new CopilotException("ACP write failed: " + method, e, true);
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
        } else {
            failAllPendingRequests();
        }
    }

    private void attemptAutoRestart() {
        if (restartAttempts.get() >= MAX_RESTART_ATTEMPTS) {
            LOG.warn("ACP process terminated after " + MAX_RESTART_ATTEMPTS + " restart attempts");
            showNotification("Copilot Disconnected",
                "Could not reconnect after " + MAX_RESTART_ATTEMPTS + " attempts. Please restart the IDE.",
                com.intellij.notification.NotificationType.ERROR);
            failAllPendingRequests();
            return;
        }

        int attempts = restartAttempts.incrementAndGet();
        long delayMs = RESTART_DELAYS_MS[Math.min(attempts - 1, RESTART_DELAYS_MS.length - 1)];

        LOG.info("ACP process terminated. Attempting restart " + attempts + "/" + MAX_RESTART_ATTEMPTS +
            " after " + delayMs + "ms...");
        showNotification("Copilot Reconnecting...",
            "Attempt " + attempts + "/" + MAX_RESTART_ATTEMPTS,
            com.intellij.notification.NotificationType.INFORMATION);

        // Schedule restart on background thread
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                start();
                LOG.info("ACP process successfully restarted");
                showNotification("Copilot Reconnected",
                    "Successfully reconnected to Copilot",
                    com.intellij.notification.NotificationType.INFORMATION);
                restartAttempts.set(0); // Reset counter on successful restart
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Restart attempt interrupted", e);
                failAllPendingRequests();
            } catch (CopilotException e) {
                LOG.warn("Failed to restart ACP process (attempt " + restartAttempts + ")", e);
                attemptAutoRestart(); // Try again
            }
        }, "CopilotACP-Restart").start();
    }

    private void showNotification(String title, String content, com.intellij.notification.NotificationType type) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Copilot Notifications")
            .createNotification(title, content, type)
            .notify(null);
    }

    private void failAllPendingRequests() {
        for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                new CopilotException("ACP process terminated", null, false));
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
        future.completeExceptionally(new CopilotException("ACP error: " + errorMessage, null, false));
    }

    private void handleNotificationMessage(JsonObject msg) {
        String method = msg.has(METHOD) ? msg.get(METHOD).getAsString() : "unknown";
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
                LOG.warn("Copilot CLI stderr: " + line);
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("Stderr reader thread ended: " + e.getMessage());
            }
        }
    }

    private void ensureStarted() throws CopilotException {
        if (closed) {
            throw new CopilotException("ACP client is closed", null, false);
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

        // Track activity and tool call count
        lastActivityTimestamp = System.currentTimeMillis();
        toolCallsInTurn.incrementAndGet();

        // Check if run_command is trying to do something we have a dedicated tool for
        String commandAbuse = detectCommandAbuse(toolCall);
        if (commandAbuse != null) {
            String rejectOptionId = findRejectOption(reqParams);
            LOG.info("ACP request_permission: DENYING run_command abuse: " + commandAbuse);

            // Send guidance BEFORE rejecting so agent sees it while still in turn
            Map<String, Object> retryParams = buildRetryParams(RUN_COMMAND_ABUSE_PREFIX + commandAbuse);
            String retryMessage = (String) retryParams.get(MESSAGE);
            fireDebugEvent(PRE_REJECTION_GUIDANCE_EVENT, SENDING_GUIDANCE_DESC, retryMessage);
            sendPromptMessage(retryMessage);

            fireDebugEvent(PERMISSION_DENIED_EVENT, "run_command abuse: " + commandAbuse,
                toolCall.toString());
            builtInActionDeniedDuringTurn = true;
            lastDeniedKind = RUN_COMMAND_ABUSE_PREFIX + commandAbuse;
            sendPermissionResponse(reqId, rejectOptionId);
            return;
        }

        // Check if a CLI tool is targeting project files (should use IntelliJ MCP tools instead)
        String cliToolAbuse = detectCliToolAbuse(toolCall);
        if (cliToolAbuse != null) {
            String rejectOptionId = findRejectOption(reqParams);
            LOG.info("ACP request_permission: DENYING CLI tool abuse: " + cliToolAbuse);

            Map<String, Object> retryParams = buildRetryParams(CLI_TOOL_ABUSE_PREFIX + cliToolAbuse);
            String retryMessage = (String) retryParams.get(MESSAGE);
            fireDebugEvent(PRE_REJECTION_GUIDANCE_EVENT, SENDING_GUIDANCE_DESC, retryMessage);
            sendPromptMessage(retryMessage);

            fireDebugEvent(PERMISSION_DENIED_EVENT, "CLI tool abuse: " + cliToolAbuse,
                toolCall.toString());
            builtInActionDeniedDuringTurn = true;
            lastDeniedKind = CLI_TOOL_ABUSE_PREFIX + cliToolAbuse;
            sendPermissionResponse(reqId, rejectOptionId);
            return;
        }

        // Check if a sub-agent is trying to use git write tools
        String gitWriteAbuse = detectSubAgentGitWrite(toolCall);
        if (gitWriteAbuse != null) {
            String rejectOptionId = findRejectOption(reqParams);
            LOG.info("ACP request_permission: DENYING sub-agent git write: " + gitWriteAbuse);

            Map<String, Object> retryParams = buildRetryParams(GIT_WRITE_ABUSE_PREFIX + gitWriteAbuse);
            String retryMessage = (String) retryParams.get(MESSAGE);
            fireDebugEvent(PRE_REJECTION_GUIDANCE_EVENT, SENDING_GUIDANCE_DESC, retryMessage);
            sendPromptMessage(retryMessage);

            fireDebugEvent(PERMISSION_DENIED_EVENT, "Sub-agent git write: " + gitWriteAbuse,
                toolCall.toString());
            builtInActionDeniedDuringTurn = true;
            lastDeniedKind = GIT_WRITE_ABUSE_PREFIX + gitWriteAbuse;
            sendPermissionResponse(reqId, rejectOptionId);
            return;
        }

        // Use per-tool permission settings (DENY / ASK / ALLOW)
        String toolId = resolveToolId(permKind, toolCall);
        ToolPermission perm = resolveEffectivePermission(toolId, permKind, toolCall);

        if (perm == ToolPermission.DENY) {
            String rejectOptionId = findRejectOption(reqParams);
            LOG.info("ACP request_permission: DENYING " + permKind + " (tool=" + toolId + "), option=" + rejectOptionId);

            // Send guidance BEFORE rejecting so agent sees it while still in turn
            Map<String, Object> retryParams = buildRetryParams(permKind);
            String retryMessage = (String) retryParams.get(MESSAGE);
            fireDebugEvent(PRE_REJECTION_GUIDANCE_EVENT, SENDING_GUIDANCE_DESC, retryMessage);
            sendPromptMessage(retryMessage);

            fireDebugEvent(PERMISSION_DENIED_EVENT, "Denied: " + permKind + " (tool=" + toolId + ")",
                "Permission mode: DENY");
            builtInActionDeniedDuringTurn = true;
            lastDeniedKind = permKind;
            sendPermissionResponse(reqId, rejectOptionId);
        } else if (perm == ToolPermission.ASK) {
            // ASK: fire permission request to UI and block until user responds (60s timeout)
            var listener = permissionRequestListener;
            if (listener == null) {
                // No UI listener registered — fall back to auto-deny for safety
                LOG.warn("ACP request_permission: ASK for " + toolId + " but no listener — auto-denying");
                String rejectOptionId = findRejectOption(reqParams);
                sendPermissionResponse(reqId, rejectOptionId);
                return;
            }
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pendingPermissionAsks.put(reqId, future);
            ToolRegistry.ToolEntry toolEntry = ToolRegistry.findById(toolId);
            String displayName = toolEntry != null ? toolEntry.displayName : permKind;
            PermissionRequest req = new PermissionRequest(reqId, toolId, displayName, formattedPermission,
                future::complete);
            listener.accept(req);
            boolean allowed;
            try {
                allowed = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.info("ACP request_permission: ASK timed out / cancelled for " + toolId + " — denying");
                allowed = false;
            } finally {
                pendingPermissionAsks.remove(reqId);
            }
            if (allowed) {
                String allowOptionId = findAllowOption(reqParams);
                LOG.info("ACP request_permission: ASK approved by user for " + toolId);
                fireDebugEvent("PERMISSION_APPROVED", formattedPermission, "user-approved");
                sendPermissionResponse(reqId, allowOptionId);
            } else {
                String rejectOptionId = findRejectOption(reqParams);
                LOG.info("ACP request_permission: ASK denied by user for " + toolId);
                fireDebugEvent(PERMISSION_DENIED_EVENT, "ASK denied by user: " + toolId, "");
                builtInActionDeniedDuringTurn = true;
                lastDeniedKind = permKind;
                sendPermissionResponse(reqId, rejectOptionId);
            }
        } else {
            // ALLOW
            String allowOptionId = findAllowOption(reqParams);
            LOG.info("ACP request_permission: auto-approving " + permKind + " (tool=" + toolId + "), option=" + allowOptionId);
            fireDebugEvent("PERMISSION_APPROVED", formattedPermission, "");
            sendPermissionResponse(reqId, allowOptionId);
        }
    }

    /**
     * Strip "intellij-code-tools-" prefix and normalise the tool ID from permKind / toolCall name.
     */
    private String resolveToolId(@Nullable String permKind, @Nullable JsonObject toolCall) {
        String name = "";
        if (toolCall != null && toolCall.has("name")) {
            name = toolCall.get("name").getAsString();
        }
        if (name.startsWith("intellij-code-tools-")) {
            name = name.substring("intellij-code-tools-".length());
        }
        return name.isEmpty() ? (permKind != null ? permKind : "") : name;
    }

    /**
     * Look up the effective ToolPermission for a tool call.
     * For file tools, checks inside/outside-project sub-permission when a path is present.
     */
    private ToolPermission resolveEffectivePermission(String toolId, String permKind, @Nullable JsonObject toolCall) {
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolId);

        // Path-based sub-permissions for file tools (ceiling enforced by CopilotSettings)
        if (entry != null && entry.supportsPathSubPermissions && toolCall != null) {
            String path = extractPathFromToolCall(toolCall);
            if (path != null && !path.isEmpty()) {
                boolean insideProject = isPathInsideProject(path);
                return CopilotSettings.resolveEffectivePermission(toolId, insideProject);
            }
        }
        return CopilotSettings.getToolPermission(toolId);
    }

    /**
     * Extract a file path argument from a tool call JSON (checks common arg names).
     */
    private @Nullable String extractPathFromToolCall(@Nullable JsonObject toolCall) {
        if (toolCall == null) return null;
        for (String key : new String[]{"path", "file", "file1", "file2"}) {
            if (toolCall.has(key) && toolCall.get(key).isJsonPrimitive()) {
                return toolCall.get(key).getAsString();
            }
        }
        // Also check inside a nested "arguments" / "input" object
        for (String wrapper : new String[]{"arguments", "input", "params"}) {
            if (toolCall.has(wrapper) && toolCall.get(wrapper).isJsonObject()) {
                JsonObject inner = toolCall.getAsJsonObject(wrapper);
                for (String key : new String[]{"path", "file", "file1", "file2"}) {
                    if (inner.has(key) && inner.get(key).isJsonPrimitive()) {
                        return inner.get(key).getAsString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns true if the given absolute-or-relative path falls inside the current project root.
     */
    private boolean isPathInsideProject(String path) {
        if (projectBasePath == null) return true;
        java.io.File f = new java.io.File(path);
        if (!f.isAbsolute()) return true; // relative paths assumed in-project
        try {
            return f.getCanonicalPath().startsWith(new java.io.File(projectBasePath).getCanonicalPath());
        } catch (java.io.IOException e) {
            return true;
        }
    }

    private String formatPermissionDisplay(String permKind, String permTitle) {
        return permKind + (permTitle.isEmpty() ? "" : " - " + permTitle);
    }

    /**
     * Detect if run_command is being abused to do something we have a dedicated tool for.
     * Returns abuse type if detected, null otherwise.
     */
    private String detectCommandAbuse(JsonObject toolCall) {
        if (toolCall == null) return null;

        String toolName = toolCall.has("name") ? toolCall.get("name").getAsString() : "";
        if (!"run_command".equals(toolName) && !"intellij-code-tools-run_command".equals(toolName)) {
            return null;
        }

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

    /**
     * Detect if a CLI tool (view, grep, glob, edit, create) is targeting project files.
     * These tools read/write from disk and may be stale. IntelliJ MCP tools operate on
     * live editor buffers and should be used instead.
     * Returns the tool type (e.g. "view", "grep") if abuse detected, null otherwise.
     */
    private String detectCliToolAbuse(JsonObject toolCall) {
        if (toolCall == null) return null;

        String toolTitle = toolCall.has(TITLE_KEY) ? toolCall.get(TITLE_KEY).getAsString() : "";
        String toolName = toolCall.has("name") ? toolCall.get("name").getAsString() : "";
        String tool = !toolTitle.isEmpty() ? toolTitle : toolName;

        return switch (tool) {
            case "view", "read" -> {
                String path = extractPathParam(toolCall);
                yield !path.isEmpty() && isInsideProject(path) ? "view" : null;
            }
            case "edit" -> {
                String path = extractPathParam(toolCall);
                yield !path.isEmpty() && isInsideProject(path) ? "edit" : null;
            }
            case CREATE_KIND -> {
                String path = extractPathParam(toolCall);
                yield !path.isEmpty() && isInsideProject(path) ? CREATE_KIND : null;
            }
            case "grep" -> {
                // grep defaults to cwd (project root) when path is omitted
                String path = extractPathParam(toolCall);
                yield (path.isEmpty() || isInsideProject(path)) ? "grep" : null;
            }
            case "glob" -> {
                // glob defaults to cwd (project root) when path is omitted
                String path = extractPathParam(toolCall);
                yield (path.isEmpty() || isInsideProject(path)) ? "glob" : null;
            }
            case "bash" -> "bash";
            default -> null;
        };
    }

    private String extractPathParam(JsonObject toolCall) {
        if (!toolCall.has(PARAMETERS_KEY)) return "";
        JsonObject params = toolCall.getAsJsonObject(PARAMETERS_KEY);
        return params.has("path") ? params.get("path").getAsString() : "";
    }

    private boolean isInsideProject(String path) {
        if (projectBasePath == null || projectBasePath.isEmpty()) return false;
        String normalizedBase = projectBasePath.endsWith("/") ? projectBasePath : projectBasePath + "/";
        return path.startsWith(normalizedBase) || path.equals(projectBasePath);
    }

    /**
     * Detect if a git write tool is being called by a sub-agent.
     * Returns the tool name (e.g. "git_commit") if blocked, null otherwise.
     */
    private String detectSubAgentGitWrite(JsonObject toolCall) {
        if (!subAgentActive || toolCall == null) return null;

        String toolName = toolCall.has("name") ? toolCall.get("name").getAsString() : "";
        // Strip MCP prefix if present
        String shortName = toolName.replace("intellij-code-tools-", "");
        return GIT_WRITE_TOOLS.contains(shortName) ? shortName : null;
    }

    /**
     * Build guidance message for denied actions.
     * Returns a map with "message" key containing the instruction text.
     */
    private Map<String, Object> buildRetryParams(@NotNull String deniedKind) {
        String instruction;

        // Specific guidance for run_command abuse
        if (deniedKind.startsWith(RUN_COMMAND_ABUSE_PREFIX)) {
            String abuseType = deniedKind.substring(RUN_COMMAND_ABUSE_PREFIX.length());
            instruction = switch (abuseType) {
                case "test" -> "⚠ Don't use run_command for tests (including build/check/verify which " +
                    "implicitly run tests). Use 'intellij-code-tools-run_tests' instead. " +
                    "Provides structured results, coverage, and failure details.";
                case "sed" -> "⚠ Don't use sed. Use 'intellij-code-tools-intellij_write_file' instead. " +
                    "It provides proper file editing with undo/redo and live editor buffer access.";
                case "cat" ->
                    "⚠ Don't use cat/head/tail/less/more. Use 'intellij-code-tools-intellij_read_file' instead. " +
                        "It reads from the live editor buffer, not stale disk files.";
                case "grep" -> "⚠ Don't use grep. Use 'intellij-code-tools-search_symbols' or " +
                    "'intellij-code-tools-find_references' instead. They search live editor buffers.";
                case "find" -> "⚠ Don't use find. Use 'intellij-code-tools-list_project_files' instead.";
                case "git" -> "⚠ Don't use git commands via run_command — it desyncs IntelliJ editor buffers. " +
                    "Use dedicated git tools: git_status, git_diff, git_log, git_commit, git_stage, " +
                    "git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote.";
                default -> TOOL_DENIED_DEFAULT_MSG;
            };
        } else if (deniedKind.startsWith(CLI_TOOL_ABUSE_PREFIX)) {
            String toolType = deniedKind.substring(CLI_TOOL_ABUSE_PREFIX.length());
            instruction = switch (toolType) {
                case "view" -> "⚠ Don't use 'view' for project files — it reads from disk and may be stale. " +
                    "Use 'intellij-code-tools-intellij_read_file' instead (reads live editor buffer).";
                case "edit" -> "⚠ Don't use 'edit' for project files — it writes to disk, bypassing the editor. " +
                    "Use 'intellij-code-tools-intellij_write_file' instead (writes to live editor buffer with undo).";
                case CREATE_KIND -> "⚠ Don't use 'create' for project files. " +
                    "Use 'intellij-code-tools-create_file' instead (integrates with IntelliJ's project index).";
                case "grep" -> "⚠ Don't use 'grep' for project files — it reads from disk and may be stale. " +
                    "Use 'intellij-code-tools-search_text' instead (searches live editor buffers).";
                case "glob" -> "⚠ Don't use 'glob' for project files. " +
                    "Use 'intellij-code-tools-list_project_files' instead (uses IntelliJ's project index).";
                case "bash" ->
                    "⚠ Don't use 'bash' — it reads/writes disk directly, bypassing IntelliJ editor buffers. " +
                        "Use 'intellij-code-tools-run_command' instead (flushes buffers to disk first). " +
                        "For file operations use intellij_read_file, intellij_write_file, search_text, etc.";
                default -> TOOL_DENIED_DEFAULT_MSG;
            };
        } else if (deniedKind.startsWith(GIT_WRITE_ABUSE_PREFIX)) {
            instruction = "⚠ Sub-agents must not use git write commands (git_commit, git_stage, git_unstage, " +
                "git_branch, git_stash, git_push, git_remote). Only the parent agent may perform git writes. " +
                "Use read-only git tools (git_status, git_diff, git_log, git_show, git_blame) instead.";
        } else if ("bash".equals(deniedKind)) {
            instruction = "⚠ Don't use 'bash' — it reads/writes disk directly, bypassing IntelliJ editor buffers. " +
                "Use 'intellij-code-tools-run_command' instead (flushes buffers to disk first). " +
                "For file operations use intellij_read_file, intellij_write_file, search_text, etc.";
        } else {
            // Generic message for other denials
            instruction = TOOL_DENIED_DEFAULT_MSG;
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

    // DTOs

    public static class Model {
        private String id;
        private String name;
        private String description;
        private String usage; // e.g., "1x", "3x", "0.33x"

        public String getId() {
            return id;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setName(String name) {
            this.name = name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public String getDescription() {
            return description;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setDescription(String description) {
            this.description = description;
        }

        public String getUsage() {
            return usage;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setUsage(String usage) {
            this.usage = usage;
        }
    }

    /**
     * ACP resource reference ? file or selection context sent with prompts.
     */
    public record ResourceReference(@NotNull String uri, @Nullable String mimeType, @NotNull String text) {
    }

    public static class AuthMethod {
        private String id;
        private String name;
        private String description;
        private String command;
        private List<String> args;

        public String getId() {
            return id;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setName(String name) {
            this.name = name;
        }

        @SuppressWarnings("unused") // Public API for external use
        public String getDescription() {
            return description;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setDescription(String description) {
            this.description = description;
        }

        public String getCommand() {
            return command;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        @SuppressWarnings("unused") // Public API for external use
        public void setArgs(List<String> args) {
            this.args = args;
        }
    }
}
