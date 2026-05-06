package com.github.catatafishen.agentbridge.custommcp.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A short-lived HTTP server on {@code localhost:0} (OS-assigned port) that captures the
 * OAuth authorization code from the browser redirect after user authentication.
 *
 * <p>Usage:
 * <pre>{@code
 * try (McpOAuthCallbackServer srv = new McpOAuthCallbackServer()) {
 *     srv.start();
 *     BrowserUtil.browse(buildAuthUrl(srv.getCallbackUri(), ...));
 *     Result result = srv.waitForCallback(5, TimeUnit.MINUTES);
 * }
 * }</pre>
 */
public final class McpOAuthCallbackServer implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(McpOAuthCallbackServer.class);
    private static final String CALLBACK_PATH = "/callback";
    private static final String SUCCESS_HTML =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<title>Authentication Successful</title>" +
        "<style>body{font-family:sans-serif;text-align:center;padding:60px;color:#1a1a1a}" +
        "h1{color:#0a7c42}</style></head><body>" +
        "<h1>&#x2713; Authentication Successful</h1>" +
        "<p>You can close this window and return to your IDE.</p>" +
        "</body></html>";
    private static final String ERROR_HTML_TEMPLATE =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<title>Authentication Failed</title>" +
        "<style>body{font-family:sans-serif;text-align:center;padding:60px;color:#1a1a1a}" +
        "h1{color:#c0392b}</style></head><body>" +
        "<h1>Authentication Failed</h1><p>%s</p>" +
        "<p>You can close this window and retry from your IDE.</p>" +
        "</body></html>";

    /** Result returned by {@link #waitForCallback(long, TimeUnit)}. */
    public record Result(
        @NotNull String code,
        @NotNull String state
    ) {
    }

    private HttpServer server;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<Result> resultRef = new AtomicReference<>();
    private final AtomicReference<String> errorRef = new AtomicReference<>();

    /**
     * Starts the server on {@code localhost:0}.
     *
     * @throws IOException if the server cannot bind
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(CALLBACK_PATH, this::handleCallback);
        server.setExecutor(null);
        server.start();
        LOG.info("OAuth callback server listening on port " + getPort());
    }

    /**
     * Returns the localhost port the server is bound to. Call after {@link #start()}.
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /**
     * Returns the full redirect URI to use in the OAuth authorization request.
     * Call after {@link #start()}.
     */
    @NotNull
    public String getCallbackUri() {
        return "http://localhost:" + getPort() + CALLBACK_PATH;
    }

    /**
     * Blocks until the browser redirect is received or the timeout expires.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit for the timeout
     * @return the received code and state, or {@code null} on timeout or error
     */
    @Nullable
    public Result waitForCallback(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        boolean received = latch.await(timeout, unit);
        if (!received) {
            LOG.warn("OAuth callback timed out after " + timeout + " " + unit);
            return null;
        }
        if (errorRef.get() != null) {
            LOG.warn("OAuth callback returned error: " + errorRef.get());
            return null;
        }
        return resultRef.get();
    }

    @Override
    public void close() {
        latch.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleCallback(@NotNull HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> params = parseQuery(query != null ? query : "");

            if (params.containsKey("error")) {
                String description = params.getOrDefault("error_description", params.get("error"));
                errorRef.set(description);
                sendHtmlResponse(exchange, 400, String.format(ERROR_HTML_TEMPLATE, description));
            } else {
                String code = params.get("code");
                String state = params.get("state");
                if (code == null || code.isBlank()) {
                    errorRef.set("Missing authorization code");
                    sendHtmlResponse(exchange, 400, String.format(ERROR_HTML_TEMPLATE, "Missing authorization code"));
                } else {
                    resultRef.set(new Result(code, state != null ? state : ""));
                    sendHtmlResponse(exchange, 200, SUCCESS_HTML);
                }
            }
        } finally {
            latch.countDown();
        }
    }

    private static void sendHtmlResponse(@NotNull HttpExchange exchange, int status, @NotNull String html)
        throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @NotNull
    private static Map<String, String> parseQuery(@NotNull String query) {
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                params.put(decode(key), decode(value));
            }
        }
        return params;
    }

    private static String decode(@NotNull String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
