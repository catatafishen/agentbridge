package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlockSerializer;
import com.github.catatafishen.ideagentforcopilot.acp.model.InitializeRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.InitializeResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.NewSessionResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.NewSessionResponseDeserializer;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.acp.transport.JsonRpcErrorCodes;
import com.github.catatafishen.ideagentforcopilot.acp.transport.JsonRpcException;
import com.github.catatafishen.ideagentforcopilot.acp.transport.JsonRpcTransport;
import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient;
import com.github.catatafishen.ideagentforcopilot.agent.AgentPromptException;
import com.github.catatafishen.ideagentforcopilot.agent.AgentSessionException;
import com.github.catatafishen.ideagentforcopilot.agent.AgentStartException;
import com.github.catatafishen.ideagentforcopilot.bridge.McpServerJarLocator;
import com.github.catatafishen.ideagentforcopilot.bridge.SessionOption;
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.github.catatafishen.ideagentforcopilot.services.McpServerControl;
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public abstract class AcpClient extends AbstractAgentClient {

    private static final Logger LOG = Logger.getInstance(AcpClient.class);

    private static final long INITIALIZE_TIMEOUT_SECONDS = 90;
    private static final long SESSION_TIMEOUT_SECONDS = 30;
    private static final long AUTH_TIMEOUT_SECONDS = 30;
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private static final int PROTOCOL_VERSION = 1;
    private static final String CLIENT_NAME = "AgentBridge";
    private static final String CLIENT_TITLE = "AgentBridge for IntelliJ";
    private static final String CLIENT_VERSION = "2.0.0";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_UPDATE = "update";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_ARGUMENTS = "arguments";
    private static final String KEY_OPTIONS = "options";
    private static final String KEY_OPTION_ID = "optionId";
    private static final String KEY_OUTCOME = "outcome";
    private static final String KEY_TOOL_CALL_ID = "toolCallId";
    private static final String VALUE_SELECTED = "selected";
    private static final String VALUE_ALLOW_ONCE = "allow_once";
    private static final String VALUE_DENY_ONCE = "deny_once";
    private static final String VALUE_REJECT_ONCE = "reject_once";
    private static final String KEY_TOOL_CALL = "toolCall";
    private static final Set<String> ALLOWED_BUILT_IN_TOOLS = Set.of("web_fetch", "web_search");

    protected final Gson gson = new GsonBuilder()
        .registerTypeAdapter(NewSessionResponse.class, new NewSessionResponseDeserializer())
        .registerTypeHierarchyAdapter(ContentBlock.class, new ContentBlockSerializer())
        .create();
    protected final JsonRpcTransport transport = new JsonRpcTransport();
    protected final Project project;
    private final AcpFileSystemHandler fsHandler;
    private final AcpTerminalHandler terminalHandler;

    private @Nullable Process agentProcess;
    private @Nullable InitializeResponse capabilities;
    private @Nullable String currentSessionId;
    private @Nullable String launchCwd;
    /**
     * Tracks the resume session ID requested in the current launch cycle.
     * Set at the start of {@link #createSession}, used by {@link #loadSession} and
     * {@link #enableInjectionFallback}.
     */
    private @Nullable String requestedResumeId;
    private final List<Model> availableModels = new ArrayList<>();
    private final List<AbstractAgentClient.AgentMode> availableModes = new ArrayList<>();
    private @Nullable String currentModeSlug = null;
    private @Nullable String currentModelId = null;
    private @Nullable String currentAgentSlug = null;
    private final List<AbstractAgentClient.AgentConfigOption> availableConfigOptions = new ArrayList<>();
    private volatile @Nullable Consumer<SessionUpdate> updateConsumer;
    /**
     * Conversation history replayed by the agent during {@code session/load}.
     * Non-null and non-empty when the agent successfully restored a session with
     * history replay via {@code session/update} notifications.
     * Null when no session was loaded or the agent didn't replay any history.
     * The UI layer can use this to determine whether injection is needed.
     */
    private volatile @Nullable List<SessionUpdate> loadedSessionHistory;
    /**
     * Tracks pending {@code session/request_permission} request IDs so we can respond with
     * {@code {outcome: "cancelled"}} when {@link #cancelSession} is called.
     * Per ACP spec, the Client MUST respond to all pending permission requests with the
     * cancelled outcome when a turn is cancelled.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, JsonElement> pendingPermissionRequests =
        new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Nanotime of the last {@code session/update} notification received; used for inactivity detection.
     */
    private volatile long lastActivityNanos = System.nanoTime();

    private final AcpMessageParser messageParser = new AcpMessageParser(
        new AcpMessageParser.Delegate() {
            @Override
            public String resolveToolId(String t) {
                return AcpClient.this.resolveToolId(t);
            }

            @Override
            public @Nullable JsonObject parseToolCallArguments(@NotNull JsonObject p) {
                return AcpClient.this.parseToolCallArguments(p);
            }

            @Override
            public @Nullable String extractSubAgentType(@NotNull JsonObject p, @NotNull String t, @Nullable JsonObject a) {
                return AcpClient.this.extractSubAgentType(p, t, a);
            }
        },
        this::displayName
    );

    protected AcpClient(Project project) {
        this.project = project;
        this.fsHandler = new AcpFileSystemHandler(project);
        this.terminalHandler = new AcpTerminalHandler(project);
    }

    // ═══════════════════════════════════════════════════
    // Final protocol methods — subclasses cannot override
    // ═══════════════════════════════════════════════════

    private static final int LOG_MAX_CHARS = 2000;

    private static String truncateForLog(String s) {
        if (s == null || s.length() <= LOG_MAX_CHARS) return s;
        return s.substring(0, LOG_MAX_CHARS) + "... [truncated " + (s.length() - LOG_MAX_CHARS) + " chars]";
    }

    @Override
    public final void start() throws AgentStartException {
        try {
            LOG.info(displayName() + " starting...");
            int mcpPort = resolveMcpPort();
            LOG.info(displayName() + " launching process (MCP port: " + mcpPort + ")");
            agentProcess = launchProcess(mcpPort);
            LOG.info(displayName() + " process launched, starting transport");
            transport.start(agentProcess);
            transport.setDebugLogger(line -> {
                if (McpServerSettings.getInstance(project).isDebugLoggingEnabled()) {
                    LOG.info("[ACP] " + truncateForLog(line));
                }
            });
            LOG.info(displayName() + " transport started, registering handlers");
            registerHandlers();
            LOG.info(displayName() + " handlers registered, initializing");
            try {
                capabilities = initialize();
            } catch (Exception e) {
                LOG.warn(displayName() + " initialization failed: " + e.getMessage(), e);
                throw e;
            }
            LOG.info(displayName() + " initialized, authenticating");
            try {
                authenticate();
            } catch (Exception e) {
                LOG.warn(displayName() + " authentication failed: " + e.getMessage(), e);
                throw e;
            }
            LOG.info(displayName() + " authenticated, fetching models");
            try {
                eagerFetchModels();
            } catch (Exception e) {
                LOG.warn(displayName() + " model fetching failed: " + e.getMessage(), e);
                throw e;
            }
            LOG.info(displayName() + " agent started successfully");
        } catch (Exception e) {
            LOG.warn(displayName() + " startup failed at: " + getStartupStepFromException(e), e);
            stop();
            throw new AgentStartException("Failed to start " + displayName(), e);
        }
    }

    private String getStartupStepFromException(Exception e) {
        StackTraceElement[] stack = e.getStackTrace();
        if (stack.length > 0) {
            String method = stack[0].getMethodName();
            if (method.contains("launch")) return "process launch";
            if (method.contains("start")) return "transport start";
            if (method.contains("initialize")) return "initialization";
            if (method.contains("authenticate")) return "authentication";
            if (method.contains("fetchModels")) return "model fetch";
        }
        return "unknown step";
    }

    @Override
    public String checkAuthentication() {
        if (!isHealthy()) {
            return "Agent not started";
        }
        // For ACP agents, try to create a session to check if authentication is required
        // This catches auth errors early instead of waiting for the first message
        try {
            String cwd = project.getBasePath();
            if (cwd == null) {
                cwd = System.getProperty("user.home");
            }
            createSession(cwd);
            return null; // Auth successful
        } catch (AgentSessionException e) {
            // Check if it's an auth error
            Throwable cause = e;
            while (cause != null) {
                String msg = cause.getMessage();
                if (msg != null && (msg.toLowerCase().contains("auth") || msg.toLowerCase().contains("sign in"))) {
                    return msg;
                }
                cause = cause.getCause();
            }
            // Not an auth error, agent is healthy
            return null;
        }
    }

    @Override
    public com.github.catatafishen.ideagentforcopilot.bridge.AuthMethod getAuthMethod() {
        if (capabilities == null || capabilities.authMethods() == null || capabilities.authMethods().isEmpty()) {
            return null;
        }
        // Return the first auth method from capabilities
        var method = capabilities.authMethods().getFirst();
        LOG.info(displayName() + ": ACP authMethod = " + method + ", id=" + method.id() + ", name=" + method.name());
        var authMethod = new com.github.catatafishen.ideagentforcopilot.bridge.AuthMethod();
        authMethod.setId(method.id());
        authMethod.setName(method.name());
        authMethod.setDescription(method.description());
        return authMethod;
    }

    @Override
    public final void stop() {
        try {
            transport.stop();
        } catch (Exception e) {
            LOG.warn("Transport stop encountered an error; proceeding to kill process", e);
        } finally {
            destroyProcess();
            agentProcess = null;
            capabilities = null;
            currentSessionId = null;
            launchCwd = null;
            availableModels.clear();
            availableModes.clear();
            currentModeSlug = null;
            currentModelId = null;
            currentAgentSlug = null;
            availableConfigOptions.clear();
            pendingPermissionRequests.clear();
            terminalHandler.releaseAll();
            loadedSessionHistory = null;
            updateConsumer = null;
        }
    }

    @Override
    public final boolean isConnected() {
        return transport.isAlive() && agentProcess != null && agentProcess.isAlive();
    }

    @Override
    public final String createSession(String cwd) throws AgentSessionException {
        // Reuse the existing session if we already have one for the same working directory.
        // eagerFetchModels() creates a session at startup — avoid a redundant second session/new.
        if (currentSessionId != null && cwd != null && cwd.equals(launchCwd)) {
            LOG.info(displayName() + ": reusing existing session " + currentSessionId);
            return currentSessionId;
        }
        try {
            beforeCreateSession(cwd);
            requestedResumeId = loadResumeSessionId();

            // Per ACP spec, session/load resumes an existing session.
            // Subclasses may override loadSession() for agent-specific behavior
            // (e.g. CopilotClient throws because Copilot doesn't support it).
            if (requestedResumeId != null) {
                try {
                    String loaded = loadSession(cwd, requestedResumeId);
                    if (loadedSessionHistory != null) {
                        // Agent replayed history — it has conversation context.
                        // Disable injection in case it was left enabled from a prior failed load.
                        ActiveAgentManager.setInjectConversationHistory(project, false);
                    } else {
                        // Agent loaded the session but didn't replay any history.
                        // It may not have conversation context — inject as a safety net.
                        LOG.info(displayName() + ": session loaded but no history replayed — enabling injection fallback");
                        enableInjectionFallback(requestedResumeId, supportsSessionResumption());
                    }
                    return loaded;
                } catch (Exception e) {
                    LOG.warn(displayName() + ": session/load failed for " + requestedResumeId
                        + ", falling back to session/new: " + e.getMessage());
                    enableInjectionFallback(requestedResumeId, supportsSessionResumption());
                }
            }

            // Standard session/new path — creates a fresh session.
            JsonObject params = buildNewSessionParams(cwd);

            CompletableFuture<JsonElement> future = transport.sendRequest("session/new", params);
            JsonElement result = future.get(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.debug(displayName() + ": session/new raw response: " + result);

            NewSessionResponse response = gson.fromJson(result, NewSessionResponse.class);
            LOG.debug(displayName() + ": session/new: " + (response.models() != null ? response.models().size() : 0) + " model(s), "
                + (response.modes() != null ? response.modes().size() : 0) + " mode(s)");

            currentSessionId = response.sessionId();
            processSessionResponse(response);

            onSessionCreated(currentSessionId);
            persistResumeSessionId(currentSessionId);
            return currentSessionId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentSessionException("Session creation interrupted for " + displayName(), e);
        } catch (Exception e) {
            throw new AgentSessionException("Failed to create session for " + displayName(), e);
        }
    }

    private JsonObject buildNewSessionParams(String cwd) {
        int mcpPort = resolveMcpPort();
        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd);
        customizeNewSession(cwd, mcpPort, params);
        return params;
    }

    /**
     * Processes the models, modes, and config options from a session response.
     * Used by both {@code session/new} and {@code session/resume} paths.
     * The caller is responsible for setting {@code currentSessionId} before calling this.
     */
    private void processSessionResponse(NewSessionResponse response) {
        if (response.models() != null) {
            availableModels.clear();
            availableModels.addAll(response.models());
        }

        if (response.currentModelId() != null) {
            currentModelId = response.currentModelId();
        }

        if (response.modes() != null) {
            updateModes(response);
        }

        if (response.configOptions() != null) {
            updateConfigOptions(response);
        }
    }

    private void updateModes(NewSessionResponse response) {
        availableModes.clear();
        if (response.modes() != null) {
            for (NewSessionResponse.AvailableMode m : response.modes()) {
                availableModes.add(new AbstractAgentClient.AgentMode(m.slug(), m.name(), m.description()));
            }
        }
        if (currentModeSlug == null) {
            String reportedMode = response.currentModeId();
            currentModeSlug = reportedMode != null ? reportedMode : defaultModeSlug();
        }
        if (currentAgentSlug == null) {
            currentAgentSlug = defaultAgentSlug();
        }
    }

    private void updateConfigOptions(NewSessionResponse response) {
        availableConfigOptions.clear();
        if (response.configOptions() != null) {
            for (NewSessionResponse.SessionConfigOption opt : response.configOptions()) {
                List<AbstractAgentClient.AgentConfigOptionValue> vals = opt.values() == null ? List.of()
                    : opt.values().stream()
                      .map(v -> new AbstractAgentClient.AgentConfigOptionValue(v.id(), v.label()))
                      .toList();
                String optId = opt.id() != null ? opt.id() : "";
                String label = opt.label() != null ? opt.label() : optId;
                availableConfigOptions.add(
                    new AbstractAgentClient.AgentConfigOption(optId, label, opt.description(), vals, opt.selectedValueId())
                );
            }
        }
        LOG.debug(displayName() + ": session/new: " + availableConfigOptions.size() + " config option(s)");
    }

    /**
     * Loads an existing session by ID, per the ACP {@code session/load} spec.
     * <p>
     * The default implementation checks the {@code loadSession} agent capability advertised
     * during initialization. If supported, sends a {@code session/load} JSON-RPC request.
     * Per the ACP spec, the agent replays conversation history via {@code session/update}
     * notifications and responds with {@code null}.
     * <p>
     * Subclasses may override this to:
     * <ul>
     *   <li>Use an agent-specific variant (e.g. OpenCode's {@code session/resume})</li>
     *   <li>Throw immediately if the agent is known not to support session loading
     *       (e.g. Copilot CLI)</li>
     * </ul>
     *
     * @return the loaded session ID (same as {@code sessionId} param)
     * @throws AgentSessionException if the agent does not support session loading
     * @throws Exception             if the RPC call fails
     * @see <a href="https://agentclientprotocol.com/protocol/session-setup">ACP Session Setup</a>
     */
    protected String loadSession(String cwd, String sessionId) throws Exception {
        if (!supportsSessionResumption()) {
            throw new AgentSessionException(
                displayName() + " does not advertise loadSession capability");
        }
        return sendLoadSessionRequest("session/load", cwd, sessionId);
    }

    /**
     * Whether this agent supports session resumption via {@link #loadSession}.
     * <p>
     * Default: checks if the agent advertised the {@code loadSession} capability
     * during initialization. Subclasses can override to return {@code false}
     * for agents known not to support resumption even if the capability is
     * accidentally advertised, or to return {@code true} for agents that use
     * non-standard resumption methods.
     */
    protected boolean supportsSessionResumption() {
        return capabilities != null
            && capabilities.agentCapabilities() != null
            && Boolean.TRUE.equals(capabilities.agentCapabilities().loadSession());
    }

    /**
     * Sends a session load/resume RPC request and processes the response.
     * Handles all internal state management (currentSessionId, models, modes, etc.).
     * <p>
     * Per ACP spec, the agent may replay conversation history during {@code session/load}
     * via {@code session/update} notifications. This method buffers those notifications
     * into {@link #loadedSessionHistory} so the UI layer can determine whether the agent
     * successfully restored context.
     * <p>
     * Subclasses that override {@link #loadSession} should call this method with the
     * appropriate RPC method name (e.g. {@code "session/resume"} for OpenCode).
     *
     * @param method    the JSON-RPC method name (e.g. {@code "session/load"} or {@code "session/resume"})
     * @param cwd       working directory
     * @param sessionId session to load
     * @return the loaded session ID
     */
    protected final String sendLoadSessionRequest(String method, String cwd, String sessionId) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty(KEY_SESSION_ID, sessionId);
        params.addProperty("cwd", cwd);
        int mcpPort = resolveMcpPort();
        customizeNewSession(cwd, mcpPort, params);

        // Buffer session/update notifications that arrive during session/load.
        // Per ACP spec, the agent replays conversation history via these notifications.
        List<SessionUpdate> loadBuffer = new ArrayList<>();
        updateConsumer = loadBuffer::add;

        LOG.info(displayName() + ": attempting " + method + " for " + sessionId);
        try {
            CompletableFuture<JsonElement> future = transport.sendRequest(method, params);
            JsonElement result = future.get(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.debug(displayName() + ": " + method + " response: " + result);

            // Per ACP spec, session/load response is null (history replayed via session/update).
            // Some agents (e.g. OpenCode's session/resume) return models/modes/configOptions.
            if (result != null && !result.isJsonNull()) {
                NewSessionResponse response = gson.fromJson(result, NewSessionResponse.class);
                processSessionResponse(response);
            }
        } finally {
            updateConsumer = null;
        }

        loadedSessionHistory = loadBuffer.isEmpty() ? null : List.copyOf(loadBuffer);
        LOG.info(displayName() + ": loaded session " + sessionId + " via " + method
            + " (" + loadBuffer.size() + " history update(s) replayed)");

        currentSessionId = sessionId;
        onSessionCreated(sessionId);
        persistResumeSessionId(sessionId);
        return sessionId;
    }

    /**
     * Marks the session history as loaded internally by the agent, even though no
     * {@code session/update} notifications were replayed during {@code session/load}.
     * <p>
     * Some agents (e.g. OpenCode) restore conversation history from their own storage
     * and do not replay it via ACP notifications. Call this from {@link #loadSession}
     * after {@link #sendLoadSessionRequest} to prevent the injection fallback.
     */
    protected void markSessionHistoryLoadedInternally() {
        loadedSessionHistory = List.of();
    }

    /**
     * Enables conversation history injection as a fallback when session loading fails.
     * Shows a notification to the user explaining the limitation if resumption was expected.
     *
     * @param requestedId the session ID that was requested for resumption
     * @param expected    whether resumption was expected to work for this agent
     */
    private void enableInjectionFallback(String requestedId, boolean expected) {
        if (!ActiveAgentManager.getInjectConversationHistory(project)) {
            ActiveAgentManager.setInjectConversationHistory(project, true);
        }

        if (!expected) {
            LOG.info(displayName() + ": session resume not supported — silently enabled injection fallback");
            return;
        }

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
            NotificationGroupManager.getInstance()
                .getNotificationGroup("AgentBridge Notifications")
                .createNotification(
                    displayName() + " session resume not available",
                    "Session load was requested but " + displayName() + " could not resume session "
                        + requestedId + ". "
                        + "Conversation history injection has been enabled as a fallback — "
                        + "a compressed summary of the previous session will be prepended to "
                        + "your first prompt. You can configure this in "
                        + "Settings → IDE Agent → Chat History.",
                    NotificationType.INFORMATION)
                .notify(project));
    }

    @Override
    public @Nullable List<SessionUpdate> getLoadedSessionHistory() {
        return loadedSessionHistory;
    }

    @Override
    public void dropCurrentSession() {
        currentSessionId = null;
    }

    @Override
    public final void cancelSession(String sessionId) {
        // ACP spec: Client MUST respond to all pending session/request_permission
        // requests with the "cancelled" outcome before sending session/cancel.
        cancelPendingPermissionRequests();

        JsonObject params = new JsonObject();
        params.addProperty(KEY_SESSION_ID, sessionId);
        transport.sendNotification("session/cancel", params);
        // Clear the cached session ID so the next createSession() starts a new one
        if (sessionId.equals(currentSessionId)) {
            currentSessionId = null;
        }
    }

    // ── Session resumption helpers ───────────────────────────────────────────

    private @Nullable String loadResumeSessionId() {
        try {
            ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
            return manager.getSettings().getResumeSessionId();
        } catch (Exception e) {
            LOG.warn("Failed to load resume session ID", e);
            return null;
        }
    }

    private void persistResumeSessionId(@Nullable String sessionId) {
        try {
            ActiveAgentManager manager = ActiveAgentManager.getInstance(project);
            manager.getSettings().setResumeSessionId(sessionId);
        } catch (Exception e) {
            LOG.warn("Failed to persist resume session ID", e);
        }
    }

    private int getTurnTimeoutSeconds() {
        return ActiveAgentManager.getInstance(project).getSharedTurnTimeoutSeconds();
    }

    private int getInactivityTimeoutSeconds() {
        return ActiveAgentManager.getInstance(project).getSharedInactivityTimeoutSeconds();
    }

    @Override
    public final PromptResponse sendPrompt(PromptRequest request,
                                           Consumer<SessionUpdate> onUpdate) throws AgentPromptException {
        try {
            long turnStartNanos = System.nanoTime();
            lastActivityNanos = turnStartNanos;
            updateConsumer = onUpdate;
            PromptRequest effectiveRequest = beforeSendPrompt(request);
            JsonObject params = gson.toJsonTree(effectiveRequest).getAsJsonObject();
            LOG.debug(displayName() + ": sending session/prompt, sessionId=" + request.sessionId());
            CompletableFuture<JsonElement> future = transport.sendRequest("session/prompt", params);
            JsonElement result = waitForPromptResult(future, turnStartNanos);
            return gson.fromJson(result, PromptResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentPromptException("Prompt interrupted for " + displayName(), e);
        } catch (Exception e) {
            PromptResponse recovery = tryRecoverPromptException(e);
            if (recovery != null) return recovery;
            String rootMsg = extractRootCauseMessage(e);
            String msg = rootMsg != null
                ? "Prompt failed for " + displayName() + ": " + rootMsg
                : "Prompt failed for " + displayName();
            throw new AgentPromptException(msg, e);
        } finally {
            afterPromptComplete();
        }
    }

    /**
     * Hook called before sending a {@code session/prompt}. Subclasses may override to
     * augment the request (e.g. prepend corrective guidance). Default: returns unchanged.
     */
    protected PromptRequest beforeSendPrompt(PromptRequest request) {
        return request;
    }

    /**
     * Hook called when a non-allowed built-in tool is approved. Subclasses may
     * override to track tool misuse for corrective guidance. Default: no-op.
     *
     * @param toolId       the tool that was approved
     * @param userApproved {@code true} if the user explicitly approved via a prompt;
     *                     {@code false} if the plugin auto-approved without asking
     */
    protected void onBuiltInToolApproved(String toolId, boolean userApproved) {
        // no-op — subclasses like CopilotClient may track for reprimand
    }

    /**
     * Called in the finally block after {@code sendPrompt} completes (success or failure).
     * Default: clears {@code updateConsumer}. Override to retain it (e.g. Kiro sends
     * thought chunks asynchronously after the prompt response).
     */
    protected void afterPromptComplete() {
        updateConsumer = null;
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
     * Walks the cause chain of an exception and returns the most descriptive non-null message.
     * Unwraps {@link java.util.concurrent.ExecutionException} wrappers and strips unhelpful
     * outer messages like "Prompt failed for ..." so the user sees the real reason.
     */
    @Nullable
    private static String extractRootCauseMessage(Throwable e) {
        Throwable current = e;
        String bestMsg = null;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && !msg.isBlank()
                && !msg.startsWith("Prompt failed for ")
                && !msg.startsWith("Prompt interrupted for ")) {
                bestMsg = msg;
            }
            current = current.getCause();
        }
        return bestMsg;
    }

    /**
     * Waits for the {@code session/prompt} response using an inactivity-based deadline.
     * <p>
     * Rather than a hard wall-clock timeout from the time the request was sent (which
     * would prematurely kill legitimately long agentic turns), this method polls in short
     * intervals and only times out when no {@code session/update} notification has arrived
     * for the configured inactivity timeout seconds. As long as the agent keeps streaming
     * chunks — even during a multi-tool, multi-minute turn — the deadline keeps resetting.
     */
    private JsonElement waitForPromptResult(CompletableFuture<JsonElement> future, long turnStartNanos)
        throws InterruptedException, java.util.concurrent.ExecutionException,
        java.util.concurrent.TimeoutException {
        long pollMs = 5_000L;
        long turnDeadlineNanos = turnStartNanos + TimeUnit.SECONDS.toNanos(getTurnTimeoutSeconds());
        long inactivityLimitNanos = TimeUnit.SECONDS.toNanos(getInactivityTimeoutSeconds());
        while (true) {
            long now = System.nanoTime();
            long lastActivity = lastActivityNanos;
            long inactivityDeadlineNanos = lastActivity + inactivityLimitNanos;
            long remainingNanos = Math.min(turnDeadlineNanos, inactivityDeadlineNanos) - now;
            if (remainingNanos <= 0) {
                if (turnDeadlineNanos <= inactivityDeadlineNanos) {
                    long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(now - turnStartNanos);
                    LOG.warn(displayName() + ": turn timeout after " + elapsedSec + "s");
                    throw new java.util.concurrent.TimeoutException(
                        "Agent turn timed out after " + elapsedSec + "s"
                    );
                }
                long silenceSec = TimeUnit.NANOSECONDS.toSeconds(now - lastActivity);
                LOG.warn(displayName() + ": inactivity timeout after " + silenceSec + "s of silence");
                throw new java.util.concurrent.TimeoutException(
                    "Agent inactive for " + silenceSec + "s (no session/update received)"
                );
            }
            long waitMillis = Math.max(1L, Math.min(pollMs, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (future.isDone()) {
                    return future.get();
                }
            }
        }
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
    public final @Nullable String getCurrentModelId() {
        return currentModelId;
    }

    @Override
    public final List<AbstractAgentClient.AgentMode> getAvailableModes() {
        return Collections.unmodifiableList(availableModes);
    }

    @Override
    public final @Nullable String getCurrentModeSlug() {
        return currentModeSlug != null ? currentModeSlug : defaultModeSlug();
    }

    @Override
    public final void setCurrentModeSlug(@Nullable String slug) {
        currentModeSlug = slug;
    }

    @Override
    public final @Nullable String getCurrentAgentSlug() {
        return currentAgentSlug != null ? currentAgentSlug : defaultAgentSlug();
    }

    @Override
    public final void setCurrentAgentSlug(@Nullable String slug) {
        currentAgentSlug = slug;
    }

    @Override
    public final List<AbstractAgentClient.AgentConfigOption> getAvailableConfigOptions() {
        return Collections.unmodifiableList(availableConfigOptions);
    }

    /**
     * Bridges ACP config options (from session/new) to the {@link SessionOption} type used by
     * the UI toolbar dropdowns. Called by the toolbar on every repaint — returns the live list.
     *
     * <p>Config options whose values exactly cover the available model list are suppressed when
     * the session already provided a proper {@code models} array. Such options are a fallback
     * for clients that don't advertise models at session start; exposing them alongside the
     * primary model selector would render a duplicate model dropdown.</p>
     */
    @Override
    @NotNull
    public final List<SessionOption> listSessionOptions() {
        Set<String> sessionModelIds = availableModels.isEmpty()
            ? Collections.emptySet()
            : availableModels.stream().map(Model::id).collect(java.util.stream.Collectors.toSet());

        return availableConfigOptions.stream()
            .filter(opt -> {
                if (sessionModelIds.isEmpty()) return true;
                Set<String> optValueIds = opt.values().stream()
                    .map(AbstractAgentClient.AgentConfigOptionValue::id)
                    .collect(java.util.stream.Collectors.toSet());
                return !sessionModelIds.equals(optValueIds) && !sessionModelIds.containsAll(optValueIds);
            })
            .map(opt -> {
                List<String> valueIds = opt.values().stream()
                    .map(AbstractAgentClient.AgentConfigOptionValue::id)
                    .toList();
                java.util.Map<String, String> labels = opt.values().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        AbstractAgentClient.AgentConfigOptionValue::id,
                        AbstractAgentClient.AgentConfigOptionValue::label,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
                return new SessionOption(opt.id(), opt.label(), valueIds, labels, opt.selectedValueId());
            })
            .toList();
    }

    /**
     * Delegates to {@link #setConfigOption} so the ACP server is notified.
     */
    @Override
    public final void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        setConfigOption(sessionId, key, value);
    }

    @Override
    public final void setConfigOption(@NotNull String sessionId, @NotNull String configId, @NotNull String valueId) {
        JsonObject params = new JsonObject();
        params.addProperty(KEY_SESSION_ID, sessionId);
        params.addProperty("configId", configId);
        params.addProperty("value", valueId);
        transport.sendRequest("session/set_config_option", params);
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
     * Default: advertises {@code fs.readTextFile}, {@code fs.writeTextFile}, and {@code terminal}
     * capabilities as these are now implemented by the ACP base class.
     * Override to suppress capabilities for agents that reject unknown fields.
     */
    protected InitializeRequest.ClientCapabilities buildClientCapabilities() {
        return InitializeRequest.ClientCapabilities.standard();
    }

    /**
     * Called once, just before the agent process is launched.
     * Override for pre-launch setup such as writing config or agent definition files.
     *
     * @throws IOException if setup fails (causes the launch to abort)
     */
    protected void beforeLaunch(String cwd, int mcpPort) throws IOException {
        // no-op by default
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
     * Called after a session is successfully created. Subclasses can override to detect
     * resume failures or perform post-session setup.
     *
     * @param sessionId the created session ID
     */
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
    @SuppressWarnings("unused") // Overridden by subclasses (CopilotClient, OpenCodeClient)
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
    @SuppressWarnings("java:S1871") // Similar logic in ActiveAgentManager serves different purpose
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

        beforeLaunch(cwd, mcpPort);

        List<String> command = buildCommand(cwd, mcpPort);

        // Resolve the binary to an absolute path so ProcessBuilder can find it even when
        // it's installed via nvm/sdkman/homebrew in a non-standard location. Java's exec()
        // does not search PATH the same way a shell does.
        List<String> resolvedCommand = resolveCommand(command);

        // Check if binary exists before trying to launch
        String binaryPath = resolvedCommand.getFirst();
        File binaryFile = new File(binaryPath);
        if (!binaryPath.contains("/") && !binaryPath.contains("\\")) {
            // Relative name - check if it's in PATH
            String foundPath = com.github.catatafishen.ideagentforcopilot.settings.BinaryDetector.findBinaryPath(binaryPath);
            if (foundPath == null) {
                throw new IOException(displayName() + " binary '" + binaryPath + "' not found in PATH. " +
                    "Please install it or configure the path in Settings → Tools → AgentBridge → " + displayName());
            }
        } else if (!binaryFile.exists()) {
            throw new IOException(displayName() + " binary not found at: " + binaryPath + ". " +
                "Please install it or configure the correct path in Settings → Tools → AgentBridge → " + displayName());
        }

        ProcessBuilder pb = new ProcessBuilder(resolvedCommand);
        pb.directory(new File(cwd));
        pb.redirectErrorStream(false);

        // Merge shell environment (for PATH, etc.)
        pb.environment().putAll(com.github.catatafishen.ideagentforcopilot.settings.ShellEnvironment.getEnvironment());

        // Override with custom environment (these take precedence)
        Map<String, String> env = buildEnvironment(mcpPort, cwd);
        if (!env.isEmpty()) {
            LOG.info("Setting custom environment for " + displayName() + ": " + env);
            pb.environment().putAll(env);
        }

        LOG.info("Launching " + displayName() + ": " + String.join(" ", resolvedCommand));
        LOG.info("Environment size: " + pb.environment().size() + " variables");
        Process process = pb.start();
        AgentProcessRegistry.register(process);
        return process;
    }

    // ─── Per-agent bubble color settings (application-level) ────────────────

    private static final String PROP_AGENT_BUBBLE_COLOR = "agentbridge.client.%s.bubbleColor";

    /**
     * Returns the user-configured bubble color key (a {@link com.github.catatafishen.ideagentforcopilot.ui.ThemeColor}
     * name) for the given CSS client type (e.g. {@code "copilot"}, {@code "claude"}),
     * or {@code null} if the default color should be used.
     */
    public static @Nullable String loadAgentBubbleColorKey(String clientType) {
        String stored = PropertiesComponent.getInstance()
            .getValue(PROP_AGENT_BUBBLE_COLOR.formatted(clientType), "").trim();
        return stored.isEmpty() ? null : stored;
    }

    /**
     * Persists the bubble color key for the given CSS client type.
     * Pass {@code null} or blank to restore the default color.
     */
    public static void saveAgentBubbleColorKey(String clientType, @Nullable String colorKey) {
        PropertiesComponent.getInstance()
            .setValue(PROP_AGENT_BUBBLE_COLOR.formatted(clientType), colorKey != null ? colorKey : "", "");
    }

    // ────────────────────────────────────────────────────────────────────────

    private List<String> resolveCommand(List<String> command) {
        if (command.isEmpty()) return command;
        String binaryName = command.getFirst();

        // Already an absolute or relative path — no resolution needed
        if (binaryName.startsWith("/") || binaryName.startsWith("./")) return command;

        // Check user-configured override first, then auto-detect via shell environment
        var profile = com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager.getInstance().getProfile(agentId());
        String[] alternates = profile != null ? profile.getAlternateNames().toArray(new String[0]) : new String[0];

        String resolvedPath = new com.github.catatafishen.ideagentforcopilot.settings.AcpClientBinaryDetector(agentId()).resolve(binaryName, alternates);
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            List<String> resolved = new ArrayList<>(command);
            resolved.set(0, resolvedPath);
            return resolved;
        }

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
        CompletableFuture<JsonElement> future = transport.sendRequest("initialize", params);

        JsonElement result = future.get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        InitializeResponse response = gson.fromJson(result, InitializeResponse.class);

        // ACP spec: version negotiation — if agent responds with a different version,
        // client SHOULD close the connection. We log a warning and continue for pragmatism
        // since most agents in practice use version 1.
        if (response.protocolVersion() != null && response.protocolVersion() != PROTOCOL_VERSION) {
            LOG.warn(displayName() + ": protocol version mismatch — requested "
                + PROTOCOL_VERSION + ", agent supports " + response.protocolVersion()
                + ". Continuing with best-effort compatibility.");
        }

        if (response.agentInfo() != null) {
            String displayTitle = response.agentInfo().title() != null
                ? response.agentInfo().title() : response.agentInfo().name();
            LOG.info(displayName() + " initialized: " + displayTitle
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
            CompletableFuture<JsonElement> future = transport.sendRequest("authenticate", params);
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
     * Creates the initial session immediately after startup to populate models, modes, and
     * config options. The session is kept alive and reused for the first user prompt — this
     * avoids a redundant second {@code session/new} when the user sends their first message.
     * If the call fails, the failure is logged and swallowed; the first real {@code createSession}
     * call will retry.
     */
    private void eagerFetchModels() {
        String cwd = launchCwd != null ? launchCwd : project.getBasePath();
        if (cwd == null) return;
        try {
            createSession(cwd);
            // Keep currentSessionId set — createSession() will reuse it when the user sends a prompt
            LOG.info(displayName() + ": eagerly loaded " + availableModels.size() + " model(s), session=" + currentSessionId);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String errorMsg = e.getMessage() + (cause != e ? " — caused by: " + cause.getMessage() : "");

            // Check if this is an auth error - if so, re-throw it so startup fails immediately
            Throwable current = e;
            while (current != null) {
                String msg = current.getMessage();
                if (msg != null && (msg.toLowerCase().contains("auth") || msg.toLowerCase().contains("sign in"))) {
                    LOG.warn(displayName() + ": authentication required during session creation");
                    // Extract clean message from JsonRpcException format: "JsonRpcException{code=-32000, message='Authentication required'}"
                    String cleanMsg = msg;
                    if (msg.contains("message='") && msg.contains("'}")) {
                        int start = msg.indexOf("message='") + 9;
                        int end = msg.indexOf("'}", start);
                        if (end > start) {
                            cleanMsg = msg.substring(start, end);
                        }
                    }
                    throw new RuntimeException(cleanMsg, e);
                }
                current = current.getCause();
            }

            // Not an auth error - log and continue (models will be empty but agent can still work)
            LOG.warn(displayName() + ": eager session creation failed (models will be empty): " + errorMsg);
        }
    }

    protected void registerHandlers() {
        transport.onNotification(notification -> {
            if ("session/update".equals(notification.method())) {
                handleSessionUpdate(notification.params());
            }
        });

        transport.onRequest(this::handleAgentRequest);

        transport.onStderr(line ->
            LOG.warn("[" + agentId() + " stderr] " + line));
    }

    protected void handleSessionUpdate(@Nullable JsonObject params) {
        if (params == null) return;

        // Reset inactivity clock on every update so long turns with active streaming never time out.
        lastActivityNanos = System.nanoTime();

        JsonObject updateObj = normalizeSessionUpdateParams(params);

        Consumer<SessionUpdate> consumer = updateConsumer;
        if (consumer == null) {
            LOG.debug("Session update received but no consumer registered");
            return;
        }

        SessionUpdate update = messageParser.parse(updateObj);
        if (update != null) {
            update = processUpdate(update);
            if (update != null) {
                consumer.accept(update);
            }
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
        if (params.has(KEY_UPDATE) && params.get(KEY_UPDATE).isJsonObject()) {
            return params.getAsJsonObject(KEY_UPDATE);
        }
        return params;
    }

    /**
     * Extract tool call arguments from a {@code tool_call} params object.
     * The standard ACP field is {@code arguments} (a JSON object).
     * Override in subclasses for agent-specific field names.
     */
    @Nullable
    protected JsonObject parseToolCallArguments(@NotNull JsonObject params) {
        if (params.has(KEY_ARGUMENTS) && params.get(KEY_ARGUMENTS).isJsonObject()) {
            return params.getAsJsonObject(KEY_ARGUMENTS);
        }
        return null;
    }

    /**
     * Detect sub-agent invocations in a {@code tool_call} notification and return the agent type.
     * Returns {@code null} if this is not a sub-agent call.
     * <p>
     * The base implementation checks for explicit {@code agentType}/{@code subagent_type}/{@code agent_type}
     * fields in both the top-level params and the arguments object.
     * Subclasses can override to add client-specific detection (e.g., title-based matching).
     */
    @Nullable
    protected String extractSubAgentType(@NotNull JsonObject params, @NotNull String resolvedTitle,
                                         @Nullable JsonObject argumentsObj) {
        // Check top-level params first (some ACP extensions put agentType here)
        for (String key : new String[]{"agentType", "agent_type", "subagent_type"}) {
            if (params.has(key) && params.get(key).isJsonPrimitive()) {
                return params.get(key).getAsString();
            }
        }
        // Check inside the arguments object
        if (argumentsObj != null) {
            for (String key : new String[]{"agentType", "agent_type", "subagent_type"}) {
                if (argumentsObj.has(key) && argumentsObj.get(key).isJsonPrimitive()) {
                    return argumentsObj.get(key).getAsString();
                }
            }
        }
        return null;
    }

    private static String getStringOrEmpty(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive()
            ? obj.get(key).getAsString() : "";
    }

    protected void handleAgentRequest(JsonElement id, JsonRpcTransport.IncomingRequest request) {
        switch (request.method()) {
            case "session/request_permission" -> handlePermissionRequest(id, request.params());
            case "fs/read_text_file" -> handleFsRequest(id, () -> fsHandler.readTextFile(request.params()));
            case "fs/write_text_file" -> handleFsRequest(id, () -> fsHandler.writeTextFile(request.params()));
            case "terminal/create" -> handleTerminalRequest(id, () -> terminalHandler.create(request.params()));
            case "terminal/output" -> handleTerminalRequest(id, () -> terminalHandler.output(request.params()));
            case "terminal/wait_for_exit" ->
                handleTerminalRequest(id, () -> terminalHandler.waitForExit(request.params()));
            case "terminal/kill" -> handleTerminalRequest(id, () -> terminalHandler.kill(request.params()));
            case "terminal/release" -> handleTerminalRequest(id, () -> terminalHandler.release(request.params()));
            default -> {
                LOG.warn("Unknown agent request: " + request.method());
                transport.sendError(id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: " + request.method());
            }
        }
    }

    /**
     * Dispatches an ACP file system request, catching exceptions and sending
     * the appropriate JSON-RPC response (success or error).
     */
    private void handleFsRequest(JsonElement id, java.util.concurrent.Callable<JsonObject> handler) {
        try {
            JsonObject result = handler.call();
            // ACP spec: fs/write_text_file returns null on success
            transport.sendResponse(id, result != null ? gson.toJsonTree(result) : null);
        } catch (IllegalArgumentException e) {
            transport.sendError(id, JsonRpcErrorCodes.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            LOG.warn("FS request failed: " + e.getMessage(), e);
            transport.sendError(id, JsonRpcErrorCodes.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Dispatches an ACP terminal request, catching exceptions and sending
     * the appropriate JSON-RPC response (success or error).
     */
    private void handleTerminalRequest(JsonElement id, java.util.concurrent.Callable<JsonObject> handler) {
        try {
            JsonObject result = handler.call();
            transport.sendResponse(id, gson.toJsonTree(result));
        } catch (IllegalArgumentException e) {
            transport.sendError(id, JsonRpcErrorCodes.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            LOG.warn("Terminal request failed: " + e.getMessage(), e);
            transport.sendError(id, JsonRpcErrorCodes.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Responds to all pending {@code session/request_permission} requests with the
     * {@code cancelled} outcome. Per ACP spec, the Client MUST do this when a turn is cancelled.
     */
    private void cancelPendingPermissionRequests() {
        for (var entry : pendingPermissionRequests.entrySet()) {
            JsonElement requestId = entry.getValue();
            JsonObject cancelledOutcome = new JsonObject();
            cancelledOutcome.addProperty(KEY_OUTCOME, "cancelled");
            JsonObject result = new JsonObject();
            result.add(KEY_OUTCOME, cancelledOutcome);
            transport.sendResponse(requestId, result);
            LOG.info(displayName() + ": responded cancelled to pending permission request " + entry.getKey());
        }
        pendingPermissionRequests.clear();
    }

    private void handlePermissionRequest(JsonElement id, @Nullable JsonObject params) {
        String requestKey = id != null ? id.toString() : "";
        if (!requestKey.isEmpty()) {
            pendingPermissionRequests.put(requestKey, id);
        }

        String toolCallId = "";
        String toolId = "";
        if (params != null && params.has(KEY_TOOL_CALL)) {
            JsonObject toolCallObj = params.getAsJsonObject(KEY_TOOL_CALL);
            String protocolTitle = getStringOrEmpty(toolCallObj, "title");
            toolCallId = getStringOrEmpty(toolCallObj, KEY_TOOL_CALL_ID);
            toolId = resolveToolId(protocolTitle);
            if (!toolCallId.isEmpty()) {
                onPermissionRequest(toolCallId, toolCallObj);
            }
        }

        String protocolTitle = params != null && params.has(KEY_TOOL_CALL)
            ? getStringOrEmpty(params.getAsJsonObject(KEY_TOOL_CALL), "title")
            : "";

        JsonObject chosenOption;

        if (!toolId.isEmpty() && isToolBlocked(protocolTitle, toolId)) {
            chosenOption = handleBlockedTool(toolId, toolCallId, params);
        } else if (isBuiltInTool(protocolTitle)) {
            if (isAllowedBuiltInTool(toolId)) {
                LOG.info(displayName() + ": auto-approving built-in web tool '" + toolId + "' — no MCP alternative exists");
            } else {
                LOG.warn(displayName() + ": auto-approving built-in tool '" + toolId
                    + "' — should use MCP tools instead");
                onBuiltInToolApproved(toolId, false);
            }
            chosenOption = findOptionByKind(params, VALUE_ALLOW_ONCE);
            if (chosenOption == null) {
                chosenOption = findFirstOption(params);
            }
        } else {
            LOG.info(displayName() + ": auto-approving MCP tool '" + toolId + "' at ACP level (MCP server will check permissions)");
            chosenOption = findOptionByKind(params, VALUE_ALLOW_ONCE);
            if (chosenOption == null) {
                chosenOption = findFirstOption(params);
            }
        }

        sendPermissionResponse(id, requestKey, chosenOption);
    }

    private @Nullable JsonObject handleBlockedTool(String toolId, String toolCallId, @Nullable JsonObject params) {
        String reason = "Tool '" + toolId + "' is blocked by the current agent profile (excludeAgentBuiltInTools=true).";
        LOG.warn(displayName() + ": " + reason);

        Consumer<SessionUpdate> consumer = updateConsumer;
        if (consumer != null && !toolCallId.isEmpty()) {
            consumer.accept(new SessionUpdate.ToolCallUpdate(
                toolCallId,
                SessionUpdate.ToolCallStatus.FAILED,
                null,
                "Auto-denied: " + reason,
                null,
                true,
                reason
            ));
        }
        return findDenyOption(params);
    }

    private void sendPermissionResponse(JsonElement id, String requestKey, @Nullable JsonObject chosenOption) {
        String optionId = chosenOption != null && chosenOption.has(KEY_OPTION_ID)
            ? chosenOption.get(KEY_OPTION_ID).getAsString()
            : VALUE_DENY_ONCE;
        JsonObject result = new JsonObject();
        result.add(KEY_OUTCOME, buildPermissionOutcome(optionId, chosenOption));
        transport.sendResponse(id, result);
        pendingPermissionRequests.remove(requestKey);
    }

    /**
     * Whether this agent should block all built-in (non-MCP) tool calls,
     * forcing the model to use agentbridge tools exclusively.
     * Override in subclasses that require exclusive agentbridge usage.
     */
    protected boolean excludeBuiltInTools() {
        return false;
    }

    private boolean isToolBlocked(String protocolTitle, String toolId) {
        if (!isBuiltInTool(protocolTitle)) {
            return false;
        }
        if (excludeBuiltInTools()) {
            return !ALLOWED_BUILT_IN_TOOLS.contains(toolId.toLowerCase());
        }
        return false;
    }

    static boolean isAllowedBuiltInTool(@NotNull String toolId) {
        return ALLOWED_BUILT_IN_TOOLS.contains(toolId.toLowerCase());
    }

    static boolean shouldAutoDenyBuiltInTool(@NotNull String toolId) {
        if (isMcpResourceTool(toolId)) {
            return false;
        }
        if (toolId.startsWith("agentbridge-")
            || toolId.startsWith("agentbridge_")
            || toolId.startsWith("Tool: agentbridge/")
            || toolId.startsWith("Running: @agentbridge/")
            || toolId.startsWith("@agentbridge/")) {
            return false;
        }
        return !toolId.contains("/") && !toolId.contains("@") && !isAllowedBuiltInTool(toolId);
    }

    private static boolean isMcpResourceTool(@NotNull String toolId) {
        String lower = toolId.toLowerCase();
        return "read_mcp_resource".equals(lower)
            || "list_mcp_resources".equals(lower);
    }

    protected final boolean isBuiltInTool(@NotNull String protocolTitle) {
        return !isMcpToolTitle(protocolTitle);
    }

    protected abstract boolean isMcpToolTitle(@NotNull String protocolTitle);

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
        outcome.addProperty(KEY_OUTCOME, VALUE_SELECTED);
        outcome.addProperty(KEY_OPTION_ID, optionId);
        return outcome;
    }

    /**
     * Called when a {@code session/request_permission} arrives, before the response is sent.
     * Override in subclasses to capture tool call arguments for chip correlation (e.g. Junie
     * sends args only in the permission request content, not in the {@code tool_call} update).
     *
     * @param toolCallId     the tool call ID from {@code toolCall.toolCallId}
     * @param toolCallParams the {@code toolCall} sub-object from the permission request params
     */
    protected void onPermissionRequest(@NotNull String toolCallId, @NotNull JsonObject toolCallParams) {
    }

    @Nullable
    private static JsonObject findOptionByKind(@Nullable JsonObject params, String kind) {
        if (params == null || !params.has(KEY_OPTIONS)) return null;
        JsonElement options = params.get(KEY_OPTIONS);
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
        if (params == null || !params.has(KEY_OPTIONS)) return null;
        JsonElement options = params.get(KEY_OPTIONS);
        if (!options.isJsonArray()) return null;
        JsonArray arr = options.getAsJsonArray();
        return (!arr.isEmpty() && arr.get(0).isJsonObject()) ? arr.get(0).getAsJsonObject() : null;
    }

    /**
     * Searches the permission request's options array for a deny/reject option.
     * Different agents use different kind values: Copilot CLI sends {@code "reject_once"},
     * while the ACP spec uses {@code "deny_once"}.
     */
    @Nullable
    private static JsonObject findDenyOption(@Nullable JsonObject params) {
        JsonObject option = findOptionByKind(params, VALUE_DENY_ONCE);
        if (option != null) return option;
        return findOptionByKind(params, VALUE_REJECT_ONCE);
    }

    protected void destroyProcess() {
        AgentProcessRegistry.unregister(agentProcess);
        destroyProcessTree(agentProcess);
    }

    static void destroyProcessTree(@Nullable Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }

        ProcessHandle handle = process.toHandle();
        List<ProcessHandle> descendants = handle.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }

        handle.destroy();
        try {
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                for (int i = descendants.size() - 1; i >= 0; i--) {
                    descendants.get(i).destroyForcibly();
                }
                handle.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (int i = descendants.size() - 1; i >= 0; i--) {
                descendants.get(i).destroyForcibly();
            }
            handle.destroyForcibly();
        }
    }
}
