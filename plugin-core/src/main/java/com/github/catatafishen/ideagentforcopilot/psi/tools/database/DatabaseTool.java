package com.github.catatafishen.ideagentforcopilot.psi.tools.database;

import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
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
}
