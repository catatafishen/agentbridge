package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class JunieAcpClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(JunieAcpClient.class);

    public static final String PROFILE_ID = "junie";

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
