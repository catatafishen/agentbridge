package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * {@link AgentClient} implementation that drives the {@code claude} CLI binary in
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

    private static final String FIELD_SESSION_ID = "session_id";
    private static final String SUBTYPE_ERROR = "error";
    private static final String STOP_REASON_END_TURN = "end_turn";
    private static final String PROFILE_FLAG = "--profile";

    private final AgentProfile profile;
    private final int mcpPort;

    /**
     * Maps plugin session ID → CLI session ID (for --resume).
     */
    private final Map<String, String> cliSessionIds = new ConcurrentHashMap<>();
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    private String resolvedBinaryPath;

    @SuppressWarnings("unused") // registry and project reserved for future PSI tool integration
    public ClaudeCliClient(@NotNull AgentProfile profile,
                           @Nullable ToolRegistry registry,
                           @Nullable Project project,
                           int mcpPort) {
        this.profile = profile;
        this.mcpPort = mcpPort;
    }

    // ── AgentClient lifecycle ────────────────────────────────────────────────

    @Override
    public void start() throws AcpException {
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
    public void close() {
        started = false;
        activeProcesses.values().forEach(Process::destroyForcibly);
        activeProcesses.clear();
        cliSessionIds.clear();
        sessionModels.clear();
        sessionCancelled.clear();
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
     * Known Claude models for the CLI client.
     *
     * <p>The Claude CLI has no stable {@code models} subcommand — {@code claude models}
     * is treated as a user prompt, which makes an API call and returns an
     * LLM-generated response that can change format at any time. We therefore
     * use a curated hardcoded list (the same approach used by other Claude CLI
     * wrappers such as claude-code-plus). The list should be updated when
     * Anthropic releases new model families.</p>
     */
    private static final List<Model> KNOWN_MODELS = buildKnownModels();

    private static List<Model> buildKnownModels() {
        Object[][] rows = {
            // { id, displayName }
            {"claude-opus-4-6", "Claude Opus 4.6"},
            {"claude-sonnet-4-6", "Claude Sonnet 4.6"},
            {"claude-haiku-4-5-20251001", "Claude Haiku 4.5"},
        };
        List<Model> list = new ArrayList<>(rows.length);
        for (Object[] row : rows) {
            Model m = new Model();
            m.setId((String) row[0]);
            m.setName((String) row[1]);
            list.add(m);
        }
        return java.util.Collections.unmodifiableList(list);
    }

    @Override
    public @NotNull List<Model> listModels() throws AcpException {
        ensureStarted();
        List<String> custom = profile.getCustomCliModels();
        if (!custom.isEmpty()) {
            return parseCustomModels(custom);
        }
        return KNOWN_MODELS;
    }

    /**
     * Parses {@code "model-id=Display Name"} entries; malformed lines are skipped.
     */
    @NotNull
    private static List<Model> parseCustomModels(@NotNull List<String> entries) {
        List<Model> result = new ArrayList<>(entries.size());
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String id = entry.substring(0, eq).trim();
            String name = entry.substring(eq + 1).trim();
            if (id.isEmpty()) continue;
            Model m = new Model();
            m.setId(id);
            m.setName(name.isEmpty() ? id : name);
            result.add(m);
        }
        return result.isEmpty() ? KNOWN_MODELS : result;
    }

    // ── Prompt execution ─────────────────────────────────────────────────────

    @Override
    public @NotNull String sendPrompt(@NotNull String sessionId,
                                      @NotNull String prompt,
                                      @Nullable String model,
                                      @Nullable List<ResourceReference> references,
                                      @Nullable Consumer<String> onChunk,
                                      @Nullable Consumer<JsonObject> onUpdate,
                                      @Nullable Runnable onRequest) throws AcpException {
        ensureStarted();
        AtomicBoolean cancelled = sessionCancelled.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        cancelled.set(false);
        if (onRequest != null) onRequest.run();

        String resolvedModel = resolveModel(sessionId, model);
        String fullPrompt = buildFullPrompt(prompt, references);
        List<String> cmd = buildCommand(sessionId, resolvedModel);

        Path mcpConfig = null;
        try {
            mcpConfig = writeMcpConfigIfNeeded();
            if (mcpConfig != null) {
                cmd.add("--mcp-config");
                cmd.add(mcpConfig.toString());
            }
            return runSubprocess(sessionId, cmd, fullPrompt, onChunk, onUpdate, cancelled);
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
     * All Claude Code CLI built-in tool names. Used with {@code --disallowed-tools} when
     * {@link AgentProfile#isExcludeAgentBuiltInTools()} is set, so only MCP tools remain active.
     */
    private static final List<String> CLAUDE_BUILT_IN_TOOLS = List.of(
        "Bash", "BashOutput", "KillShell",
        "Edit", "MultiEdit", "Read", "Write",
        "Glob", "Grep",
        "WebFetch", "WebSearch",
        "TodoWrite", "Task",
        "ExitPlanMode", "EnterPlanMode",
        "AskUserQuestion", "NotebookEdit"
    );

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

        // Disable all Claude built-in tools when the profile requests it, leaving only MCP tools.
        if (profile.isExcludeAgentBuiltInTools()) {
            for (String tool : CLAUDE_BUILT_IN_TOOLS) {
                cmd.add("--disallowed-tools");
                cmd.add(tool);
            }
        }

        String effort = getSessionOption(sessionId, "effort");
        if (effort != null && !effort.isEmpty()) {
            cmd.add("--effort");
            cmd.add(effort);
        }

        String cliSessionId = cliSessionIds.get(sessionId);
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

    @NotNull
    private String runSubprocess(@NotNull String sessionId,
                                 @NotNull List<String> cmd,
                                 @NotNull String prompt,
                                 @Nullable Consumer<String> onChunk,
                                 @Nullable Consumer<JsonObject> onUpdate,
                                 @NotNull AtomicBoolean cancelled) throws AcpException {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
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
            throw new AcpException("Failed to start claude process: " + e.getMessage(), e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcpException("Interrupted waiting for claude process", e, false);
        }
    }

    // ── stream-json parsing ──────────────────────────────────────────────────

    @NotNull
    private String parseStreamOutput(@NotNull String sessionId,
                                     @NotNull Process proc,
                                     @NotNull OutputStream stdin,
                                     @Nullable Consumer<String> onChunk,
                                     @Nullable Consumer<JsonObject> onUpdate,
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
                                     @Nullable Consumer<JsonObject> onUpdate) {
        String type = event.has(FIELD_TYPE) ? event.get(FIELD_TYPE).getAsString() : "";
        return switch (type) {
            case "system" -> {
                if (event.has(FIELD_SESSION_ID)) {
                    cliSessionIds.put(sessionId, event.get(FIELD_SESSION_ID).getAsString());
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
                    cliSessionIds.put(sessionId, event.get(FIELD_SESSION_ID).getAsString());
                }
                boolean isError = event.has(FIELD_SUBTYPE)
                    && SUBTYPE_ERROR.equals(event.get(FIELD_SUBTYPE).getAsString());
                if (isError && onChunk != null && event.has(SUBTYPE_ERROR)) {
                    onChunk.accept("\n[Error: " + extractErrorText(event.get(SUBTYPE_ERROR)) + "]");
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
                                        @Nullable Consumer<JsonObject> onUpdate) {
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
                                    @Nullable Consumer<JsonObject> onUpdate) {
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
    private static final String FIELD_TOTAL_INPUT_TOKENS = "total_input_tokens";
    private static final String FIELD_TOTAL_OUTPUT_TOKENS = "total_output_tokens";
    private static final String FIELD_COST_USD = "cost_usd";

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

    private void emitToolCallStart(@NotNull JsonObject event, @Nullable Consumer<JsonObject> onUpdate) {
        String id = event.has("id") ? event.get("id").getAsString() : UUID.randomUUID().toString();
        String name = event.has("name") ? event.get("name").getAsString() : "tool";
        JsonObject input = event.has(FIELD_INPUT) ? event.getAsJsonObject(FIELD_INPUT) : new JsonObject();
        emitToolCallStart(id, name, input, onUpdate);
    }

    private void emitToolCallEnd(@NotNull JsonObject event, @Nullable Consumer<JsonObject> onUpdate) {
        String toolUseId = event.has("tool_use_id") ? event.get("tool_use_id").getAsString() : "";
        boolean isError = event.has("is_error") && event.get("is_error").getAsBoolean();
        String content = extractToolResultContent(event);
        emitToolCallEnd(toolUseId, content, !isError, onUpdate);
    }

    /**
     * Reads {@code total_input_tokens}, {@code total_output_tokens}, and {@code cost_usd}
     * from a {@code result} event and emits a {@code claude_usage} update so the UI layer
     * can display per-prompt and session-aggregate token counts and dollar cost.
     */
    private void emitUsageStats(@NotNull JsonObject resultEvent, @Nullable Consumer<JsonObject> onUpdate) {
        if (onUpdate == null) return;
        if (!resultEvent.has(FIELD_TOTAL_INPUT_TOKENS) && !resultEvent.has(FIELD_COST_USD)) return;

        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", SESSION_UPDATE_TURN_USAGE);
        update.addProperty("inputTokens",
            resultEvent.has(FIELD_TOTAL_INPUT_TOKENS) ? resultEvent.get(FIELD_TOTAL_INPUT_TOKENS).getAsInt() : 0);
        update.addProperty("outputTokens",
            resultEvent.has(FIELD_TOTAL_OUTPUT_TOKENS) ? resultEvent.get(FIELD_TOTAL_OUTPUT_TOKENS).getAsInt() : 0);
        update.addProperty("costUsd",
            resultEvent.has(FIELD_COST_USD) ? resultEvent.get(FIELD_COST_USD).getAsDouble() : 0.0);
        onUpdate.accept(update);
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
        throws AcpException {
        try {
            stdin.write(buildJsonUserMessage(prompt).getBytes(StandardCharsets.UTF_8));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            closeQuietly(stdin);
            throw new AcpException("Failed to write prompt to claude process: " + e.getMessage(), e, true);
        }
    }

    // ── MCP injection ────────────────────────────────────────────────────────

    @Nullable
    private Path writeMcpConfigIfNeeded() throws AcpException {
        if (mcpPort <= 0) return null;
        try {
            String json = "{\"mcpServers\":{\"intellij-code-tools\":{"
                + "\"type\":\"http\","
                + "\"url\":\"http://localhost:" + mcpPort + "/mcp\"}}}";
            Path tmp = Files.createTempFile("ide-agent-mcp-", ".json");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            return tmp;
        } catch (IOException e) {
            throw new AcpException("Could not write MCP config: " + e.getMessage(), e, true);
        }
    }

    // ── Prompt building ──────────────────────────────────────────────────────

    @NotNull
    private static String buildFullPrompt(@NotNull String prompt,
                                          @Nullable List<ResourceReference> references) {
        if (references == null || references.isEmpty()) return prompt;
        StringBuilder sb = new StringBuilder();
        for (ResourceReference ref : references) {
            String text = ref.text();
            if (!text.isEmpty()) {
                sb.append("File: ").append(ref.uri()).append("\n```\n").append(text).append("\n```\n\n");
            }
        }
        sb.append(prompt);
        return sb.toString();
    }

    // ── Binary resolution ────────────────────────────────────────────────────

    @NotNull
    private String resolveBinary() throws AcpException {
        String custom = profile.getCustomBinaryPath();
        if (!custom.isEmpty()) {
            if (Files.isExecutable(Path.of(custom))) return custom;
            throw new AcpException("Claude binary not found at: " + custom, null, false);
        }
        for (String name : candidateNames()) {
            String found = findOnPath(name);
            if (found != null) return found;
        }
        throw new AcpException(
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
