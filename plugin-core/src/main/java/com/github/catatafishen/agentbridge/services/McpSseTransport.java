package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.diagnostic.Logger;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the MCP SSE (Server-Sent Events) transport.
 *
 * <p>Protocol flow:
 * <ol>
 *   <li>Client opens {@code GET /sse} — server sends an {@code endpoint} event
 *       with the message URL, then keeps the connection open.</li>
 *   <li>Client sends JSON-RPC requests to {@code POST /message?sessionId=xxx}.</li>
 *   <li>Server processes the request via {@link McpProtocolHandler} and pushes
 *       the response back through the SSE stream as a {@code message} event.</li>
 * </ol>
 *
 * <p>A background keep-alive task sends SSE comments every 30 seconds to
 * prevent proxy and client timeouts.
 */
final class McpSseTransport {

    private static final Logger LOG = Logger.getInstance(McpSseTransport.class);
    private static final long KEEP_ALIVE_INTERVAL_SECONDS = 30;
    private static final int MAX_SSE_SESSIONS = 10;
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    private final McpProtocolHandler protocolHandler;
    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCount = new AtomicInteger(0);
    private ScheduledExecutorService keepAliveExecutor;

    McpSseTransport(@NotNull McpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
    }

    void start() {
        keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-sse-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepAliveExecutor.scheduleAtFixedRate(
            this::sendKeepAliveToAll,
            KEEP_ALIVE_INTERVAL_SECONDS,
            KEEP_ALIVE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    void stop() {
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
            keepAliveExecutor = null;
        }
        for (SseSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Handles {@code GET /sse}: opens an SSE stream and sends the endpoint event.
     */
    void handleSseConnect(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        // Atomic session count check: increment first, rollback if over limit
        int count = sessionCount.incrementAndGet();
        if (count > MAX_SSE_SESSIONS) {
            sessionCount.decrementAndGet();
            LOG.warn("SSE session limit reached (" + MAX_SSE_SESSIONS + "), rejecting new connection");
            exchange.getResponseHeaders().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            sendJsonError(exchange, 503, "SSE session limit reached (" + MAX_SSE_SESSIONS + ")");
            return;
        }

        exchange.getResponseHeaders().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        exchange.getResponseHeaders().set(CONTENT_TYPE, "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        SseSession session = new SseSession(exchange);
        sessions.put(session.getSessionId(), session);

        String endpointUrl = "/message?sessionId=" + session.getSessionId();
        try {
            session.sendEvent("endpoint", endpointUrl);
            LOG.info("SSE session opened: " + session.getSessionId());
            // Block the handler thread to keep the HTTP exchange open until the client
            // disconnects or the server is shut down. Without this, HttpServer closes the
            // exchange as soon as the handler returns, immediately dropping the SSE stream.
            session.awaitClose();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.warn("Failed to send endpoint event", e);
        } finally {
            removeSession(session.getSessionId());
            sessionCount.decrementAndGet();
            LOG.info("SSE session handler exiting: " + session.getSessionId());
        }
    }

    /**
     * Handles {@code POST /message?sessionId=xxx}: processes a JSON-RPC request
     * and sends the response through the corresponding SSE stream.
     */
    void handleMessage(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
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

        String query = exchange.getRequestURI().getQuery();
        String sessionId = parseSessionId(query);
        if (sessionId == null) {
            sendJsonError(exchange, 400, "Missing sessionId parameter");
            return;
        }

        SseSession session = sessions.get(sessionId);
        if (session == null || session.isClosed()) {
            sendJsonError(exchange, 404, "Unknown or closed session: " + sessionId);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            String response = protocolHandler.handleMessage(body);

            // Acknowledge the POST request
            exchange.sendResponseHeaders(202, -1);
            exchange.close();

            // Send the response through the SSE stream (if not a notification)
            if (response != null) {
                session.sendEvent("message", response);
            }
        } catch (IOException e) {
            LOG.warn("SSE send failed for session " + sessionId, e);
            removeSession(sessionId);
            sendJsonError(exchange, 500, "SSE stream error: " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("MCP request error in SSE session " + sessionId, e);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            // Try to send error through SSE stream
            try {
                String errJson = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,"
                    + "\"message\":\"Internal error: " + e.getMessage().replace("\"", "'") + "\"}}";
                session.sendEvent("message", errJson);
            } catch (IOException ioEx) {
                LOG.warn("Failed to send error via SSE", ioEx);
                removeSession(sessionId);
            }
        }
    }

    private void removeSession(String sessionId) {
        SseSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            LOG.info("SSE session removed: " + sessionId);
        }
    }

    private void sendKeepAliveToAll() {
        for (Map.Entry<String, SseSession> entry : sessions.entrySet()) {
            SseSession session = entry.getValue();
            try {
                session.sendKeepAlive();
            } catch (IOException e) {
                LOG.info("SSE session disconnected during keepalive: " + entry.getKey());
                removeSession(entry.getKey());
            }
        }
    }

    private static String parseSessionId(String query) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "sessionId".equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    private static void sendJsonError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message.replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
