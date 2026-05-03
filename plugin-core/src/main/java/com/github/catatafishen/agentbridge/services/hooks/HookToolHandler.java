package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
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
public final class HookToolHandler extends AbstractHookHandler {

    private static final Logger LOG = Logger.getInstance(HookToolHandler.class);
    private static final int MAX_BODY_BYTES = 65_536;
    private static final int MAX_RESULT_CHARS = 100_000;

    private static final Set<ToolDefinition.Kind> ALLOWED_KINDS = Set.of(
        ToolDefinition.Kind.READ,
        ToolDefinition.Kind.SEARCH
    );

    public HookToolHandler(@NotNull Project project) {
        super(project);
    }

    @Override
    int maxBodyBytes() {
        return MAX_BODY_BYTES;
    }

    @Override
    String handlerName() {
        return "Hook tool execution";
    }

    @Override
    String processPost(@NotNull JsonObject request) {
        if (!request.has("tool")) {
            return errorResponse("'tool' field is required");
        }
        String toolId = request.get("tool").getAsString();
        JsonObject arguments = request.has("arguments")
            ? request.getAsJsonObject("arguments")
            : new JsonObject();
        return executeTool(toolId, arguments);
    }

    @Override
    void sendError(@NotNull HttpExchange exchange, int status, @NotNull String message) throws IOException {
        sendJson(exchange, status, errorResponse(message));
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
}
