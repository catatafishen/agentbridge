package com.github.catatafishen.ideagentforcopilot.psi.tools.database;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Lists all configured data sources with name, URL, and driver info.
 */
public final class ListDataSourcesTool extends DatabaseTool {

    public ListDataSourcesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "database_list_sources";
    }

    @Override
    public @NotNull String displayName() {
        return "List Database Sources";
    }

    @Override
    public @NotNull String description() {
        return "List configured data sources (name, type, database, connection status)";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{});
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        return ReadAction.compute(() -> {
            List<DbDataSource> sources = DbPsiFacade.getInstance(project).getDataSources();
            if (sources.isEmpty()) {
                return "No data sources configured. Add one via the Database tool window (View → Tool Windows → Database).";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(sources.size()).append(" data source(s):\n\n");
            for (DbDataSource source : sources) {
                sb.append("  Name: ").append(source.getName()).append("\n");
                var connectionConfig = source.getConnectionConfig();
                if (connectionConfig != null) {
                    sb.append("  URL: ").append(connectionConfig.getUrl()).append("\n");
                    String driverClass = connectionConfig.getDriverClass();
                    if (driverClass != null && !driverClass.isEmpty()) {
                        sb.append("  Driver: ").append(driverClass).append("\n");
                    }
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        });
    }
}
