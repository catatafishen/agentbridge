package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
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
 * {@code --print --output-format stream-json} mode.
 *
 * <p>Authentication is handled entirely by the {@code claude} subprocess using the
 * OAuth credentials stored by {@code claude auth login} — no Anthropic API key is
 * required. Multi-turn conversations are maintained via {@code --resume <session-id>},
 * where the session ID is extracted from the CLI's {@code stream-json} output.</p>
 *
 * <p>If an MCP port is provided ({@code > 0}), a temporary MCP config file is written
 * and passed via {@code --mcp-config} so the CLI can call IDE tools from the plugin's
 * MCP server.</p>
 */
public final class ClaudeCliClient extends AbstractClaudeAgentClient {

    private static final Logger LOG = Logger.getInstance(ClaudeCliClient.class);

    private static final String FIELD_SESSION_ID = "session_id";
    private static final String SUBTYPE_ERROR = "error";
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

    // ── Model listing ────────────────────────────────────────────────────────

    @Override
    public @NotNull List<Model> listModels() throws AcpException {
        ensureStarted();
        List<Model> dynamic = fetchModelsFromCli();
        if (!dynamic.isEmpty()) return dynamic;
        return builtInModels();
    }

    @NotNull
    private List<Model> fetchModelsFromCli() {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(resolvedBinaryPath);
            cmd.add("models");
            cmd.add("--output-format");
            cmd.add("json");

            // If profile is configured in acpArgs, pass it to the models command
            String profileName = extractProfileName();
            if (profileName != null) {
                cmd.add(PROFILE_FLAG);
                cmd.add(profileName);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor();
            if (!output.startsWith("[")) return List.of();
            List<Model> models = new ArrayList<>();
            for (JsonElement el : JsonParser.parseString(output).getAsJsonArray()) {
                JsonObject m = el.getAsJsonObject();
                String id = m.has("id") ? m.get("id").getAsString() : null;
                if (id == null || id.isEmpty()) continue;
                String name = m.has("name") ? m.get("name").getAsString() : id;
                Model model = new Model();
                model.setId(id);
                model.setName(name);
                models.add(model);
            }
            return models;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("Could not fetch models from Claude CLI: " + e.getMessage());
            return List.of();
        }
    }

    @NotNull
    private static List<Model> builtInModels() {
        return List.of(
            makeModel(DEFAULT_MODEL, "Claude Opus 4.5"),
            makeModel("claude-sonnet-4-5-20251101", "Claude Sonnet 4.5"),
            makeModel("claude-haiku-4-5-20251101", "Claude Haiku 4.5")
        );
    }

    @NotNull
    private static Model makeModel(@NotNull String id, @NotNull String name) {
        Model m = new Model();
        m.setId(id);
        m.setName(name);
        return m;
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

    @NotNull
    private List<String> buildCommand(@NotNull String sessionId, @NotNull String model) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedBinaryPath);
        cmd.add("--print");
        cmd.add("--output-format");
        cmd.add("stream-json");

        // If profile is configured, pass it to the claude CLI
        String profileName = extractProfileName();
        if (profileName != null) {
            cmd.add(PROFILE_FLAG);
            cmd.add(profileName);
        }

        cmd.add("--model");
        cmd.add(model);

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

            // Write prompt to stdin and close it so the CLI knows input is complete
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            String stopReason = parseStreamOutput(sessionId, proc, onChunk, onUpdate, cancelled);
            proc.waitFor();
            activeProcesses.remove(sessionId);
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
                                     @Nullable Consumer<String> onChunk,
                                     @Nullable Consumer<JsonObject> onUpdate,
                                     @NotNull AtomicBoolean cancelled) throws IOException {
        String stopReason = "end_turn";
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    stopReason = handleStreamEvent(sessionId, event, stopReason, onChunk, onUpdate);
                } catch (RuntimeException e) {
                    LOG.debug("Could not parse stream-json line: " + line, e);
                }
            }
        }
        if (cancelled.get()) stopReason = "cancelled";
        return stopReason;
    }

    @NotNull
    private String handleStreamEvent(@NotNull String sessionId,
                                     @NotNull JsonObject event,
                                     @NotNull String currentStopReason,
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
                streamAssistantText(event, onChunk);
                yield currentStopReason;
            }
            case "tool_use" -> {
                emitToolCallStart(event, onUpdate);
                yield currentStopReason;
            }
            case "tool_result" -> {
                emitToolCallEnd(event, onUpdate);
                yield currentStopReason;
            }
            case "result" -> {
                // Always capture session_id from the result event (most reliable location)
                if (event.has(FIELD_SESSION_ID)) {
                    cliSessionIds.put(sessionId, event.get(FIELD_SESSION_ID).getAsString());
                }
                boolean isError = event.has("subtype")
                    && SUBTYPE_ERROR.equals(event.get("subtype").getAsString());
                if (isError && onChunk != null && event.has(SUBTYPE_ERROR)) {
                    onChunk.accept("\n[Error: " + event.get(SUBTYPE_ERROR).getAsString() + "]");
                }
                yield isError ? SUBTYPE_ERROR : "end_turn";
            }
            default -> currentStopReason;
        };
    }

    private void streamAssistantText(@NotNull JsonObject event, @Nullable Consumer<String> onChunk) {
        if (onChunk == null || !event.has("message")) return;
        JsonObject message = event.getAsJsonObject("message");
        if (!message.has(FIELD_CONTENT)) return;
        for (JsonElement block : message.getAsJsonArray(FIELD_CONTENT)) {
            if (!block.isJsonObject()) continue;
            JsonObject b = block.getAsJsonObject();
            String blockType = b.has(FIELD_TYPE) ? b.get(FIELD_TYPE).getAsString() : "";
            if ("text".equals(blockType) && b.has("text")) {
                String text = b.get("text").getAsString();
                if (!text.isEmpty()) onChunk.accept(text);
            }
        }
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
        String content = event.has(FIELD_CONTENT) ? event.get(FIELD_CONTENT).getAsString() : "";
        emitToolCallEnd(toolUseId, content, !isError, onUpdate);
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
