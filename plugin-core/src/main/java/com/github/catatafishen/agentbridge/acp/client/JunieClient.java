package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AgentSessionException;
import com.github.catatafishen.agentbridge.agent.junie.JunieKeyStore;
import com.github.catatafishen.agentbridge.settings.StartupInstructionsSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * JetBrains Junie ACP client.
 * <p>
 * Tool prefix: {@code Tool: agentbridge/read_file} → strip {@code Tool: agentbridge/}
 * MCP: injected via session/new mcpServers array
 * Model display: token count
 * Correlation: ToolChipRegistry handles chip correlation via args hash
 *
 * <h2>Chip correlation for Junie</h2>
 * Junie's {@code tool_call} update carries empty {@code content:[]} — the real arguments
 * are only in the simultaneously-sent {@code session/request_permission} content text.
 * {@link #onPermissionRequest} captures them, and {@link #parseToolCallArguments} exposes
 * them so the base-class chip-registry correlation can work.
 */
public final class JunieClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(JunieClient.class);

    /**
     * Set when we detect that Junie's process has a poisoned permission-response queue.
     * The process must be restarted before the next session to clear the error queue.
     */
    private volatile boolean restartBeforeNextSession = false;

    private static final String KEY_CONTENT = "content";
    private static final String KEY_RAW_INPUT = "rawInput";
    private static final String PREFIX_AGENTBRIDGE_UNDERSCORE = "agentbridge_";
    private static final String PREFIX_TOOL_AGENTBRIDGE_SLASH = "Tool: agentbridge/";
    private static final String KEY_DEFAULT_BEHAVIOR = "defaultBehavior";

    /**
     * Args extracted from {@code session/request_permission} content, keyed by toolCallId.
     * Junie's {@code tool_call} event has empty content, so we cache args here and look them
     * up when the base class calls {@link #parseToolCallArguments}.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, com.google.gson.JsonObject> permissionArgs =
        new java.util.concurrent.ConcurrentHashMap<>();

    public JunieClient(Project project) {
        super(project);
    }

    @Override
    public String displayName() {
        return "Junie";
    }

    @Override
    public String agentId() {
        return "junie";
    }

    @Override
    protected boolean excludeBuiltInTools() {
        return true;
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return resolveToolIdStatic(protocolTitle);
    }

    /**
     * Maps a Junie protocol title to the underlying MCP tool name.
     * Handles: {@code agentbridge_} prefix, {@code Tool: agentbridge/} prefix,
     * natural language titles (e.g. "Open file.txt" → "read_file"), and
     * case-insensitive exact matches (e.g. "build project" → "build_project").
     */
    static String resolveToolIdStatic(String protocolTitle) {
        String title = protocolTitle.trim();
        if (title.startsWith(PREFIX_AGENTBRIDGE_UNDERSCORE)) {
            return title.substring(PREFIX_AGENTBRIDGE_UNDERSCORE.length());
        }
        if (title.startsWith(PREFIX_TOOL_AGENTBRIDGE_SLASH)) {
            return title.substring(PREFIX_TOOL_AGENTBRIDGE_SLASH.length());
        }

        String lower = title.toLowerCase();
        if (lower.startsWith("open ")) return "read_file";
        if (lower.startsWith("searched ") || lower.startsWith("found ")) return "search_text";
        if (lower.startsWith("edit ")) return "edit_text";
        if (lower.startsWith("bash ") || lower.startsWith("run ")) return "run_command";
        if (lower.startsWith("build ")) return "build_project";

        return switch (lower) {
            case "build project" -> "build_project";
            case "read file" -> "read_file";
            case "edit text" -> "edit_text";
            case "search text" -> "search_text";
            case "run command" -> "run_command";
            default -> title.replaceFirst("^Tool: ", "");
        };
    }

    @Override
    protected boolean isMcpToolTitle(@NotNull String protocolTitle) {
        return isMcpToolTitleStatic(protocolTitle);
    }

    /**
     * Checks whether a Junie protocol title refers to an agentbridge MCP tool.
     */
    static boolean isMcpToolTitleStatic(String protocolTitle) {
        return protocolTitle.startsWith(PREFIX_AGENTBRIDGE_UNDERSCORE)
            || protocolTitle.startsWith(PREFIX_TOOL_AGENTBRIDGE_SLASH);
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        List<String> cmd = new ArrayList<>();
        cmd.add("junie");
        cmd.add("--acp=true");

        // If user has configured an auth token in plugin settings, use it instead of relying on CLI credentials
        String authToken = JunieKeyStore.getAuthToken();
        if (authToken != null && !authToken.isEmpty()) {
            cmd.add("--auth=" + authToken);
            LOG.info("Junie: using plugin-configured auth token");
        } else {
            LOG.info("Junie: using CLI credentials (no token configured in plugin settings)");
        }

        return cmd;
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws java.io.IOException {
        java.nio.file.Path junieDir = java.nio.file.Path.of(cwd, ".junie");
        java.nio.file.Files.createDirectories(junieDir);
        java.nio.file.Path allowlistPath = junieDir.resolve("allowlist.json");

        JsonObject allowlist = new JsonObject();
        allowlist.addProperty(KEY_DEFAULT_BEHAVIOR, "deny");
        allowlist.addProperty("allowReadonlyCommands", false);

        JsonObject rules = new JsonObject();

        JsonObject mcpTools = new JsonObject();
        JsonArray mcpRules = new JsonArray();
        JsonObject agentbridgeRule = new JsonObject();
        agentbridgeRule.addProperty("prefix", "agentbridge");
        agentbridgeRule.addProperty("action", "allow");
        mcpRules.add(agentbridgeRule);
        mcpTools.add("rules", mcpRules);

        rules.add("mcpTools", mcpTools);

        JsonObject terminal = new JsonObject();
        terminal.addProperty(KEY_DEFAULT_BEHAVIOR, "deny");
        rules.add("terminal", terminal);

        JsonObject fileEditing = new JsonObject();
        fileEditing.addProperty(KEY_DEFAULT_BEHAVIOR, "deny");
        rules.add("fileEditing", fileEditing);

        allowlist.add("rules", rules);

        try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(allowlistPath)) {
            gson.toJson(allowlist, writer);
            LOG.info("Junie: wrote .junie/allowlist.json to restrict built-in tools");
        }
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Junie injects MCP via session/new mcpServers array using stdio (command + args)
        JsonObject server = buildMcpStdioServer("agentbridge", mcpPort);
        if (server == null) {
            throw new IllegalStateException(
                "Cannot configure Junie MCP server — " + describeMcpStdioServerFailure());
        }
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);

        // Junie resumes sessions via resumeSessionId in session/new (not via session/load RPC).
        // Pass it here so Junie restores conversation history natively when available.
        if (requestedResumeId != null) {
            params.addProperty("resumeSessionId", requestedResumeId);
        }
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws Exception {
        // Junie restores sessions via resumeSessionId in session/new (not via session/load RPC).
        // Throw here so createSession falls through to session/new where we add resumeSessionId.
        throw new AgentSessionException(
            "Junie uses resumeSessionId in session/new — skipping session/load");
    }

    @Override
    protected boolean supportsSessionResumption() {
        // Junie resumes via resumeSessionId in session/new (see customizeNewSession + loadSession).
        // Return false so the "session resume not available" notification is suppressed — resume
        // is handled transparently by session/new rather than via a separate session/load RPC.
        return false;
    }

    @Override
    protected void onSessionCreated(String sessionId) {
        String userInstructions = StartupInstructionsSettings.getInstance().getInstructions();
        sendSessionMessage(sessionId, buildInstructions(userInstructions));
    }

    /**
     * Junie's {@code tool_call} event has empty {@code content:[]} — actual tool arguments
     * are only in the {@code session/request_permission} content. This hook is called before
     * we respond to the permission, so we can extract and cache the args for chip correlation.
     */
    @Override
    protected void onPermissionRequest(@NotNull String toolCallId, @NotNull JsonObject toolCallParams) {
        if (!toolCallParams.has(KEY_CONTENT)) return;
        JsonElement contentEl = toolCallParams.get(KEY_CONTENT);
        if (!contentEl.isJsonArray()) return;
        for (JsonElement item : contentEl.getAsJsonArray()) {
            JsonObject parsed = tryParseArgsFromContentItem(item);
            if (parsed != null) {
                permissionArgs.put(toolCallId, parsed);
                return;
            }
        }
    }

    /**
     * Junie sends args only in the permission request, not in {@code tool_call}.
     * If the standard field is absent, look up args cached by {@link #onPermissionRequest}.
     */
    @Override
    protected JsonObject parseToolCallArguments(@NotNull JsonObject params) {
        JsonObject standard = super.parseToolCallArguments(params);
        if (standard != null) return standard;

        // Try rawInput (Junie sometimes puts arguments there for built-in tools)
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            return params.getAsJsonObject(KEY_RAW_INPUT);
        }

        if (params.has("toolCallId")) {
            return permissionArgs.remove(params.get("toolCallId").getAsString());
        }
        return null;
    }

    @Override
    protected JsonObject buildPermissionOutcome(String optionId, @Nullable JsonObject chosenOption) {
        return buildPermissionOutcomeStatic(optionId, chosenOption);
    }

    /**
     * Builds the permission outcome JSON for Junie's kotlinx.serialization format.
     * The discriminator value ({@code kind}) must match the option's "kind" field.
     */
    static JsonObject buildPermissionOutcomeStatic(String optionId, @Nullable JsonObject chosenOption) {
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        String kind = chosenOption != null && chosenOption.has("kind")
            ? chosenOption.get("kind").getAsString() : optionId;
        outcome.addProperty("kind", kind);
        outcome.addProperty("optionId", optionId);
        return outcome;
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        // No extra processing needed — ToolChipRegistry handles correlation via args hash
        return update;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.TOKEN_COUNT;
    }

    /**
     * Junie's {@code RequestPermissionOutcome} deserializer is broken: it uses kotlinx.serialization
     * polymorphic but has no registered subtypes, so ANY permission response we send causes a
     * {@code -32700} error on the subsequent {@code session/prompt} result. The streaming updates
     * already rendered the response in the UI. We recover gracefully here and schedule a process
     * restart so the poisoned error queue is cleared before the next session.
     */
    @Override
    protected @Nullable PromptResponse tryRecoverPromptException(Exception cause) {
        if (isJuniePermissionBug(cause)) {
            LOG.warn("Junie: recovering from known permission-response deserialization bug; process will restart before next session");
            restartBeforeNextSession = true;
            return new PromptResponse("end_turn", null);
        }
        return null;
    }

    /**
     * Restarts the Junie process before creating a new session if the previous session poisoned
     * the error queue via a broken permission response.
     */
    @Override
    protected void beforeCreateSession(String cwd) throws Exception {
        if (!restartBeforeNextSession) return;
        restartBeforeNextSession = false;
        LOG.info("Junie: restarting process to clear poisoned permission-response queue");
        stop();
        start();
    }

    /**
     * Tries to extract and parse tool call arguments from a single Junie content block.
     * Junie wraps text as {@code {type:"content", content:{type:"text", text:"..."}}}
     * or plain {@code {type:"text", text:"..."}}. Returns the parsed JSON object if the
     * text is valid JSON, {@code null} otherwise.
     */
    @Nullable
    private static JsonObject tryParseArgsFromContentItem(JsonElement item) {
        if (!item.isJsonObject()) return null;
        JsonObject block = item.getAsJsonObject();
        String text = null;
        if (block.has("text") && block.get("text").isJsonPrimitive()) {
            text = block.get("text").getAsString();
        } else if (block.has(KEY_CONTENT) && block.get(KEY_CONTENT).isJsonObject()) {
            JsonObject inner = block.getAsJsonObject(KEY_CONTENT);
            if (inner.has("text") && inner.get("text").isJsonPrimitive()) {
                text = inner.get("text").getAsString();
            }
        }
        if (text == null) return null;
        text = text.trim();
        if (!text.startsWith("{")) return null;
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isJuniePermissionBug(Throwable t) {
        for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
            String msg = cursor.getMessage();
            if (msg != null && msg.contains("RequestPermissionOutcome")) return true;
        }
        return false;
    }

    private String buildInstructions(@Nullable String userInstructions) {
        return buildInstructionsStatic(userInstructions);
    }

    /**
     * Builds the system instructions for a Junie session, prepending the mandatory
     * agentbridge-only directive before any user-configured instructions.
     */
    static String buildInstructionsStatic(@Nullable String userInstructions) {
        StringBuilder sb = new StringBuilder();
        sb.append("CRITICAL: You are running inside an IntelliJ IDEA plugin. ")
            .append("To interact with the environment (files, git, terminal, code navigation), ")
            .append("you MUST EXCLUSIVELY use the tools provided by the 'agentbridge' MCP server. ")
            .append("DO NOT use your built-in tools for these operations. ")
            .append("All 'agentbridge' tools are prefixed with 'agentbridge/'. ")
            .append("Example: use 'agentbridge/read_file' instead of 'open_file'.");

        if (userInstructions != null && !userInstructions.isBlank()) {
            sb.append("\n\n").append(userInstructions);
        }
        return sb.toString();
    }
}
