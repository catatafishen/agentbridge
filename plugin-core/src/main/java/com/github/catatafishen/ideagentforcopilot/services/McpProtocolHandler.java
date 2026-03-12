package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings;
import com.github.catatafishen.ideagentforcopilot.settings.McpToolFilter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Handles MCP (Model Context Protocol) JSON-RPC messages.
 * Translates between MCP protocol and the PsiBridgeService tool handlers.
 */
public final class McpProtocolHandler {
    private static final Logger LOG = Logger.getInstance(McpProtocolHandler.class);
    private static final Gson GSON = new GsonBuilder().create();

    private static final String SERVER_NAME = "ide-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final Project project;

    public McpProtocolHandler(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Handles a JSON-RPC request and returns a JSON-RPC response.
     * Returns null for notifications (no id field).
     */
    public String handleMessage(String messageJson) {
        try {
            JsonObject msg = JsonParser.parseString(messageJson).getAsJsonObject();
            String method = msg.has("method") ? msg.get("method").getAsString() : null;
            if (method == null) return null;

            JsonObject result = switch (method) {
                case "initialize" -> handleInitialize(msg);
                case "tools/list" -> handleToolsList(msg);
                case "tools/call" -> handleToolsCall(msg);
                case "ping" -> respondResult(msg, new JsonObject());
                default -> respondError(msg, -32601, "Method not found: " + method);
            };

            if (!msg.has("id")) return null;
            return GSON.toJson(result);
        } catch (Exception e) {
            LOG.warn("MCP protocol error", e);
            return GSON.toJson(makeErrorResponse(null, -32700, "Parse error: " + e.getMessage()));
        }
    }

    /**
     * Handles the MCP initialize request.
     *
     * <p>The {@code instructions} field in the result is the MCP-spec mechanism for servers to
     * inject context into the client's system prompt. Agents that properly implement MCP (e.g.
     * OpenCode, Goose, Continue) will incorporate this into the model context automatically —
     * no project-file mutation needed. Copilot ignores this field (known bug), so it relies on
     * the separate {@code CopilotInstructionsManager} file-injection workaround instead.</p>
     */
    private JsonObject handleInitialize(JsonObject msg) {
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);

        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);

        String instructions = loadInstructions();
        if (instructions != null) {
            result.addProperty("instructions", instructions);
        }

        return respondResult(msg, result);
    }

    private static String loadInstructions() {
        try (java.io.InputStream is = McpProtocolHandler.class.getResourceAsStream("/default-startup-instructions.md")) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            LOG.warn("Failed to load default-startup-instructions.md for MCP initialize", e);
        }
        return null;
    }

    private JsonObject handleToolsList(JsonObject msg) {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        List<ToolDefinition> enabledTools = McpToolFilter.getEnabledTools(settings, project);

        JsonArray tools = new JsonArray();
        for (ToolDefinition entry : enabledTools) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", entry.id());
            tool.addProperty("description", entry.description());
            JsonObject schema = entry.inputSchema();
            tool.add("inputSchema", schema != null ? schema : new JsonObject());
            tool.add("annotations", entry.mcpAnnotations());
            tools.add(tool);
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return respondResult(msg, result);
    }

    private JsonObject handleToolsCall(JsonObject msg) {
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
        String toolName = params.has("name") ? params.get("name").getAsString() : null;
        if (toolName == null) {
            return respondError(msg, -32602, "Missing tool name");
        }

        // Check if tool is enabled
        McpServerSettings settings = McpServerSettings.getInstance(project);
        if (!settings.isToolEnabled(toolName) || McpToolFilter.isAlwaysHidden(toolName)) {
            return respondError(msg, -32602, "Tool is disabled: " + toolName);
        }

        JsonObject arguments = params.has("arguments")
            ? params.getAsJsonObject("arguments") : new JsonObject();

        LOG.info("MCP tool call: " + toolName);

        // Delegate to PsiBridgeService
        try {
            PsiBridgeService bridge = PsiBridgeService.getInstance(project);
            String resultText = bridge.callTool(toolName, arguments);

            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("text", resultText != null ? resultText : "");

            JsonArray contentArray = new JsonArray();
            contentArray.add(content);

            JsonObject result = new JsonObject();
            result.add("content", contentArray);
            result.addProperty("isError", resultText != null && resultText.startsWith("Error:"));

            return respondResult(msg, result);
        } catch (Exception e) {
            LOG.warn("MCP tool error: " + toolName, e);
            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("text", "Error: " + e.getMessage());

            JsonArray contentArray = new JsonArray();
            contentArray.add(content);

            JsonObject result = new JsonObject();
            result.add("content", contentArray);
            result.addProperty("isError", true);

            return respondResult(msg, result);
        }
    }

    private static JsonObject respondResult(JsonObject request, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (request != null && request.has("id")) {
            response.add("id", request.get("id"));
        }
        response.add("result", result);
        return response;
    }

    private static JsonObject respondError(JsonObject request, int code, String message) {
        return makeErrorResponse(
            request != null && request.has("id") ? request.get("id") : null,
            code, message
        );
    }

    private static JsonObject makeErrorResponse(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", id);
        }
        response.add("error", error);
        return response;
    }
}
