package com.github.catatafishen.agentbridge.psi.tools.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory for Code Graph MCP tools. Registers {@code query_code_graph}
 * when the feature is <b>enabled</b> in {@link CodeGraphSettings}.
 *
 * <p>The tool is registered unconditionally once enabled — an empty graph
 * returns empty results gracefully without errors. The
 * {@link com.github.catatafishen.agentbridge.ui.graph.CodeGraphPanel}
 * triggers a rebuild on enable and refreshes stats in the UI.
 */
public final class GraphToolFactory {

    private GraphToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        if (!settings.isEnabled()) {
            return List.of();
        }
        // Tool is registered unconditionally when the feature is enabled.
        // An empty graph returns empty results gracefully — no need to guard.
        return List.of(new QueryCodeGraphTool(project));
    }
}
