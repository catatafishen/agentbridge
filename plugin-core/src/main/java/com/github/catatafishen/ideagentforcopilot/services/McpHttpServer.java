package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings;
import com.github.catatafishen.ideagentforcopilot.settings.TransportMode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
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

    public synchronized void start() throws IOException {
        if (running) return;
        McpServerSettings settings = McpServerSettings.getInstance(project);
        int port = settings.getPort();
        activeTransportMode = settings.getTransportMode();

        // Ensure PsiBridgeService (tool execution engine) is running
        PsiBridgeService bridge = PsiBridgeService.getInstance(project);
        if (!bridge.isRunning()) {
            bridge.start();
        }

        protocolHandler = new McpProtocolHandler(project);
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpServer.createContext("/health", this::handleHealth);

        if (activeTransportMode == TransportMode.SSE) {
            sseTransport = new McpSseTransport(protocolHandler);
            httpServer.createContext("/sse", sseTransport::handleSseConnect);
            httpServer.createContext("/message", sseTransport::handleMessage);
            sseTransport.start();
        } else {
            httpServer.createContext("/mcp", this::handleMcp);
        }

        httpServer.setExecutor(Executors.newFixedThreadPool(8));
        httpServer.start();
        running = true;
        LOG.info("MCP server started on port " + port + " (" + activeTransportMode.getDisplayName()
            + ") for project: " + project.getBasePath());
    }

    /**
     * Start on a specific port (saves the port to settings first).
     */
    public synchronized void start(int port) throws IOException {
        McpServerSettings.getInstance(project).setPort(port);
        start();
    }

    public synchronized void stop() {
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
        LOG.info("MCP HTTP server stopped for project: " + project.getBasePath());
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
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = protocolHandler.handleMessage(body);

            if (response == null) {
                // Notification — no response needed
                exchange.sendResponseHeaders(202, -1);
            } else {
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
