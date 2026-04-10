package com.github.catatafishen.agentbridge.psi.tools.database;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets column metadata for a specific table in a data source.
 */
public final class GetSchemaTool extends DatabaseTool {

    private static final String PARAM_DATA_SOURCE = "data_source";
    private static final String PARAM_TABLE = "table";
    private static final String PARAM_SCHEMA = "schema";
    private static final String COLUMN_FORMAT = "  %-30s %-20s %-8s %-5s %-30s%n";

    public GetSchemaTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "database_get_schema";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Table Schema";
    }

    @Override
    public @NotNull String description() {
        return "Get table/view column metadata for a data source or specific table";
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
            Param.required(PARAM_DATA_SOURCE, TYPE_STRING, "Name of the data source"),
            Param.required(PARAM_TABLE, TYPE_STRING, "Table name to get schema for"),
            Param.optional(PARAM_SCHEMA, TYPE_STRING, "Optional: schema name (if table name is ambiguous)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        activateDatabaseToolWindow();
        String dataSourceName = args.get(PARAM_DATA_SOURCE).getAsString();
        String tableName = args.get(PARAM_TABLE).getAsString();
        String schemaFilter = args.has(PARAM_SCHEMA) && !args.get(PARAM_SCHEMA).isJsonNull()
            ? args.get(PARAM_SCHEMA).getAsString() : null;

        DbDataSource dataSource = resolveDataSource(dataSourceName);
        if (dataSource == null) {
            return "Error: data source '" + dataSourceName + "' not found. " + availableDataSourceNames();
        }

        return ReadAction.compute(() -> {
            DasTable matchedTable = findTable(dataSource, tableName, schemaFilter);
            if (matchedTable == null) {
                return "Error: table '" + tableName + "' not found in '" + dataSourceName + "'"
                    + (schemaFilter != null ? " (schema: " + schemaFilter + ")" : "") + ".";
            }
            return formatTableSchema(matchedTable);
        });
    }

    private static @Nullable DasTable findTable(
        @NotNull DbDataSource dataSource,
        @NotNull String tableName,
        @Nullable String schemaFilter) {
        for (var table : DasUtil.getTables(dataSource)) {
            if (!tableName.equalsIgnoreCase(table.getName())) continue;
            if (schemaFilter == null || schemaFilter.equalsIgnoreCase(DasUtil.getSchema(table))) {
                return table;
            }
        }
        return null;
    }

    private static @NotNull String formatTableSchema(@NotNull DasTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(formatQualifiedName(DasUtil.getSchema(table), table.getName()));
        sb.append(" (").append(table.getKind().name()).append(")\n\n");

        sb.append("Columns:\n");
        sb.append(String.format(COLUMN_FORMAT, "Name", "Type", "Nullable", "PK", "Default"));
        sb.append(String.format(COLUMN_FORMAT,
            "─".repeat(30), "─".repeat(20), "─".repeat(8), "─".repeat(5), "─".repeat(30)));

        for (var column : DasUtil.getColumns(table)) {
            boolean isPk = DasUtil.isPrimary(column);
            boolean isNotNull = column.isNotNull();
            String defaultValue = column.getDefault() != null ? column.getDefault() : "";

            sb.append(String.format(COLUMN_FORMAT,
                column.getName(),
                column.getDasType().toDataType().getSpecification(),
                isNotNull ? "NOT NULL" : "NULL",
                isPk ? "PK" : "",
                defaultValue));
        }

        return sb.toString().trim();
    }
}
