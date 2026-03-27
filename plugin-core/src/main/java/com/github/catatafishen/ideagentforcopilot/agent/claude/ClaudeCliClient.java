package com.github.catatafishen.ideagentforcopilot.agent.claude;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.agent.AgentException;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.SessionOption;
import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Claude CLI implementation that drives the {@code claude} CLI binary in
 * bidirectional {@code --input-format stream-json --output-format stream-json} mode.
 *
 * <p>Authentication is handled entirely by the {@code claude} subprocess using the
 * OAuth credentials stored by {@code claude auth login} — no Anthropic API key is
 * required. Multi-turn conversations are maintained via {@code --resume <session-id>},
 * where the session ID is extracted from the CLI's {@code stream-json} output.</p>
 *
 * <p>The bidirectional protocol keeps stdin open after writing the user message as a JSON
 * envelope ({@code {"type":"user","message":{...}}}). When the CLI sends a
 * {@code control_request} event (e.g. a {@code can_use_tool} permission check), this client
 * writes a {@code control_response} back to stdin to auto-approve. Stdin is closed once the
 * {@code result} event arrives (or on cancellation).</p>
 *
 * <p>If an MCP port is provided ({@code > 0}), a temporary MCP config file is written
 * and passed via {@code --mcp-config} so the CLI can call IDE tools from the plugin's
 * MCP server.</p>
 */
public final class ClaudeCliClient extends AbstractClaudeAgentClient {

    private static final Logger LOG = Logger.getInstance(ClaudeCliClient.class);

    public static final String PROFILE_ID = "claude-cli";

    @Override
    public String agentId() {
        return PROFILE_ID;
    }

    @Override
    public String displayName() {
        return profile.getDisplayName();
    }

    @Override
    public boolean isConnected() {
        return isHealthy();
    }

    private static final String FIELD_SESSION_ID = "session_id";
    private static final String SUBTYPE_ERROR = "error";
    private static final String STOP_REASON_END_TURN = "end_turn";
    private static final String PROFILE_FLAG = "--profile";

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Claude Code CLI");
        p.setBuiltIn(true);
        p.setExperimental(false);
        p.setTransportType(TransportType.CLAUDE_CLI);
        p.setDescription("""
            Claude Code CLI profile — experimental support. Drives the locally-installed \
            'claude' binary in --print mode via subprocess. \
            Uses your Claude subscription — no Anthropic API key required. \
            Install the CLI from code.claude.com and run 'claude auth login' once to set up.""");
        p.setBinaryName("claude");
        p.setAlternateNames(List.of());
        p.setInstallHint("Install the Claude CLI from code.claude.com and run 'claude auth login'.");
        p.setInstallUrl("https://code.claude.com");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of());
        p.setMcpMethod(McpInjectionMethod.CONFIG_FLAG);
        p.setSupportsMcpConfigFlag(true);
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        // requiresResourceDuplication removed (always false for CLI agents)
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setPrependInstructionsTo("");  // Claude CLI gets instructions via MCP initialize, not file prepend
        return p;
    }

    private final AgentProfile profile;
    private final Project project;
    private final int mcpPort;
    private final AgentConfig config;

    /**
     * Maps plugin session ID → CLI session ID (for --resume).
     */
    private final Map<String, String> cliSessionIds = new ConcurrentHashMap<>();
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    private static final String KEY_CLI_RESUME_SESSION = "cliResumeSessionId";

    @Nullable
    private String loadCliResumeSessionId() {
        if (project == null) return null;
        return PropertiesComponent.getInstance(project).getValue(profile.getId() + "." + KEY_CLI_RESUME_SESSION);
    }

    private void persistCliResumeSessionId(@Nullable String id) {
        if (project == null) return;
        if (id == null) {
            PropertiesComponent.getInstance(project).unsetValue(profile.getId() + "." + KEY_CLI_RESUME_SESSION);
        } else {
            PropertiesComponent.getInstance(project).setValue(profile.getId() + "." + KEY_CLI_RESUME_SESSION, id);
        }
    }

    private String resolvedBinaryPath;

    public ClaudeCliClient(@NotNull AgentProfile profile,
                           @NotNull AgentConfig config,
                           @Nullable ToolRegistry registry,
                           @Nullable Project project,
                           int mcpPort) {
        super(registry);
        this.profile = profile;
        this.config = config;
        this.project = project;
        this.mcpPort = mcpPort;
    }

    // ── AgentClient lifecycle ────────────────────────────────────────────────

    @Override
    public void start() throws AgentException {
        resolvedBinaryPath = resolveBinary();
        started = true;
        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        String name = creds.getDisplayName();
        if (!creds.isLoggedIn()) {
            LOG.warn("Claude CLI credentials not found or expired — prompts will fail until 'claude auth login' is run");
        }
        LOG.info("ClaudeCliClient started for profile: " + profile.getDisplayName()
            + (name != null ? " (account: " + name + ")" : ""));
    }

    @Override
    public boolean isHealthy() {
        return started;
    }

    @Override
    public @org.jetbrains.annotations.Nullable String checkAuthentication() {
        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        return creds.isLoggedIn() ? null
            : "Not authenticated with Claude. Run 'claude auth login' in a terminal, then retry.";
    }

    @Override
    public void stop() {
        started = false;
        activeProcesses.values().forEach(Process::destroyForcibly);
        activeProcesses.clear();
        cliSessionIds.clear();
        sessionModels.clear();
        sessionCancelled.clear();
    }

    @Override
    public void clearPersistedSession() {
        persistCliResumeSessionId(null);
    }

    // ── Session management ───────────────────────────────────────────────────

    @Override
    public @NotNull String createSession(@Nullable String cwd) {
        String sessionId = UUID.randomUUID().toString();
        sessionCancelled.put(sessionId, new AtomicBoolean(false));
        LOG.info("Created ClaudeCLI session: " + sessionId);
        return sessionId;
    }

    @Override
    public void cancelSession(@NotNull String sessionId) {
        AtomicBoolean flag = sessionCancelled.get(sessionId);
        if (flag != null) flag.set(true);
        Process proc = activeProcesses.remove(sessionId);
        if (proc != null) proc.destroyForcibly();
    }

    // ── Session options ──────────────────────────────────────────────────────

    /**
     * Effort levels supported by the {@code --effort} flag.
     */
    private static final SessionOption EFFORT_OPTION = new SessionOption(
        "effort", "Effort",
        List.of("", "low", "medium", "high", "max")
    );

    @Override
    public @NotNull List<SessionOption> listSessionOptions() {
        return List.of(EFFORT_OPTION);
    }

    // ── Model listing ────────────────────────────────────────────────────────

    /**
     * Claude CLI model aliases.
     *
     * <p>The Claude CLI provides stable model aliases that automatically resolve to the
     * latest version of each model family. These aliases are preferred over specific
     * version IDs because they don't require updates when new model versions are released.</p>
     *
     * <p>See: https://docs.claude.ai/claude-for-desktop/reference#model-aliases</p>
     */
    private static final List<com.github.catatafishen.ideagentforcopilot.acp.model.Model> KNOWN_MODELS =
        buildKnownModels();

    private static List<com.github.catatafishen.ideagentforcopilot.acp.model.Model> buildKnownModels() {
        Object[][] rows = {
            // { alias, displayName }
            {"default", "Default (recommended)"},
            {"sonnet", "Sonnet (latest, daily coding)"},
            {"opus", "Opus (latest, complex reasoning)"},
            {"haiku", "Haiku (fast and efficient)"},
            {"sonnet[1m]", "Sonnet 1M context"},
            {"opus[1m]", "Opus 1M context"},
            {"opusplan", "Opus Plan (opus→sonnet)"},
        };
        List<com.github.catatafishen.ideagentforcopilot.acp.model.Model> list = new ArrayList<>(rows.length);
        for (Object[] row : rows) {
            list.add(new com.github.catatafishen.ideagentforcopilot.acp.model.Model(
                (String) row[0], (String) row[1], null, null));
        }
        return java.util.Collections.unmodifiableList(list);
    }

    @Override
    public @NotNull List<com.github.catatafishen.ideagentforcopilot.acp.model.Model> getAvailableModels() {
        return KNOWN_MODELS;
    }

    // ── Prompt execution ─────────────────────────────────────────────────────

    @Override
    public @NotNull PromptResponse sendPrompt(@NotNull PromptRequest request,
                                              @NotNull Consumer<SessionUpdate> onUpdate) throws AgentException {
        ensureStarted();
        String sessionId = request.sessionId();
        AtomicBoolean cancelled = sessionCancelled.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        cancelled.set(false);

        String resolvedModel = resolveModel(sessionId, request.modelId());
        boolean isNewSession = !cliSessionIds.containsKey(sessionId);
        String rawPrompt = extractPromptText(request.prompt());
        String fullPrompt = buildFullPrompt(rawPrompt, isNewSession);
        List<String> cmd = buildCommand(sessionId, resolvedModel);

        // Wrap text chunks as AgentMessageChunk updates
        Consumer<String> onChunk = chunk ->
            onUpdate.accept(new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(chunk))));

        Path mcpConfig = null;
        try {
            mcpConfig = writeMcpConfigIfNeeded();
            if (mcpConfig != null) {
                cmd.add("--mcp-config");
                cmd.add(mcpConfig.toString());
            }
            String stopReason = runSubprocess(sessionId, cmd, fullPrompt, onChunk, onUpdate, cancelled);
            return new PromptResponse(stopReason, null);
        } finally {
            if (mcpConfig != null) {
                try {
                    Files.deleteIfExists(mcpConfig);
                } catch (IOException e) {
                    LOG.debug("Could not delete temp MCP config: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Extract plain text from content blocks, inlining resource file content.
     */
    @NotNull
    private static String extractPromptText(@NotNull List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Text t) {
                sb.append(t.text());
            } else if (block instanceof ContentBlock.Resource r) {
                ContentBlock.ResourceLink rl = r.resource();
                if (rl.text() != null && !rl.text().isEmpty()) {
                    sb.append("File: ").append(rl.uri()).append("\n```\n").append(rl.text()).append("\n```\n\n");
                }
            }
        }
        return sb.toString();
    }

    @NotNull
    private List<String> buildCommand(@NotNull String sessionId, @NotNull String model) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedBinaryPath);
        cmd.add("--verbose");  // required for full assistant events (thinking blocks, tool events)
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--input-format");
        cmd.add("stream-json");  // bidirectional: enables control_request / control_response

        // Suppress interactive terminal permission prompts. The control_request / control_response
        // protocol handles tool approval instead, so Claude must not open a TTY dialog.
        if (profile.isUsePluginPermissions()) {
            cmd.add("--dangerously-skip-permissions");
        }

        String profileName = extractProfileName();
        if (profileName != null) {
            cmd.add(PROFILE_FLAG);
            cmd.add(profileName);
        }

        cmd.add("--model");
        cmd.add(model);

        // Allow only safe web tools (WebFetch, WebSearch) when excluding agent built-in tools.
        // All other tools (Bash, Edit, Write, Read, etc.) are provided via MCP instead.
        if (profile.isExcludeAgentBuiltInTools()) {
            cmd.add("--tools");
            cmd.add("WebFetch,WebSearch");
        }

        String effort = getSessionOption(sessionId, "effort");
        if (effort != null && !effort.isEmpty()) {
            cmd.add("--effort");
            cmd.add(effort);
        }

        String cliSessionId = cliSessionIds.get(sessionId);
        if (cliSessionId == null) {
            cliSessionId = loadCliResumeSessionId();
        }
        if (cliSessionId != null) {
            cmd.add("--resume");
            cmd.add(cliSessionId);
        }
        return cmd;
    }

    /**
     * Extract Claude CLI profile name from agent profile's acpArgs if configured.
     * Looks for "--profile <name>" in the acpArgs list.
     *
     * @return the profile name, or null if not configured
     */
    @Nullable
    private String extractProfileName() {
        List<String> args = profile.getAcpArgs();
        for (int i = 0; i < args.size() - 1; i++) {
            if (PROFILE_FLAG.equals(args.get(i))) {
                return args.get(i + 1);
            }
        }
        return null;
    }

    private String runSubprocess(@NotNull String sessionId,
                                 @NotNull List<String> cmd,
                                 @NotNull String prompt,
                                 @Nullable Consumer<String> onChunk,
                                 @Nullable Consumer<SessionUpdate> onUpdate,
                                 @NotNull AtomicBoolean cancelled) throws AgentException {
        try {
            LOG.info("Executing Claude CLI command: " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);

            // Inject captured shell environment (includes nvm, sdkman, etc.)
            pb.environment().putAll(com.github.catatafishen.ideagentforcopilot.settings.ShellEnvironment.getEnvironment());

            // Set working directory to project base path so the CLI detects correct git repo and cwd
            if (project != null && project.getBasePath() != null) {
                pb.directory(new File(project.getBasePath()));
            }

            pb.redirectErrorStream(false);
            Process proc = pb.start();
            activeProcesses.put(sessionId, proc);

            // Drain stderr on a background thread to prevent buffer deadlock
            StringBuilder stderrBuf = new StringBuilder();
            Thread stderrThread = startStderrDrainer(proc, stderrBuf);

            // Write JSON user message; stdin is kept open for bidirectional control_response exchange.
            // handleStreamEvent closes stdin when the result event arrives; parseStreamOutput's
            // finally block closes it again as a safety net (double-close is a no-op).
            OutputStream stdin = proc.getOutputStream();
            writeJsonPromptToStdin(stdin, prompt);

            String stopReason = parseStreamOutput(sessionId, proc, stdin, onChunk, onUpdate, cancelled);
            proc.waitFor();
            stderrThread.join(2000);
            activeProcesses.remove(sessionId);

            String stderr = stderrBuf.toString().trim();
            if (!stderr.isEmpty()) {
                LOG.warn("claude CLI stderr: " + stderr);
                if (stopReason.equals(STOP_REASON_END_TURN) && onChunk != null) {
                    // No output was produced; surface the CLI error to the user
                    onChunk.accept("\n[Claude CLI error: " + stderr + "]");
                }
            }

            return stopReason;
        } catch (IOException e) {
            throw new AgentException("Failed to start claude process: " + e.getMessage(), e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted waiting for claude process", e, false);
        }
    }

    // ── stream-json parsing ──────────────────────────────────────────────────

    @NotNull
    private String parseStreamOutput(@NotNull String sessionId,
                                     @NotNull Process proc,
                                     @NotNull OutputStream stdin,
                                     @Nullable Consumer<String> onChunk,
                                     @Nullable Consumer<SessionUpdate> onUpdate,
                                     @NotNull AtomicBoolean cancelled) throws IOException {
        String stopReason = STOP_REASON_END_TURN;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    stopReason = handleStreamEvent(sessionId, event, stopReason, stdin, onChunk, onUpdate);
                } catch (RuntimeException e) {
                    LOG.debug("Could not parse stream-json line: " + line, e);
                }
            }
        } finally {
            closeQuietly(stdin);
        }
        if (cancelled.get()) stopReason = "cancelled";
        return stopReason;
    }

    @NotNull
    private String handleStreamEvent(@NotNull String sessionId,
                                     @NotNull JsonObject event,
                                     @NotNull String currentStopReason,
                                     @NotNull OutputStream stdin,
                                     @Nullable Consumer<String> onChunk,
                                     @Nullable Consumer<SessionUpdate> onUpdate) {
        String type = event.has(FIELD_TYPE) ? event.get(FIELD_TYPE).getAsString() : "";
        return switch (type) {
            case "system" -> {
                if (event.has(FIELD_SESSION_ID)) {
                    String captured = event.get(FIELD_SESSION_ID).getAsString();
                    cliSessionIds.put(sessionId, captured);
                    persistCliResumeSessionId(captured);
                }
                yield currentStopReason;
            }
            case "assistant" -> {
                streamAssistantMessage(event, onChunk, onUpdate);
                yield currentStopReason;
            }
            case "tool_use" -> {
                emitToolCallStart(event, onUpdate);
                yield currentStopReason;
            }
            case "tool_result" -> {
                // Fallback: some CLI versions may emit tool_result as a top-level event.
                emitToolCallEnd(event, onUpdate);
                yield currentStopReason;
            }
            case "user" -> {
                // In stream-json format, tool results arrive as a "user" message event with
                // tool_result content blocks. Process each block to emit tool_call_update events,
                // which sets toolJustCompleted in the UI and creates a new message segment before
                // the next assistant response.
                if (event.has(FIELD_MESSAGE)) {
                    JsonObject msg = event.getAsJsonObject(FIELD_MESSAGE);
                    if (msg.has(FIELD_CONTENT) && msg.get(FIELD_CONTENT).isJsonArray()) {
                        for (JsonElement el : msg.getAsJsonArray(FIELD_CONTENT)) {
                            if (el.isJsonObject()) {
                                JsonObject block = el.getAsJsonObject();
                                String blockType = block.has(FIELD_TYPE) ? block.get(FIELD_TYPE).getAsString() : "";
                                if ("tool_result".equals(blockType)) {
                                    emitToolCallEnd(block, onUpdate);
                                }
                            }
                        }
                    }
                }
                yield currentStopReason;
            }
            case "control_request" -> {
                respondToControlRequest(event, stdin);
                yield currentStopReason;
            }
            case "result" -> {
                // Always capture session_id from the result event (most reliable location)
                if (event.has(FIELD_SESSION_ID)) {
                    String captured = event.get(FIELD_SESSION_ID).getAsString();
                    cliSessionIds.put(sessionId, captured);
                    persistCliResumeSessionId(captured);
                }
                boolean isError = event.has(FIELD_SUBTYPE)
                    && SUBTYPE_ERROR.equals(event.get(FIELD_SUBTYPE).getAsString());
                if (isError && event.has(SUBTYPE_ERROR)) {
                    String errorText = extractErrorText(event.get(SUBTYPE_ERROR));
                    if (onChunk != null) onChunk.accept("\n[Error: " + errorText + "]");
                    if (isRateLimitError(errorText)) emitRateLimitBanner(errorText, onUpdate);
                }
                // Emit token/cost usage so the UI can display it in the toolbar
                if (!isError) emitUsageStats(event, onUpdate);
                // Close stdin so profile-based sessions (which keep stdout open waiting
                // for the next message) receive the EOF signal and exit cleanly.
                closeQuietly(stdin);
                yield isError ? SUBTYPE_ERROR : STOP_REASON_END_TURN;
            }
            default -> currentStopReason;
        };
    }

    private void streamAssistantMessage(@NotNull JsonObject event,
                                        @Nullable Consumer<String> onChunk,
                                        @Nullable Consumer<SessionUpdate> onUpdate) {
        if (!event.has(FIELD_MESSAGE)) return;
        JsonObject message = event.getAsJsonObject(FIELD_MESSAGE);
        if (!message.has(FIELD_CONTENT)) return;
        for (JsonElement block : message.getAsJsonArray(FIELD_CONTENT)) {
            if (block.isJsonObject()) {
                streamContentBlock(block.getAsJsonObject(), onChunk, onUpdate);
            }
        }
    }

    private void streamContentBlock(@NotNull JsonObject block,
                                    @Nullable Consumer<String> onChunk,
                                    @Nullable Consumer<SessionUpdate> onUpdate) {
        String blockType = block.has(FIELD_TYPE) ? block.get(FIELD_TYPE).getAsString() : "";
        if (BLOCK_TYPE_TEXT.equals(blockType) && block.has(BLOCK_TYPE_TEXT) && onChunk != null) {
            String text = block.get(BLOCK_TYPE_TEXT).getAsString();
            if (!text.isEmpty()) onChunk.accept(text);
        } else if (BLOCK_TYPE_THINKING.equals(blockType) && block.has(BLOCK_TYPE_THINKING)) {
            String thinking = block.get(BLOCK_TYPE_THINKING).getAsString();
            emitThought(thinking, onUpdate);
        } else if ("tool_use".equals(blockType)) {
            // Tool-use blocks are embedded in the assistant message content in stream-json format.
            // Emit a tool_call start so the UI registers the tool call entry.
            emitToolCallStart(block, onUpdate);
        }
    }

    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_SUBTYPE = "subtype";
    private static final String FIELD_REQUEST_ID = "requestId";
    private static final String BLOCK_TYPE_TEXT = "text";
    private static final String BLOCK_TYPE_THINKING = "thinking";
    private static final String FIELD_TOTAL_COST_USD = "total_cost_usd";
    private static final String FIELD_USAGE = "usage";
    private static final String FIELD_INPUT_TOKENS = "input_tokens";
    private static final String FIELD_OUTPUT_TOKENS = "output_tokens";

    /**
     * Extracts a human-readable string from a Claude CLI error element (string or object).
     */
    @NotNull
    private static String extractErrorText(@NotNull JsonElement el) {
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has(FIELD_MESSAGE)) return obj.get(FIELD_MESSAGE).getAsString();
        }
        return el.toString();
    }

    private void emitToolCallStart(@NotNull JsonObject event, @Nullable Consumer<SessionUpdate> onUpdate) {
        LOG.info("[tool_use event] raw: " + event);
        String id = event.has("id") ? event.get("id").getAsString() : UUID.randomUUID().toString();
        String name = event.has("name") ? event.get("name").getAsString() : "tool";
        JsonObject input = event.has(FIELD_INPUT) ? event.getAsJsonObject(FIELD_INPUT) : new JsonObject();
        LOG.info("[tool_use event] extracted: id=" + id + ", name=" + name + ", input=" + input);
        emitToolCallStart(id, name, input, onUpdate);
    }

    private void emitToolCallEnd(@NotNull JsonObject event, @Nullable Consumer<SessionUpdate> onUpdate) {
        LOG.info("[tool_result event] raw: " + event);
        String toolUseId = event.has("tool_use_id") ? event.get("tool_use_id").getAsString() : "";
        boolean isError = event.has("is_error") && event.get("is_error").getAsBoolean();
        String content = extractToolResultContent(event);
        LOG.info("[tool_result event] extracted: toolUseId=" + toolUseId + ", isError=" + isError + ", contentLen=" + content.length());
        emitToolCallEnd(toolUseId, content, !isError, onUpdate);
    }

    /**
     * Reads {@code total_input_tokens}, {@code total_output_tokens}, and {@code cost_usd}
     * from a {@code result} event and emits a {@link SessionUpdate.TurnUsage} update so the UI
     * layer can display per-prompt and session-aggregate token counts and dollar cost.
     */
    private void emitUsageStats(@NotNull JsonObject resultEvent, @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null) return;
        // Tokens are nested under "usage"; cost is top-level "total_cost_usd"
        if (!resultEvent.has(FIELD_USAGE) && !resultEvent.has(FIELD_TOTAL_COST_USD)) return;
        JsonObject usage = resultEvent.has(FIELD_USAGE) && resultEvent.get(FIELD_USAGE).isJsonObject()
            ? resultEvent.getAsJsonObject(FIELD_USAGE) : null;
        int inputTokens = usage != null ? safeGetInt(usage, FIELD_INPUT_TOKENS) : 0;
        int outputTokens = usage != null ? safeGetInt(usage, FIELD_OUTPUT_TOKENS) : 0;
        double costUsd = safeGetDouble(resultEvent, FIELD_TOTAL_COST_USD);
        if (inputTokens == 0 && outputTokens == 0 && costUsd == 0.0) return;
        onUpdate.accept(new SessionUpdate.TurnUsage(inputTokens, outputTokens, costUsd));
    }

    private static int safeGetInt(@NotNull JsonObject obj, @NotNull String field) {
        if (!obj.has(field)) return 0;
        JsonElement el = obj.get(field);
        return el.isJsonNull() ? 0 : el.getAsInt();
    }

    private static double safeGetDouble(@NotNull JsonObject obj, @NotNull String field) {
        if (!obj.has(field)) return 0.0;
        JsonElement el = obj.get(field);
        return el.isJsonNull() ? 0.0 : el.getAsDouble();
    }

    /**
     * Extracts tool result content as a plain string.
     * The CLI may emit {@code content} as either a plain string or an array of content blocks
     * (e.g. {@code [{"type":"text","text":"..."}]}). Both forms are handled.
     */
    @NotNull
    private String extractToolResultContent(@NotNull JsonObject event) {
        if (!event.has(FIELD_CONTENT)) return "";
        JsonElement el = event.get(FIELD_CONTENT);
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    JsonObject obj = item.getAsJsonObject();
                    if (obj.has(BLOCK_TYPE_TEXT)) sb.append(obj.get(BLOCK_TYPE_TEXT).getAsString());
                } else if (item.isJsonPrimitive()) {
                    sb.append(item.getAsString());
                }
            }
            return sb.toString();
        }
        return el.toString();
    }

    // ── Bidirectional control protocol ───────────────────────────────────────

    /**
     * Responds to a {@code control_request} from the CLI by writing a {@code control_response}
     * JSON message to stdin. All {@code can_use_tool} requests are auto-approved; only trusted
     * MCP tools are exposed when {@code excludeAgentBuiltInTools} is enabled.
     */
    private static void respondToControlRequest(@NotNull JsonObject event, @NotNull OutputStream stdin) {
        String subtype = event.has(FIELD_SUBTYPE) ? event.get(FIELD_SUBTYPE).getAsString() : "";
        String requestId = event.has(FIELD_REQUEST_ID) ? event.get(FIELD_REQUEST_ID).getAsString() : "";

        JsonObject response = new JsonObject();
        response.addProperty("type", "control_response");
        response.addProperty(FIELD_SUBTYPE, subtype);
        response.addProperty(FIELD_REQUEST_ID, requestId);

        if ("can_use_tool".equals(subtype)) {
            JsonObject decision = new JsonObject();
            decision.addProperty("decision", "allow");
            response.add("response", decision);
        }

        try {
            stdin.write(response.toString().getBytes(StandardCharsets.UTF_8));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            LOG.debug("Could not write control_response: " + e.getMessage());
        }
    }

    /**
     * Builds the JSON user-message envelope required by {@code --input-format stream-json}.
     * Format: {@code {"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}}
     */
    @NotNull
    private static String buildJsonUserMessage(@NotNull String prompt) {
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", prompt);
        JsonArray content = new JsonArray();
        content.add(textBlock);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonObject userEvent = new JsonObject();
        userEvent.addProperty("type", "user");
        userEvent.add(FIELD_MESSAGE, message);
        return userEvent.toString();
    }

    private static void closeQuietly(@NotNull OutputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            LOG.debug("Could not close stdin stream: " + e.getMessage());
        }
    }

    @NotNull
    private static Thread startStderrDrainer(@NotNull Process proc, @NotNull StringBuilder stderrBuf) {
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    stderrBuf.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // Process may have exited; stderr is no longer readable
            }
        }, "claude-stderr-reader");
        stderrThread.setDaemon(true);
        stderrThread.start();
        return stderrThread;
    }

    private static void writeJsonPromptToStdin(@NotNull OutputStream stdin, @NotNull String prompt)
        throws AgentException {
        try {
            stdin.write(buildJsonUserMessage(prompt).getBytes(StandardCharsets.UTF_8));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            closeQuietly(stdin);
            throw new AgentException("Failed to write prompt to claude process: " + e.getMessage(), e, true);
        }
    }

    // ── MCP injection ────────────────────────────────────────────────────────

    @Nullable
    private Path writeMcpConfigIfNeeded() throws AgentException {
        if (mcpPort <= 0) return null;
        try {
            String json = "{\"mcpServers\":{\"agentbridge\":{"
                + "\"type\":\"http\","
                + "\"url\":\"http://localhost:" + mcpPort + "/mcp\"}}}";
            Path tmp = Files.createTempFile("ide-agent-mcp-", ".json");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            return tmp;
        } catch (IOException e) {
            throw new AgentException("Could not write MCP config: " + e.getMessage(), e, true);
        }
    }

    // ── Prompt building ──────────────────────────────────────────────────────

    @NotNull
    private String buildFullPrompt(@NotNull String prompt, boolean isNewSession) {
        StringBuilder sb = new StringBuilder();

        if (isNewSession) {
            String instructions = config.getSessionInstructions();
            if (instructions != null && !instructions.isEmpty()) {
                sb.append("<system-reminder>\n");
                sb.append(instructions);
                sb.append("\n</system-reminder>\n\n");
                LOG.info("Injected startup instructions into first message (" + instructions.length() + " chars)");
            }
        }

        sb.append(prompt);
        return sb.toString();
    }

    // ── Binary resolution ────────────────────────────────────────────────────

    @NotNull
    private String resolveBinary() throws AgentException {
        String custom = profile.getCustomBinaryPath();
        if (!custom.isEmpty()) {
            if (Files.isExecutable(Path.of(custom))) return custom;
            throw new AgentException("Claude binary not found at: " + custom, null, false);
        }
        for (String name : candidateNames()) {
            String found = findOnPath(name);
            if (found != null) return found;
        }
        throw new AgentException(
            "Claude CLI not found. Install it from code.claude.com and run 'claude auth login'.",
            null, false);
    }

    @NotNull
    private List<String> candidateNames() {
        List<String> names = new ArrayList<>();
        String primary = profile.getBinaryName();
        if (!primary.isEmpty()) names.add(primary);
        names.addAll(profile.getAlternateNames());
        if (!names.contains("claude")) names.add("claude");
        return names;
    }

    @Nullable
    private static String findOnPath(@NotNull String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

}
