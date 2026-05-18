package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.BuildInfo;
import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.memory.layers.EssentialStoryLayer;
import com.github.catatafishen.agentbridge.memory.layers.IdentityLayer;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.psi.McpErrorCode;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolError;
import com.github.catatafishen.agentbridge.psi.ToolTimeoutDialog;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.settings.ChatInputSettings;
import com.github.catatafishen.agentbridge.psi.tools.quality.PendingPopupService;
import com.github.catatafishen.agentbridge.psi.tools.quality.PopupGateLogic;
import com.github.catatafishen.agentbridge.services.hooks.HookExecutor;
import com.github.catatafishen.agentbridge.services.hooks.HookPipeline;
import com.github.catatafishen.agentbridge.services.hooks.HookRegistry;
import com.github.catatafishen.agentbridge.services.hooks.HookStageResult;
import com.github.catatafishen.agentbridge.services.hooks.ToolHookConfig;
import com.github.catatafishen.agentbridge.session.db.ConversationService;
import com.github.catatafishen.agentbridge.session.db.ToolCallStatsEnrichment;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.settings.McpToolFilter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles MCP (Model Context Protocol) JSON-RPC messages.
 * Translates between MCP protocol and the PsiBridgeService tool handlers.
 */
public final class McpProtocolHandler {
    private static final Logger LOG = Logger.getInstance(McpProtocolHandler.class);
    private static final Gson GSON = new GsonBuilder().create();

    private static final String STAGE_PERMISSION = "permission";
    private static final String STAGE_PRE = "pre";
    private static final String STAGE_SUCCESS = "success";
    private static final String STAGE_FAILURE = "failure";
    private static final String STAGE_HOOK_CHAIN = "hook-chain";
    private static final String OUTCOME_ERROR = "error";
    private static final String OUTCOME_MODIFIED = "modified";

    /**
     * Hard cap on tool result size. Keeps output below client-side truncation thresholds.
     */
    private static final int MAX_RESULT_CHARS = 80_000;
    private static final int RESOURCE_PAGE_SIZE = 200;
    private static final int RESOURCE_NOT_FOUND_ERROR = -32002;

    private static final String SERVER_NAME = "agentbridge";
    private static final String SERVER_VERSION = BuildInfo.getVersion();
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String STARTUP_INSTRUCTIONS_URI = "resource://default-startup-instructions.md";
    private static final String STARTUP_INSTRUCTIONS = loadInstructions();
    private static final String RESOURCES_CURSOR_PREFIX = "resources:";
    private static final String RESOURCE_TEMPLATES_CURSOR_PREFIX = "resourceTemplates:";

    private static final String KEY_PARAMS = "params";
    private static final String KEY_CURSOR = "cursor";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_RESOURCE = "resource";
    private static final String MSG_RESOURCE_NOT_FOUND = "Resource not found";
    private static final String MIME_TEXT_MARKDOWN = "text/markdown";
    private static final String KEY_MIME_TYPE = "mimeType";
    private static final String KEY_META = "_meta";
    private static final String KEY_JSONRPC = "jsonrpc";

    private final Project project;

    /**
     * Prevents multiple concurrent timeout dialogs. If a dialog is already active
     * (another tool timed out at the same time), subsequent timeouts skip the dialog
     * and wait indefinitely so the user is not bombarded with stacked modals.
     */
    private final AtomicBoolean timeoutDialogActive = new AtomicBoolean(false);

    /**
     * The name of the connected agent, extracted from the MCP {@code initialize}
     * request's {@code clientInfo.name} field. {@code null} until the first
     * {@code initialize} handshake completes.
     */
    private volatile @Nullable String connectedAgentName;

    public McpProtocolHandler(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Returns the name of the agent that connected via MCP {@code initialize},
     * or {@code null} if no agent has connected yet.
     */
    public @Nullable String getConnectedAgentName() {
        return connectedAgentName;
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
        } catch (com.google.gson.JsonSyntaxException | com.google.gson.JsonIOException e) {
            // Malformed input from client — warn, not error (this is a caller mistake, not a server fault)
            LOG.warn("MCP protocol error: malformed JSON from client", e);
            return GSON.toJson(makeErrorResponse(JsonNull.INSTANCE, -32700, "Parse error: " + e.getMessage()));
        } catch (Exception e) {
            LOG.error("MCP protocol error processing request", e);
            return GSON.toJson(makeErrorResponse(null, -32603, "Internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private JsonObject handleInitialize(JsonObject msg) {
        // Extract connected agent identity from clientInfo (MCP spec)
        JsonObject params = msg.has(KEY_PARAMS) ? msg.getAsJsonObject(KEY_PARAMS) : null;
        if (params != null && params.has("clientInfo")) {
            JsonObject clientInfo = params.getAsJsonObject("clientInfo");
            if (clientInfo.has("name")) {
                connectedAgentName = clientInfo.get("name").getAsString();
                LOG.info("[MCP] connected agent: " + connectedAgentName);
            }
        }

        JsonObject serverInfo = new JsonObject();
        String projectName = project.getName();
        serverInfo.addProperty("name", SERVER_NAME + " (" + projectName + ")");
        serverInfo.addProperty("version", SERVER_VERSION);
        serverInfo.addProperty(KEY_DESCRIPTION, "Code Intelligence tools for IntelliJ IDEA — project: " + projectName);

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
        result.addProperty("instructions", buildInstructions());

        return respondResult(msg, result);
    }

    private @NotNull String buildInstructions() {
        String memoryContext = buildMemoryContext();
        if (memoryContext.isEmpty()) {
            return STARTUP_INSTRUCTIONS;
        }
        return STARTUP_INSTRUCTIONS + "\n\n" + memoryContext;
    }

    private @NotNull String buildMemoryContext() {
        try {
            if (!MemorySettings.getInstance(project).isEnabled()) {
                return "";
            }

            MemoryService memoryService = MemoryService.getInstance(project);
            // getStore() triggers lazy initialization — don't check isActive() first,
            // as that reads the pre-init `initialized` flag and would always return false
            // on the first call (e.g., during MCP initialize).
            MemoryStore store = memoryService.getStore();
            if (store == null || store.getDrawerCount() == 0) {
                return "";
            }

            String wing = memoryService.getEffectiveWing();
            StringBuilder sb = new StringBuilder("---\n\nSEMANTIC MEMORY (auto-injected at session start):\n\n");

            IdentityLayer identity = new IdentityLayer(project);
            String identityContent = identity.render(wing, null);
            if (!identityContent.isEmpty()) {
                sb.append(identityContent).append("\n\n");
            }

            EssentialStoryLayer essentialStory = new EssentialStoryLayer(store);
            String storyContent = essentialStory.render(wing, null);
            if (!storyContent.isEmpty()) {
                sb.append(storyContent).append("\n\n");
            }

            sb.append("Memory tools: memory_search (semantic recall), memory_recall (room-filtered), ")
                .append("memory_store (save fact), memory_status (stats), ")
                .append("memory_kg_query / memory_kg_add / memory_kg_timeline (knowledge graph).");

            return sb.toString();
        } catch (Exception e) {
            LOG.warn("Failed to build memory context for MCP init", e);
            return "";
        }
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
            tool.addProperty(KEY_DESCRIPTION, entry.description());
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
        return buildPagedResourceResponse(msg, RESOURCES_CURSOR_PREFIX, buildResourceEntries(), "resources");
    }

    private JsonObject handleResourceTemplatesList(JsonObject msg) {
        JsonObject template = new JsonObject();
        template.addProperty("uriTemplate", "file:///{path}");
        template.addProperty("name", "Project Files");
        template.addProperty("title", "Project Files");
        template.addProperty(KEY_DESCRIPTION, "Access files in the current project directory");
        template.addProperty(KEY_MIME_TYPE, "application/octet-stream");
        return buildPagedResourceResponse(msg, RESOURCE_TEMPLATES_CURSOR_PREFIX, List.of(template), "resourceTemplates");
    }

    private static JsonObject buildPagedResourceResponse(JsonObject msg,
                                                         String cursorPrefix,
                                                         List<JsonObject> items,
                                                         String itemKey) {
        JsonObject params = msg.has(KEY_PARAMS) ? msg.getAsJsonObject(KEY_PARAMS) : new JsonObject();
        int offset;
        try {
            offset = parseCursorOffset(params.has(KEY_CURSOR) ? params.get(KEY_CURSOR) : null, cursorPrefix);
        } catch (InvalidCursorException e) {
            return respondError(msg, -32602, e.getMessage());
        }

        JsonArray page = new JsonArray();
        int endExclusive = Math.min(items.size(), offset + RESOURCE_PAGE_SIZE);
        for (int i = offset; i < endExclusive; i++) {
            page.add(items.get(i));
        }

        JsonObject result = new JsonObject();
        result.add(itemKey, page);
        if (endExclusive < items.size()) {
            result.addProperty("nextCursor", encodeCursor(cursorPrefix, endExclusive));
        }
        return respondResult(msg, result);
    }

    private JsonObject handleResourcesRead(JsonObject msg) {
        JsonObject params = msg.has(KEY_PARAMS) ? msg.getAsJsonObject(KEY_PARAMS) : new JsonObject();
        String uri = null;
        if (params.has("uri") && !params.get("uri").isJsonNull()) {
            uri = params.get("uri").getAsString();
        } else if (params.has(KEY_RESOURCE) && params.get(KEY_RESOURCE).isJsonObject()) {
            JsonObject resource = params.getAsJsonObject(KEY_RESOURCE);
            if (resource.has("uri") && !resource.get("uri").isJsonNull()) {
                uri = resource.get("uri").getAsString();
            }
        }

        if (uri == null || uri.isEmpty()) {
            return respondError(msg, -32602, "Missing resource URI");
        }

        ResourceReadResult readResult = readResource(uri);
        if (readResult.errorCode() != null) {
            String errorMessage = readResult.errorMessage() != null ? readResult.errorMessage() : MSG_RESOURCE_NOT_FOUND;
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
        JsonObject resource = new JsonObject();
        resource.addProperty("uri", STARTUP_INSTRUCTIONS_URI);
        resource.addProperty("name", "default-startup-instructions");
        resource.addProperty("title", "Default Startup Instructions");
        resource.addProperty(KEY_DESCRIPTION, "Default startup instructions injected during initialize");
        resource.addProperty(KEY_MIME_TYPE, MIME_TEXT_MARKDOWN);
        return List.of(resource);
    }

    @NotNull
    private ResourceReadResult readResource(@NotNull String uri) {
        if (STARTUP_INSTRUCTIONS_URI.equals(uri)) {
            JsonObject content = new JsonObject();
            content.addProperty("uri", STARTUP_INSTRUCTIONS_URI);
            content.addProperty(KEY_MIME_TYPE, MIME_TEXT_MARKDOWN);
            content.addProperty("text", STARTUP_INSTRUCTIONS);
            return ResourceReadResult.success(content);
        }

        if (!uri.startsWith("file:")) {
            return ResourceReadResult.notFound();
        }

        Path projectRoot = getProjectRoot();
        if (projectRoot == null) {
            return ResourceReadResult.notFound();
        }

        Path path;
        try {
            path = Paths.get(new URI(uri)).toAbsolutePath().normalize();
        } catch (URISyntaxException | InvalidPathException e) {
            return ResourceReadResult.error(-32602, "Invalid resource URI: " + uri);
        }

        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!path.startsWith(normalizedRoot) || !Files.exists(path) || !Files.isRegularFile(path)) {
            return ResourceReadResult.notFound();
        }

        JsonObject content = new JsonObject();
        content.addProperty("uri", path.toUri().toString());
        content.addProperty(KEY_MIME_TYPE, guessMimeType(path));

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
            // probeContentType fails on some platforms; fall through to extension-based detection
        }

        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".md")) return MIME_TEXT_MARKDOWN;
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

    private record ToolCallMeta(@Nullable String progressToken, @Nullable String toolUseId) {
    }

    private static ToolCallMeta extractMeta(JsonObject params) {
        if (!params.has(KEY_META) || !params.get(KEY_META).isJsonObject()) {
            return new ToolCallMeta(null, null);
        }
        JsonObject meta = params.getAsJsonObject(KEY_META);
        String progressToken = getOptionalMetaString(meta, "progressToken");
        String toolUseId = getOptionalMetaString(meta, "claudecode/toolUseId");
        return new ToolCallMeta(progressToken, toolUseId);
    }

    private static @Nullable String getOptionalMetaString(@NotNull JsonObject meta, @NotNull String key) {
        if (!meta.has(key)) {
            return null;
        }
        JsonElement value = meta.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString() || primitive.isNumber()) {
            return value.getAsString();
        }
        return null;
    }

    private static JsonObject buildToolResult(JsonObject msg, @Nullable String text, boolean isError) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text != null ? text : "");
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);
        JsonObject result = new JsonObject();
        result.add("content", contentArray);
        result.addProperty("isError", isError);
        return respondResult(msg, result);
    }

    private JsonObject handleToolsCall(JsonObject msg) {
        JsonObject params = msg.has(KEY_PARAMS) ? msg.getAsJsonObject(KEY_PARAMS) : new JsonObject();
        String toolName = params.has("name") ? params.get("name").getAsString() : null;
        if (toolName == null) {
            return respondError(msg, -32602, "Missing tool name");
        }

        McpServerSettings settings = McpServerSettings.getInstance(project);
        if (!settings.isToolEnabled(toolName) || McpToolFilter.isAlwaysHidden(toolName)) {
            return respondError(msg, -32602, "Tool is disabled: " + toolName);
        }

        JsonObject arguments = params.has("arguments")
            ? params.getAsJsonObject("arguments") : new JsonObject();

        ToolCallMeta meta = extractMeta(params);
        logToolCall(toolName, meta.progressToken(), settings);

        String sessionKey = sessionKey(project);
        PopupGateResult gateResult = evaluatePopupGate(toolName, sessionKey, msg);
        if (gateResult.blocked != null) return gateResult.blocked;

        List<HookStageResult> hookStages = new ArrayList<>();

        // Pause: defer execution before permission hook so the user can review the call,
        // send a nudge, or let the agent reconsider before any denial is issued.
        McpPauseService.getInstance(project).awaitResumeIfPaused();

        // Permission hook: deny/allow before any execution
        String permissionDenial = evaluatePermissionHook(toolName, arguments, hookStages);
        if (permissionDenial != null) return buildToolResult(msg, permissionDenial, true);

        // Snapshot the original arguments before hooks can mutate them.
        // ToolCallTracker needs the pre-hook args for hash-based correlation with ACP,
        // which only sees the original arguments (before our MCP-side hooks modify them).
        JsonObject originalArguments = arguments.deepCopy();

        // Pre hook: can modify arguments or stop execution before tool execution
        PreHookApplication preHookResult = applyPreHook(toolName, arguments, hookStages);
        if (preHookResult.blockedMessage != null) return buildToolResult(msg, preHookResult.blockedMessage, true);
        arguments = preHookResult.arguments;

        return executeToolCall(msg, toolName, arguments, preHookResult, hookStages,
            meta.toolUseId(), originalArguments, gateResult.prefix);
    }

    @SuppressWarnings("java:S107")
    // Parameters are all semantically distinct; a wrapper object would add indirection without clarity
    private JsonObject executeToolCall(JsonObject msg, String toolName, JsonObject arguments,
                                       PreHookApplication preHookResult,
                                       List<HookStageResult> hookStages,
                                       @Nullable String toolUseId, JsonObject originalArguments,
                                       String resultPrefix) {
        LiveToolCallService liveService = LiveToolCallService.getInstance(project);
        ToolDefinition definition = ToolRegistry.getInstance(project).findById(toolName);
        String kind = definition != null ? definition.kind().value() : null;
        String displayName = definition != null ? definition.displayName() : toolName;
        String inputJson = arguments.toString();
        String originalInputJson = originalArguments.equals(arguments) ? null : originalArguments.toString();
        ToolHookConfig hookConfig = HookRegistry.getInstance(project).findConfig(toolName);
        boolean hasHooks = hookConfig != null && !hookConfig.isEmpty();
        long callId = liveService.recordStart(toolName, displayName, inputJson, kind, hasHooks, originalInputJson);
        long callStartMs = System.currentTimeMillis();

        try {
            String resultText = callToolWithTimeout(toolName, arguments, toolUseId, originalArguments, displayName);

            // Pause: if the user requested a pause while the tool was running,
            // block here before the result is sent back to the agent. This lets the
            // user append a nudge that the agent sees together with the tool output.
            McpPauseService.getInstance(project).awaitResumeIfPaused();

            // Enrich the live entry display name with the ACP title once it is available.
            // ACP registers before MCP executes, so the record (and its acpTitle) should
            // already exist by the time callTool() returns.
            ToolCallRecord trackerRecord = ToolCallTracker.getInstance(project).findByToolUseId(toolUseId);
            if (trackerRecord != null) {
                String acpTitle = trackerRecord.getAcpTitle();
                if (acpTitle != null && !acpTitle.equals(displayName)) {
                    liveService.setDisplayName(callId, acpTitle);
                }
            }

            long durationMs = System.currentTimeMillis() - callStartMs;
            var postOutcome = applyPostHook(toolName, arguments, resultText, hookStages);
            resultText = postOutcome.output();
            resultText = truncateIfNeeded(resultText);
            boolean isError = postOutcome.isError() || ToolError.isError(resultText);

            if (!isError) {
                resultText = applyPreHookTextModifiers(preHookResult, resultText);
            }
            String fullResult = resultPrefix + resultText;
            if (!hookStages.isEmpty()) {
                liveService.setHookStages(callId, hookStages);
            }
            liveService.complete(callId, fullResult,
                System.currentTimeMillis() - callStartMs, !isError);

            enrichConversationDb(new ToolCallEnrichmentData(
                toolUseId, toolName, displayName, originalArguments,
                inputJson, fullResult, durationMs, !isError,
                isError ? resultText : null, kind, hookStages));

            return buildToolResult(msg, fullResult, isError);
        } catch (Exception e) {
            LOG.warn("[MCP] tool error: " + toolName, e);

            // Pause: also check after failure — the user may want to nudge before
            // the agent sees the error result.
            McpPauseService.getInstance(project).awaitResumeIfPaused();

            String errorMsg = ToolError.of(McpErrorCode.INTERNAL_ERROR, e.getMessage());
            long durationMs = System.currentTimeMillis() - callStartMs;
            var failOutcome = applyFailureHook(toolName, arguments, errorMsg, durationMs, hookStages);
            String finalOutput = Objects.requireNonNullElse(failOutcome.output(), errorMsg);
            boolean isError = failOutcome.isError();
            if (!hookStages.isEmpty()) {
                liveService.setHookStages(callId, hookStages);
            }
            liveService.complete(callId, finalOutput,
                System.currentTimeMillis() - callStartMs, !isError);

            enrichConversationDb(new ToolCallEnrichmentData(
                toolUseId, toolName, displayName, originalArguments,
                inputJson, finalOutput, durationMs, false,
                e.getMessage(), kind, hookStages));

            return buildToolResult(msg, finalOutput, isError);
        } finally {
            McpCallContext.clear();
        }
    }

    /**
     * Calls a tool on a pooled thread with an initial timeout. If the timeout fires, shows
     * an interactive dialog so the user can extend the wait or cancel. {@link McpCallContext}
     * is set and cleared on the worker thread, not the MCP reader thread.
     *
     * <p>Cancellation is implemented by interrupting the worker thread directly — not via
     * {@code future.cancel(true)}, which does not interrupt threads running inside
     * {@link CompletableFuture#supplyAsync}.
     *
     * <p>If a timeout dialog is already active for another concurrent tool call, the new
     * timeout skips the dialog and waits indefinitely to avoid stacking modal dialogs.
     */
    private String callToolWithTimeout(String toolName, JsonObject arguments,
                                       @Nullable String toolUseId, JsonObject originalArguments,
                                       @Nullable String displayName) {
        String sessionKey = sessionKey(project);
        PsiBridgeService bridge = PsiBridgeService.getInstance(project);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        long startMs = System.currentTimeMillis();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            workerThread.set(Thread.currentThread());
            McpCallContext.setCurrent(sessionKey);
            try {
                return bridge.callTool(toolName, arguments, toolUseId, originalArguments);
            } finally {
                McpCallContext.clear();
                workerThread.set(null);
            }
        });

        try {
            int initialTimeoutSeconds = ChatInputSettings.getInstance().getToolTimeoutSeconds();
            return future.get(initialTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            int elapsedSeconds = (int) ((System.currentTimeMillis() - startMs) / 1000);
            return waitAfterTimeout(future, workerThread, toolName, displayName, elapsedSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptWorker(workerThread);
            return "Error: MCP handler interrupted while waiting for '" + toolName + "'";
        } catch (ExecutionException e) {
            return unwrapExecutionException(toolName, e);
        } catch (CancellationException e) {
            return toolError(toolName, "was unexpectedly cancelled");
        }
    }

    private String waitAfterTimeout(CompletableFuture<String> future,
                                    AtomicReference<Thread> workerThread,
                                    String toolName, @Nullable String displayName,
                                    int elapsedSeconds) {
        // If the tool is blocked at the diff-review gate, the user is actively reviewing changes
        // in the side panel — a timeout dialog would be confusing and intrusive. Wait silently.
        if (AgentEditSession.getInstance(project).isGateActive()) {
            return getFutureResult(future, workerThread, toolName, "diff review gate");
        }

        // If the user has disabled the dialog (permanently or for this session), wait silently.
        if (!ChatInputSettings.getInstance().isToolTimeoutDialogEnabled()
            || ToolTimeoutDialog.isSuppressedForSession()) {
            return getFutureResult(future, workerThread, toolName, "suppressed wait");
        }

        // If another concurrent tool already has a dialog showing, skip ours and wait indefinitely
        // to avoid stacking modal dialogs on the user.
        if (!timeoutDialogActive.compareAndSet(false, true)) {
            return getFutureResult(future, workerThread, toolName, "extended wait");
        }

        int extra;
        try {
            ChatInputSettings s = ChatInputSettings.getInstance();
            int ext1Seconds = s.getToolTimeoutExtension1Minutes() * 60;
            int ext2Seconds = s.getToolTimeoutExtension2Minutes() * 60;
            extra = ToolTimeoutDialog.askForExtension(
                project, displayName != null ? displayName : toolName, elapsedSeconds, future,
                ext1Seconds, ext2Seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptWorker(workerThread);
            return "Error: interrupted while showing timeout dialog for '" + toolName + "'";
        } finally {
            timeoutDialogActive.set(false);
        }

        if (extra == ToolTimeoutDialog.COMPLETED) {
            // The tool finished while the dialog was showing — read the result directly.
            return getFutureResult(future, workerThread, toolName, "completed result");
        }

        if (extra == ToolTimeoutDialog.CANCEL) {
            interruptWorker(workerThread);
            return toolError(toolName, "was cancelled by user after " + elapsedSeconds + "s");
        }

        try {
            return extra == ToolTimeoutDialog.INDEFINITE
                ? future.get()
                : future.get(extra, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            interruptWorker(workerThread);
            return toolError(toolName, "timed out after extended " + extra + "s wait");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptWorker(workerThread);
            return "Error: MCP handler interrupted during extended wait for '" + toolName + "'";
        } catch (ExecutionException e) {
            return unwrapExecutionException(toolName, e);
        } catch (CancellationException e) {
            return toolError(toolName, "was unexpectedly cancelled during extended wait");
        }
    }

    /**
     * Waits for {@code future} without a timeout, translating all exceptions to error strings.
     */
    private String getFutureResult(CompletableFuture<String> future, AtomicReference<Thread> workerThread,
                                   String toolName, String phase) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptWorker(workerThread);
            return "Error: MCP handler interrupted during " + phase + " for '" + toolName + "'";
        } catch (ExecutionException e) {
            return unwrapExecutionException(toolName, e);
        } catch (CancellationException e) {
            return toolError(toolName, "was unexpectedly cancelled during " + phase);
        }
    }

    private static void interruptWorker(@NotNull AtomicReference<Thread> workerThread) {
        Thread worker = workerThread.get();
        if (worker != null) {
            worker.interrupt();
        }
    }

    private static String toolError(String toolName, String reason) {
        return "Error: '" + toolName + "' " + reason;
    }

    private static String unwrapExecutionException(String toolName, ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException re) throw re;
        throw new IllegalStateException("Unexpected exception in tool '" + toolName + "'", cause);
    }

    private record ToolCallEnrichmentData(
        @Nullable String toolUseId,
        @NotNull String toolName,
        @Nullable String displayName,
        @NotNull JsonObject arguments,
        @NotNull String inputJson,
        @Nullable String output,
        long durationMs,
        boolean success,
        @Nullable String errorMessage,
        @Nullable String category,
        @NotNull List<HookStageResult> hookStages
    ) {
    }

    private void enrichConversationDb(@NotNull ToolCallEnrichmentData data) {
        // Resolve the record to get the stable DB event_id (= record.recordId).
        // Fallback: toolUseId lookup → MCP tool+args match → skip if neither succeeds.
        ToolCallTracker tracker = ToolCallTracker.getInstance(project);
        ToolCallRecord callRecord = tracker.findByToolUseId(data.toolUseId());
        if (callRecord == null) {
            callRecord = tracker.findByMcpCall(data.toolName(), data.arguments());
        }
        String dbEventId = callRecord != null ? callRecord.getRecordId() : data.toolUseId();
        if (dbEventId == null) return;
        if (callRecord == null) {
            LOG.debug("[MCP] No tracker record for tool '" + data.toolName()
                + "'; falling back to toolUseId '" + dbEventId + "' for DB enrichment");
        }

        ConversationService service = ConversationService.getInstance(project);
        long inputSize = data.inputJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        long outputSize = data.output() != null
            ? data.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0;
        service.enrichToolCallStats(new ToolCallStatsEnrichment(
            dbEventId, inputSize, outputSize, data.durationMs(),
            data.success(), data.errorMessage(), data.category(), data.displayName()));
        // Only record hook stages when we have a confirmed tracker record — the FK on
        // hook_executions.tool_event_id requires the events row to already exist.
        // When callRecord is null we're falling back to a raw toolUseId that may not be
        // in the events table yet, which would cause a SQLITE_CONSTRAINT_FOREIGNKEY error.
        if (!data.hookStages().isEmpty() && callRecord != null) {
            service.recordHookStages(dbEventId, data.hookStages());
        }
    }

    private String sessionKey(com.intellij.openapi.project.Project proj) {
        return proj.getLocationHash() + ":" + System.identityHashCode(this);
    }

    private @Nullable String evaluatePermissionHook(@NotNull String toolName,
                                                    @NotNull JsonObject arguments,
                                                    @NotNull List<HookStageResult> hookStages) {
        try {
            long start = System.currentTimeMillis();
            HookPipeline.PermissionResult result = HookPipeline.runPermissionHooks(project, toolName, arguments);
            long elapsed = System.currentTimeMillis() - start;
            if (result instanceof HookPipeline.PermissionResult.Denied(String reason)) {
                hookStages.add(new HookStageResult(STAGE_PERMISSION, STAGE_HOOK_CHAIN, "denied", elapsed, reason));
                return ToolError.of(McpErrorCode.NOT_APPLICABLE, "Hook denied: " + reason);
            }
            if (elapsed > 0) {
                hookStages.add(new HookStageResult(STAGE_PERMISSION, STAGE_HOOK_CHAIN, "allowed", elapsed, null));
            }
        } catch (HookExecutor.HookExecutionException e) {
            LOG.warn("[MCP] permission hook failed for " + toolName, e);
            hookStages.add(new HookStageResult(STAGE_PERMISSION, STAGE_HOOK_CHAIN, OUTCOME_ERROR, 0, e.getMessage()));
            return ToolError.of(McpErrorCode.INTERNAL_ERROR, e.getMessage());
        }
        return null;
    }

    private @NotNull PreHookApplication applyPreHook(@NotNull String toolName,
                                                     @NotNull JsonObject arguments,
                                                     @NotNull List<HookStageResult> hookStages) {
        try {
            long start = System.currentTimeMillis();
            HookPipeline.PreHookOutput output = HookPipeline.runPreHooks(project, toolName, arguments);
            long elapsed = System.currentTimeMillis() - start;
            HookPipeline.PreHookResult result = output.result();
            if (result instanceof HookPipeline.PreHookResult.Blocked(String error)) {
                hookStages.add(new HookStageResult(STAGE_PRE, STAGE_HOOK_CHAIN, "blocked", elapsed, error));
                return new PreHookApplication(arguments, error, null, null);
            }
            JsonObject resolvedArgs = result instanceof HookPipeline.PreHookResult.Modified(
                JsonObject m
            ) ? m : arguments;
            String outcome = result instanceof HookPipeline.PreHookResult.Modified ? OUTCOME_MODIFIED : "unchanged";
            if (elapsed > 0) {
                hookStages.add(new HookStageResult(STAGE_PRE, STAGE_HOOK_CHAIN, outcome, elapsed, null));
            }
            return new PreHookApplication(resolvedArgs, null, output.pendingPrepend(), output.pendingAppend());
        } catch (HookExecutor.HookExecutionException e) {
            LOG.warn("[MCP] pre-hook failed for " + toolName, e);
            hookStages.add(new HookStageResult(STAGE_PRE, STAGE_HOOK_CHAIN, OUTCOME_ERROR, 0, e.getMessage()));
            return new PreHookApplication(arguments, "Pre-hook failed: " + e.getMessage(), null, null);
        }
    }

    private @NotNull HookPipeline.PostHookOutcome applyPostHook(@NotNull String toolName,
                                                                @NotNull JsonObject arguments,
                                                                @Nullable String output,
                                                                @NotNull List<HookStageResult> hookStages) {
        try {
            long start = System.currentTimeMillis();
            String nonNullOutput = output != null ? output : "";
            HookPipeline.PostHookOutcome outcome = HookPipeline.runSuccessHooks(project, toolName, arguments, nonNullOutput);
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > 0) {
                boolean modified = !Objects.equals(output, outcome.output()) || outcome.isError();
                hookStages.add(new HookStageResult(STAGE_SUCCESS, STAGE_HOOK_CHAIN,
                    modified ? OUTCOME_MODIFIED : "pass-through", elapsed, null));
            }
            return outcome;
        } catch (HookExecutor.HookExecutionException e) {
            LOG.warn("[MCP] success hook failed for " + toolName, e);
            hookStages.add(new HookStageResult(STAGE_SUCCESS, STAGE_HOOK_CHAIN, OUTCOME_ERROR, 0, e.getMessage()));
            return new HookPipeline.PostHookOutcome(output, false);
        }
    }

    private @NotNull HookPipeline.PostHookOutcome applyFailureHook(@NotNull String toolName,
                                                                   @NotNull JsonObject arguments,
                                                                   @NotNull String errorMsg, long durationMs,
                                                                   @NotNull List<HookStageResult> hookStages) {
        try {
            long start = System.currentTimeMillis();
            HookPipeline.PostHookOutcome outcome = HookPipeline.runFailureHooks(project, toolName, arguments, errorMsg, durationMs);
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > 0) {
                boolean modified = !Objects.equals(errorMsg, outcome.output()) || !outcome.isError();
                hookStages.add(new HookStageResult(STAGE_FAILURE, STAGE_HOOK_CHAIN,
                    modified ? OUTCOME_MODIFIED : "pass-through", elapsed, null));
            }
            return outcome;
        } catch (HookExecutor.HookExecutionException e) {
            LOG.warn("[MCP] failure hook failed for " + toolName, e);
            hookStages.add(new HookStageResult(STAGE_FAILURE, STAGE_HOOK_CHAIN, OUTCOME_ERROR, 0, e.getMessage()));
            return new HookPipeline.PostHookOutcome(errorMsg, true);
        }
    }

    /**
     * Applies the pending prepend/append text modifiers produced by PRE hooks to the tool output.
     * These modifiers are accumulated during the PRE phase and applied only on success.
     */
    private static @NotNull String applyPreHookTextModifiers(@NotNull PreHookApplication preHook,
                                                             @Nullable String resultText) {
        String base = resultText != null ? resultText : "";
        if (preHook.pendingPrepend() != null && !preHook.pendingPrepend().isEmpty()) {
            base = preHook.pendingPrepend() + "\n\n" + base;
        }
        if (preHook.pendingAppend() != null && !preHook.pendingAppend().isEmpty()) {
            base = base + "\n\n" + preHook.pendingAppend();
        }
        return base;
    }

    private static void logToolCall(String toolName, @Nullable String progressToken,
                                    McpServerSettings settings) {
        if (settings.isDebugLoggingEnabled()) {
            String tokenSuffix = progressToken != null
                ? " [progressToken=" + progressToken + "]" : " [no progressToken]";
            LOG.info("[MCP] >>> tools/call: " + toolName + tokenSuffix);
        } else {
            LOG.info("[MCP] tools/call: " + toolName);
        }
    }

    private record PreHookApplication(@NotNull JsonObject arguments,
                                      @Nullable String blockedMessage,
                                      @Nullable String pendingPrepend,
                                      @Nullable String pendingAppend) {
    }

    private record PopupGateResult(String prefix, @Nullable JsonObject blocked) {
    }

    private PopupGateResult evaluatePopupGate(String toolName, String sessionKey,
                                              JsonObject msg) {
        PendingPopupService pps = PendingPopupService.getInstance();
        PopupGateLogic.Decision decision = PopupGateLogic.evaluate(
            pps.peek(), toolName, sessionKey, java.time.Instant.now());

        if (decision instanceof PopupGateLogic.Block(var message)) {
            return new PopupGateResult("", buildToolResult(msg, message, true));
        }
        if (decision instanceof PopupGateLogic.AllowWithCancelNote(var cancelled, var note)) {
            pps.cancelAndClear(cancelled.id());
            return new PopupGateResult(note + "\n\n", null);
        }
        if (!PopupGateLogic.POPUP_RESPOND_TOOL.equals(toolName) && pps.peek() != null) {
            pps.recordUnrelatedCall(sessionKey);
        }
        return new PopupGateResult("", null);
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
        response.addProperty(KEY_JSONRPC, "2.0");
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
        response.addProperty(KEY_JSONRPC, "2.0");
        if (request != null && request.has("id")) {
            response.add("id", request.get("id"));
        }
        response.add(OUTCOME_ERROR, error);
        return response;
    }

    private static JsonObject makeErrorResponse(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty(KEY_JSONRPC, "2.0");
        if (id != null) {
            response.add("id", id);
        }
        response.add(OUTCOME_ERROR, error);
        return response;
    }

    private record ResourceReadResult(@Nullable JsonObject content,
                                      @Nullable Integer errorCode,
                                      @Nullable String errorMessage) {
        private static ResourceReadResult success(@NotNull JsonObject content) {
            return new ResourceReadResult(content, null, null);
        }

        private static ResourceReadResult notFound() {
            return new ResourceReadResult(null, RESOURCE_NOT_FOUND_ERROR, MSG_RESOURCE_NOT_FOUND);
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
