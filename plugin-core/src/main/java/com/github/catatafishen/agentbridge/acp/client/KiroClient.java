package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class KiroClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(KiroClient.class);
    private static final String KEY_RAW_INPUT = "rawInput";
    private static final String KEY_AGENTBRIDGE = "@agentbridge/";
    private static final String KEY_STATUS = "status";

    /**
     * Rolling buffer of the last few stderr lines for crash diagnostics.
     */
    private final java.util.Deque<String> recentStderr = new java.util.ArrayDeque<>();
    private static final int STDERR_BUFFER_SIZE = 30;

    public KiroClient(Project project) {
        super(project);
    }

    @Override
    protected void registerHandlers() {
        // Register combined handler for both standard and Kiro-specific notifications
        transport.onNotification(notification -> {
            String method = notification.method();
            if ("session/update".equals(method)) {
                // Delegate to parent's session update handler
                handleSessionUpdate(notification.params());
            } else if (method.startsWith("_kiro.dev/") || method.equals("_session/terminate")) {
                handleKiroNotification(method, notification.params());
            }
        });

        // Register request and stderr handlers from parent
        transport.onRequest(this::handleAgentRequest);
        transport.onStderr(line -> {
            LOG.warn("[" + agentId() + " stderr] " + line);
            synchronized (recentStderr) {
                recentStderr.addLast(line);
                if (recentStderr.size() > STDERR_BUFFER_SIZE) recentStderr.removeFirst();
            }
        });
    }

    private void handleKiroNotification(String method, JsonObject params) {
        switch (method) {
            case "_kiro.dev/commands/available" -> handleCommandsAvailable(params);
            case "_kiro.dev/mcp/oauth_request" -> handleMcpOAuthRequest(params);
            case "_kiro.dev/mcp/server_initialized" -> handleMcpServerInitialized(params);
            case "_kiro.dev/compaction/status" -> handleCompactionStatus(params);
            case "_kiro.dev/clear/status" -> handleClearStatus(params);
            case "_kiro.dev/metadata" -> { /* context usage telemetry — intentionally ignored */ }
            case "_session/terminate" -> handleSessionTerminate(params);
            default -> LOG.debug("Unhandled Kiro notification: " + method);
        }
    }

    private JsonArray availableCommands = new JsonArray();

    private void handleCommandsAvailable(JsonObject params) {
        if (params != null && params.has("commands")) {
            availableCommands = params.getAsJsonArray("commands");
            LOG.info("Kiro slash commands available: " + availableCommands.size());
        }
    }

    public void executeSlashCommand(String command, java.util.function.Consumer<Boolean> callback) {
        JsonObject params = new JsonObject();
        params.addProperty("command", command);
        transport.sendRequest("_kiro.dev/commands/execute", params).thenAccept(response -> {
            boolean success = response != null && response.isJsonObject()
                && response.getAsJsonObject().has("success")
                && response.getAsJsonObject().get("success").getAsBoolean();
            callback.accept(success);
        });
    }

    public JsonArray getAvailableCommands() {
        return availableCommands;
    }

    private void handleMcpOAuthRequest(JsonObject params) {
        if (params != null && params.has("url")) {
            String oauthUrl = params.get("url").getAsString();
            LOG.info("MCP OAuth required: " + oauthUrl);
            // OAuth for MCP servers is not yet exposed via ACP — log and ignore for now.
        }
    }

    private void handleMcpServerInitialized(JsonObject params) {
        if (params != null && params.has("serverName")) {
            String serverName = params.get("serverName").getAsString();
            LOG.info("MCP server initialized: " + serverName);
        }
    }

    private void handleCompactionStatus(JsonObject params) {
        if (params != null && params.has(KEY_STATUS)) {
            String status = params.get(KEY_STATUS).getAsString();
            LOG.debug("Context compaction: " + status);
        }
    }

    private void handleClearStatus(JsonObject params) {
        if (params != null && params.has(KEY_STATUS)) {
            String status = params.get(KEY_STATUS).getAsString();
            LOG.debug("Clear session: " + status);
        }
    }

    private void handleSessionTerminate(JsonObject params) {
        if (params != null && params.has("sessionId")) {
            String sessionId = params.get("sessionId").getAsString();
            LOG.info("Subagent session terminated: " + sessionId);
        }
    }

    /**
     * Kiro sends agent_thought_chunk updates asynchronously — they can arrive after
     * session/prompt returns. Keep the consumer set so late-arriving thoughts are
     * delivered to the UI instead of being silently dropped.
     */
    @Override
    protected void afterPromptComplete() {
        // Do NOT clear updateConsumer — Kiro may send thoughts after the response
    }

    @Override
    public String displayName() {
        return "Kiro";
    }

    @Override
    public String agentId() {
        return "kiro";
    }

    @Override
    protected boolean excludeBuiltInTools() {
        return true;
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        if (protocolTitle.startsWith(KEY_AGENTBRIDGE)) {
            return protocolTitle.substring(KEY_AGENTBRIDGE.length());
        }
        String cleaned = protocolTitle.replaceFirst("^Running: @agentbridge/", "");
        // Map Kiro's human-readable titles to actual tool names
        return switch (cleaned) {
            case "Searching the web" -> "web_search";
            case "Fetching web content" -> "web_fetch";
            default -> cleaned;
        };
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return protocolTitle.startsWith("Running: " + KEY_AGENTBRIDGE)
            || protocolTitle.startsWith(KEY_AGENTBRIDGE);
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        // --trust-all-tools bypasses Kiro's per-tool permission prompts.
        // Without it, Kiro blocks on TTY permission dialogs that never appear because
        // stdin/stdout are wired to JSON-RPC. Tool-level permissions are still enforced
        // by the MCP server (PermissionResolver).
        return List.of("kiro-cli", "--agent", "intellij-task", "acp", "--trust-all-tools");
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws java.io.IOException {
        java.nio.file.Path kiroDir = java.nio.file.Path.of(System.getProperty("user.home"), ".kiro", "agents");
        java.nio.file.Files.createDirectories(kiroDir);
        java.nio.file.Path agentPath = kiroDir.resolve("intellij-task.json");

        JsonObject agent = new JsonObject();
        agent.addProperty("name", "intellij-task");
        agent.addProperty("description", "IDE-only agent");

        JsonArray tools = new JsonArray();
        tools.add("@agentbridge/*");
        tools.add("web_fetch");
        tools.add("web_search");
        agent.add("tools", tools);

        JsonArray allowedTools = new JsonArray();
        allowedTools.add("@agentbridge/*");
        agent.add("allowedTools", allowedTools);

        try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(agentPath)) {
            gson.toJson(agent, writer);
            com.intellij.openapi.diagnostic.Logger.getInstance(KiroClient.class)
                .info("Kiro: wrote agent definition to " + agentPath + " to restrict built-in tools");
        }
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Kiro requires mcpServers in session/new params (field is mandatory)
        JsonObject server = buildMcpStdioServer("agentbridge", mcpPort);
        if (server == null) {
            throw new IllegalStateException("Cannot configure Kiro MCP server — Java binary or mcp-server.jar not found");
        }
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected JsonObject parseToolCallArguments(@NotNull JsonObject update) {
        // Kiro sends args in "rawInput" (object) instead of "content" (array)
        return update.has(KEY_RAW_INPUT) && update.get(KEY_RAW_INPUT).isJsonObject()
            ? update.getAsJsonObject(KEY_RAW_INPUT)
            : null;
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        // Kiro sends thinking as agent_message_chunk with ContentBlock.Thinking blocks —
        // convert to agent_thought_chunk for proper UI rendering.
        if (update instanceof SessionUpdate.AgentMessageChunk(var content)) {
            boolean hasThinking = content.stream()
                .anyMatch(block -> block instanceof ContentBlock.Thinking);
            if (hasThinking) {
                return new SessionUpdate.AgentThoughtChunk(content);
            }
        }
        if (update instanceof SessionUpdate.ToolCall tc) {
            // Kiro sends multiple tool_call updates for the same toolCallId:
            // 1. First with just title (e.g., "search_text") - NO rawInput
            // 2. Second with full details ("Running: @agentbridge/search_text" + rawInput)
            // We need the rawInput to compute the hash for MCP correlation, so skip the first one
            if (tc.arguments() == null || tc.arguments().isEmpty()) {
                return null;  // Skip - wait for the one with rawInput
            }
            return extractPurpose(tc);
        }
        return update;  // Pass through all other update types unchanged
    }

    /**
     * When Kiro crashes (Rust panic), the process writes the panic message to stderr and the
     * transport stops. The generic "Transport stopped" message is unhelpful; this override
     * inspects the buffered stderr lines and surfaces the actual panic reason to the UI.
     */
    @Override
    protected @org.jetbrains.annotations.Nullable PromptResponse
    tryRecoverPromptException(Exception cause) {
        String panicLine;
        synchronized (recentStderr) {
            panicLine = recentStderr.stream()
                .filter(l -> l.contains("panicked") || l.contains("Message:"))
                .reduce((first, second) -> second) // keep last matching line
                .orElse(null);
        }
        if (panicLine == null) return null;
        // Throw an unchecked exception whose message surfaces in the UI via handlePromptError.
        throw new java.io.UncheckedIOException(
            new java.io.IOException("Kiro crashed: " + panicLine.trim(), cause));
    }

    private SessionUpdate.ToolCall extractPurpose(SessionUpdate.ToolCall tc) {
        String args = tc.arguments();
        if (args != null && args.contains("__tool_use_purpose")) {
            int start = args.indexOf("\"__tool_use_purpose\"");
            if (start >= 0) {
                int colonIdx = args.indexOf(':', start);
                int quoteStart = args.indexOf('"', colonIdx + 1);
                int quoteEnd = args.indexOf('"', quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    String purpose = args.substring(quoteStart + 1, quoteEnd);
                    return new SessionUpdate.ToolCall(
                        tc.toolCallId(), tc.title(), tc.kind(), tc.arguments(),
                        tc.locations(), tc.agentType(), tc.subAgentDescription(),
                        tc.subAgentPrompt(), purpose
                    );
                }
            }
        }
        return tc;
    }
}
