package com.github.catatafishen.agentbridge.psi.tools.graph;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory for Code Graph MCP tools. Always registers {@code query_code_graph}
 * in the {@link com.github.catatafishen.agentbridge.services.ToolRegistry} so
 * it appears in the MCP settings UI. Whether it is <em>advertised</em> to agents
 * is controlled by {@link com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings#isEnabled()}
 * via {@link com.github.catatafishen.agentbridge.settings.McpToolFilter}.
 */
public final class GraphToolFactory {

    private GraphToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(new QueryCodeGraphTool(project));
    }
}
