package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Handles {@code POST /hooks/tool} requests from hook scripts.
 *
 * <p>Provides direct, non-agentic access to read-only MCP tools. Hook scripts
 * can call any tool with {@link ToolDefinition.Kind#READ} or
 * {@link ToolDefinition.Kind#SEARCH} kind to query IDE state during hook
 * execution.</p>
 *
 * <p>Calls go straight to {@link ToolDefinition#execute} — bypassing the
 * entire {@code PsiBridgeService} pipeline (no permission checks, no hook
 * triggering, no focus guards, no chip registry, no auto-highlights).</p>
 *
 * <h3>Request format:</h3>
 * <pre>{@code
 * POST /hooks/tool
 * {"tool": "search_text", "arguments": {"query": "MyClass", "file_pattern": "*.java"}}
 * }</pre>
 *
 * <h3>Response format:</h3>
 * <pre>{@code
 * {"result": "...", "error": false}
 * }</pre>
 */
public final class HookToolHandler {
    private static final Logger LOG = Logger.getInstance(HookToolHandler.class);
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final int MAX_BODY_BYTES = 65_536;
    private static final int MAX_RESULT_CHARS = 100_000;

    private static final Set<ToolDefinition.Kind> ALLOWED_KINDS = Set.of(
        ToolDefinition.Kind.READ,
        ToolDefinition.Kind.SEARCH
    );

    private final Project project;

    public HookToolHandler(@NotNull Project project) {
        this.project = project;
    }

    public void handle(@NotNull HttpExchange exchange) throws IOException {
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

        try {
            byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bodyBytes.length > MAX_BODY_BYTES) {
                sendError(exchange, 413, "Request body exceeds " + MAX_BODY_BYTES + " bytes");
                return;
            }

            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();

            if (!request.has("tool")) {
                sendError(exchange, 400, "'tool' field is required");
                return;
            }

            String toolId = request.get("tool").getAsString();
            JsonObject arguments = request.has("arguments")
                ? request.getAsJsonObject("arguments")
                : new JsonObject();

            String response = executeTool(toolId, arguments);
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            LOG.warn("Hook tool execution error", e);
            sendError(exchange, 500,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            exchange.close();
        }
    }

    private String executeTool(String toolId, JsonObject arguments) {
        ToolRegistry registry = ToolRegistry.getInstance(project);
        ToolDefinition def = registry.findDefinition(toolId);
        if (def == null) {
            return errorResponse("Unknown tool: " + toolId);
        }

        if (!ALLOWED_KINDS.contains(def.kind())) {
            return errorResponse("Tool '" + toolId + "' is not allowed from hooks. "
                + "Only read-only and search tools are available (kind: "
                + def.kind().value() + " is not permitted).");
        }

        if (!def.hasExecutionHandler()) {
            return errorResponse("Tool '" + toolId + "' has no execution handler");
        }

        try {
            String result = def.execute(arguments, null);
            return successResponse(result);
        } catch (Exception e) {
            LOG.warn("Hook tool call failed: " + toolId, e);
            return errorResponse("Tool execution failed: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static String successResponse(String result) {
        JsonObject response = new JsonObject();
        if (result != null && result.length() > MAX_RESULT_CHARS) {
            response.addProperty("result", result.substring(0, MAX_RESULT_CHARS));
            response.addProperty("truncated", true);
        } else {
            response.addProperty("result", result != null ? result : "");
            response.addProperty("truncated", false);
        }
        response.addProperty("error", false);
        return response.toString();
    }

    private static String errorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("error", true);
        response.addProperty("message", message);
        return response.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, errorResponse(message));
    }
}
