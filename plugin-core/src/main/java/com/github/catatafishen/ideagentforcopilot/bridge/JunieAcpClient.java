package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Junie-specific ACP client. Handles Junie's streaming updates which only contain:
 * - IN_PROGRESS: tool arguments as JSON (not useful to display)
 * - COMPLETED: natural language summary (should go into description, not result)
 * <p>
 * Junie never streams the raw tool output from MCP servers - it converts everything
 * to natural language summaries.
 */
public class JunieAcpClient extends AcpClient {
    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(JunieAcpClient.class);

    public JunieAcpClient(@NotNull AgentConfig config,
                          @NotNull AgentSettings settings,
                          @Nullable ToolRegistry registry,
                          @Nullable String projectBasePath,
                          int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }

    @Override
    @NotNull
    public String normalizeToolName(@NotNull String name) {
        // Junie uses MCP tool names like "intellij-code-tools/git_status"
        // Strip the MCP server prefix to get just "git_status"
        return name.replaceFirst("^[^/]+/", "");
    }

    @NotNull
    @Override
    protected SessionUpdate.ToolCallUpdate buildToolCallUpdateEvent(@NotNull com.google.gson.JsonObject update) {
        SessionUpdate.ToolCallUpdate base = super.buildToolCallUpdateEvent(update);
        String toolCallId = base.toolCallId();
        String content = base.result();

        // Junie only sends:
        // 1. IN_PROGRESS: tool arguments as JSON (e.g., {"paths": [...]})
        // 2. COMPLETED: natural language summary
        // We only care about COMPLETED summaries.

        if (base.status() == SessionUpdate.ToolCallStatus.COMPLETED && content != null && !content.isEmpty()) {
            // This is a natural language explanation, not raw tool output.
            // Put it in description so the UI shows it as explanatory text.
            LOG.debug("Junie completed: moving summary to description, len=" + content.length());
            return new SessionUpdate.ToolCallUpdate(toolCallId, base.status(), null, base.error(), content);
        }

        // For IN_PROGRESS or empty content, just return as-is
        return base;
    }
}
