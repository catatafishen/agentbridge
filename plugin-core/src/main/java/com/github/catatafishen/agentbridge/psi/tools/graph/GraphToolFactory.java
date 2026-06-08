package com.github.catatafishen.agentbridge.psi.tools.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.diagnostic.Logger;
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

    private static final Logger LOG = Logger.getInstance(GraphToolFactory.class);

    private GraphToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        boolean enabled = settings.isEnabled();
        LOG.info("GraphToolFactory.create(): isEnabled=" + enabled);
        if (!enabled) {
            return List.of();
        }
        return List.of(new QueryCodeGraphTool(project));
    }
}
