package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
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
import java.io.File;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Generic ACP (Agent Client Protocol) client over JSON-RPC 2.0 stdin/stdout.
 * Agent-specific concerns (binary discovery, auth, model parsing) are delegated
 * to the {@link AgentConfig} strategy provided at construction time.
 */
public abstract class AcpClient implements AgentClient {
    protected static final Logger LOG = Logger.getInstance(AcpClient.class);
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
    protected static final String CONTENT = "content";
    private static final String UNKNOWN = "unknown";
    private static final String RUN_COMMAND_ABUSE_PREFIX = "run_command_abuse:";
    private static final String MCP_SERVERS_KEY = "mcpServers";
    private static final String GIT_WRITE_ABUSE_PREFIX = "git_write_abuse:";
    private static final String USER_HOME = "user.home";
    protected static final String TITLE_KEY = "title";
    private static final String PARAMETERS_KEY = "parameters";
    private static final String TOOL_DENIED_DEFAULT_MSG_TEMPLATE = "⚠ Tool denied. Use tools with '%s' prefix instead.";
    protected static final String TOOL_CALL_ID_KEY = "toolCallId";
    private static final String STATUS_KEY = "status";
    protected static final String KIND_KEY = "kind";
    protected static final String ARGUMENTS_KEY = "arguments";
    protected static final String INPUT_KEY = "input";
    protected static final String RAW_INPUT_KEY = "rawInput";
    private static final String AGENT_TYPE_KEY = "agent_type";
    private static final String PROMPT = "prompt";
    protected static final String OUTPUT_KEY = "output";
    private static final String TOOL_RESULT_KEY = "toolResult";

    private static final String TOOL_PREFIX = " (tool=";
    private static final String TOOL_CALL_KEY = "toolCall";

    // Note: DENIED_PERMISSION_KINDS was removed — permission denial is now handled by
    // per-tool ToolPermission settings in handlePermissionRequest, not a static set.

    protected final Gson gson = new Gson();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonObject>> notificationListeners = new CopyOnWriteArrayList<>();

    private final Object writerLock = new Object();
    protected final String projectBasePath; // Project path for config-dir (protected for subclass access)
    @Nullable
    protected final ToolRegistry registry;
    protected final AgentConfig agentConfig;
    protected final AgentSettings agentSettings;
    protected final int mcpPort;
    /**
     * Prefix used to strip the MCP server name from incoming tool-call names (e.g. "agentbridge-").
     */
    protected volatile String effectiveMcpPrefix = "agentbridge-";

    /**
     * Prefix used when identifying tool calls in the log or for permission mapping.
     * Default matches GitHub Copilot style.
     */
    protected String logMcpPrefix = "agentbridge-";
    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean closed = false;

    // Auto-restart state
    private final AtomicInteger restartAttempts = new AtomicInteger(0);
    // Guards against double-scheduling: both readLoop and the restart thread's catch block can
    // call attemptAutoRestart() for the same crash event. CAS ensures only one proceeds.
    private final AtomicBoolean restartPending = new AtomicBoolean(false);
    private volatile boolean lastFailureWasAuth = false; // Track auth failures to avoid auto-retry
    private static final int MAX_RESTART_ATTEMPTS = 1; // Reduced from 3 to prevent auth lockouts
    private static final long[] RESTART_DELAYS_MS = {2000}; // Single retry with 2s delay

    // State from the initialization response
    private boolean initialized = false;

    // Session state
    private String currentSessionId;
    protected List<Model> availableModels;

    // Permission tracking: set when a built-in permission is denied during a prompt turn
    private volatile boolean builtInActionDeniedDuringTurn = false;
    // Track tool calls that were explicitly denied during this turn to update UI chips accordingly
    protected final java.util.Set<String> deniedToolCallIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Sub-agent tracking: set by UI layer when a Task tool call is active
    private volatile boolean subAgentActive = false;

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
    @Override
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
    @Override
    public void setSubAgentActive(boolean active) {
        this.subAgentActive = active;
    }

    /**
     * Create ACP client with an agent configuration, settings, and optional project base path.
     */
    public AcpClient(@NotNull AgentConfig agentConfig, @NotNull AgentSettings agentSettings,
                     @Nullable ToolRegistry registry,
                     @Nullable String projectBasePath, int mcpPort) {
        this.agentConfig = agentConfig;
        this.agentSettings = agentSettings;
        this.registry = registry;
        this.projectBasePath = projectBasePath;
        this.mcpPort = mcpPort;
    }

    @Nullable
    protected String resolveMcpTemplate(@NotNull String template) {
        String resolved = template.replace("{mcpPort}", String.valueOf(mcpPort));

        if (resolved.contains("{mcpJarPath}")) {
            String jarPath = McpServerJarLocator.findMcpServerJar();
            if (jarPath == null) {
                LOG.warn("MCP server JAR not found — IntelliJ tools will be unavailable for "
                    + agentConfig.getDisplayName());
                return null;
            }
            resolved = resolved.replace("{mcpJarPath}", jarPath);
        }

        if (resolved.contains("{javaPath}")) {
            String javaPath = resolveJavaPath();
            if (javaPath == null) {
                LOG.warn("Java binary not found — IntelliJ tools will be unavailable for "
                    + agentConfig.getDisplayName());
                return null;
            }
            resolved = resolved.replace("{javaPath}", javaPath);
        }

        return resolved;
    }

    @Nullable
    protected static String resolveJavaPath() {
        String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + javaExe;
        return new File(javaPath).exists() ? javaPath : null;
    }

    /**
     * Start the ACP process and perform the initialization handshake.
     */
    public synchronized void start() throws AcpException {
        // If already running healthy (e.g. getClient() restarted us before the scheduled retry fires), skip.
        if (isHealthy()) {
            LOG.debug("ACP client is already running and healthy — skipping redundant start()");
            return;
        }

        // Clear auth failure flag on manual restart attempts
        lastFailureWasAuth = false;

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
            customizeProcessBuilder(pb);
            effectiveMcpPrefix = agentConfig.getEffectiveMcpServerName() + "-";
            if (projectBasePath != null) {
                pb.directory(new java.io.File(projectBasePath));
            }
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
            try {
                doInitialize();
                // Set initial active agent label (the profile's display name)
                agentSettings.setActiveAgentLabel(agentConfig.getDisplayName());
            } catch (AcpException e) {
                // If the process died or rejected the handshake, clean up so the next attempt starts fresh.
                // This is especially important for model rejection recovery.
                LOG.warn("ACP initialization handshake failed: " + e.getMessage());
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                throw e;
            }

        } catch (IOException e) {
            throw new AcpException("Failed to start " + agentConfig.getDisplayName() + " ACP process", e);
        }
    }

    /**
     * Hook to allow subclasses to modify the ProcessBuilder before the process starts.
     */
    protected void customizeProcessBuilder(@NotNull ProcessBuilder pb) {
        // Default implementation does nothing
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
     * Hook for subclasses to inject extra parameters into the {@code session/new} request.
     * Called after all standard parameters (cwd, mcpServers) have been set.
     */
    protected void addExtraSessionParams(@NotNull JsonObject params) {
        // No-op hook for subclasses to override
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
    @Override
    public synchronized @NotNull String createSession(@Nullable String cwd) throws AcpException {
        ensureStarted();

        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd != null ? cwd : System.getProperty(USER_HOME));

        // mcpServers format depends on agent: Kiro uses array, others may use object
        if (agentConfig.requiresMcpInSessionNew()) {
            String template = agentConfig.getMcpConfigTemplate();
            if (template != null && !template.isEmpty()) {
                String resolved = resolveMcpTemplate(template);
                if (resolved != null) {
                    try {
                        JsonObject mcpConfig = JsonParser.parseString(resolved).getAsJsonObject();
                        if (mcpConfig.has(MCP_SERVERS_KEY)) {
                            JsonElement serversElement = mcpConfig.get(MCP_SERVERS_KEY);
                            // session/new requires array format: [{"name":"agentbridge",...}]
                            // Config files may use object format: {"agentbridge":{...}}
                            // Convert object to array when needed
                            if (serversElement.isJsonArray()) {
                                params.add(MCP_SERVERS_KEY, serversElement.getAsJsonArray());
                                LOG.info("Creating session with " + serversElement.getAsJsonArray().size() + " injected MCP servers");
                            } else if (serversElement.isJsonObject()) {
                                // Convert object format to array format for session/new
                                JsonArray serversArray = new JsonArray();
                                for (Map.Entry<String, JsonElement> entry : serversElement.getAsJsonObject().entrySet()) {
                                    JsonObject serverEntry = entry.getValue().getAsJsonObject().deepCopy();
                                    serverEntry.addProperty("name", entry.getKey());
                                    // Copilot requires env and headers as arrays: [{"key":"K","value":"V"},...]
                                    if (serverEntry.has("env") && serverEntry.get("env").isJsonObject()) {
                                        JsonArray envArray = new JsonArray();
                                        for (Map.Entry<String, JsonElement> envEntry : serverEntry.getAsJsonObject("env").entrySet()) {
                                            JsonObject envItem = new JsonObject();
                                            envItem.addProperty("key", envEntry.getKey());
                                            envItem.addProperty("value", envEntry.getValue().getAsString());
                                            envArray.add(envItem);
                                        }
                                        serverEntry.add("env", envArray);
                                    }
                                    if (serverEntry.has("headers") && serverEntry.get("headers").isJsonObject()) {
                                        JsonArray headersArray = new JsonArray();
                                        for (Map.Entry<String, JsonElement> hdrEntry : serverEntry.getAsJsonObject("headers").entrySet()) {
                                            JsonObject hdrItem = new JsonObject();
                                            hdrItem.addProperty("key", hdrEntry.getKey());
                                            hdrItem.addProperty("value", hdrEntry.getValue().getAsString());
                                            headersArray.add(hdrItem);
                                        }
                                        serverEntry.add("headers", headersArray);
                                    }
                                    serversArray.add(serverEntry);
                                }

                                params.add(MCP_SERVERS_KEY, serversArray);
                                LOG.info("Creating session with " + serversArray.size() + " injected MCP servers (converted from object format)");
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to parse MCP config for session/new injection", e);
                        LOG.info("Creating session (MCP servers configured via CLI or filesystem)");
                    }
                }
            }
        } else {
            LOG.info("Creating session (MCP servers configured via CLI or filesystem)");
        }

        addExtraSessionParams(params);

        JsonObject result;
        try {
            result = sendRequest("session/new", params);
        } catch (AcpException e) {
            // If session creation fails with an authentication error, reset initialized state
            // and destroy the process. This ensures that the next attempt starts a fresh
            // process that can pick up new authentication credentials.
            String msg = e.getMessage().toLowerCase();
            if (msg.contains("auth") || msg.contains("authenticated") || msg.contains("sign in")) {
                LOG.info("Authentication required for session/new - resetting process");
                lastFailureWasAuth = true; // Mark as auth failure to prevent auto-restart
                initialized = false;
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            } else {
                lastFailureWasAuth = false; // Non-auth failure, allow auto-restart
            }
            throw e;
        }

        currentSessionId = result.get(SESSION_ID).getAsString();

        // Clear auth failure flag on successful session creation
        lastFailureWasAuth = false;

        // Parse available models
        parseAvailableModels(result);

        // Inject startup instructions into the conversation via session/message.
        // This is the primary mechanism for agents like Junie that read in-conversation context.
        // Skip for agents that don't support session/message (e.g. OpenCode).
        String instructions = agentConfig.getSessionInstructions();
        sendSessionMessageIfSupported(instructions);
        if (instructions != null && !instructions.isEmpty() && agentConfig.supportsSessionMessage()) {
            LOG.info("Sent startup instructions via session/message (" + instructions.length() + " chars)");
        }

        LOG.info("ACP session created: " + currentSessionId + " with " +
            (availableModels != null ? availableModels.size() : 0) + " models");
        return currentSessionId;
    }

    private void parseAvailableModels(JsonObject result) {
        if (result.has("models")) {
            JsonObject modelsObj = result.getAsJsonObject("models");
            List<Model> models = new ArrayList<>();
            if (modelsObj.has("availableModels")) {
                Set<String> seen = new java.util.HashSet<>();
                for (JsonElement elem : modelsObj.getAsJsonArray("availableModels")) {
                    JsonObject m = elem.getAsJsonObject();
                    Model model = parseModel(m);
                    if (seen.add(model.getId())) {
                        models.add(model);
                    }
                }
            }
            // Single atomic assignment to the field — prevents partial-read races
            availableModels = models;
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
        if (availableModels != null && !availableModels.isEmpty()) {
            boolean supported = false;
            for (Model m : availableModels) {
                if (m.getId().equalsIgnoreCase(modelId)) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                LOG.warn("Requested model " + modelId + " is not supported by the agent. Available models: " +
                    availableModels.stream().map(Model::getId).collect(java.util.stream.Collectors.joining(", ")));
                return;
            }
        }
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
     *
     * <p>Synchronized to prevent concurrent {@link #createSession} calls from two threads
     * racing inside {@link #parseAvailableModels}, which could produce duplicate model entries.
     * mess
     */
    @NotNull
    public synchronized List<Model> listModels() throws AcpException {
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
                             @Nullable Consumer<SessionUpdate> onUpdate)
        throws AcpException {
        return sendPrompt(sessionId, prompt, model, references, onChunk, onUpdate, null);
    }

    /**
     * Send a prompt with full control over ACP session/update notifications.
     *
     * @param onChunk   receives text chunks for streaming display
     * @param onUpdate  receives structured update events for plan events, tool calls, etc.
     * @param onRequest called each time a session/prompt RPC request is sent (including retries)
     */
    @Override
    public @NotNull String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                                      @Nullable String model, @Nullable List<ResourceReference> references,
                                      @Nullable Consumer<String> onChunk,
                                      @Nullable Consumer<SessionUpdate> onUpdate,
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
                deniedToolCallIds.clear();
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
                    currentPrompt = "The previous tool calls were denied. Please continue with the task using the correct tools with '"
                        + effectiveMcpPrefix + "' prefix.";
                    continue;
                }

                if (builtInActionDeniedDuringTurn) {
                    LOG.info("Turn ended with denied tools after " + retryCount + " retries - giving up");
                }

                return stopReason;
            }
        } finally {
            notificationListeners.remove(listener);
        }
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
                                                         @Nullable Consumer<SessionUpdate> onUpdate) {
        return notification -> {
            String method = notification.has(METHOD) ? notification.get(METHOD).getAsString() : "";
            // Handle both session/update (Copilot) and session/chunk (Claude CLI, Junie)
            if (!"session/update".equals(method) && !"session/chunk".equals(method)) return;

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
                                     @Nullable Consumer<SessionUpdate> onUpdate) {
        String updateType = update.has("sessionUpdate") ? update.get("sessionUpdate").getAsString() : "";
        LOG.debug("sendPrompt: received update type=" + updateType);
        lastActivityTimestamp = System.currentTimeMillis();

        if (AgentClient.SessionUpdateType.AGENT_MESSAGE_CHUNK.value().equals(updateType)
            || AgentClient.SessionUpdateType.MESSAGE_CHUNK.value().equals(updateType)
            || AgentClient.SessionUpdateType.TEXT_CHUNK.value().equals(updateType)) {
            handleAgentMessageChunk(update, onChunk);
        }

        if (onUpdate == null) return;
        AgentClient.SessionUpdateType type = AgentClient.SessionUpdateType.fromString(updateType);
        if (type == null) return;

        switch (type) {
            case TOOL_CALL -> handleToolCallEvent(update, onUpdate);
            case TOOL_CALL_UPDATE -> handleToolCallUpdateEvent(update, onUpdate);
            case AGENT_THOUGHT -> {
                String text = extractContentText(update);
                if (text != null && !text.isEmpty()) onUpdate.accept(new SessionUpdate.AgentThought(text));
            }
            case PLAN -> onUpdate.accept(new SessionUpdate.Plan(extractPlanEntries(update)));
            default -> { /* AGENT_MESSAGE_CHUNK handled above; TURN_USAGE and BANNER come from Claude clients */ }
        }
    }

    private void handleAgentMessageChunk(@NotNull JsonObject update, @Nullable Consumer<String> onChunk) {
        if (onChunk == null) return;
        String text = extractContentText(update);
        if (text != null) {
            onChunk.accept(text);
        }
    }

    /**
     * Handle tool_call events. OpenCode sends arguments in tool_call_update, not initial tool_call,
     * so we defer emitting the ToolCall until we receive an update with populated arguments.
     */
    protected void handleToolCallEvent(@NotNull JsonObject update, @NotNull Consumer<SessionUpdate> onUpdate) {
        // Emit tool call event immediately (default behavior)
        onUpdate.accept(buildToolCallEvent(update));
    }

    /**
     * Handle tool_call_update events.
     */
    protected void handleToolCallUpdateEvent(@NotNull JsonObject update, @NotNull Consumer<SessionUpdate> onUpdate) {
        // Emit tool call update event
        onUpdate.accept(buildToolCallUpdateEvent(update));
    }

    @NotNull
    protected SessionUpdate.ToolCall buildToolCallEvent(@NotNull JsonObject update) {
        LOG.info("[ACP tool_call event] raw: " + update);
        String toolCallId = update.has(TOOL_CALL_ID_KEY) ? update.get(TOOL_CALL_ID_KEY).getAsString() : "";
        String toolId = getToolId(update);
        // Use protocol's kind if present and specific, otherwise get from tool registry.
        // Some ACP implementations (e.g., OpenCode) send "kind":"other" for all tools.
        String kindStr = update.has(KIND_KEY) ? update.get(KIND_KEY).getAsString() : null;
        SessionUpdate.ToolKind kind = kindStr != null
            ? SessionUpdate.ToolKind.fromString(kindStr)
            : SessionUpdate.ToolKind.OTHER;
        if (kind == SessionUpdate.ToolKind.OTHER && registry != null) {
            ToolDefinition tool = registry.findById(toolId);
            if (tool != null) {
                kind = SessionUpdate.ToolKind.fromCategory(tool.category());
            }
        }
        String args = extractAcpArguments(update);
        List<String> filePaths = extractFilePaths(update, toolId);
        String agentType = extractSubAgentField(args, AGENT_TYPE_KEY);

        // Update active agent label in settings if a sub-agent is active
        if (agentType != null && !agentType.isEmpty()) {
            agentSettings.setActiveAgentLabel(agentType);
        }

        String subAgentDesc = agentType != null ? extractSubAgentField(args, DESCRIPTION) : null;
        String subAgentPrompt = agentType != null ? extractSubAgentField(args, PROMPT) : null;
        LOG.info("[ACP tool_call event] extracted: id=" + toolCallId + ", toolId=" + toolId + ", kind=" + kind + ", args=" + args);

        // Allow subclasses to extract and process additional metadata from the tool call
        onToolCallEventReceived(toolCallId, update, args);

        return new SessionUpdate.ToolCall(toolCallId, toolId, kind, args, filePaths, agentType, subAgentDesc, subAgentPrompt);
    }

    /**
     * Hook for subclasses to extract and process metadata from tool call events.
     * For example, Kiro extracts {@code __tool_use_purpose} from the arguments
     * to provide as a description in the tool call update.
     *
     * @param toolCallId the ID of the tool call
     * @param update     the raw tool_call event JSON
     * @param argsJson   the extracted tool arguments as JSON string (may be null)
     */
    protected void onToolCallEventReceived(@NotNull String toolCallId, @NotNull JsonObject update,
                                           @Nullable String argsJson) {
        // Default implementation does nothing
    }

    /**
     * Hook for subclasses to format error data from JSON-RPC error responses.
     * Base implementation returns the message as-is.
     * Subclasses can override to add client-specific error formatting.
     *
     * @param errorData the error data JSON object
     * @param message   the extracted error message
     * @return formatted error message
     */
    protected String formatErrorData(@NotNull JsonObject errorData, @NotNull String message) {
        return message;
    }

    /**
     * Extracts a string field from an already-serialised JSON arguments string, or {@code null}.
     */
    @Nullable
    private static String extractSubAgentField(@Nullable String argsJson, @NotNull String field) {
        if (argsJson == null || argsJson.isEmpty()) return null;
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(argsJson);
            if (!parsed.isJsonObject()) return null;
            com.google.gson.JsonElement el = parsed.getAsJsonObject().get(field);
            return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @NotNull
    protected SessionUpdate.ToolCallUpdate buildToolCallUpdateEvent(@NotNull JsonObject update) {
        LOG.info("[ACP tool_call_update event] raw: " + update);
        String toolCallId = update.has(TOOL_CALL_ID_KEY) ? update.get(TOOL_CALL_ID_KEY).getAsString() : "";
        SessionUpdate.ToolCallStatus status = SessionUpdate.ToolCallStatus.fromString(update.has(STATUS_KEY) ? update.get(STATUS_KEY).getAsString() : null);
        String result = extractAcpResult(update);
        String error = update.has(ERROR) ? update.get(ERROR).getAsString() : null;
        int resultLen = result != null ? result.length() : 0;

        // If the tool call was denied by our permission system, ensure the UI clearly shows it
        String description = null;
        if (deniedToolCallIds.contains(toolCallId)) {
            description = "(Denied)";
            if (status == SessionUpdate.ToolCallStatus.COMPLETED) {
                // If it was denied but somehow marked completed, force it to failed status for the UI
                status = SessionUpdate.ToolCallStatus.FAILED;
            }
        }

        LOG.info("[ACP tool_call_update event] extracted: id=" + toolCallId + ", status=" + status + ", resultLen=" + resultLen + ", result=" + (result != null ? result.substring(0, Math.min(200, result.length())) : null));
        if (resultLen == 0 && error == null) {
            LOG.warn("Tool update with empty result: " + update);
        }
        return new SessionUpdate.ToolCallUpdate(toolCallId, status, result, error, description);
    }

    @Nullable
    protected String extractAcpArguments(@NotNull JsonObject update) {
        for (String key : new String[]{ARGUMENTS_KEY, INPUT_KEY, RAW_INPUT_KEY}) {
            if (!update.has(key)) continue;
            com.google.gson.JsonElement el = update.get(key);
            if (el.isJsonPrimitive()) return el.getAsString();
            if (el.isJsonObject() || el.isJsonArray()) return gson.toJson(el);
        }
        // Fallback: look in content for JSON-formatted arguments (common for Junie)
        if (update.has(CONTENT)) {
            String text = extractContentText(update);
            if (text != null && !text.isEmpty()) {
                String trimmed = text.trim();
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    @NotNull
    private List<String> extractFilePaths(@NotNull JsonObject update, @NotNull String title) {
        List<String> paths = new java.util.ArrayList<>();
        if (update.has("locations")) {
            com.google.gson.JsonArray locations = update.getAsJsonArray("locations");
            for (com.google.gson.JsonElement loc : locations) {
                if (loc.isJsonObject()) {
                    com.google.gson.JsonElement path = loc.getAsJsonObject().get("path");
                    if (path != null && path.isJsonPrimitive()) paths.add(path.getAsString());
                }
            }
        }
        if (paths.isEmpty()) {
            // Look into arguments
            String argsJson = extractAcpArguments(update);
            if (argsJson != null) {
                for (String key : new String[]{"path", "file", "filename", "filepath"}) {
                    String val = extractSubAgentField(argsJson, key);
                    if (val != null && !val.isEmpty()) {
                        paths.add(val);
                        break;
                    }
                }
            }
        }
        if (paths.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:Creating|Writing|Editing|Reading|Opening|Viewing)\\s+(.+\\.\\w+)")
                .matcher(title);
            if (m.find()) paths.add(m.group(1));
        }
        return paths;
    }

    @Nullable
    protected String extractAcpResult(@NotNull JsonObject update) {
        // Check for agent-specific result extraction first
        String agentSpecificResult = extractAgentSpecificResult(update);
        if (agentSpecificResult != null) {
            return agentSpecificResult;
        }

        // Try various keys that different agents use for tool results
        for (String key : new String[]{RESULT, CONTENT, OUTPUT_KEY, TOOL_RESULT_KEY, "result_text", "resultText",
            "text", "response", "data", "value", "message", "rawInput"}) {
            if (!update.has(key)) continue;
            if (CONTENT.equals(key)) return extractContentText(update);
            com.google.gson.JsonElement el = update.get(key);
            if (el.isJsonPrimitive()) return el.getAsString();
            if (el.isJsonObject() || el.isJsonArray()) return gson.toJson(el);
        }
        return null;
    }

    /**
     * Hook for agent-specific result extraction.
     * Base implementation returns null; subclasses can override for custom formats.
     *
     * @param update the tool call update JSON
     * @return extracted result or null if no agent-specific format found
     */
    @Nullable
    protected String extractAgentSpecificResult(@NotNull JsonObject update) {
        return null;
    }

    /**
     * Send a message via session/message if the agent supports it.
     * This is the common pattern used for startup instructions and permission denial messages.
     *
     * @param message the message to send, or null/empty to skip
     */
    protected void sendSessionMessageIfSupported(@Nullable String message) {
        if (message != null && !message.isEmpty() && agentConfig.supportsSessionMessage()) {
            sendPromptMessage(message);
        }
    }

    @Nullable
    protected String extractContentText(@NotNull JsonObject update) {
        if (!update.has(CONTENT)) return null;
        com.google.gson.JsonElement el = update.get(CONTENT);
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonObject()) return extractTextFromObject(el.getAsJsonObject());
        if (el.isJsonArray()) return extractTextFromArray(el.getAsJsonArray());
        return null;
    }

    @Nullable
    protected String extractTextFromObject(@NotNull JsonObject obj) {
        com.google.gson.JsonElement text = obj.get("text");
        return text != null ? text.getAsString() : null;
    }

    @Nullable
    protected String extractTextFromArray(@NotNull com.google.gson.JsonArray array) {
        StringBuilder sb = new StringBuilder();
        for (com.google.gson.JsonElement item : array) {
            if (item.isJsonObject()) {
                JsonObject itemObj = item.getAsJsonObject();
                // Direct text field
                if (itemObj.has("text")) {
                    sb.append(itemObj.get("text").getAsString());
                }
                // Nested content structure: content[].content.text
                else if (itemObj.has(CONTENT)) {
                    JsonElement nested = itemObj.get(CONTENT);
                    if (nested.isJsonObject()) {
                        JsonObject nestedObj = nested.getAsJsonObject();
                        if (nestedObj.has("text")) {
                            sb.append(nestedObj.get("text").getAsString());
                        }
                    }
                }
            } else if (item.isJsonPrimitive()) {
                sb.append(item.getAsString());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @NotNull
    private List<SessionUpdate.Plan.PlanEntry> extractPlanEntries(@NotNull JsonObject update) {
        if (!update.has("entries")) return List.of();
        List<SessionUpdate.Plan.PlanEntry> result = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement el : update.getAsJsonArray("entries")) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String content = obj.has(CONTENT) ? obj.get(CONTENT).getAsString() : "Step";
            String status = obj.has(STATUS_KEY) ? obj.get(STATUS_KEY).getAsString() : "pending";
            String priority = obj.has("priority") ? obj.get("priority").getAsString() : "";
            result.add(new SessionUpdate.Plan.PlanEntry(content, status, priority));
        }
        return result;
    }

    /**
     * Normalize tool name before checking permissions or displaying it.
     */
    @NotNull
    public abstract String getToolId(@NotNull JsonObject toolCall);

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
        StringBuilder fullPrompt = new StringBuilder();
        boolean sendResourceRefs = agentConfig.sendResourceReferences();
        boolean duplicateResources = agentConfig.requiresResourceDuplication();

        if (references != null) {
            for (ResourceReference ref : references) {
                // Some agents (e.g. OpenCode) don't support ACP resource references;
                // for those, skip sending them and rely on inlined content instead.
                // ALSO skip if we're already duplicating content in the text prompt
                // (redundancy requested to be fixed for Junie).
                if (sendResourceRefs && !duplicateResources) {
                    promptArray.add(createResourceReference(ref));
                }

                // Some agents (Copilot, OpenCode, Junie) require resource content to be duplicated
                // in the text prompt, not just sent as references
                if (duplicateResources) {
                    fullPrompt.append("\n\n--- ").append(ref.uri()).append(" ---\n");
                    fullPrompt.append(ref.text()).append("\n");
                }
            }
        }

        // Add text prompt (with duplicated resource content if required)
        if (duplicateResources && fullPrompt.length() > 0) {
            fullPrompt.append("\n").append(prompt);
        } else {
            fullPrompt.append(prompt);
        }

        String finalPromptText = fullPrompt.toString();

        // JUNIE WORKAROUND: If the prompt starts with '/' (or common command patterns),
        // it might be misinterpreted as a slash command. Prepend a space to bypass
        // the parser while keeping it readable.
        if (finalPromptText.trim().startsWith("/") || finalPromptText.startsWith("\n/")) {
            finalPromptText = " " + finalPromptText;
        }

        JsonObject promptContent = new JsonObject();
        promptContent.addProperty("type", "text");
        promptContent.addProperty("text", finalPromptText);
        promptArray.add(promptContent);
        params.add(PROMPT, promptArray);

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
    @Override
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
    @Override
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
            LOG.info("ACP sending raw message: " + json);
            synchronized (writerLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
            LOG.info("ACP raw message sent successfully");
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
        // Don't auto-restart after auth failures - let user fix auth first
        if (lastFailureWasAuth) {
            LOG.info("Skipping auto-restart after authentication failure - user must authenticate first");
            failAllPendingRequests();
            return;
        }

        // Deduplicate: when a process crashes during doInitialize(), both readLoop and the
        // restart thread's catch block call this method for the same crash event.
        // CAS ensures only one of them actually schedules a restart, preventing double-counting.
        if (!restartPending.compareAndSet(false, true)) {
            LOG.debug("ACP restart already scheduled — skipping duplicate request");
            return;
        }

        // Immediately unblock any callers stuck waiting on a response
        failAllPendingRequests();

        String name = agentConfig.getDisplayName();
        if (restartAttempts.get() >= MAX_RESTART_ATTEMPTS) {
            // Reset flag so a manual reconnect from the UI can try again
            restartPending.set(false);
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

        new Thread(() -> {
            // Clear the flag before starting the new process so readLoop of the NEW process
            // can schedule the NEXT restart if needed, without counting it as a duplicate.
            restartPending.set(false);
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
                // If the process crashed during doInitialize(), readLoop already called
                // attemptAutoRestart(). The CAS above ensures only one of the two schedules
                // the next attempt. If the process never started (binary not found), readLoop
                // was never spawned, so this call is the only retry trigger.
                attemptAutoRestart();
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
                new AcpException("Connection lost — please retry your message", null, false, 0, null));
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

    protected void handleErrorResponse(JsonObject msg, long id, CompletableFuture<JsonObject> future) {
        JsonObject error = msg.getAsJsonObject(ERROR);
        int code = error.has("code") ? error.get("code").getAsInt() : 0;
        String message = error.has(MESSAGE) ? error.get(MESSAGE).getAsString() : "Unknown error";
        String data = null;
        Integer httpStatusCode = null;

        LOG.warn("ACP error response for request id=" + id + ": " + gson.toJson(error));

        if (error.has("data") && !error.get("data").isJsonNull()) {
            try {
                JsonElement dataElem = error.get("data");
                if (dataElem.isJsonPrimitive() && dataElem.getAsJsonPrimitive().isString()) {
                    data = dataElem.getAsString();
                } else if (dataElem.isJsonObject()) {
                    // Try to extract structured error information
                    JsonObject dataObj = dataElem.getAsJsonObject();

                    // Extract HTTP status code if present
                    if (dataObj.has("statusCode")) {
                        httpStatusCode = dataObj.get("statusCode").getAsInt();
                    } else if (dataObj.has("status")) {
                        httpStatusCode = dataObj.get("status").getAsInt();
                    }

                    // Extract the actual error message from nested fields
                    String detailedMessage = null;
                    if (dataObj.has("error") && dataObj.get("error").isJsonObject()) {
                        JsonObject errorObj = dataObj.getAsJsonObject("error");
                        if (errorObj.has("message")) {
                            detailedMessage = errorObj.get("message").getAsString();
                        }
                    } else if (dataObj.has("error") && dataObj.get("error").isJsonPrimitive()) {
                        detailedMessage = dataObj.get("error").getAsString();
                    } else if (dataObj.has("message")) {
                        detailedMessage = dataObj.get("message").getAsString();
                    }

                    // Allow subclasses to format error details (e.g., Kiro adds error codes)
                    if (detailedMessage != null && !detailedMessage.isEmpty()) {
                        data = formatErrorData(dataObj, detailedMessage);
                    } else {
                        data = gson.toJson(dataElem);
                    }
                } else {
                    data = gson.toJson(dataElem);
                }
            } catch (Exception e) {
                LOG.debug("Error extracting error data", e);
            }
        }

        StringBuilder fullMessage = new StringBuilder();
        if (httpStatusCode != null) {
            fullMessage.append("[HTTP ").append(httpStatusCode).append("] ");
        }
        if (code != 0) {
            fullMessage.append("(").append(code).append(") ");
        }
        fullMessage.append(message);
        if (data != null && !data.isEmpty()) {
            if (fullMessage.length() > 0) fullMessage.append(": ");
            fullMessage.append(data);
        }

        future.completeExceptionally(new AcpException(fullMessage.toString(), null, false, code, data));
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
                // The CLI rejected our --model flag. Clear the saved model so the auto-restart
                // can launch without it and connect successfully.
                if (line.contains("invalid value for --model") || line.contains("Unknown model:") || line.contains("not found for --model")) {
                    agentConfig.clearSavedModel();
                }
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
        JsonElement reqIdElement = msg.get("id");
        String reqIdString = reqIdElement != null ? reqIdElement.toString().replace("\"", "") : "";
        LOG.info("ACP agent request: " + reqMethod + " id=" + reqIdString);

        if ("session/request_permission".equals(reqMethod)) {
            handlePermissionRequest(reqIdElement, msg.has(PARAMS) ? msg.getAsJsonObject(PARAMS) : null);
        } else {
            sendErrorResponse(reqIdElement, -32601, "Method not supported: " + reqMethod);
        }
    }

    /**
     * Handle permission requests from the Copilot agent.
     * Denies built-in write operations (edit, create), so the agent retries with IntelliJ MCP tools.
     * Auto-approves everything else (MCP tool calls, shell, reads).
     */
    private void handlePermissionRequest(JsonElement reqId, @Nullable JsonObject reqParams) {
        String reqIdStr = reqId != null ? reqId.toString().replace("\"", "") : "";
        String permKind = "";
        String permTitle = "";
        JsonObject toolCall = null;

        if (reqParams != null && reqParams.has(TOOL_CALL_KEY)) {
            toolCall = reqParams.getAsJsonObject(TOOL_CALL_KEY);
            permKind = toolCall.has("kind") ? toolCall.get("kind").getAsString() : "";
            permTitle = toolCall.has(TITLE_KEY) ? toolCall.get(TITLE_KEY).getAsString() : "";
        }
        LOG.info("ACP request_permission: id=" + reqIdStr + " kind=" + permKind + " title=" + permTitle + " params=" + reqParams);
        lastActivityTimestamp = System.currentTimeMillis();
        toolCallsInTurn.incrementAndGet();

        String toolCallId = toolCall != null && toolCall.has(TOOL_CALL_ID_KEY)
            ? toolCall.get(TOOL_CALL_ID_KEY).getAsString() : "";

        if (checkAbuseAndDeny(reqId, reqParams, toolCall, toolCallId)) return;

        String toolId = getToolId(toolCall);
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
            case DENY -> denyPermission(reqId, reqParams, permKind, toolId, toolCallId);
            case ASK -> handleAskPermission(reqId, reqParams, toolId, permKind, toolCallId);
            default -> allowPermission(reqId, reqParams, permKind, toolId);
        }
    }

    /**
     * Check for abuse patterns and deny if detected. Uses tool definitions when available,
     * falls back to built-in write tool set for unknown tools.
     * <p>
     * Three checks in priority order:
     * <ol>
     *   <li>Tool-specific abuse detection via {@link ToolDefinition#detectPermissionAbuse}</li>
     *   <li>Sub-agent denial via {@link ToolDefinition#denyForSubAgent()}</li>
     *   <li>Built-in write tool blocking for sub-agents (fallback for unknown tools)</li>
     * </ol>
     */
    private boolean checkAbuseAndDeny(JsonElement reqId, @Nullable JsonObject reqParams,
                                      @Nullable JsonObject toolCall, String toolCallId) {
        String toolId = getToolId(toolCall);
        ToolDefinition tool = registry != null ? registry.findById(toolId) : null;

        // 1. Tool-specific abuse detection (e.g., RunCommandTool detects shell abuse)
        if (tool != null) {
            String abuse = tool.detectPermissionAbuse(toolCall);
            if (abuse != null) {
                denyForAbuse(reqId, reqParams, "Tool abuse (" + toolId + "): " + abuse,
                    RUN_COMMAND_ABUSE_PREFIX + abuse, toolCallId);
                return true;
            }
        } else {
            // Fallback: check command abuse on raw JSON for unregistered tools (e.g., bash built-in)
            String commandAbuse = detectCommandAbuse(toolCall);
            if (commandAbuse != null) {
                denyForAbuse(reqId, reqParams, "run_command abuse: " + commandAbuse,
                    RUN_COMMAND_ABUSE_PREFIX + commandAbuse, toolCallId);
                return true;
            }
        }

        // 2. Sub-agent denial via tool definition
        if (subAgentActive && tool != null && tool.denyForSubAgent()) {
            denyForAbuse(reqId, reqParams, "Sub-agent denied: " + toolId,
                GIT_WRITE_ABUSE_PREFIX + toolId, toolCallId);
            return true;
        }

        // 3. Fallback: block built-in write/execute tools for sub-agents
        if (subAgentActive) {
            String writeAbuse = detectSubAgentWriteTool(toolCall);
            if (writeAbuse != null) {
                denyForAbuse(reqId, reqParams, "Sub-agent write tool: " + writeAbuse,
                    SUBAGENT_WRITE_ABUSE_PREFIX + writeAbuse, toolCallId);
                return true;
            }
        }

        return false;
    }

    /**
     * Deny a permission request for a detected abuse pattern, sending pre-rejection guidance first.
     */
    private void denyForAbuse(JsonElement reqId, @Nullable JsonObject reqParams,
                              String logSuffix, String abusePrefixedKind, String toolCallId) {
        String rejectOptionId = findRejectOption(reqParams);
        LOG.info("ACP request_permission: DENYING " + logSuffix);
        Map<String, Object> retryParams = buildRetryParams(abusePrefixedKind);
        String retryMessage = (String) retryParams.get(MESSAGE);
        sendSessionMessageIfSupported(retryMessage);
        builtInActionDeniedDuringTurn = true;
        if (toolCallId != null && !toolCallId.isEmpty()) {
            deniedToolCallIds.add(toolCallId);
        }
        sendPermissionResponse(reqId, rejectOptionId);
    }

    /**
     * Handle a DENY permission: send guidance before rejecting so the agent can retry.
     */
    private void denyPermission(JsonElement reqId, @Nullable JsonObject reqParams,
                                String permKind, String toolId, String toolCallId) {
        String rejectOptionId = findRejectOption(reqParams);
        LOG.info("ACP request_permission: DENYING " + permKind + TOOL_PREFIX + toolId + "), option=" + rejectOptionId);
        Map<String, Object> retryParams = buildRetryParams(permKind);
        String retryMessage = (String) retryParams.get(MESSAGE);
        sendSessionMessageIfSupported(retryMessage);
        builtInActionDeniedDuringTurn = true;
        if (toolCallId != null && !toolCallId.isEmpty()) {
            deniedToolCallIds.add(toolCallId);
        }
        sendPermissionResponse(reqId, rejectOptionId);
    }

    /**
     * Handle an ASK permission: prompt the UI and block until user responds (120s timeout).
     */
    private void handleAskPermission(JsonElement reqId, @Nullable JsonObject reqParams,
                                     String toolId, String permKind, String toolCallId) {
        var listener = permissionRequestListener.get();
        if (listener == null) {
            LOG.warn("ACP request_permission: ASK for " + toolId + " but no listener — auto-denying");
            if (toolCallId != null && !toolCallId.isEmpty()) {
                deniedToolCallIds.add(toolCallId);
            }
            sendPermissionResponse(reqId, findRejectOption(reqParams));
            return;
        }
        CompletableFuture<PermissionResponse> future = new CompletableFuture<>();
        ToolDefinition toolEntry = registry != null ? registry.findById(toolId) : null;
        String displayName = toolEntry != null ? toolEntry.displayName() : permKind;
        JsonObject toolArgs = extractToolArgs(reqParams);
        String contextJson = buildPermissionContextJson(toolId, toolArgs, displayName);

        String reqIdStr = reqId != null ? reqId.toString().replace("\"", "") : "";
        listener.accept(new PermissionRequest(reqIdStr, toolId, displayName, contextJson, future::complete));
        PermissionResponse response;
        try {
            response = future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.info("ACP request_permission: ASK timed out / cancelled for " + toolId + " — denying");
            response = PermissionResponse.DENY;
        }
        dispatchPermissionResponse(response, reqId, reqParams, toolId, toolCallId);
    }

    @Nullable
    private JsonObject extractToolArgs(@Nullable JsonObject reqParams) {
        if (reqParams == null || !reqParams.has(TOOL_CALL_KEY)) return null;
        JsonObject toolCallJson = reqParams.getAsJsonObject(TOOL_CALL_KEY);
        for (String wrapper : new String[]{ARGUMENTS_KEY, INPUT_KEY, PARAMS}) {
            if (toolCallJson.has(wrapper) && toolCallJson.get(wrapper).isJsonObject()) {
                return toolCallJson.getAsJsonObject(wrapper);
            }
        }
        return null;
    }

    private String buildPermissionContextJson(String toolId, @Nullable JsonObject toolArgs, String displayName) {
        ToolDefinition def = registry != null ? registry.findById(toolId) : null;
        String resolvedQuestion = def != null ? def.resolvePermissionQuestion(toolArgs) : null;
        JsonObject context = new JsonObject();
        context.addProperty("question", resolvedQuestion != null ? resolvedQuestion
            : "Can I use " + displayName + "?");
        if (toolArgs != null) context.add("args", toolArgs);
        return context.toString();
    }

    private void dispatchPermissionResponse(PermissionResponse response, JsonElement reqId,
                                            @Nullable JsonObject reqParams, String toolId, String toolCallId) {
        switch (response) {
            case ALLOW_SESSION -> {
                sessionAllowedTools.add(toolId);
                LOG.info("ACP request_permission: ASK approved for session for " + toolId);
                sendPermissionResponse(reqId, findAllowOption(reqParams));
            }
            case ALLOW_ONCE -> {
                LOG.info("ACP request_permission: ASK approved (once) for " + toolId);
                sendPermissionResponse(reqId, findAllowOption(reqParams));
            }
            default -> {
                LOG.info("ACP request_permission: ASK denied by user for " + toolId);
                builtInActionDeniedDuringTurn = true;
                if (toolCallId != null && !toolCallId.isEmpty()) {
                    deniedToolCallIds.add(toolCallId);
                }
                sendPermissionResponse(reqId, findRejectOption(reqParams));
            }
        }
    }

    /**
     * Handle an ALLOW permission: auto-approve the tool call.
     */
    private void allowPermission(JsonElement reqId, @Nullable JsonObject reqParams,
                                 String permKind, String toolId) {
        String allowOptionId = findAllowOption(reqParams);
        LOG.info("ACP request_permission: auto-approving " + permKind + TOOL_PREFIX + toolId + "), option=" + allowOptionId);
        sendPermissionResponse(reqId, allowOptionId);
    }


    /**
     * @return the list of well-known built-in agent tool IDs that should be excluded
     * from the agent session when the profile enables built-in tool exclusion.
     */
    protected List<String> getDuplicatedTools() {
        return List.of();
    }

    /**
     * Look up the effective ToolPermission for a tool call.
     * For file tools, checks inside/outside-project sub-permission when a path is present.
     */
    protected ToolPermission resolveEffectivePermission(String toolId, @NotNull JsonObject toolCall) {
        // Deny agent built-in tools via permission system when configured.
        // This forces agents to use IntelliJ MCP tools instead of their built-ins (view, edit, bash, etc.)
        // IMPORTANT: We only deny if the call is "external" (no MCP server prefix),
        // to avoid blocking our own plugin tools that might share the same base ID (like read_file).
        if (agentConfig.denyBuiltInToolsViaPermissions()
            && getDuplicatedTools().contains(toolId)) {
            LOG.info("ACP request_permission: denying built-in tool " + toolId + " (forcing MCP alternative)");
            return ToolPermission.DENY;
        }

        ToolDefinition entry = registry != null ? registry.findById(toolId) : null;

        // Path-based sub-permissions for file tools (ceiling enforced by AgentSettings)
        if (entry != null && entry.supportsPathSubPermissions() && toolCall != null) {
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
        for (String wrapper : new String[]{ARGUMENTS_KEY, INPUT_KEY, PARAMS}) {
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

    /**
     * Detect if run_command or the bash built-in tool is being abused to do something
     * we have a dedicated tool for. Returns abuse type if detected, null otherwise.
     */
    private String detectCommandAbuse(JsonObject toolCall) {
        if (toolCall == null) return null;

        String toolName = toolCall.has("name") ? toolCall.get("name").getAsString() : "";
        String permKind = toolCall.has("kind") ? toolCall.get("kind").getAsString() : "";
        String toolId = getToolId(toolCall);

        boolean isRunCommand = "run_command".equals(toolId);
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
        String p = logMcpPrefix; // shorthand for tool name prefixing in logs/messages
        String instruction;

        // Specific guidance for excluded built-in tools
        if (getDuplicatedTools().contains(deniedKind)) {
            instruction = "⚠ Don't use built-in '" + deniedKind + "' — it bypasses " +
                "IntelliJ's editor buffer and may cause desync. Use '" + p + "' prefixed tools instead: " +
                "'" + p + "intellij_read_file' to read files, " +
                "'" + p + "edit_text' for surgical edits, " +
                "'" + p + "create_file' to create files, " +
                "'" + p + "run_command' for shell commands. " +
                "These tools write through IntelliJ's Document API (undo/redo, live buffers, no desync).";
        } else if (deniedKind.startsWith(RUN_COMMAND_ABUSE_PREFIX)) {
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
    private void sendErrorResponse(JsonElement reqId, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty(JSONRPC, "2.0");
        response.add("id", reqId);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty(MESSAGE, message);
        response.add(ERROR, error);
        sendRawMessage(response);
    }

    // ---- End agent request handlers ----

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

    private void sendPermissionResponse(JsonElement reqId, String optionId) {
        JsonObject response = new JsonObject();
        response.addProperty(JSONRPC, "2.0");
        response.add("id", reqId);
        JsonObject result = new JsonObject();
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        outcome.addProperty(OPTION_ID, optionId);
        result.add("outcome", outcome);
        response.add(RESULT, result);
        LOG.info("ACP sending permission response: " + response + " (optionId=" + optionId + ")");
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
