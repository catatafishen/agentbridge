package com.github.catatafishen.agentbridge.psi.tools.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings;
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory for Code Graph MCP tools. Conditionally registers {@code query_code_graph}
 * only when the feature is <b>enabled</b> in {@link CodeGraphSettings} <em>and</em>
 * the graph has been built at least once (non-empty stats).
 *
 * <p>Honours the user's design decision: the tool is not advertised to agents until
 * there is actual data to query. The {@link com.github.catatafishen.agentbridge.ui.graph.CodeGraphPanel}
 * triggers a rebuild on enable and refreshes the tool registry afterwards.
 */
public final class GraphToolFactory {

    private GraphToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        if (!settings.isEnabled()) {
            return List.of();
        }
        // Empty-graph guard — registry refresh happens after the first rebuild completes.
        CodeGraphStore.GraphStats stats = CodeGraphStore.getInstance(project).getStats();
        if (stats.isEmpty()) {
            return List.of();
        }
        return List.of(new QueryCodeGraphTool(project));
    }
}
