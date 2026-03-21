package com.github.catatafishen.ideagentforcopilot.agent.claude;

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService;
import com.github.catatafishen.ideagentforcopilot.agent.AgentException;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.bridge.SessionOption;
import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;

/**
 * Direct Anthropic Messages API client for Claude Code profiles.
 *
 * <p>Replaces the ACP subprocess layer with direct HTTPS calls to
 * {@code api.anthropic.com/v1/messages}, implementing the full agentic
 * tool-use loop in Java. All IDE tools are executed via {@link PsiBridgeService}.</p>
 *
 * <p>Sessions are conversation threads identified by UUIDs. Message history
 * is kept in memory per session.</p>
 */
public final class AnthropicDirectClient extends AbstractClaudeAgentClient {

    private static final Logger LOG = Logger.getInstance(AnthropicDirectClient.class);

    public static final String PROFILE_ID = "claude-code";

    @Override
    public String agentId() { return PROFILE_ID; }

    @Override
    public String displayName() { return profile.getDisplayName(); }

    @Override
    public boolean isConnected() { return isHealthy(); }

    @Override
    public void stop() {
        started = false;
        sessions.clear();
        sessionModels.clear();
        sessionCancelled.clear();
    }

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Claude Code API");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setTransportType(TransportType.ANTHROPIC_DIRECT);
        p.setDescription("""
            Direct Anthropic API profile for Claude Code — experimental support. \
            Calls api.anthropic.com/v1/messages directly — no subprocess or ACP adapter needed. \
            Requires an Anthropic API key (set in Settings → Tools → IDE Agent → Agent Profiles → Claude Code API). \
            All IntelliJ IDE tools are available natively via the PSI bridge.""");
        p.setBinaryName("");
        p.setAlternateNames(List.of());
        p.setInstallHint("Set your Anthropic API key in the profile settings.");
        p.setInstallUrl("");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of());
        p.setMcpMethod(McpInjectionMethod.NONE);
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setExcludeAgentBuiltInTools(false);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        return p;
    }

    private static final String API_BASE = "https://api.anthropic.com";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String MODELS_PATH = "/v1/models";
    private static final String API_VERSION_HEADER = "anthropic-version";
    private static final String API_VERSION = "2023-06-01";
    private static final String API_KEY_HEADER = "x-api-key";
    private static final String BETA_HEADER = "anthropic-beta";
    private static final String INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14";

    private static final int MAX_TOOL_ITERATIONS = 50;
    private static final int MAX_TOKENS = 16384;

    // Message roles and content types
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_TOOL_USE = "tool_use";
    private static final String TYPE_TOOL_RESULT = "tool_result";
    private static final String TYPE_THINKING = "thinking";
    private static final String STOP_REASON_TOOL_USE = "tool_use";
    private static final String STOP_REASON_END_TURN = "end_turn";

    // JSON field names specific to the Anthropic SSE stream
    private static final String FIELD_DELTA = "delta";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_ERROR_MESSAGE = "message";
    private static final String FIELD_INDEX = "index";

    private final AgentProfile profile;
    @Nullable
    private final Project project;

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /**
     * Active conversations: session ID → ordered message history.
     */
    private final Map<String, List<JsonObject>> sessions = new ConcurrentHashMap<>();

    public AnthropicDirectClient(@NotNull AgentProfile profile,
                                 @Nullable ToolRegistry registry,
                                 @Nullable Project project) {
        super(registry);
        this.profile = profile;
        this.project = project;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    // ── AgentClient lifecycle ────────────────────────────────────────────────

    @Override
    public void start() throws AgentException {
        String apiKey = AnthropicKeyStore.getApiKey(profile.getId());
        if (apiKey == null || apiKey.isEmpty()) {
            throw new AgentException(
                "No Anthropic API key configured. "
                    + "Get your key at console.anthropic.com/settings/keys, "
                    + "then set it in Settings → Tools → IDE Agent → Agent Profiles → Claude Code.",
                null, false);
        }
        started = true;
        LOG.info("AnthropicDirectClient started for profile: " + profile.getDisplayName());
    }

    @Override
    public boolean isHealthy() {
        return started && AnthropicKeyStore.hasApiKey(profile.getId());
    }

    @Override
    public @org.jetbrains.annotations.Nullable String checkAuthentication() {
        return AnthropicKeyStore.hasApiKey(profile.getId()) ? null
            : "No Anthropic API key configured. Set it in Settings → Tools → IDE Agent → Agent Profiles.";
    }

    // stop() is the canonical lifecycle method; close() is inherited from AbstractAgentClient

    // ── Session management ───────────────────────────────────────────────────

    @Override
    public @NotNull String createSession(@Nullable String cwd) throws AgentException {
        ensureStarted();
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ArrayList<>());
        sessionCancelled.put(sessionId, new AtomicBoolean(false));
        if (cwd != null) {
            injectProjectInstructions(sessionId, cwd);
        }
        LOG.info("Created Anthropic session: " + sessionId);
        return sessionId;
    }

    @Override
    public void cancelSession(@NotNull String sessionId) {
        AtomicBoolean flag = sessionCancelled.get(sessionId);
        if (flag != null) flag.set(true);
    }

    // ── Session options ──────────────────────────────────────────────────────

    /**
     * Budget token values for each effort level.
     * Must be &lt; MAX_TOKENS (16384). Default (empty string) uses THINKING_BUDGET_DEFAULT.
     */
    private static final int THINKING_BUDGET_DEFAULT = 5000;
    private static final int THINKING_BUDGET_LOW = 1024;
    private static final int THINKING_BUDGET_MEDIUM = 5000;
    private static final int THINKING_BUDGET_HIGH = 10000;
    private static final int THINKING_BUDGET_MAX = 16000;

    private static final SessionOption THINKING_OPTION = new SessionOption(
        "effort", "Thinking Budget",
        List.of("", "low", "medium", "high", "max")
    );

    @Override
    public @NotNull List<SessionOption> listSessionOptions() {
        return List.of(THINKING_OPTION);
    }

    private int resolveThinkingBudget(@NotNull String sessionId) {
        String effort = getSessionOption(sessionId, "effort");
        if (effort == null || effort.isEmpty()) return THINKING_BUDGET_DEFAULT;
        return switch (effort) {
            case "low" -> THINKING_BUDGET_LOW;
            case "medium" -> THINKING_BUDGET_MEDIUM;
            case "high" -> THINKING_BUDGET_HIGH;
            case "max" -> THINKING_BUDGET_MAX;
            default -> THINKING_BUDGET_DEFAULT;
        };
    }

    // ── Model listing ────────────────────────────────────────────────────────

    @Override
    public @NotNull List<com.github.catatafishen.ideagentforcopilot.acp.model.Model> getAvailableModels() {
        if (!started) return List.of();
        String apiKey;
        try {
            apiKey = getApiKey();
        } catch (AgentException e) {
            return List.of();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + MODELS_PATH))
                .header(API_KEY_HEADER, apiKey)
                .header(API_VERSION_HEADER, API_VERSION)
                .header("Content-Type", "application/json")
                .GET()
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException(
                    "Failed to list models: HTTP " + resp.statusCode() + " — " + resp.body());
            }
            return parseModelsFromResponse(resp.body());
        } catch (IOException e) {
            throw new RuntimeException("Failed to contact Anthropic API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    @NotNull
    private List<com.github.catatafishen.ideagentforcopilot.acp.model.Model> parseModelsFromResponse(
            @NotNull String responseBody) {
        JsonObject body = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray data = body.getAsJsonArray("data");
        if (data == null) return List.of();
        // Use a LinkedHashMap keyed by display_name to deduplicate: the Anthropic API returns
        // both undated aliases (e.g. "claude-opus-4-6") and dated snapshots
        // (e.g. "claude-opus-4-6-20251101") with identical display names. Keep the first
        // occurrence, which is the alias and is the more stable identifier to send.
        java.util.LinkedHashMap<String, com.github.catatafishen.ideagentforcopilot.acp.model.Model> seen =
                new java.util.LinkedHashMap<>();
        for (var elem : data) {
            JsonObject m = elem.getAsJsonObject();
            String id = m.has("id") ? m.get("id").getAsString() : "";
            if (id.isEmpty()) continue;
            String displayName = m.has("display_name") ? m.get("display_name").getAsString() : id;
            seen.computeIfAbsent(displayName,
                    k -> new com.github.catatafishen.ideagentforcopilot.acp.model.Model(id, k, null, null));
        }
        return new ArrayList<>(seen.values());
    }

    // ── Prompt execution ─────────────────────────────────────────────────────

    @Override
    public @NotNull PromptResponse sendPrompt(@NotNull PromptRequest request,
                                              @NotNull Consumer<SessionUpdate> onUpdate) throws AgentException {
        ensureStarted();
        String sessionId = request.sessionId();
        List<JsonObject> messages = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        AtomicBoolean cancelled = sessionCancelled.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        cancelled.set(false);

        String resolvedModel = resolveModel(sessionId, request.modelId());
        String apiKey = getApiKey();

        messages.add(buildUserMessageFromBlocks(request.prompt()));

        // Wrap text chunks as AgentMessageChunk updates
        Consumer<String> onChunk = chunk ->
            onUpdate.accept(new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(chunk))));

        int thinkingBudget = resolveThinkingBudget(sessionId);
        String stopReason = runAgentLoop(messages, new LoopConfig(resolvedModel, apiKey, thinkingBudget, onChunk, onUpdate, null, cancelled));
        return new PromptResponse(stopReason, null);
    }

    private record LoopConfig(
        @NotNull String model,
        @NotNull String apiKey,
        int thinkingBudget,
        @Nullable Consumer<String> onChunk,
        @Nullable Consumer<SessionUpdate> onUpdate,
        @Nullable Runnable onRequest,
        @NotNull AtomicBoolean cancelled) {
    }

    @NotNull
    private String runAgentLoop(@NotNull List<JsonObject> messages,
                                @NotNull LoopConfig cfg) throws AgentException {
        AtomicReference<String> stopReason = new AtomicReference<>(STOP_REASON_END_TURN);
        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            if (cfg.cancelled().get()) throw new AgentException("Request cancelled", null, false);
            if (cfg.onRequest() != null) cfg.onRequest().run();

            JsonObject requestBody = buildRequestBody(messages, cfg.model(), cfg.thinkingBudget());
            List<JsonObject> contentBlocks = new ArrayList<>();

            streamResponse(cfg.apiKey(), requestBody, contentBlocks, stopReason, cfg.onChunk(), cfg.onUpdate(), cfg.cancelled());
            messages.add(buildAssistantMessage(contentBlocks));

            if (!STOP_REASON_TOOL_USE.equals(stopReason.get())) break;

            List<JsonObject> toolResults = executeToolUseBlocks(contentBlocks, cfg.onUpdate(), cfg.cancelled());
            messages.add(buildToolResultMessage(toolResults));
        }
        return stopReason.get();
    }

    // ── SSE streaming ────────────────────────────────────────────────────────

    private void streamResponse(@NotNull String apiKey,
                                @NotNull JsonObject requestBody,
                                @NotNull List<JsonObject> contentBlocks,
                                @NotNull AtomicReference<String> stopReason,
                                @Nullable Consumer<String> onChunk,
                                @Nullable Consumer<SessionUpdate> onUpdate,
                                @NotNull AtomicBoolean cancelled) throws AgentException {
        String body = gson.toJson(requestBody);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(API_BASE + MESSAGES_PATH))
            .header(API_KEY_HEADER, apiKey)
            .header(API_VERSION_HEADER, API_VERSION)
            .header(BETA_HEADER, INTERLEAVED_THINKING_BETA)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .timeout(Duration.ofMinutes(10))
            .build();

        try {
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = resp.body()) {
                if (resp.statusCode() != 200) {
                    String errorBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    if (resp.statusCode() == 429 || isRateLimitError(errorBody)) {
                        String userMessage = extractAnthropicErrorMessage(errorBody);
                        emitRateLimitBanner(userMessage, onUpdate);
                    }
                    throw new AgentException(
                        "Anthropic API error " + resp.statusCode() + ": " + errorBody, null, false);
                }
                parseSseStream(stream, contentBlocks, stopReason, onChunk, onUpdate, cancelled);
            }
        } catch (IOException e) {
            throw new AgentException("Stream error: " + e.getMessage(), e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted during streaming", e, false);
        }
    }

    /**
     * Holds mutable state for a single SSE stream parse pass.
     * Groups context values to avoid large parameter lists.
     * Uses {@link AtomicReference} for stopReason to avoid record array-equality warnings.
     */
    private record SseStreamState(
        Map<Integer, String> blockTypes,
        Map<Integer, JsonObject> blockObjects,
        Map<Integer, StringBuilder> toolInputBuffers,
        Map<Integer, StringBuilder> thinkingBuffers,
        List<JsonObject> contentBlocks,
        AtomicReference<String> stopReason,
        @Nullable Consumer<String> onChunk,
        @Nullable Consumer<SessionUpdate> onUpdate) {

        static SseStreamState create(
            @NotNull List<JsonObject> contentBlocks,
            @NotNull AtomicReference<String> stopReason,
            @Nullable Consumer<String> onChunk,
            @Nullable Consumer<SessionUpdate> onUpdate) {
            return new SseStreamState(
                new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(),
                contentBlocks, stopReason, onChunk, onUpdate);
        }
    }

    private void parseSseStream(@NotNull InputStream inputStream,
                                @NotNull List<JsonObject> contentBlocks,
                                @NotNull AtomicReference<String> stopReason,
                                @Nullable Consumer<String> onChunk,
                                @Nullable Consumer<SessionUpdate> onUpdate,
                                @NotNull AtomicBoolean cancelled) throws IOException {
        SseStreamState state = SseStreamState.create(contentBlocks, stopReason, onChunk, onUpdate);

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                        parseSseEvent(data, state);
                    }
                }
            }
        }
    }

    private void parseSseEvent(@NotNull String data, @NotNull SseStreamState state) {
        JsonObject event;
        try {
            event = JsonParser.parseString(data).getAsJsonObject();
        } catch (Exception e) {
            LOG.debug("Could not parse SSE event: " + data, e);
            return;
        }

        String eventType = event.has(FIELD_TYPE) ? event.get(FIELD_TYPE).getAsString() : "";
        switch (eventType) {
            case "content_block_start" -> handleBlockStart(event, state.blockTypes(), state.blockObjects(),
                state.toolInputBuffers(), state.thinkingBuffers());
            case "content_block_delta" -> handleBlockDelta(event, state.blockTypes(), state.toolInputBuffers(),
                state.thinkingBuffers(), state.onChunk(), state.onUpdate());
            case "content_block_stop" -> handleBlockStop(event, state.blockTypes(), state.blockObjects(),
                state.toolInputBuffers(), state.thinkingBuffers(), state.contentBlocks());
            case "message_delta" -> handleMessageDelta(event, state.stopReason());
            case FIELD_ERROR -> handleStreamError(event);
            default -> { /* ping, message_start, message_stop — ignore */ }
        }
    }

    private void handleMessageDelta(@NotNull JsonObject event, @NotNull AtomicReference<String> stopReason) {
        if (!event.has(FIELD_DELTA)) return;
        JsonObject delta = event.getAsJsonObject(FIELD_DELTA);
        if (delta.has("stop_reason")) {
            stopReason.set(delta.get("stop_reason").getAsString());
        }
    }

    private void handleStreamError(@NotNull JsonObject event) {
        String errMsg = event.has(FIELD_ERROR)
            ? event.getAsJsonObject(FIELD_ERROR).get(FIELD_ERROR_MESSAGE).getAsString()
            : event.toString();
        LOG.warn("Anthropic stream error: " + errMsg);
    }

    /**
     * Extracts a human-readable message from an Anthropic HTTP error response body.
     * Anthropic errors are JSON of the form {@code {"type":"error","error":{"type":"...","message":"..."}}}.
     * Falls back to the raw body if parsing fails.
     */
    @NotNull
    private static String extractAnthropicErrorMessage(@NotNull String errorBody) {
        try {
            JsonObject root = JsonParser.parseString(errorBody).getAsJsonObject();
            if (root.has(FIELD_ERROR) && root.get(FIELD_ERROR).isJsonObject()) {
                JsonObject err = root.getAsJsonObject(FIELD_ERROR);
                if (err.has(FIELD_ERROR_MESSAGE)) return err.get(FIELD_ERROR_MESSAGE).getAsString();
            }
        } catch (Exception ignored) {
            // Fall through to raw body
        }
        return errorBody;
    }

    private void handleBlockStart(@NotNull JsonObject event,
                                  @NotNull Map<Integer, String> blockTypes,
                                  @NotNull Map<Integer, JsonObject> blockObjects,
                                  @NotNull Map<Integer, StringBuilder> toolInputBuffers,
                                  @NotNull Map<Integer, StringBuilder> thinkingBuffers) {
        int index = event.get(FIELD_INDEX).getAsInt();
        JsonObject block = event.has("content_block") ? event.getAsJsonObject("content_block") : new JsonObject();
        String type = block.has(FIELD_TYPE) ? block.get(FIELD_TYPE).getAsString() : TYPE_TEXT;
        blockTypes.put(index, type);

        if (TYPE_TOOL_USE.equals(type)) {
            blockObjects.put(index, block.deepCopy());
            toolInputBuffers.put(index, new StringBuilder());
        } else if (TYPE_THINKING.equals(type)) {
            thinkingBuffers.put(index, new StringBuilder());
        }
    }

    private void handleBlockDelta(@NotNull JsonObject event,
                                  @NotNull Map<Integer, String> blockTypes,
                                  @NotNull Map<Integer, StringBuilder> toolInputBuffers,
                                  @NotNull Map<Integer, StringBuilder> thinkingBuffers,
                                  @Nullable Consumer<String> onChunk,
                                  @Nullable Consumer<SessionUpdate> onUpdate) {
        int index = event.get(FIELD_INDEX).getAsInt();
        JsonObject delta = event.has(FIELD_DELTA) ? event.getAsJsonObject(FIELD_DELTA) : new JsonObject();
        String deltaType = delta.has(FIELD_TYPE) ? delta.get(FIELD_TYPE).getAsString() : "";
        String blockType = blockTypes.getOrDefault(index, TYPE_TEXT);

        switch (deltaType) {
            case "text_delta" -> applyTextDelta(blockType, delta, onChunk);
            case "input_json_delta" -> applyInputJsonDelta(blockType, index, delta, toolInputBuffers);
            case "thinking_delta" -> handleThinkingDelta(blockType, index, delta, thinkingBuffers, onUpdate);
            default -> { /* no-op for unsupported delta types */ }
        }
    }

    private void applyTextDelta(@NotNull String blockType, @NotNull JsonObject delta,
                                @Nullable Consumer<String> onChunk) {
        if (!TYPE_TEXT.equals(blockType) || onChunk == null) return;
        String text = delta.has(TYPE_TEXT) ? delta.get(TYPE_TEXT).getAsString() : "";
        if (!text.isEmpty()) onChunk.accept(text);
    }

    private void applyInputJsonDelta(@NotNull String blockType, int index,
                                     @NotNull JsonObject delta,
                                     @NotNull Map<Integer, StringBuilder> toolInputBuffers) {
        if (!TYPE_TOOL_USE.equals(blockType)) return;
        String partial = delta.has("partial_json") ? delta.get("partial_json").getAsString() : "";
        StringBuilder buf = toolInputBuffers.get(index);
        if (buf != null) buf.append(partial);
    }

    private void handleThinkingDelta(@NotNull String blockType,
                                     int index,
                                     @NotNull JsonObject delta,
                                     @NotNull Map<Integer, StringBuilder> thinkingBuffers,
                                     @Nullable Consumer<SessionUpdate> onUpdate) {
        if (!TYPE_THINKING.equals(blockType)) return;
        String thinking = delta.has(TYPE_THINKING) ? delta.get(TYPE_THINKING).getAsString() : "";
        if (thinking.isEmpty()) return;
        StringBuilder buf = thinkingBuffers.get(index);
        if (buf != null) buf.append(thinking);
        emitThought(thinking, onUpdate);
    }

    private void handleBlockStop(@NotNull JsonObject event,
                                 @NotNull Map<Integer, String> blockTypes,
                                 @NotNull Map<Integer, JsonObject> blockObjects,
                                 @NotNull Map<Integer, StringBuilder> toolInputBuffers,
                                 @NotNull Map<Integer, StringBuilder> thinkingBuffers,
                                 @NotNull List<JsonObject> contentBlocks) {
        int index = event.get(FIELD_INDEX).getAsInt();
        String type = blockTypes.getOrDefault(index, TYPE_TEXT);

        if (TYPE_TOOL_USE.equals(type)) {
            JsonObject block = blockObjects.getOrDefault(index, new JsonObject());
            StringBuilder inputBuf = toolInputBuffers.getOrDefault(index, new StringBuilder());
            String inputJson = inputBuf.toString().trim();
            JsonObject input = inputJson.isEmpty() ? new JsonObject()
                : JsonParser.parseString(inputJson).getAsJsonObject();
            block.add(FIELD_INPUT, input);
            contentBlocks.add(block);
        } else if (TYPE_THINKING.equals(type)) {
            StringBuilder buf = thinkingBuffers.get(index);
            if (buf != null && !buf.isEmpty()) {
                JsonObject block = new JsonObject();
                block.addProperty(FIELD_TYPE, TYPE_THINKING);
                block.addProperty(TYPE_THINKING, buf.toString());
                contentBlocks.add(block);
            }
        }
        // Text blocks are streamed directly via onChunk — no need to collect here
    }

    // ── Tool execution ───────────────────────────────────────────────────────

    @NotNull
    private List<JsonObject> executeToolUseBlocks(@NotNull List<JsonObject> contentBlocks,
                                                  @Nullable Consumer<SessionUpdate> onUpdate,
                                                  @NotNull AtomicBoolean cancelled) {
        List<JsonObject> results = new ArrayList<>();
        for (JsonObject block : contentBlocks) {
            if (cancelled.get()) break;
            if (isToolUseBlock(block)) {
                results.add(buildAndExecuteToolResult(block, onUpdate));
            }
        }
        return results;
    }

    private boolean isToolUseBlock(@NotNull JsonObject block) {
        return TYPE_TOOL_USE.equals(block.has(FIELD_TYPE) ? block.get(FIELD_TYPE).getAsString() : "");
    }

    @NotNull
    private JsonObject buildAndExecuteToolResult(@NotNull JsonObject block,
                                                 @Nullable Consumer<SessionUpdate> onUpdate) {
        String toolUseId = block.has("id") ? block.get("id").getAsString() : UUID.randomUUID().toString();
        String toolName = block.has("name") ? block.get("name").getAsString() : "";
        JsonObject input = block.has(FIELD_INPUT) ? block.getAsJsonObject(FIELD_INPUT) : new JsonObject();

        emitToolCallStart(toolUseId, toolName, input, onUpdate);
        String result;
        boolean success = true;
        try {
            result = executeOneTool(toolName, input);
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
            success = false;
            LOG.warn("Tool execution error: " + toolName, e);
        }
        emitToolCallEnd(toolUseId, result, success, onUpdate);

        JsonObject toolResult = new JsonObject();
        toolResult.addProperty(FIELD_TYPE, TYPE_TOOL_RESULT);
        toolResult.addProperty("tool_use_id", toolUseId);
        toolResult.addProperty(FIELD_CONTENT, result);
        return toolResult;
    }

    private String executeOneTool(@NotNull String toolName, @NotNull JsonObject input) {
        if (project == null) return "Error: no project context available";
        return PsiBridgeService.getInstance(project).callTool(toolName, input);
    }

    // ── Request building ─────────────────────────────────────────────────────

    @NotNull
    private JsonObject buildRequestBody(@NotNull List<JsonObject> messages, @NotNull String model, int thinkingBudget) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", MAX_TOKENS);
        body.addProperty("stream", true);

        String systemPrompt = buildSystemPrompt();
        if (!systemPrompt.isEmpty()) body.addProperty("system", systemPrompt);

        JsonArray tools = buildToolsArray();
        if (!tools.isEmpty()) body.add("tools", tools);

        // Enable extended thinking
        JsonObject thinkingConfig = new JsonObject();
        thinkingConfig.addProperty(FIELD_TYPE, "enabled");
        thinkingConfig.addProperty("budget_tokens", thinkingBudget);
        body.add(TYPE_THINKING, thinkingConfig);

        JsonArray msgs = new JsonArray();
        for (JsonObject msg : messages) msgs.add(msg);
        body.add("messages", msgs);

        return body;
    }

    @NotNull
    private JsonArray buildToolsArray() {
        JsonArray tools = new JsonArray();
        if (registry == null) return tools;

        for (ToolDefinition def : registry.getAllTools()) {
            if (def.isBuiltIn()) continue;
            JsonObject tool = new JsonObject();
            tool.addProperty("name", def.id());
            tool.addProperty("description", def.description());
            JsonObject schema = def.inputSchema();
            tool.add("input_schema", schema != null ? schema : defaultSchema());
            tools.add(tool);
        }
        return tools;
    }

    @NotNull
    private JsonObject defaultSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty(FIELD_TYPE, "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @NotNull
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // Add environment context (working directory, git status, platform)
        // This ensures the agent has correct context about where it's operating
        if (project != null) {
            sb.append("# Environment\n");
            sb.append("You have been invoked in the following environment:\n");

            String basePath = project.getBasePath();
            if (basePath != null) {
                sb.append(" - Primary working directory: ").append(basePath).append("\n");

                // Check if it's a git repository
                java.nio.file.Path gitPath = java.nio.file.Path.of(basePath, ".git");
                boolean isGitRepo = java.nio.file.Files.exists(gitPath);
                sb.append(" - Is a git repository: ").append(isGitRepo ? "true" : "false").append("\n");
            }

            // Add platform information
            sb.append(" - Platform: ").append(System.getProperty("os.name").toLowerCase()).append("\n");
            sb.append(" - OS Version: ").append(System.getProperty("os.version")).append("\n");

            sb.append("\n");
        }

        sb.append("You are an expert software engineering assistant working inside IntelliJ IDEA. ");
        sb.append("Use the provided tools to read files, make edits, run tests, and help with coding tasks. ");
        sb.append("Prefer IntelliJ-native tools over generic file operations when available. ");
        sb.append("Be concise and action-oriented.");

        String additional = profile.getAdditionalInstructions();
        if (!additional.isEmpty()) {
            sb.append("\n\n").append(additional);
        }
        return sb.toString();
    }

    @NotNull
    /**
     * Builds a user message from ACP content blocks.
     * Text blocks and resource blocks are converted to Anthropic API format.
     */
    private JsonObject buildUserMessageFromBlocks(@NotNull List<ContentBlock> blocks) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", ROLE_USER);

        // Collect text content from blocks
        StringBuilder plainText = new StringBuilder();
        List<JsonObject> contentArray = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Text t) {
                plainText.append(t.text());
            } else if (block instanceof ContentBlock.Resource r) {
                ContentBlock.ResourceLink rl = r.resource();
                if (rl.text() != null && !rl.text().isEmpty()) {
                    JsonObject fileBlock = new JsonObject();
                    fileBlock.addProperty(FIELD_TYPE, TYPE_TEXT);
                    fileBlock.addProperty(TYPE_TEXT, "File: " + rl.uri() + "\n```\n" + rl.text() + "\n```");
                    contentArray.add(fileBlock);
                }
            }
        }

        if (contentArray.isEmpty()) {
            msg.addProperty(FIELD_CONTENT, plainText.toString());
        } else {
            JsonObject promptBlock = new JsonObject();
            promptBlock.addProperty(FIELD_TYPE, TYPE_TEXT);
            promptBlock.addProperty(TYPE_TEXT, plainText.toString());
            contentArray.add(promptBlock);
            JsonArray arr = new JsonArray();
            for (JsonObject b : contentArray) arr.add(b);
            msg.add(FIELD_CONTENT, arr);
        }
        return msg;
    }

    @NotNull
    private JsonObject buildAssistantMessage(@NotNull List<JsonObject> contentBlocks) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", ROLE_ASSISTANT);
        JsonArray content = new JsonArray();
        for (JsonObject block : contentBlocks) content.add(block);
        msg.add(FIELD_CONTENT, content);
        return msg;
    }

    @NotNull
    private JsonObject buildToolResultMessage(@NotNull List<JsonObject> toolResults) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", ROLE_USER);
        JsonArray content = new JsonArray();
        for (JsonObject result : toolResults) content.add(result);
        msg.add(FIELD_CONTENT, content);
        return msg;
    }

    // ── Project instruction injection ────────────────────────────────────────

    private void injectProjectInstructions(@NotNull String sessionId, @NotNull String cwd) {
        List<JsonObject> messages = sessions.get(sessionId);
        if (messages == null) return;

        Path claudeMd = Path.of(cwd, "CLAUDE.md");
        if (!Files.exists(claudeMd)) return;

        try {
            String fileContent = Files.readString(claudeMd);
            JsonObject instructionMsg = new JsonObject();
            instructionMsg.addProperty("role", ROLE_USER);
            instructionMsg.addProperty(FIELD_CONTENT,
                "Project instructions (CLAUDE.md):\n```\n" + fileContent + "\n```");
            messages.add(instructionMsg);

            JsonObject ack = new JsonObject();
            ack.addProperty("role", ROLE_ASSISTANT);
            ack.addProperty(FIELD_CONTENT, "Understood. I'll follow these project instructions.");
            messages.add(ack);

            LOG.info("Injected CLAUDE.md instructions for session " + sessionId);
        } catch (IOException e) {
            LOG.warn("Could not read CLAUDE.md: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @NotNull
    private String getApiKey() throws AgentException {
        String key = AnthropicKeyStore.getApiKey(profile.getId());
        if (key == null || key.isEmpty()) {
            throw new AgentException(
                "Anthropic API key not set. Configure it in Agent Profiles settings.", null, false);
        }
        return key;
    }
}
