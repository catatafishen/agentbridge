package com.github.catatafishen.agentbridge.psi.tools.database;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class DatabaseTool extends Tool {

    protected DatabaseTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.DATABASE;
    }

    /**
     * Resolves a data source by name (case-insensitive).
     * Returns null if no match found.
     */
    protected @Nullable DbDataSource resolveDataSource(@NotNull String name) {
        return ReadAction.compute(() -> {
            List<DbDataSource> sources = DbPsiFacade.getInstance(project).getDataSources();
            for (DbDataSource source : sources) {
                if (name.equalsIgnoreCase(source.getName())) {
                    return source;
                }
            }
            return null;
        });
    }

    /**
     * Returns a formatted list of available data source names for error messages.
     */
    protected @NotNull String availableDataSourceNames() {
        return ReadAction.compute(() -> {
            List<DbDataSource> sources = DbPsiFacade.getInstance(project).getDataSources();
            if (sources.isEmpty()) {
                return "No data sources configured. Add one via Database tool window.";
            }
            StringBuilder sb = new StringBuilder("Available data sources: ");
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("'").append(sources.get(i).getName()).append("'");
            }
            return sb.toString();
        });
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

    /**
     * Formats a schema-qualified name (e.g. "public.users") or just the name if schema is empty.
     */
    protected static @NotNull String formatQualifiedName(@Nullable String schema, @NotNull String name) {
        if (schema != null && !schema.isEmpty()) {
            return schema + "." + name;
        }
        return name;
    }
}
