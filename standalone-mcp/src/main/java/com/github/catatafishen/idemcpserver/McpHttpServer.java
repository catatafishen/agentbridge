package com.github.catatafishen.idemcpserver;

import com.github.catatafishen.idemcpserver.settings.McpServerSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
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
 * Listens on a configurable port and handles Streamable HTTP transport:
 * POST /mcp for JSON-RPC requests, GET /health for status checks.
 */
@Service(Service.Level.PROJECT)
public final class McpHttpServer implements Disposable {
    private static final Logger LOG = Logger.getInstance(McpHttpServer.class);
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final Project project;
    private HttpServer httpServer;
    private McpProtocolHandler protocolHandler;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private volatile boolean running;

    public McpHttpServer(@NotNull Project project) {
        this.project = project;
    }

    public static McpHttpServer getInstance(@NotNull Project project) {
        return project.getService(McpHttpServer.class);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        McpServerSettings settings = McpServerSettings.getInstance(project);
        int port = settings.getPort();

        protocolHandler = new McpProtocolHandler(project);
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpServer.createContext("/mcp", this::handleMcp);
        httpServer.createContext("/health", this::handleHealth);
        httpServer.setExecutor(Executors.newFixedThreadPool(8));
        httpServer.start();
        running = true;
        LOG.info("MCP HTTP server started on port " + port + " for project: " + project.getBasePath());
    }

    public synchronized void stop() {
        if (!running || httpServer == null) return;
        httpServer.stop(1);
        httpServer = null;
        protocolHandler = null;
        running = false;
        activeConnections.set(0);
        LOG.info("MCP HTTP server stopped for project: " + project.getBasePath());
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return httpServer != null ? httpServer.getAddress().getPort() : 0;
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    private void handleMcp(HttpExchange exchange) throws IOException {
        // CORS headers for browser-based agents
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

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
        String json = "{\"status\":\"" + (running ? "ok" : "stopped") + "\","
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
