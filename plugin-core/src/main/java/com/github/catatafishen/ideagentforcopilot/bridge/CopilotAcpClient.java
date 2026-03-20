package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ACP client for GitHub Copilot.
 *
 * <p>Extends the generic {@link AcpClient} with Copilot-specific behaviour:
 * <ul>
 *   <li>Premium-request multipliers — Copilot charges by model tier (e.g. "1x", "2x", "10x")
 *       rather than by token count. {@link #supportsMultiplier()} returns {@code true} so
 *       the UI shows the multiplier chip instead of token/cost figures.</li>
 *   <li>{@link #getModelMultiplier(String)} resolves the multiplier label from the model list
 *       returned during session creation.</li>
 * </ul>
 *
 * <p>Everything else (binary discovery, MCP injection, permission handling, auth) is driven
 * by the {@link AgentConfig} / {@link AgentSettings} passed at construction time — the same
 * as the generic {@link AcpClient}.</p>
 */
public class CopilotAcpClient extends AcpClient {

    public static final String PROFILE_ID = "copilot";

    private static final String ADDITIONAL_INSTRUCTIONS =
        """
            SUB-AGENT SELECTION:
            When spawning sub-agents via the `task` tool, ALWAYS prefer these IDE-aware custom agents \\
            over the equivalent built-in agents — they use IntelliJ MCP tools and live editor buffers \\
            instead of stale CLI tools:
            - Use `@ide-explore` instead of the built-in `explore` agent
            - Use `@ide-task` instead of the built-in `task` agent""";

    @NotNull
    public static AgentProfile createDefaultProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(PROFILE_ID);
        p.setDisplayName("GitHub Copilot");
        p.setBuiltIn(true);
        p.setBinaryName(PROFILE_ID);
        p.setAlternateNames(List.of("copilot-cli"));
        p.setInstallHint("Install with: npm install -g @github/copilot-cli");
        p.setInstallUrl("https://github.com/github/copilot-cli#installation");
        p.setSupportsOAuthSignIn(true);
        p.setAcpArgs(List.of("--acp", "--stdio"));
        p.setMcpMethod(McpInjectionMethod.CONFIG_FLAG);
        p.setSupportsMcpConfigFlag(true);
        p.setForceMcpInSessionNew(true);  // Copilot requires both CONFIG_FLAG + mcpServers in session/new
        p.setMcpConfigTemplate(
            "{\"mcpServers\":{\"agentbridge\":{"
                + "\"type\":\"http\","
                + "\"url\":\"http://localhost:{mcpPort}/mcp\","
                + "\"headers\":{}}}}");
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(true);
        p.setRequiresResourceDuplication(true);
        p.setModelUsageField("copilotUsage");
        p.setAgentsDirectory(".github/agents");
        p.setBundledAgentFiles(List.of("ide-explore.md", "ide-task.md"));
        p.setAdditionalInstructions(ADDITIONAL_INSTRUCTIONS);
        p.setPrependInstructionsTo(".copilot/copilot-instructions.md");
        p.setPermissionInjectionMethod(PermissionInjectionMethod.CLI_FLAGS);
        return p;
    }

    public CopilotAcpClient(@NotNull AgentConfig config,
                            @NotNull AgentSettings settings,
                            @Nullable ToolRegistry registry,
                            @Nullable String projectBasePath,
                            int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    protected boolean isBlackListed(JsonObject toolCall) {
        var title = toolCall.get("title").getAsString().trim().toLowerCase();
        return Stream.of("execute", "read", "edit", "search")
            .anyMatch(title::contains);
    }
    /**
     * Copilot bills by premium-request multiplier, not by token count.
     */
    @Override
    public boolean supportsMultiplier() {
        return true;
    }

    /**
     * Returns the multiplier label for the given model (e.g. {@code "1x"}, {@code "2x"}).
     * Looks up the model in the list returned at session creation and reads its {@code usage}
     * field, which is populated from the {@code copilotUsage} metadata field in the ACP
     * {@code session/new} response. Falls back to {@code "1x"} when the model is not found
     * or has no usage metadata.
     */
    @Override
    @NotNull
    public String getModelMultiplier(@NotNull String modelId) {
        if (availableModels == null) return "1x";
        for (Model model : availableModels) {
            if (modelId.equals(model.getId())) {
                String usage = model.getUsage();
                return (usage != null && !usage.isEmpty()) ? usage : "1x";
            }
        }
        return "1x";
    }

    @Override
    @NotNull
    public String getToolId(@NotNull JsonObject toolCall) {
        return toolCall.get("title").getAsString().trim().replaceFirst("^agentbridge-", "");
    }

    @Override
    @NotNull
    public List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry>
    getDefaultProjectFiles() {
        List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry> entries = new ArrayList<>();
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Settings", ".agent-work/copilot/config.json", false, "Copilot"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Instructions", ".github/copilot-instructions.md", false, "Copilot"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Project Settings", ".github/copilot/settings.local.json", false, "Copilot"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Agents", ".agent-work/copilot/agents/*.md", true, "Copilot"));
        return entries;
    }
}
