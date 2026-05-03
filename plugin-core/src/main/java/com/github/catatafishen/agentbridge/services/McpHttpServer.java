package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.services.hooks.HookQueryHandler;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.settings.TransportMode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

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
    private final AtomicInteger activeConnections = new AtomicInteger(0);
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

            if (activeTransportMode == TransportMode.SSE) {
                sseTransport = new McpSseTransport(protocolHandler);
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
        // CORS headers for browser-based agents
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", CONTENT_TYPE);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        activeConnections.incrementAndGet();
        McpServerSettings settings = McpServerSettings.getInstance(project);
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
            String response = protocolHandler.handleMessage(body);

            if (response == null) {
                // Notification — no response needed
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
        } catch (Exception e) {
            LOG.warn("MCP request error", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sendJsonRpcError(exchange, 500, -32603, "Internal error: " + msg);
        } finally {
            exchange.close();
            activeConnections.decrementAndGet();
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
