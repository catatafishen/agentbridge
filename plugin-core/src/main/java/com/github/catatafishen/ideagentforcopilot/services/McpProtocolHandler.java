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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Handles MCP (Model Context Protocol) JSON-RPC messages.
 * Translates between MCP protocol and the PsiBridgeService tool handlers.
 */
public final class McpProtocolHandler {
    private static final Logger LOG = Logger.getInstance(McpProtocolHandler.class);
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Hard cap on tool result size. Keeps output below client-side truncation thresholds.
     */
    private static final int MAX_RESULT_CHARS = 80_000;
    private static final int RESOURCE_PAGE_SIZE = 200;
    private static final int RESOURCE_NOT_FOUND_ERROR = -32002;

    private static final String SERVER_NAME = "agentbridge";
    private static final String SERVER_VERSION = com.github.catatafishen.ideagentforcopilot.BuildInfo.getVersion();
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String STARTUP_INSTRUCTIONS_URI = "resource://default-startup-instructions.md";
    private static final String RESOURCES_CURSOR_PREFIX = "resources:";
    private static final String RESOURCE_TEMPLATES_CURSOR_PREFIX = "resourceTemplates:";

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
                case "resources/list" -> handleResourcesList(msg);
                case "resources/templates/list" -> handleResourceTemplatesList(msg);
                case "resources/read" -> handleResourcesRead(msg);
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

    private JsonObject handleInitialize(JsonObject msg) {
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);
        serverInfo.addProperty("description", "Code Intelligence tools for IntelliJ IDEA");

        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);

        JsonObject resourcesCap = new JsonObject();
        resourcesCap.addProperty("listChanged", false);
        capabilities.add("resources", resourcesCap);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", loadInstructions());

        return respondResult(msg, result);
    }

    private static String loadInstructions() {
        try (java.io.InputStream is = McpProtocolHandler.class.getResourceAsStream("/default-startup-instructions.md")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            LOG.warn("Resource /default-startup-instructions.md not found in classpath for MCP initialize");
        } catch (IOException e) {
            LOG.error("Failed to read /default-startup-instructions.md from classpath for MCP initialize", e);
        }
        return "You are running inside an IntelliJ IDEA plugin with IDE tools accessible via MCP.";
    }

    private JsonObject handleToolsList(JsonObject msg) {
        // Ensure PsiBridgeService is initialized before listing tools
        PsiBridgeService.getInstance(project);

        McpServerSettings settings = McpServerSettings.getInstance(project);
        settings.ensureDefaultsApplied();
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

    private JsonObject handleResourcesList(JsonObject msg) {
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
        int offset;
        try {
            offset = parseCursorOffset(params.has("cursor") ? params.get("cursor") : null, RESOURCES_CURSOR_PREFIX);
        } catch (InvalidCursorException e) {
            return respondError(msg, -32602, e.getMessage());
        }
        List<JsonObject> entries = buildResourceEntries();

        JsonArray resources = new JsonArray();
        int endExclusive = Math.min(entries.size(), offset + RESOURCE_PAGE_SIZE);
        for (int i = offset; i < endExclusive; i++) {
            resources.add(entries.get(i));
        }

        JsonObject result = new JsonObject();
        result.add("resources", resources);
        if (endExclusive < entries.size()) {
            result.addProperty("nextCursor", encodeCursor(RESOURCES_CURSOR_PREFIX, endExclusive));
        }
        return respondResult(msg, result);
    }

    private JsonObject handleResourceTemplatesList(JsonObject msg) {
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
        int offset;
        try {
            offset = parseCursorOffset(
                params.has("cursor") ? params.get("cursor") : null,
                RESOURCE_TEMPLATES_CURSOR_PREFIX
            );
        } catch (InvalidCursorException e) {
            return respondError(msg, -32602, e.getMessage());
        }

        List<JsonObject> templates = List.of(createResourceTemplate(
            "file:///{path}",
            "Project Files",
            "Project Files",
            "Access files in the current project directory",
            "application/octet-stream"
        ));

        JsonArray page = new JsonArray();
        int endExclusive = Math.min(templates.size(), offset + RESOURCE_PAGE_SIZE);
        for (int i = offset; i < endExclusive; i++) {
            page.add(templates.get(i));
        }

        JsonObject result = new JsonObject();
        result.add("resourceTemplates", page);
        if (endExclusive < templates.size()) {
            result.addProperty("nextCursor", encodeCursor(RESOURCE_TEMPLATES_CURSOR_PREFIX, endExclusive));
        }
        return respondResult(msg, result);
    }

    private JsonObject handleResourcesRead(JsonObject msg) {
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
        String uri = null;
        if (params.has("uri") && !params.get("uri").isJsonNull()) {
            uri = params.get("uri").getAsString();
        } else if (params.has("resource") && params.get("resource").isJsonObject()) {
            JsonObject resource = params.getAsJsonObject("resource");
            if (resource.has("uri") && !resource.get("uri").isJsonNull()) {
                uri = resource.get("uri").getAsString();
            }
        }

        if (uri == null || uri.isEmpty()) {
            return respondError(msg, -32602, "Missing resource URI");
        }

        ResourceReadResult readResult = readResource(uri);
        if (readResult.errorCode() != null) {
            String errorMessage = readResult.errorMessage() != null ? readResult.errorMessage() : "Resource not found";
            if (RESOURCE_NOT_FOUND_ERROR == readResult.errorCode()) {
                return respondResourceNotFound(msg, uri, errorMessage);
            }
            return respondError(msg, readResult.errorCode(), errorMessage);
        }

        JsonArray contents = new JsonArray();
        contents.add(readResult.content());

        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return respondResult(msg, result);
    }

    @NotNull
    private List<JsonObject> buildResourceEntries() {
        return List.of(createResource(
            STARTUP_INSTRUCTIONS_URI,
            "default-startup-instructions",
            "Default Startup Instructions",
            "Default startup instructions injected during initialize",
            "text/markdown",
            null
        ));
    }

    @NotNull
    private JsonObject createResource(@NotNull String uri,
                                      @NotNull String name,
                                      @Nullable String title,
                                      @Nullable String description,
                                      @Nullable String mimeType,
                                      @Nullable Long size) {
        JsonObject resource = new JsonObject();
        resource.addProperty("uri", uri);
        resource.addProperty("name", name);
        if (title != null && !title.isBlank()) {
            resource.addProperty("title", title);
        }
        if (description != null && !description.isBlank()) {
            resource.addProperty("description", description);
        }
        if (mimeType != null && !mimeType.isBlank()) {
            resource.addProperty("mimeType", mimeType);
        }
        if (size != null) {
            resource.addProperty("size", size);
        }
        return resource;
    }

    @NotNull
    private static JsonObject createResourceTemplate(@NotNull String uriTemplate,
                                                     @NotNull String name,
                                                     @Nullable String title,
                                                     @Nullable String description,
                                                     @Nullable String mimeType) {
        JsonObject template = new JsonObject();
        template.addProperty("uriTemplate", uriTemplate);
        template.addProperty("name", name);
        if (title != null && !title.isBlank()) {
            template.addProperty("title", title);
        }
        if (description != null && !description.isBlank()) {
            template.addProperty("description", description);
        }
        if (mimeType != null && !mimeType.isBlank()) {
            template.addProperty("mimeType", mimeType);
        }
        return template;
    }

    @NotNull
    private ResourceReadResult readResource(@NotNull String uri) {
        if (STARTUP_INSTRUCTIONS_URI.equals(uri)) {
            JsonObject content = new JsonObject();
            content.addProperty("uri", STARTUP_INSTRUCTIONS_URI);
            content.addProperty("mimeType", "text/markdown");
            content.addProperty("text", loadInstructions());
            return ResourceReadResult.success(content);
        }

        if (!uri.startsWith("file:")) {
            return ResourceReadResult.notFound("Resource not found");
        }

        Path projectRoot = getProjectRoot();
        if (projectRoot == null) {
            return ResourceReadResult.notFound("Resource not found");
        }

        Path path;
        try {
            path = Paths.get(new URI(uri)).toAbsolutePath().normalize();
        } catch (URISyntaxException | InvalidPathException e) {
            return ResourceReadResult.error(-32602, "Invalid resource URI: " + uri);
        }

        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!path.startsWith(normalizedRoot) || !Files.exists(path) || !Files.isRegularFile(path)) {
            return ResourceReadResult.notFound("Resource not found");
        }

        JsonObject content = new JsonObject();
        content.addProperty("uri", path.toUri().toString());
        content.addProperty("mimeType", guessMimeType(path));

        try {
            if (isTextResource(path)) {
                content.addProperty("text", Files.readString(path, StandardCharsets.UTF_8));
            } else {
                content.addProperty("blob", Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        } catch (IOException e) {
            LOG.warn("Failed to read MCP resource " + path, e);
            return ResourceReadResult.error(-32603, "Failed to read resource: " + uri);
        }

        return ResourceReadResult.success(content);
    }

    @Nullable
    private Path getProjectRoot() {
        if (project.getBasePath() == null || project.getBasePath().isBlank()) {
            return null;
        }
        try {
            return Paths.get(project.getBasePath()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            LOG.warn("Invalid project base path for MCP resources: " + project.getBasePath(), e);
            return null;
        }
    }

    private static int parseCursorOffset(@Nullable JsonElement cursorElement,
                                         @NotNull String expectedPrefix) throws InvalidCursorException {
        if (cursorElement == null || cursorElement.isJsonNull()) {
            return 0;
        }
        String cursor = cursorElement.getAsString();
        if (!cursor.startsWith(expectedPrefix)) {
            throw new InvalidCursorException("Invalid cursor");
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.substring(expectedPrefix.length())));
        } catch (RuntimeException e) {
            throw new InvalidCursorException("Invalid cursor");
        }
    }

    @NotNull
    private static String encodeCursor(@NotNull String prefix, int offset) {
        return prefix + offset;
    }

    @NotNull
    private static String guessMimeType(@NotNull Path path) {
        try {
            String mimeType = Files.probeContentType(path);
            if (mimeType != null && !mimeType.isBlank()) {
                return mimeType;
            }
        } catch (IOException ignored) {
        }

        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".java")) return "text/x-java";
        if (name.endsWith(".kt")) return "text/x-kotlin";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "application/yaml";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static boolean isTextResource(@NotNull Path path) {
        String mimeType = guessMimeType(path);
        return mimeType.startsWith("text/")
            || "application/json".equals(mimeType)
            || "application/xml".equals(mimeType)
            || "application/yaml".equals(mimeType);
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

        // Extract correlation fields from _meta.
        // claudecode/toolUseId matches the ACP tool_use.id exactly — used for direct chip correlation.
        // progressToken is a sequence number, kept for debug logging only.
        String progressToken = null;
        String toolUseId = null;
        if (params.has("_meta") && params.get("_meta").isJsonObject()) {
            JsonObject meta = params.getAsJsonObject("_meta");
            if (meta.has("progressToken")) {
                progressToken = meta.get("progressToken").getAsString();
            }
            if (meta.has("claudecode/toolUseId")) {
                toolUseId = meta.get("claudecode/toolUseId").getAsString();
            }
        }

        // Always log with [MCP] prefix for easy filtering alongside [ACP] logs.
        // Include progressToken when debug logging is on — this is the key for ACP↔MCP correlation.
        String tokenSuffix = progressToken != null ? " [progressToken=" + progressToken + "]" : " [no progressToken]";
        if (settings.isDebugLoggingEnabled()) {
            LOG.info("[MCP] >>> tools/call: " + toolName + tokenSuffix);
        } else {
            LOG.info("[MCP] tools/call: " + toolName);
        }

        // Delegate to PsiBridgeService
        final String finalProgressToken = progressToken;
        final String finalToolUseId = toolUseId;
        try {
            PsiBridgeService bridge = PsiBridgeService.getInstance(project);
            String resultText = bridge.callTool(toolName, arguments, finalProgressToken, finalToolUseId);
            resultText = truncateIfNeeded(resultText);

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
            LOG.warn("[MCP] tool error: " + toolName, e);
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

    private static String truncateIfNeeded(String text) {
        if (text == null || text.length() <= MAX_RESULT_CHARS) return text;
        int removed = text.length() - MAX_RESULT_CHARS;
        return text.substring(0, MAX_RESULT_CHARS)
            + "\n\n[Output truncated: " + removed + " characters omitted."
            + " Use the tool's pagination parameters (e.g. start_line/end_line, offset/max_chars)"
            + " to read specific sections.]";
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
            code,
            message
        );
    }

    private static JsonObject respondResourceNotFound(@Nullable JsonObject request,
                                                      @NotNull String uri,
                                                      @NotNull String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", RESOURCE_NOT_FOUND_ERROR);
        error.addProperty("message", message);

        JsonObject data = new JsonObject();
        data.addProperty("uri", uri);
        error.add("data", data);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (request != null && request.has("id")) {
            response.add("id", request.get("id"));
        }
        response.add("error", error);
        return response;
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

    private record ResourceReadResult(@Nullable JsonObject content,
                                      @Nullable Integer errorCode,
                                      @Nullable String errorMessage) {
        private static ResourceReadResult success(@NotNull JsonObject content) {
            return new ResourceReadResult(content, null, null);
        }

        private static ResourceReadResult notFound(@NotNull String message) {
            return new ResourceReadResult(null, RESOURCE_NOT_FOUND_ERROR, message);
        }

        private static ResourceReadResult error(int errorCode, @NotNull String message) {
            return new ResourceReadResult(null, errorCode, message);
        }
    }

    private static final class InvalidCursorException extends Exception {
        private InvalidCursorException(@NotNull String message) {
            super(message);
        }
    }
}
