package com.github.catatafishen.agentbridge.psi.tools.database;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Lists tables and views in a data source with optional schema filter.
 */
public final class ListTablesTool extends DatabaseTool {

    private static final String PARAM_DATA_SOURCE = "data_source";
    private static final String PARAM_SCHEMA = "schema";

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
        return "List tables and views in a data source with optional schema filter";
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
        return schema(
            Param.required(PARAM_DATA_SOURCE, TYPE_STRING, "Name of the data source to list tables from"),
            Param.optional(PARAM_SCHEMA, TYPE_STRING, "Optional: filter by schema name")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        activateDatabaseToolWindow();
        String dataSourceName = args.get(PARAM_DATA_SOURCE).getAsString();
        String schemaFilter = args.has(PARAM_SCHEMA) && !args.get(PARAM_SCHEMA).isJsonNull()
            ? args.get(PARAM_SCHEMA).getAsString() : null;

        DbDataSource dataSource = resolveDataSource(dataSourceName);
        if (dataSource == null) {
            return "Error: data source '" + dataSourceName + "' not found. " + availableDataSourceNames();
        }

        return ReadAction.compute(() -> formatTables(dataSource, dataSourceName, schemaFilter));
    }

    private static @NotNull String formatTables(DbDataSource dataSource, String dataSourceName, String schemaFilter) {
        StringBuilder sb = new StringBuilder();
        int tableCount = 0;
        int viewCount = 0;

        for (var table : DasUtil.getTables(dataSource)) {
            if (!matchesSchema(DasUtil.getSchema(table), schemaFilter)) continue;

            boolean isView = table.getKind() == ObjectKind.VIEW;
            if (isView) viewCount++;
            else tableCount++;

            String tableSchema = DasUtil.getSchema(table);
            sb.append("  ").append(formatQualifiedName(tableSchema, table.getName()));
            sb.append(isView ? " (VIEW)\n" : " (TABLE)\n");
        }

        if (tableCount == 0 && viewCount == 0) {
            return "No tables or views found in '" + dataSourceName + "'"
                + (schemaFilter != null ? " (schema: " + schemaFilter + ")" : "") + ".";
        }

        String header = tableCount + " table(s), " + viewCount + " view(s) in '" + dataSourceName + "'";
        if (schemaFilter != null) header += " (schema: " + schemaFilter + ")";
        return header + ":\n\n" + sb;
    }

    private static boolean matchesSchema(String tableSchema, String schemaFilter) {
        return schemaFilter == null || schemaFilter.equalsIgnoreCase(tableSchema);
    }
}
