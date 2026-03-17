package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JunieAcpClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(JunieAcpClient.class);

    public static final String PROFILE_ID = "junie";

    // Track raw tool results that are sent in IN_PROGRESS updates before the final description
    private final Map<String, String> rawToolResults = new ConcurrentHashMap<>();

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("Junie");
        p.setBuiltIn(true);
        p.setExperimental(false);
        p.setTransportType(TransportType.ACP);
        p.setDescription("""
            Junie CLI by JetBrains. Connects via ACP (--acp true). \
            Authenticate with your JetBrains Account or a JUNIE_API_KEY token. \
            Install from junie.jetbrains.com and run 'junie' once to authenticate.""");
        p.setBinaryName(PROFILE_ID);
        p.setAlternateNames(List.of());
        p.setInstallHint("Install from junie.jetbrains.com and run 'junie' to authenticate.");
        p.setInstallUrl("https://junie.jetbrains.com/docs/junie-cli.html");
        p.setSupportsOAuthSignIn(false);
        p.setAcpArgs(List.of("--acp=true"));
        p.setMcpMethod(McpInjectionMethod.MCP_LOCATION_FLAG);
        p.setSupportsMcpConfigFlag(false);
        p.setMcpConfigTemplate(
            "{\"mcpServers\":{\"intellij-code-tools\":"
                + "{\"type\":\"stdio\","
                + "\"command\":\"{javaPath}\","
                + "\"args\":[\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"]}}}");
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(true);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(true);
        // JUNIE-1842: Toolset configuration profiles now support allow/deny list.
        // TODO: Update to CLI_FLAGS or CONFIG_JSON once the exact integration mechanism is confirmed.
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setPrependInstructionsTo("");
        return p;
    }

    public JunieAcpClient(@NotNull AgentConfig config,
                          @NotNull AgentSettings settings,
                          @Nullable ToolRegistry registry,
                          @Nullable String projectBasePath,
                          int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    protected void customizeProcessBuilder(@NotNull ProcessBuilder pb) {
        List<String> cmd = pb.command();
        int modelIdx = cmd.indexOf("--model");
        if (modelIdx >= 0 && modelIdx < cmd.size() - 1) {
            String originalModel = cmd.get(modelIdx + 1);
            if (originalModel != null) {
                String mappedModel = mapToCliModel(originalModel);
                if (!mappedModel.equals(originalModel)) {
                    LOG.info("Junie startup model mapping: " + originalModel + " -> " + mappedModel);
                }
                cmd.set(modelIdx + 1, mappedModel);
            }
        }
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        // Junie uses the standard slash format for MCP tool names:
        // "intellij-code-tools/tool_name" -> "tool_name"
        // Also strip "Tool: " prefix if present (seen in some Junie versions)
        String normalized = name.replaceFirst("^Tool: intellij-code-tools/", "");

        if (!name.equals(normalized)) {
            LOG.debug("Junie tool name normalization: '" + name + "' -> '" + normalized + "'");
        }

        // If Junie uses a built-in like 'bash' but it was supposed to be excluded,
        // it won't be found in the registry.
        if (registry != null && registry.findDefinition(normalized) == null) {
            if (ToolRegistry.getBuiltInToolIds().contains(normalized)) {
                LOG.warn("Junie is using excluded built-in tool: " + normalized);
            }
        }

        return normalized;
    }

    /**
     * Override setModel to apply Junie's model ID mapping to session/set_model calls.
     * Without this, runtime model switches send unmapped IDs (e.g., "claude-sonnet-4-6")
     * which Junie rejects with 403, while it expects short names like "sonnet".
     */
    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) throws AcpException {
        String mappedModel = mapToCliModel(modelId);
        if (!mappedModel.equals(modelId)) {
            LOG.info("Junie model mapping: " + modelId + " -> " + mappedModel);
        }
        super.setModel(sessionId, mappedModel);
    }

    @Override
    public @NotNull String sendPrompt(@NotNull String sessionId, @NotNull String prompt,
                                      @Nullable String model, @Nullable List<ResourceReference> references,
                                      @Nullable java.util.function.Consumer<String> onChunk,
                                      @Nullable java.util.function.Consumer<SessionUpdate> onUpdate,
                                      @Nullable Runnable onRequest) throws AcpException {
        // Prepend a space if the prompt starts with '/' OR if it contains code snippets (indicated by code fences),
        // to avoid it being misinterpreted as a command by Junie CLI.
        String safePrompt = prompt;
        if (safePrompt.startsWith("/") || safePrompt.contains("`") || safePrompt.contains("```")) {
            safePrompt = " " + safePrompt;
        }

        return super.sendPrompt(sessionId, safePrompt, model, references, onChunk, onUpdate, onRequest);
    }

    @NotNull
    protected SessionUpdate.ToolCallUpdate buildToolCallUpdateEvent(@NotNull com.google.gson.JsonObject update) {
        SessionUpdate.ToolCallUpdate base = super.buildToolCallUpdateEvent(update);
        String toolCallId = base.toolCallId();
        String currentResult = base.result();

        // If it's an IN_PROGRESS update, we try to capture the raw tool output.
        // Junie often streams the tool arguments first as JSON, then the actual output.
        if (base.status() == null || base.status().value().equals("in_progress")) {
            if (currentResult != null && !currentResult.isEmpty()) {
                String trimmed = currentResult.trim();
                // Check if it's NOT just JSON arguments (starts with { and contains path/file keys)
                // Junie arguments examples: {"path":"..."}, {"query":"..."}, {"file":"..."}
                boolean isArguments = (trimmed.startsWith("{") || trimmed.startsWith("[")) 
                        && (trimmed.contains("\"path\"") || trimmed.contains("\"file\"") || trimmed.contains("\"query\"") || trimmed.contains("\"target\""));
                
                if (!isArguments) {
                    // It looks like actual tool output (e.g. diff, file content, search matches)
                    // We accumulate it because it might be streamed in chunks.
                    rawToolResults.merge(toolCallId, currentResult, (old, val) -> old + val);
                    LOG.debug("Junie accumulated raw output for " + toolCallId + ", len=" + rawToolResults.get(toolCallId).length());
                }
            }
            return base;
        }

        // If it's COMPLETED, Junie often sends a natural language summary in the 'content' field.
        // The actual tool result might have been captured during IN_PROGRESS, or it might be
        // part of this final update, concatenated with the summary.
        if (base.status() == SessionUpdate.ToolCallStatus.COMPLETED) {
            String rawOutput = rawToolResults.remove(toolCallId);
            String finalContent = currentResult;

            // 1. If we have a stored raw output AND it's different from the final summary
            if (rawOutput != null && finalContent != null && !finalContent.contains(rawOutput) && !rawOutput.contains(finalContent)) {
                LOG.debug("Junie tool result merged: rawOutputLen=" + rawOutput.length() + ", descriptionLen=" + finalContent.length());
                return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), rawOutput, base.error(), finalContent);
            }

            // 2. If no raw output was captured, or it's similar to finalContent,
            // try to split finalContent if it contains both summary and raw data.
            if (finalContent != null && !finalContent.isEmpty()) {
                String[] lines = finalContent.split("\n");
                if (lines.length >= 2) {
                    // Markers that commonly indicate the start of a raw tool output in Junie
                    java.util.List<String> markers = java.util.List.of(
                        "Written:", "Created:", "Edited:", "Context after edit",
                        "Test Results:", "diff --git", "Changes:", "Matches:",
                        "Symbol:", "Declaration:", "Type hierarchy:", "Documentation:",
                        "## ", "--- ", "  ", "  - ", "* ", "    ", "[", "{"
                    );

                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.isEmpty()) continue;
                        boolean found = false;
                        for (String marker : markers) {
                            if (line.startsWith(marker)) {
                                found = true;
                                break;
                            }
                        }
                        if (found && i > 0) {
                            java.util.StringJoiner explanationJoiner = new java.util.StringJoiner("\n");
                            for (int j = 0; j < i; j++) explanationJoiner.add(lines[j]);
                            String explanation = explanationJoiner.toString().trim();

                            java.util.StringJoiner rawOutputJoiner = new java.util.StringJoiner("\n");
                            for (int j = i; j < lines.length; j++) rawOutputJoiner.add(lines[j]);
                            String rawOutputSplitted = rawOutputJoiner.toString().trim();

                            if (!explanation.isEmpty() && !rawOutputSplitted.isEmpty()) {
                                LOG.debug("Junie tool result split from final content: explanationLen="
                                    + explanation.length() + ", rawOutputLen=" + rawOutputSplitted.length());
                                return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), rawOutputSplitted, base.error(), explanation);
                            }
                        }
                    }
                }
            }

            // 3. Fallback: if we have rawOutput, use it as result and finalContent as description
            if (rawOutput != null) {
                LOG.debug("Junie using captured raw output for " + toolCallId + ", len=" + rawOutput.length());
                return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), rawOutput, base.error(), finalContent);
            }
        }

        // Cleanup on failure
        if (base.status() == SessionUpdate.ToolCallStatus.FAILED) {
            rawToolResults.remove(toolCallId);
        }

        return base;
    }

    private String mapToCliModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) return modelId;
        String lower = modelId.toLowerCase();
        if (lower.contains("gemini")) {
            if (lower.contains("flash")) return "gemini-flash";
            return "gemini-pro";
        }
        if (lower.contains("gpt-4o")) return "gpt";
        if (lower.contains("gpt-4")) return "gpt-codex";
        // Handle both "claude-sonnet-4-6" and "SONNET_4_6" formats
        if (lower.contains("haiku")) return "haiku";
        if (lower.contains("sonnet")) return "sonnet";
        if (lower.contains("opus")) return "opus";
        if (lower.contains("grok")) return "grok";
        return modelId;
    }
}
