package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.McpInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.PermissionInjectionMethod;
import com.github.catatafishen.ideagentforcopilot.services.ToolExecutionCorrelator;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Junie-specific ACP client.
 *
 * <h2>Junie's Tool Update Protocol</h2>
 * Junie sends tool_call_update events with:
 * <ul>
 *   <li><b>IN_PROGRESS:</b> Tool arguments as JSON (e.g., {"paths": [...]}) - not useful to display</li>
 *   <li><b>COMPLETED:</b> Natural language summary only ("Two files staged") - no raw output</li>
 * </ul>
 *
 * <p>Junie never forwards the raw MCP tool results (diffs, file contents, etc.).
 * It converts everything to natural language summaries for the user.
 *
 * <h2>Correlation Strategy</h2>
 * To show both raw results AND summaries:
 * <ol>
 *   <li>When MCP tool executes, {@link ToolExecutionCorrelator} records the raw result</li>
 *   <li>When Junie sends COMPLETED update, we look up the matching execution</li>
 *   <li>Combine: raw result (for technical accuracy) + summary (for context)</li>
 *   <li>Fallback: if no match found, just show summary as description</li>
 * </ol>
 *
 * <p>See {@link ToolExecutionCorrelator} for matching strategy details.
 */
public class JunieAcpClient extends AcpClient {
    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(JunieAcpClient.class);

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
        p.setMcpMethod(McpInjectionMethod.SESSION_NEW);
        p.setSupportsMcpConfigFlag(false);
        // ACP spec requires array format for mcpServers in session/new
        p.setMcpConfigTemplate(
            "{\"mcpServers\":["
                + "{\"name\":\"agentbridge\","
                + "\"command\":\"{javaPath}\","
                + "\"args\":[\"-jar\",\"{mcpJarPath}\",\"--port\",\"{mcpPort}\"],"
                + "\"env\":[]}"
                + "]}");
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(true);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        p.setPrependInstructionsTo("");
        p.setAdditionalInstructions("");
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
    protected void addExtraSessionParams(@NotNull JsonObject params) {
        // NOTE: As of Junie v888.212, there is no verified protocol-level parameter
        // for tool filtering (e.g., 'excludedTools', 'denyList', 'toolFilter' are NOT supported).
        // Tool filtering is handled internally by Junie's CapabilityFilterAgentTask.
    }

    @Override
    protected boolean isBlackListed(JsonObject toolCall) {
        var title = toolCall.get("title").getAsString().trim().toLowerCase();
        return Stream.of("execute", "read_file", "write_file", "list_files",
                "view_file", "grep", "glob", "bash", "run_command").anyMatch(title::contains);
    }

    @Override
    @NotNull
    public String getToolId(@NotNull JsonObject toolCall) {
        return toolCall.get("title").getAsString().trim().replaceFirst("Tool: agentbridge/", "");
    }

    @Override
    @NotNull
    public List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry>
    getDefaultProjectFiles() {
        List<com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry> entries = new ArrayList<>();
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Guidelines", ".junie/guidelines.md", false, "Junie"));
        entries.add(new com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings.FileEntry(
            "Agents", ".junie/AGENTS.md", false, "Junie"));
        return entries;
    }

    @NotNull
    @Override
    protected SessionUpdate.ToolCallUpdate buildToolCallUpdateEvent(@NotNull JsonObject update) {
        SessionUpdate.ToolCallUpdate base = super.buildToolCallUpdateEvent(update);
        String toolCallId = base.toolCallId();
        String naturalLanguageSummary = base.result();
        String description = base.description();

        // IN_PROGRESS: Just tool arguments, not useful - return with null result to avoid showing JSON args in UI
        if (base.status() == SessionUpdate.ToolCallStatus.IN_PROGRESS) {
            return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), null, null, description);
        }

        // FAILED: Return as-is (super already handled "(Denied)" in description if applicable)
        if (base.status() == SessionUpdate.ToolCallStatus.FAILED) {
            return base;
        }

        // COMPLETED: Try to correlate with actual MCP execution to get raw result
        if (naturalLanguageSummary != null && !naturalLanguageSummary.isEmpty()) {
            String toolId = getToolId(update);

            // Look up the matching tool execution
            if (projectBasePath != null) {
                try {
                    com.intellij.openapi.project.Project project = findProject(projectBasePath);
                    if (project != null) {
                        ToolExecutionCorrelator correlator = ToolExecutionCorrelator.getInstance(project);
                        String rawResult = correlator.consumeResult(toolId, null);

                        if (rawResult != null) {
                            // Success: we have both raw result and natural language summary
                            LOG.debug("Junie completed with correlation: tool=" + toolId
                                + ", rawLen=" + rawResult.length()
                                + ", descLen=" + naturalLanguageSummary.length());
                            return new SessionUpdate.ToolCallUpdate(
                                toolCallId,
                                base.status(),
                                rawResult,           // Raw tool output (diffs, file contents, etc.)
                                base.error(),
                                combineDescription(description, naturalLanguageSummary) // Natural language explanation
                            );
                        } else {
                            // No match - log for debugging
                            LOG.debug("Junie completed without correlation: tool=" + toolId
                                + " (no matching execution found, using summary only)");
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to correlate Junie tool result for " + toolId, e);
                }
            }

            // Fallback: Just use Junie's natural language summary as description
            LOG.debug("Junie completed (fallback): using summary only, len=" + naturalLanguageSummary.length());
            return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), null, base.error(), combineDescription(description, naturalLanguageSummary));
        }

        // Empty or null content - return as-is
        return base;
    }

    /**
     * Combines a base description (e.g. "(Denied)") with a natural language summary.
     */
    private String combineDescription(@Nullable String base, @Nullable String summary) {
        if (base == null) return summary;
        if (summary == null) return base;
        return base + " " + summary;
    }

    /**
     * Finds the Project instance for the given project base path.
     * Required to get the ToolExecutionCorrelator service instance.
     */
    @Nullable
    private com.intellij.openapi.project.Project findProject(@NotNull String projectBasePath) {
        for (com.intellij.openapi.project.Project project : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            if (project.getBasePath() != null && project.getBasePath().equals(projectBasePath)) {
                return project;
            }
        }
        return null;
    }
}
