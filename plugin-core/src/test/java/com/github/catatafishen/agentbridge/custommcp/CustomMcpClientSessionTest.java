package com.github.catatafishen.agentbridge.custommcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CustomMcpClient} session handling.
 * Uses {@link HttpServer} (JDK built-in) as a lightweight embedded MCP server stub.
 */
class CustomMcpClientSessionTest {

    private HttpServer server;
    private String serverUrl;
    private final CopyOnWriteArrayList<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        serverUrl = "http://127.0.0.1:" + port + "/mcp";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── Phase 1: Session ID capture & propagation ─────────────────────

    @Test
    void initialize_capturesSessionIdFromResponseHeader() throws IOException {
        String sessionId = "test-session-abc123";
        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, sessionId);
            respondJson(exchange, initializeResult());
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            assertEquals(sessionId, client.getSessionId(), "Session ID should be captured from initialize response");
        }
    }

    @Test
    void initialize_noSessionHeader_sessionIdRemainsNull() throws IOException {
        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            respondJson(exchange, initializeResult());
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            assertNull(client.getSessionId(), "Session ID should remain null when server doesn't send it");
        }
    }

    @Test
    void listTools_sendsSessionIdWhenPresent() throws IOException {
        String sessionId = "session-for-list";
        AtomicInteger callCount = new AtomicInteger();

        setupHandler((exchange) -> {
            RecordedRequest req = recordRequest(exchange);
            requests.add(req);
            int call = callCount.incrementAndGet();

            if (call == 1) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, sessionId);
                respondJson(exchange, initializeResult());
            } else {
                respondJson(exchange, toolsListResult("echo", "Echo tool"));
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            client.listTools();

            assertEquals(2, requests.size());
            assertNull(requests.get(0).sessionHeader, "initialize must not send session header");
            assertEquals(sessionId, requests.get(1).sessionHeader, "listTools must send session header");
        }
    }

    @Test
    void callTool_sendsSessionIdWhenPresent() throws IOException {
        String sessionId = "session-for-call";
        AtomicInteger callCount = new AtomicInteger();

        setupHandler((exchange) -> {
            RecordedRequest req = recordRequest(exchange);
            requests.add(req);
            int call = callCount.incrementAndGet();

            if (call == 1) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, sessionId);
                respondJson(exchange, initializeResult());
            } else if (call == 2) {
                respondJson(exchange, toolsListResult("echo", "Echo tool"));
            } else {
                respondJson(exchange, toolCallResult("hello"));
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            client.listTools();
            client.callTool("echo", new JsonObject());

            assertEquals(3, requests.size());
            assertNull(requests.get(0).sessionHeader, "initialize must not send session header");
            assertEquals(sessionId, requests.get(1).sessionHeader, "listTools must send session header");
            assertEquals(sessionId, requests.get(2).sessionHeader, "callTool must send session header");
        }
    }

    @Test
    void statelessServer_noSessionHeaderSentOnSubsequentRequests() throws IOException {
        AtomicInteger callCount = new AtomicInteger();

        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            int call = callCount.incrementAndGet();

            if (call == 1) {
                respondJson(exchange, initializeResult());
            } else {
                respondJson(exchange, toolsListResult("ping", "Ping"));
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            client.listTools();

            assertNull(client.getSessionId());
            assertNull(requests.get(0).sessionHeader);
            assertNull(requests.get(1).sessionHeader);
        }
    }

    // ── Phase 2: Session expiry recovery (404 retry) ──────────────────

    @Test
    void callTool_recoversFromSessionExpiry() throws IOException {
        String firstSession = "session-old";
        String secondSession = "session-new";
        AtomicInteger callCount = new AtomicInteger();

        setupHandler((exchange) -> {
            RecordedRequest req = recordRequest(exchange);
            requests.add(req);
            int call = callCount.incrementAndGet();

            if (call == 1) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, firstSession);
                respondJson(exchange, initializeResult());
            } else if (call == 2) {
                // tools/call → 404 (session expired)
                exchange.sendResponseHeaders(404, -1);
            } else if (call == 3) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, secondSession);
                respondJson(exchange, initializeResult());
            } else {
                respondJson(exchange, toolCallResult("recovered"));
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            String result = client.callTool("echo", new JsonObject());

            assertEquals("recovered", result);
            assertEquals(secondSession, client.getSessionId());
            assertEquals(4, requests.size(), "Should be: init → call(404) → reinit → retry");
            assertNull(requests.get(2).sessionHeader, "Re-initialize must not send session header");
            assertEquals(secondSession, requests.get(3).sessionHeader, "Retry must use new session");
        }
    }

    @Test
    void callTool_doesNotRetryMoreThanOnce() throws IOException {
        String session = "session-doomed";
        AtomicInteger callCount = new AtomicInteger();

        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            int call = callCount.incrementAndGet();

            if (call == 1) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, session);
                respondJson(exchange, initializeResult());
            } else if (call == 2) {
                exchange.sendResponseHeaders(404, -1);
            } else if (call == 3) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, "session-new");
                respondJson(exchange, initializeResult());
            } else {
                // Retry also gets 404 — should NOT trigger another retry
                exchange.sendResponseHeaders(404, -1);
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            String result = client.callTool("echo", new JsonObject());

            assertEquals(4, requests.size(), "Should not retry more than once");
            assertTrue(result.contains("Failed") || result.contains("Error") || result.isEmpty(),
                "Should surface error when retry also fails");
        }
    }

    @Test
    void listTools_recoversFromSessionExpiry() throws IOException {
        String firstSession = "sess-1";
        String secondSession = "sess-2";
        AtomicInteger callCount = new AtomicInteger();

        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            int call = callCount.incrementAndGet();

            if (call == 1) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, firstSession);
                respondJson(exchange, initializeResult());
            } else if (call == 2) {
                exchange.sendResponseHeaders(404, -1);
            } else if (call == 3) {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, secondSession);
                respondJson(exchange, initializeResult());
            } else {
                respondJson(exchange, toolsListResult("ping", "Ping tool"));
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            List<CustomMcpClient.ToolInfo> tools = client.listTools();

            assertEquals(1, tools.size());
            assertEquals("ping", tools.getFirst().name());
            assertEquals(secondSession, client.getSessionId());
        }
    }

    // ── Phase 3: Session termination (close) ──────────────────────────

    @Test
    void close_sendsDeleteWithSessionId() throws IOException {
        String sessionId = "session-to-close";
        AtomicReference<String> deleteMethod = new AtomicReference<>();
        AtomicReference<String> deleteSessionHeader = new AtomicReference<>();

        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteMethod.set(exchange.getRequestMethod());
                deleteSessionHeader.set(exchange.getRequestHeaders().getFirst(CustomMcpClient.SESSION_HEADER));
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, sessionId);
                respondJson(exchange, initializeResult());
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            assertEquals(sessionId, client.getSessionId());

            client.close();

            assertNull(client.getSessionId(), "Session ID should be cleared after close");
            assertEquals("DELETE", deleteMethod.get());
            assertEquals(sessionId, deleteSessionHeader.get());
        }
    }

    @Test
    void close_noopWhenNoSession() throws IOException {
        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            respondJson(exchange, initializeResult());
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            assertNull(client.getSessionId());

            int requestsBefore = requests.size();
            client.close();

            assertEquals(requestsBefore, requests.size(), "close should not send DELETE when no session");
        }
    }

    @Test
    void close_swallows405FromServer() throws IOException {
        String sessionId = "session-405";

        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
            } else {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, sessionId);
                respondJson(exchange, initializeResult());
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();
            client.close();
            assertNull(client.getSessionId());
        }
    }

    @Test
    void close_isIdempotent() throws IOException {
        String sessionId = "session-idempotent";
        AtomicInteger deleteCount = new AtomicInteger();

        setupHandler((exchange) -> {
            requests.add(recordRequest(exchange));
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteCount.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.getResponseHeaders().add(CustomMcpClient.SESSION_HEADER, sessionId);
                respondJson(exchange, initializeResult());
            }
        });
        server.start();

        try (CustomMcpClient client = new CustomMcpClient(serverUrl)) {
            client.initialize();

            client.close();
            client.close();
            client.close();

            assertEquals(1, deleteCount.get(), "Only the first close should send DELETE");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void setupHandler(RequestHandler handler) {
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
    }

    @FunctionalInterface
    private interface RequestHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record RecordedRequest(String method, String sessionHeader, String body) {
    }

    private static RecordedRequest recordRequest(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String sessionHeader = exchange.getRequestHeaders().getFirst(CustomMcpClient.SESSION_HEADER);
        String body = "";
        try {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // DELETE may have no body
        }
        return new RecordedRequest(method, sessionHeader, body);
    }

    private static void respondJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String initializeResult() {
        return """
            {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1.0"}}}""";
    }

    private static String toolsListResult(String toolName, String description) {
        return """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"%s","description":"%s","inputSchema":{"type":"object","properties":{}}}]}}"""
            .formatted(toolName, description);
    }

    private static String toolCallResult(String text) {
        return """
            {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"%s"}]}}"""
            .formatted(text);
    }
}
