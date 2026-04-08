package com.github.catatafishen.agentbridge.services;

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
import java.util.concurrent.Executors;
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
            activeTransportMode = settings.getTransportMode();

            protocolHandler = new McpProtocolHandler(project);

            // Try to bind to the configured port; if it fails, auto-allocate the next available port
            int actualPort = port;
            IOException lastError = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                try {
                    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", actualPort), 0);
                    break;
                } catch (IOException e) {
                    lastError = e;
                    actualPort++;
                }
            }

            if (httpServer == null) {
                throw new IOException("Failed to bind MCP server to any port starting from " + port, lastError);
            }

            // If port changed, save it to settings
            if (actualPort != port) {
                settings.setPort(actualPort);
                LOG.info("[MCP] port conflict: " + port + " was in use; allocated " + actualPort + " instead for project: " + project.getBasePath());
            }

            httpServer.createContext("/health", this::handleHealth);

            if (activeTransportMode == TransportMode.SSE) {
                sseTransport = new McpSseTransport(protocolHandler);
                httpServer.createContext("/sse", sseTransport::handleSseConnect);
                httpServer.createContext("/message", sseTransport::handleMessage);
                sseTransport.start();
            } else {
                httpServer.createContext("/mcp", this::handleMcp);
            }

            // SSE mode blocks one thread per connection; use a cached pool so it scales.
            // Streamable HTTP uses short-lived requests, so a fixed pool would also work.
            httpServer.setExecutor(Executors.newCachedThreadPool());
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
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
            byte[] err = ("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: "
                + e.getMessage().replace("\"", "'") + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
            exchange.sendResponseHeaders(500, err.length);
            exchange.getResponseBody().write(err);
        } finally {
            exchange.close();
            activeConnections.decrementAndGet();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        String transport = activeTransportMode != null ? activeTransportMode.name() : "none";
        String json = "{\"status\":\"" + (running ? "ok" : "stopped") + "\","
            + "\"transport\":\"" + transport + "\","
            + "\"project\":\"" + (project.getName().replace("\"", "'")) + "\"}";
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
}
