package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.services.hooks.HookQueryHandler;
import com.github.catatafishen.agentbridge.services.hooks.HookToolHandler;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.settings.TransportMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP server exposing the MCP (Model Context Protocol) endpoint.
 * Supports two transport modes configured via {@link McpServerSettings#getTransportMode()}:
 * <ul>
 *   <li><b>Streamable HTTP</b> — POST /mcp for JSON-RPC request/response</li>
 *   <li><b>SSE</b> — GET /sse opens an event stream; POST /message sends requests,
 *       responses arrive via the SSE stream</li>
 * </ul>
 * GET /health is always available for status checks.
 */
public final class McpHttpServer implements Disposable, McpServerControl {
    private static final Logger LOG = Logger.getInstance(McpHttpServer.class);
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";
    private static final String MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    private static final long HTTP_SESSION_SWEEP_INTERVAL_MINUTES = 5;
    private static final String HTTP_OWNER_PREFIX = "http:";
    private static final String ALLOWED_REQUEST_HEADERS =
        CONTENT_TYPE + ", " + MCP_SESSION_ID_HEADER + ", " + MCP_PROTOCOL_VERSION_HEADER;

    /**
     * Fired on the project message bus when the MCP server starts or stops.
     */
    public static final Topic<StatusListener> STATUS_TOPIC =
        Topic.create("McpHttpServer.Status", StatusListener.class);

    /**
     * Listener notified when the MCP HTTP server starts or stops.
     */
    public interface StatusListener {
        void serverStatusChanged();
    }

    private final Project project;
    private HttpServer httpServer;
    private McpProtocolHandler protocolHandler;
    private McpSseTransport sseTransport;
    private TransportMode activeTransportMode;
    private java.util.concurrent.ExecutorService requestExecutor;
    private java.util.concurrent.ScheduledExecutorService sessionCleanupExecutor;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final McpSessionRegistry httpSessions = new McpSessionRegistry();
    private volatile boolean running;

    public McpHttpServer(@NotNull Project project) {
        this.project = project;
    }

    public static McpHttpServer getInstance(@NotNull Project project) {
        return (McpHttpServer) project.getService(McpServerControl.class);
    }

    public void start() throws IOException {
        synchronized (this) {
            if (running) return;
            McpServerSettings settings = McpServerSettings.getInstance(project);
            int port = settings.getPort();
            boolean isStatic = settings.isStaticPort();
            activeTransportMode = settings.getTransportMode();

            protocolHandler = new McpProtocolHandler(project);

            int actualPort = bindServerPort(port, isStatic);
            if (!isStatic && actualPort != port) {
                settings.setPort(actualPort);
                LOG.info("[MCP] port conflict: " + port + " was in use; allocated " + actualPort + " instead for project: " + project.getBasePath());
            }

            httpServer.createContext("/health", this::handleHealth);
            httpServer.createContext("/hooks/query", new HookQueryHandler(project)::handle);
            httpServer.createContext("/hooks/tool", new HookToolHandler(project)::handle);

            if (activeTransportMode == TransportMode.SSE) {
                sseTransport = new McpSseTransport(project, protocolHandler);
                httpServer.createContext("/sse", sseTransport::handleSseConnect);
                httpServer.createContext("/message", sseTransport::handleMessage);
                sseTransport.start();
            } else {
                httpServer.createContext("/mcp", this::handleMcp);
            }

            // Bounded thread pool: SSE mode blocks one thread per connection, streamable HTTP
            // uses short-lived requests. Cap at 20 to prevent thread exhaustion from reconnection storms.
            // Uses a small queue (50) to absorb bursts; tasks rejected beyond capacity get auto-500'd by HttpServer.
            requestExecutor = new java.util.concurrent.ThreadPoolExecutor(
                2, 20, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "mcp-http");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
            );
            httpServer.setExecutor(requestExecutor);
            httpServer.start();
            if (activeTransportMode == TransportMode.STREAMABLE_HTTP) {
                startHttpSessionCleanup();
            }
            running = true;
            LOG.info("[MCP] server started on port " + actualPort + " (" + activeTransportMode.getDisplayName()
                + ") for project: " + project.getBasePath());
        }
        // Fire status notification AFTER releasing the synchronized lock. Firing inside the lock
        // is a deadlock risk: syncPublisher dispatches listeners synchronously, and any listener
        // that re-enters start()/stop() from another thread would deadlock on the monitor.
        project.getMessageBus().syncPublisher(STATUS_TOPIC).serverStatusChanged();
    }

    /**
     * Attempts to bind to the given port. In static mode, fails immediately if unavailable.
     * In dynamic mode, tries up to 100 consecutive ports starting from the configured one.
     * Sets {@link #httpServer} on success and returns the actual bound port.
     */
    private int bindServerPort(int port, boolean isStatic) throws IOException {
        if (isStatic) {
            try {
                httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            } catch (IOException e) {
                throw new IOException("MCP server port " + port + " is already in use. "
                    + "Disable 'Static Port' in settings to allow automatic port allocation, "
                    + "or free port " + port + " and try again.", e);
            }
            return port;
        }

        int actualPort = port;
        IOException lastError = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", actualPort), 0);
                return actualPort;
            } catch (IOException e) {
                lastError = e;
                actualPort++;
            }
        }
        throw new IOException("Failed to bind MCP server to any port starting from " + port, lastError);
    }

    /**
     * Start on a specific port (saves the port to settings first).
     */
    public void start(int port) throws IOException {
        McpServerSettings.getInstance(project).setPort(port);
        start();
    }

    public void stop() {
        boolean notify;
        synchronized (this) {
            if (!running || httpServer == null) return;
            if (sseTransport != null) {
                sseTransport.stop();
                sseTransport = null;
            }
            stopHttpSessionCleanup();
            closeAllHttpSessions();
            httpServer.stop(1);
            httpServer = null;
            if (requestExecutor != null) {
                requestExecutor.shutdownNow();
                requestExecutor = null;
            }
            protocolHandler = null;
            activeTransportMode = null;
            running = false;
            activeConnections.set(0);
            notify = !project.isDisposed();
            LOG.info("[MCP] server stopped for project: " + project.getBasePath());
        }
        // Fire status notification AFTER releasing the synchronized lock. Firing inside the lock
        // is a deadlock risk: syncPublisher dispatches listeners synchronously, and any listener
        // that re-enters start()/stop() from another thread would deadlock on the monitor.
        if (notify) {
            project.getMessageBus().syncPublisher(STATUS_TOPIC).serverStatusChanged();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the name of the agent that connected via MCP {@code initialize},
     * or {@code null} if no agent has connected yet or the server is not running.
     */
    public @Nullable String getConnectedAgentName() {
        McpProtocolHandler handler = protocolHandler;
        return handler != null ? handler.getConnectedAgentName() : null;
    }

    public TransportMode getActiveTransportMode() {
        return activeTransportMode;
    }

    public int getPort() {
        return httpServer != null ? httpServer.getAddress().getPort() : 0;
    }

    public int getActiveConnections() {
        if (sseTransport != null) {
            return sseTransport.getActiveSessionCount();
        }
        return activeConnections.get();
    }

    private static final int LOG_MAX_CHARS = 2000;

    private static String truncateForLog(String s) {
        if (s == null || s.length() <= LOG_MAX_CHARS) return s;
        return s.substring(0, LOG_MAX_CHARS) + "... [truncated " + (s.length() - LOG_MAX_CHARS) + " chars]";
    }

    private static final int MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024; // 10 MB

    private void handleMcp(HttpExchange exchange) throws IOException {
        // CORS headers for browser-based agents and MCP transport session propagation.
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Methods", "POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Headers", ALLOWED_REQUEST_HEADERS);
        exchange.getResponseHeaders().set(
            "Access-Control-Expose-Headers", MCP_SESSION_ID_HEADER);

        // Force a fresh localhost connection for each request to avoid stale pooled sockets after
        // long model think gaps. MCP ownership is carried by Mcp-Session-Id, not by the TCP socket.
        // See issue #841.
        exchange.getResponseHeaders().set("Connection", "close");

        String requestMethod = exchange.getRequestMethod();
        if ("OPTIONS".equals(requestMethod)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if ("DELETE".equals(requestMethod)) {
            handleSessionDelete(exchange);
            return;
        }
        if (!"POST".equals(requestMethod)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        activeConnections.incrementAndGet();
        McpServerSettings settings = McpServerSettings.getInstance(project);
        HttpOwnerResolution owner = null;
        boolean retainNewSession = false;
        try {
            byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (bodyBytes.length > MAX_REQUEST_BODY_BYTES) {
                sendJsonRpcError(exchange, 413, -32600,
                    "Request body exceeds " + MAX_REQUEST_BODY_BYTES + " byte limit");
                return;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            if (settings.isDebugLoggingEnabled()) {
                LOG.info("[MCP] <<< " + truncateForLog(body));
            }

            owner = resolveHttpOwner(exchange, body);
            if (owner == null) return;
            String response;
            String ownerKey = owner.ownerKey();
            try {
                response = ownerKey != null
                    ? protocolHandler.handleMessage(body, ownerKey)
                    : protocolHandler.handleMessage(body);
            } finally {
                if (ownerKey != null) {
                    finishHttpRequest(ownerKey);
                }
            }

            boolean initialized = completeInitialization(exchange, owner, response);

            if (response == null) {
                // Notification — no response needed.
                exchange.sendResponseHeaders(202, -1);
            } else {
                if (settings.isDebugLoggingEnabled()) {
                    LOG.info("[MCP] >>> " + truncateForLog(response));
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }

            retainNewSession = initialized;
        } catch (Exception e) {
            LOG.warn("MCP request error", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sendJsonRpcError(exchange, 500, -32603, "Internal error: " + msg);
        } finally {
            if (owner != null && owner.newSessionId() != null && !retainNewSession) {
                closeHttpSession(owner.newSessionId());
            }
            exchange.close();
            activeConnections.decrementAndGet();
        }
    }

    enum HttpOwnerKind {
        INVALID,
        INITIALIZE,
        ESTABLISHED
    }

    record HttpOwnerResolution(
        @NotNull HttpOwnerKind kind,
        @Nullable String ownerKey,
        @Nullable String newSessionId
    ) {
        static @NotNull HttpOwnerResolution invalid() {
            return new HttpOwnerResolution(HttpOwnerKind.INVALID, null, null);
        }

        static @NotNull HttpOwnerResolution initialize(@NotNull String sessionId) {
            return new HttpOwnerResolution(
                HttpOwnerKind.INITIALIZE,
                McpSessionRegistry.ownerKey("http", sessionId),
                sessionId);
        }

        static @NotNull HttpOwnerResolution established(@NotNull String sessionId) {
            return new HttpOwnerResolution(
                HttpOwnerKind.ESTABLISHED,
                McpSessionRegistry.ownerKey("http", sessionId),
                null);
        }
    }

    @Nullable HttpOwnerResolution resolveHttpOwner(
        @NotNull HttpExchange exchange,
        @NotNull String body
    ) throws IOException {
        McpSessionRegistry.RequestKind kind = McpSessionRegistry.classifyRequest(body);
        if (kind == McpSessionRegistry.RequestKind.INVALID) {
            // Let the protocol handler return the precise JSON-RPC parse/validation error.
            return HttpOwnerResolution.invalid();
        }

        if (kind == McpSessionRegistry.RequestKind.INITIALIZE) {
            int cap = getMaxOpenHttpSessions();
            String sessionId = httpSessions.openSession(cap);
            if (sessionId == null) {
                sendJsonRpcError(exchange, 503, -32000,
                    "MCP session limit reached (" + cap + " concurrent sessions)."
                        + " Close an existing session or raise the limit in"
                        + " Settings → AgentBridge → MCP Server.");
                return null;
            }
            return HttpOwnerResolution.initialize(sessionId);
        }

        String sessionId = exchange.getRequestHeaders().getFirst(MCP_SESSION_ID_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            sendJsonRpcError(exchange, 400, -32600,
                "Missing " + MCP_SESSION_ID_HEADER
                    + ". Initialize the MCP transport session first.");
            return null;
        }
        if (!httpSessions.touch(sessionId)) {
            sendJsonRpcError(exchange, 404, -32600,
                "Unknown or expired MCP session: " + sessionId);
            return null;
        }

        exchange.getResponseHeaders().set(MCP_SESSION_ID_HEADER, sessionId);
        return HttpOwnerResolution.established(sessionId);
    }

    /**
     * Publishes a newly allocated session only on the HTTP response containing an
     * InitializeResult, as required by the Streamable HTTP transport specification.
     */
    boolean completeInitialization(
        @NotNull HttpExchange exchange,
        @NotNull HttpOwnerResolution owner,
        @Nullable String response
    ) {
        String sessionId = owner.newSessionId();
        if (sessionId == null) return true;
        if (!containsInitializeResult(response)) {
            closeHttpSession(sessionId);
            return false;
        }
        exchange.getResponseHeaders().set(MCP_SESSION_ID_HEADER, sessionId);
        return true;
    }

    static boolean containsInitializeResult(@Nullable String response) {
        if (response == null) return false;
        try {
            JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
            return !parsed.has("error")
                && parsed.has("result")
                && parsed.get("result").isJsonObject();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void handleSessionDelete(@NotNull HttpExchange exchange) throws IOException {
        String sessionId = exchange.getRequestHeaders().getFirst(MCP_SESSION_ID_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            sendJsonRpcError(exchange, 400, -32600,
                "Missing " + MCP_SESSION_ID_HEADER);
            exchange.close();
            return;
        }
        if (!closeHttpSession(sessionId)) {
            sendJsonRpcError(exchange, 404, -32600,
                "Unknown or expired MCP session: " + sessionId);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private boolean closeHttpSession(@NotNull String sessionId) {
        if (!httpSessions.closeSession(sessionId)) return false;
        AgentTabTracker.getInstance(project).closeOwnedTerminalTabs(
            McpSessionRegistry.ownerKey("http", sessionId));
        return true;
    }

    private void startHttpSessionCleanup() {
        sessionCleanupExecutor = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "mcp-http-session-cleanup");
                thread.setDaemon(true);
                return thread;
            });
        sessionCleanupExecutor.scheduleAtFixedRate(
            this::closeExpiredHttpSessions,
            HTTP_SESSION_SWEEP_INTERVAL_MINUTES,
            HTTP_SESSION_SWEEP_INTERVAL_MINUTES,
            java.util.concurrent.TimeUnit.MINUTES
        );
    }

    private void stopHttpSessionCleanup() {
        if (sessionCleanupExecutor == null) return;
        sessionCleanupExecutor.shutdownNow();
        sessionCleanupExecutor = null;
    }

    private void closeExpiredHttpSessions() {
        try {
            var expired = httpSessions.expireIdleSessions(getHttpSessionIdleTimeoutNanos());
            if (expired.isEmpty() || project.isDisposed()) return;

            AgentTabTracker tracker = AgentTabTracker.getInstance(project);
            for (String sessionId : expired) {
                tracker.closeOwnedTerminalTabs(
                    McpSessionRegistry.ownerKey("http", sessionId));
            }
            LOG.info("Expired " + expired.size() + " idle MCP HTTP session(s)");
        } catch (RuntimeException e) {
            LOG.warn("Failed to expire idle MCP HTTP sessions", e);
        }
    }

    private int getMaxOpenHttpSessions() {
        McpServerSettings settings = tryGetSettings();
        return settings != null
            ? settings.getMaxOpenHttpSessions()
            : McpServerSettings.DEFAULT_MAX_OPEN_HTTP_SESSIONS;
    }

    private long getHttpSessionIdleTimeoutNanos() {
        McpServerSettings settings = tryGetSettings();
        int minutes = settings != null
            ? settings.getHttpSessionIdleTimeoutMinutes()
            : McpServerSettings.DEFAULT_HTTP_SESSION_IDLE_TIMEOUT_MINUTES;
        return java.util.concurrent.TimeUnit.MINUTES.toNanos(minutes);
    }

    /**
     * Reads the persistent settings service for this project. Returns {@code null} when the
     * service cannot be resolved (e.g. mock projects in unit tests) so callers can fall back
     * to the shipping defaults without letting a test-only wiring quirk break production paths.
     */
    private @Nullable McpServerSettings tryGetSettings() {
        try {
            return McpServerSettings.getInstance(project);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Refreshes activity after a request completes. If DELETE or idle expiry won a race with an
     * in-flight tool call, close any terminal that the call created after session termination.
     */
    private void finishHttpRequest(@NotNull String ownerKey) {
        if (!ownerKey.startsWith(HTTP_OWNER_PREFIX)) return;
        String sessionId = ownerKey.substring(HTTP_OWNER_PREFIX.length());
        if (!httpSessions.touch(sessionId) && !project.isDisposed()) {
            AgentTabTracker.getInstance(project).closeOwnedTerminalTabs(ownerKey);
        }
    }

    private void closeAllHttpSessions() {
        AgentTabTracker tracker = AgentTabTracker.getInstance(project);
        for (String sessionId : httpSessions.drainSessions()) {
            tracker.closeOwnedTerminalTabs(
                McpSessionRegistry.ownerKey("http", sessionId));
        }
    }

    /**
     * Builds a health-check JSON response string. Package-private for testing.
     */
    static String buildHealthResponse(boolean serverRunning, String transportName, String projectName) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("status", serverRunning ? "ok" : "stopped");
        obj.addProperty("transport", transportName);
        obj.addProperty("project", projectName);
        obj.addProperty("server", "agentbridge");
        obj.addProperty("version", com.github.catatafishen.agentbridge.BuildInfo.getVersion());
        return obj.toString();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        String transport = activeTransportMode != null ? activeTransportMode.name() : "none";
        String json = buildHealthResponse(running, transport, project.getName());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void dispose() {
        stop();
    }

    /**
     * Builds a JSON-RPC error response string. Uses Gson for proper JSON escaping.
     * Package-private for testing.
     */
    static String buildJsonRpcErrorResponse(int rpcCode, String message) {
        com.google.gson.JsonObject error = new com.google.gson.JsonObject();
        error.addProperty("code", rpcCode);
        error.addProperty("message", message);
        com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("error", error);
        return resp.toString();
    }

    /**
     * Sends a JSON-RPC error response over the HTTP exchange.
     */
    private static void sendJsonRpcError(HttpExchange exchange, int httpStatus, int rpcCode, String message) throws IOException {
        byte[] bytes = buildJsonRpcErrorResponse(rpcCode, message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(httpStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
