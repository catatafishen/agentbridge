package com.github.catatafishen.ideagentforcopilot.psi.tools.database;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class ListTablesTool extends DatabaseTool {

    public ListTablesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "database_list_tables";
    }

    @Override
    public @NotNull String displayName() {
        return "List Database Tables";
    }

    @Override
    public @NotNull String description() {
        return "List tables and views in a data source";
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
        return schema(new Object[][]{
            {"data_source", TYPE_STRING, "Name of the data source to list tables from"},
            {"schema", TYPE_STRING, "Optional: filter by schema name"},
        }, "data_source");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String dataSourceName = args.get("data_source").getAsString();
        String schemaFilter = args.has("schema") && !args.get("schema").isJsonNull()
            ? args.get("schema").getAsString() : null;

        DbDataSource dataSource = resolveDataSource(dataSourceName);
        if (dataSource == null) {
            return "Error: data source '" + dataSourceName + "' not found. " + availableDataSourceNames();
        }

        return ReadAction.compute(() -> {
            StringBuilder sb = new StringBuilder();
            int tableCount = 0;
            int viewCount = 0;

            var tables = DasUtil.getTables(dataSource);
            for (var table : tables) {
                String tableSchema = DasUtil.getSchema(table);
                if (schemaFilter != null && !schemaFilter.equalsIgnoreCase(tableSchema)) {
                    continue;
                }
                ObjectKind objKind = table.getKind();
                String kindLabel = objKind == ObjectKind.VIEW ? "VIEW" : "TABLE";
                if (objKind == ObjectKind.VIEW) viewCount++;
                else tableCount++;

                sb.append("  ");
                if (tableSchema != null && !tableSchema.isEmpty()) {
                    sb.append(tableSchema).append(".");
                }
                sb.append(table.getName()).append(" (").append(kindLabel).append(")\n");
            }

            if (tableCount == 0 && viewCount == 0) {
                return "No tables or views found in '" + dataSourceName + "'"
                    + (schemaFilter != null ? " (schema: " + schemaFilter + ")" : "") + ".";
            }

            String header = tableCount + " table(s), " + viewCount + " view(s) in '" + dataSourceName + "'";
            if (schemaFilter != null) header += " (schema: " + schemaFilter + ")";
            return header + ":\n\n" + sb;
        });
    }
}
