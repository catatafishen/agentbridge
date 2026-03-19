package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ACP client for Kiro.
 */
public class KiroAcpClient extends AcpClient {

    public static final String PROFILE_ID = "kiro";

    /**
     * Maps tool call IDs to their purposes from {@code __tool_use_purpose} field.
     * Used to provide descriptions for tool call updates.
     */
    private final ConcurrentMap<String, String> toolPurposes = new ConcurrentHashMap<>();

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Kiro");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setTransportType(TransportType.ACP);
        p.setDescription("Kiro CLI — experimental support. Ensure 'kiro-cli' is in your PATH.");
        p.setBinaryName("kiro-cli");
        p.setAlternateNames(List.of("kiro"));
        p.setInstallHint("Install Kiro CLI and ensure it's available on your PATH.");
        p.setInstallUrl("https://kiro.dev/docs/cli/acp/");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of("acp"));

        // Kiro doesn't support MCP config flags, only SESSION_NEW injection
        // mcpCapabilities shows {"http": false, "sse": false} - command-based only
        p.setMcpMethod(McpInjectionMethod.SESSION_NEW);
        // ACP spec requires: name (required), command, args, env (as array, not object!)
        p.setMcpConfigTemplate(
            "{\"mcpServers\":["
                + "{\"name\":\"agentbridge\","
                + "\"command\":\"{javaPath}\","
                + "\"args\":[\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"],"
                + "\"env\":[]}"
                + "]}");

        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setSupportsSessionMessage(true);
        p.setAdditionalInstructions("⚠ IMPORTANT: Do NOT use your built-in tools (view, read, edit, write, grep, glob, bash, execute). " +
            "These tools bypass IntelliJ's editor buffer and cause desync. " +
            "Instead, ALWAYS use the tools with '@agentbridge/' prefix (e.g., '@agentbridge/read_file', '@agentbridge/search_text'). " +
            "IntelliJ MCP tools are faster and always stay in sync with the IDE.");
        return p;
    }

    public KiroAcpClient(@NotNull AgentConfig config,
                         @NotNull AgentSettings settings,
                         @Nullable ToolRegistry registry,
                         @Nullable String projectBasePath,
                         int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    protected List<String> getDuplicatedTools() {
        return List.of(   "read", "glob", "grep", "write", "shell", "aws", "code");
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        return name.trim()
            .replaceFirst("Running: @agentbridge/", "")
            .replaceFirst("Running: ", "");
    }

    @Override
    @NotNull
    public List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry>
    getDefaultProjectFiles() {
        List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry> entries = new ArrayList<>();
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Settings", ".agent-work/kiro/settings/cli.json", false, "Kiro"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Steering", ".agent-work/kiro/steering/*.md", true, "Kiro"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Agents", ".agent-work/kiro/agents/*.md", true, "Kiro"));
        return entries;
    }

    @Override
    protected void onToolCallEventReceived(@NotNull String toolCallId, @NotNull JsonObject update,
                                           @Nullable String argsJson) {
        // Extract __tool_use_purpose from tool arguments (Kiro includes this for better UI context)
        if (argsJson != null && !argsJson.isEmpty()) {
            try {
                JsonObject argsObj = JsonParser.parseString(argsJson).getAsJsonObject();
                if (argsObj.has("__tool_use_purpose")) {
                    String purpose = argsObj.get("__tool_use_purpose").getAsString();
                    if (purpose != null && !purpose.isEmpty()) {
                        storeToolPurpose(toolCallId, purpose);
                        LOG.debug("Stored tool purpose for " + toolCallId + ": " + purpose.substring(0, Math.min(100, purpose.length())) + "...");
                    }
                }
            } catch (Exception e) {
                LOG.debug("Failed to extract __tool_use_purpose from args", e);
            }
        }
    }

    /**
     * Kiro-specific error response handling.
     * Kiro error data format: "Encountered an error in the response stream: request_id: [uuid], error: [error message]"
     * This extracts the request_id and error type for better diagnostics.
     */
    @Override
    protected void handleErrorResponse(JsonObject msg, long id, CompletableFuture<JsonObject> future) {
        // Let the parent do the initial processing
        super.handleErrorResponse(msg, id, future);

        // Additionally parse Kiro-specific error format from the errorData
        if (msg.has("error")) {
            JsonObject error = msg.getAsJsonObject("error");
            if (error.has("data") && error.get("data").isJsonPrimitive()) {
                String errorData = error.get("data").getAsString();
                KiroErrorContext kiroError = parseKiroErrorData(errorData);
                if (kiroError != null) {
                    // Log the parsed Kiro error details for better debugging
                    LOG.info("Kiro error details - Request ID: " + kiroError.requestId +
                        ", Error type: " + kiroError.errorType);
                }
            }
        }
    }

    @NotNull
    @Override
    protected SessionUpdate.ToolCallUpdate buildToolCallUpdateEvent(@NotNull JsonObject update) {
        SessionUpdate.ToolCallUpdate base = super.buildToolCallUpdateEvent(update);
        String toolCallId = base.toolCallId();

        // For tool calls that completed or failed, try to extract and attach the purpose
        if (base.status() == SessionUpdate.ToolCallStatus.COMPLETED ||
            base.status() == SessionUpdate.ToolCallStatus.FAILED) {
            String purpose = toolPurposes.remove(toolCallId);
            if (purpose != null && !purpose.isEmpty()) {
                return new SessionUpdate.ToolCallUpdate(
                    toolCallId,
                    base.status(),
                    base.result(),
                    base.error(),
                    purpose  // Use the stored purpose as description
                );
            }
        }

        return base;
    }

    /**
     * Stores tool purposes extracted from __tool_use_purpose field for later retrieval.
     * Called by hook methods when tool events are processed.
     */
    protected void storeToolPurpose(@NotNull String toolCallId, @NotNull String purpose) {
        toolPurposes.put(toolCallId, purpose);
    }

    /**
     * Kiro-specific error formatting that includes error codes and AWS request IDs.
     * @param errorData the error data JSON object
     * @param message the extracted error message
     * @return formatted error message with Kiro-specific details
     */
    @Override
    protected String formatErrorData(@NotNull JsonObject errorData, @NotNull String message) {
        StringBuilder detailsBuilder = new StringBuilder(message);

        // Include Kiro-specific error code if present (e.g., AccessDeniedException)
        if (errorData.has("code") && errorData.get("code").isJsonPrimitive()) {
            String errorCode = errorData.get("code").getAsString();
            if (!errorCode.isEmpty() && !message.contains(errorCode)) {
                detailsBuilder.insert(0, "[" + errorCode + "] ");
            }
        }

        // Include AWS request ID if present for debugging
        if (errorData.has("aws_request_id")) {
            String requestId = errorData.get("aws_request_id").getAsString();
            detailsBuilder.append(" (Request ID: ").append(requestId).append(")");
        }

        return detailsBuilder.toString();
    }

    /**
     * Parses Kiro-specific error format.
     * Expected format: "Encountered an error in the response stream: request_id: [uuid], error: [error message]"
     */
    @Nullable
    private KiroErrorContext parseKiroErrorData(@NotNull String errorData) {
        try {
            // Extract request_id
            Pattern requestIdPattern = Pattern.compile("request_id:\\s*([a-f0-9\\-]+)");
            Matcher requestIdMatcher = requestIdPattern.matcher(errorData);
            String requestId = requestIdMatcher.find() ? requestIdMatcher.group(1) : null;

            // Extract error type (e.g., "AccessDeniedException")
            Pattern errorTypePattern = Pattern.compile("error:\\s*[^:]*:\\s*(\\w+(?:Exception|Error)?)");
            Matcher errorTypeMatcher = errorTypePattern.matcher(errorData);
            String errorType = errorTypeMatcher.find() ? errorTypeMatcher.group(1) : null;

            if (requestId != null || errorType != null) {
                return new KiroErrorContext(requestId, errorType);
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse Kiro error data", e);
        }
        return null;
    }

    /**
     * Container for parsed Kiro error information.
     */
    private static class KiroErrorContext {
        final String requestId;
        final String errorType;

        KiroErrorContext(String requestId, String errorType) {
            this.requestId = requestId;
            this.errorType = errorType;
        }
    }
}
