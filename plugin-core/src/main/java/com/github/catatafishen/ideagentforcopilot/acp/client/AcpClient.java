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
    /**
     * How long a prompt may be silent (no {@code session/update} received) before it is
     * considered stuck. Unlike a hard deadline from send time, this resets on every streaming
     * chunk, so arbitrarily long agentic turns are fine as long as the agent keeps sending.
     */
    private static final long INACTIVITY_TIMEOUT_SECONDS = 300; // 5 minutes of silence
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
    private static final Set<String> ALLOWED_BUILT_IN_TOOLS = Set.of("web_fetch", "web_search");

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
    private final List<AbstractAgentClient.AgentMode> availableModes = new ArrayList<>();
    private @Nullable String currentModeSlug = null;
    private @Nullable String currentModelId = null;
    private @Nullable String currentAgentSlug = null;
    private final List<AbstractAgentClient.AgentConfigOption> availableConfigOptions = new ArrayList<>();
    private volatile @Nullable Consumer<SessionUpdate> updateConsumer;
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
        return new com.github.catatafishen.ideagentforcopilot.bridge.AuthMethod(
            method.command(),
            method.args() != null ? method.args().toArray(new String[0]) : null
        );
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
        availableModes.clear();
        currentModeSlug = null;
        currentModelId = null;
        currentAgentSlug = null;
        availableConfigOptions.clear();
        updateConsumer = null;
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
            JsonObject params = buildNewSessionParams(cwd);

            CompletableFuture<JsonElement> future = transport.sendRequest("session/new", params);
            JsonElement result = future.get(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.debug(displayName() + ": session/new raw response: " + result);

            NewSessionResponse response = gson.fromJson(result, NewSessionResponse.class);
            LOG.debug(displayName() + ": session/new: " + (response.models() != null ? response.models().size() : 0) + " model(s), "
                + (response.modes() != null ? response.modes().size() : 0) + " mode(s)");

            processNewSessionResponse(response);

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

        // Request continuation of the previous conversation if one was saved.
        String savedResumeId = loadResumeSessionId();
        if (savedResumeId != null) {
            params.addProperty("resumeSessionId", savedResumeId);
            LOG.info(displayName() + ": requesting resume of session " + savedResumeId);
        }
        return params;
    }

    private void processNewSessionResponse(NewSessionResponse response) {
        currentSessionId = response.sessionId();

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

    @Override
    public final void cancelSession(String sessionId) {
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

    @Override
    public final PromptResponse sendPrompt(PromptRequest request,
                                           Consumer<SessionUpdate> onUpdate) throws AgentPromptException {
        try {
            lastActivityNanos = System.nanoTime();
            updateConsumer = onUpdate;
            JsonObject params = gson.toJsonTree(request).getAsJsonObject();
            LOG.debug(displayName() + ": sending session/prompt, sessionId=" + request.sessionId());
            CompletableFuture<JsonElement> future = transport.sendRequest("session/prompt", params);
            JsonElement result = waitForPromptResult(future);
            return gson.fromJson(result, PromptResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentPromptException("Prompt interrupted for " + displayName(), e);
        } catch (Exception e) {
            PromptResponse recovery = tryRecoverPromptException(e);
            if (recovery != null) return recovery;
            throw new AgentPromptException("Prompt failed for " + displayName(), e);
        } finally {
            afterPromptComplete();
        }
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
     * Waits for the {@code session/prompt} response using an inactivity-based deadline.
     * <p>
     * Rather than a hard wall-clock timeout from the time the request was sent (which
     * would prematurely kill legitimately long agentic turns), this method polls in short
     * intervals and only times out when no {@code session/update} notification has arrived
     * for {@value #INACTIVITY_TIMEOUT_SECONDS} seconds. As long as the agent keeps streaming
     * chunks — even during a multi-tool, multi-minute turn — the deadline keeps resetting.
     */
    private JsonElement waitForPromptResult(CompletableFuture<JsonElement> future)
        throws InterruptedException, java.util.concurrent.ExecutionException,
        java.util.concurrent.TimeoutException {
        long pollMs = 5_000L;
        long inactivityLimitNanos = TimeUnit.SECONDS.toNanos(INACTIVITY_TIMEOUT_SECONDS);
        while (true) {
            try {
                return future.get(pollMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                long silenceNanos = System.nanoTime() - lastActivityNanos;
                if (silenceNanos >= inactivityLimitNanos) {
                    long silenceSec = TimeUnit.NANOSECONDS.toSeconds(silenceNanos);
                    LOG.warn(displayName() + ": inactivity timeout after " + silenceSec + "s of silence");
                    throw new java.util.concurrent.TimeoutException(
                        "Agent inactive for " + silenceSec + "s (no session/update received)"
                    );
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
     * Default: empty (no declared capabilities). Override to advertise fs/terminal support.
     */
    protected InitializeRequest.ClientCapabilities buildClientCapabilities() {
        return InitializeRequest.ClientCapabilities.empty();
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
        return pb.start();
    }

    // ─── Per-agent binary path settings (application-level) ────────────────

    private static final String PROP_CUSTOM_BINARY = "agentbridge.%s.customBinary";

    /**
     * Returns the user-configured binary path for the given agent ID,
     * or {@code null} if not set (auto-detect will be used instead).
     */
    public static @Nullable String loadCustomBinaryPath(String agentId) {
        String stored = PropertiesComponent.getInstance()
            .getValue(PROP_CUSTOM_BINARY.formatted(agentId), "").trim();
        return stored.isEmpty() ? null : stored;
    }

    /**
     * Persists a custom binary path for the given agent ID.
     * Pass {@code null} or blank to clear the override and use auto-detection.
     */
    public static void saveCustomBinaryPath(String agentId, @Nullable String path) {
        PropertiesComponent.getInstance()
            .setValue(PROP_CUSTOM_BINARY.formatted(agentId), path != null ? path.trim() : "", "");
    }

    // ────────────────────────────────────────────────────────────────────────

    private List<String> resolveCommand(List<String> command) {
        if (command.isEmpty()) {
            return command;
        }
        String binaryName = command.getFirst();

        // User-configured override takes priority over auto-detection
        String customPath = loadCustomBinaryPath(agentId());
        if (customPath != null) {
            List<String> resolved = new ArrayList<>(command);
            resolved.set(0, customPath);
            return resolved;
        }

        // Already absolute — no resolution needed
        if (binaryName.startsWith("/") || binaryName.startsWith("./")) {
            return command;
        }
        String absolutePath = com.github.catatafishen.ideagentforcopilot.settings.BinaryDetector.findBinaryPath(binaryName);
        if (absolutePath != null && !absolutePath.isEmpty()) {
            List<String> resolved = new ArrayList<>(command);
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
        CompletableFuture<JsonElement> future = transport.sendRequest("initialize", params);

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
            LOG.warn(displayName() + ": eager session creation failed (models will be empty): "
                + e.getMessage() + (cause != e ? " — caused by: " + cause.getMessage() : ""));
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
        // Notify subclass before responding, so it can capture args for chip correlation.
        String toolCallId = "";
        String toolId = "";
        if (params != null && params.has("toolCall")) {
            JsonObject toolCallObj = params.getAsJsonObject("toolCall");
            String protocolTitle = getStringOrEmpty(toolCallObj, "title");
            toolCallId = getStringOrEmpty(toolCallObj, KEY_TOOL_CALL_ID);
            toolId = resolveToolId(protocolTitle);
            if (!toolCallId.isEmpty()) {
                onPermissionRequest(toolCallId, toolCallObj);
            }
        }

        JsonObject chosenOption = null;
        String protocolTitle = params != null && params.has("toolCall")
            ? getStringOrEmpty(params.getAsJsonObject("toolCall"), "title")
            : "";
        if (!toolId.isEmpty() && isToolBlocked(protocolTitle, toolId)) {
            String reason = "Tool '" + toolId + "' is blocked by the current agent profile (excludeAgentBuiltInTools=true).";
            LOG.warn(displayName() + ": " + reason);
            chosenOption = findOptionByKind(params, VALUE_DENY_ONCE);

            // Notify UI that this tool was auto-denied
            if (updateConsumer != null) {
                updateConsumer.accept(new SessionUpdate.ToolCallUpdate(
                    toolCallId,
                    SessionUpdate.ToolCallStatus.FAILED,
                    null,
                    "Auto-denied: " + reason,
                    null,
                    true,
                    reason
                ));
            }

            if (chosenOption == null) {
                // Fallback: if we must deny but agent doesn't offer "deny_once",
                // we'll try to find any option that isn't allow. But typically "deny_once" exists.
                chosenOption = findFirstOption(params);
            }
        } else if (isBuiltInTool(protocolTitle)) {
            LOG.info(displayName() + ": permission request for built-in tool '" + toolId + "' requires user approval");

            if (isAllowedBuiltInTool(toolId)) {
                LOG.warn(displayName() + ": auto-approving built-in web tool '" + toolId + "' - this is allowed because no MCP alternative exists");
                chosenOption = findOptionByKind(params, VALUE_ALLOW_ONCE);
            } else {
                String reason = "Built-in tool '" + toolId + "' is not auto-approved; deny it unless the user explicitly allows it.";
                LOG.warn(displayName() + ": " + reason);
                chosenOption = findOptionByKind(params, VALUE_DENY_ONCE);

                if (chosenOption == null) {
                    chosenOption = findFirstOption(params);
                }
            }
        } else {
            // MCP tools are our own server-side tools, so we keep the existing auto-approval path.
            LOG.info(displayName() + ": permission request for MCP tool '" + toolId + "' requires user approval");
            LOG.warn(displayName() + ": auto-approving MCP tool '" + toolId + "' - this should require user approval");
            chosenOption = findOptionByKind(params, VALUE_ALLOW_ONCE);

            if (chosenOption == null) {
                chosenOption = findFirstOption(params);
            }
        }

        String optionId = chosenOption != null && chosenOption.has(KEY_OPTION_ID)
            ? chosenOption.get(KEY_OPTION_ID).getAsString()
            : VALUE_ALLOW_ONCE;
        JsonObject result = new JsonObject();
        result.add(KEY_OUTCOME, buildPermissionOutcome(optionId, chosenOption));
        transport.sendResponse(id, result);
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
        if (toolId.startsWith("agentbridge-")
            || toolId.startsWith("agentbridge_")
            || toolId.startsWith("Tool: agentbridge/")
            || toolId.startsWith("Running: @agentbridge/")
            || toolId.startsWith("@agentbridge/")) {
            return false;
        }
        return !toolId.contains("/") && !toolId.contains("@") && !isAllowedBuiltInTool(toolId);
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

    protected void destroyProcess() {
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
