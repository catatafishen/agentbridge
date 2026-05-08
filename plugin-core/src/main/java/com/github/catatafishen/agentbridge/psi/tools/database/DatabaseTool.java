package com.github.catatafishen.agentbridge.psi.tools.database;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Base class for database tools. Provides category classification and
 * follow-agent tool window activation.
 * <p>
 * JetBrains' built-in MCP server (AI Assistant plugin) provides schema inspection
 * and query execution. AgentBridge only supplements with {@code database_add_source},
 * which has no native equivalent. See {@code docs/DATABASE-TOOLS.md}.
 */
public abstract class DatabaseTool extends Tool {

    protected DatabaseTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.DATABASE;
    }

    /**
     * Activates the Database tool window when follow-agent mode is enabled.
     */
    protected void activateDatabaseToolWindow() {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            return;
        }
        EdtUtil.invokeLater(() -> {
            var tw = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.DATABASE_VIEW);
            if (tw == null) return;
            // Don't steal focus from the chat prompt while the user is typing.
            if (PsiBridgeService.isChatToolWindowActive(project)) {
                tw.show();
            } else {
                tw.activate(null);
            }
        });
    }
}
