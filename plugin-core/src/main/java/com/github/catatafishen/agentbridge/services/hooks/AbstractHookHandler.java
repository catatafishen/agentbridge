package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared HTTP request handling for hook endpoint handlers.
 *
 * <p>Centralises CORS preflight, POST-only enforcement, body reading with size limit,
 * JSON dispatch, and error handling. Subclasses implement {@link #processPost} to
 * handle the parsed request and return a JSON response string.</p>
 */
abstract class AbstractHookHandler {

    static final String CONTENT_TYPE = "Content-Type";
    static final String APPLICATION_JSON = "application/json";

    @NotNull
    protected final Project project;

    private final Logger log = Logger.getInstance(getClass());

    AbstractHookHandler(@NotNull Project project) {
        this.project = project;
    }

    public final void handle(@NotNull HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Only POST is supported");
            return;
        }

        int maxBytes = maxBodyBytes();
        try {
            byte[] bodyBytes = exchange.getRequestBody().readNBytes(maxBytes + 1);
            if (bodyBytes.length > maxBytes) {
                sendError(exchange, 413, "Request body exceeds " + maxBytes + " bytes");
                return;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            String response = processPost(request);
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            log.warn(handlerName() + " error", e);
            sendError(exchange, 500, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            exchange.close();
        }
    }

    /**
     * Maximum number of request body bytes this handler accepts.
     */
    abstract int maxBodyBytes();

    /**
     * Human-readable handler name used in log messages.
     */
    abstract String handlerName();

    /**
     * Processes the parsed request body and returns a JSON response string.
     */
    abstract String processPost(@NotNull JsonObject request) throws Exception;

    /**
     * Sends an error response in this handler's error JSON format.
     */
    abstract void sendError(@NotNull HttpExchange exchange, int status, @NotNull String message) throws IOException;

    static void sendJson(@NotNull HttpExchange exchange, int status, @NotNull String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
