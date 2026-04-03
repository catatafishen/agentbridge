package com.github.catatafishen.ideagentforcopilot.psi.tools.database;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
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
        return schema(new Object[][]{
            {"data_source", TYPE_STRING, "Name of the data source"},
            {"table", TYPE_STRING, "Table name to get schema for"},
            {"schema", TYPE_STRING, "Optional: schema name (if table name is ambiguous)"},
        }, "data_source", "table");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String dataSourceName = args.get("data_source").getAsString();
        String tableName = args.get("table").getAsString();
        String schemaFilter = args.has("schema") && !args.get("schema").isJsonNull()
            ? args.get("schema").getAsString() : null;

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
        var tables = DasUtil.getTables(dataSource);
        for (var table : tables) {
            if (!tableName.equalsIgnoreCase(table.getName())) continue;
            if (schemaFilter != null) {
                String tableSchema = DasUtil.getSchema(table);
                if (!schemaFilter.equalsIgnoreCase(tableSchema)) continue;
            }
            return table;
        }
        return null;
    }

    private static @NotNull String formatTableSchema(@NotNull DasTable table) {
        String tableSchema = DasUtil.getSchema(table);
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ");
        if (tableSchema != null && !tableSchema.isEmpty()) {
            sb.append(tableSchema).append(".");
        }
        sb.append(table.getName());
        sb.append(" (").append(table.getKind().name()).append(")\n\n");

        sb.append("Columns:\n");
        sb.append(String.format("  %-30s %-20s %-8s %-5s %-30s%n",
            "Name", "Type", "Nullable", "PK", "Default"));
        sb.append(String.format("  %-30s %-20s %-8s %-5s %-30s%n",
            "─".repeat(30), "─".repeat(20), "─".repeat(8), "─".repeat(5), "─".repeat(30)));

        var columns = DasUtil.getColumns(table);
        for (var column : columns) {
            boolean isPk = DasUtil.isPrimary(column);
            boolean isNotNull = column.isNotNull();
            String defaultValue = column.getDefault() != null ? column.getDefault() : "";

            sb.append(String.format("  %-30s %-20s %-8s %-5s %-30s%n",
                column.getName(),
                column.getDasType().toDataType().getSpecification(),
                isNotNull ? "NOT NULL" : "NULL",
                isPk ? "PK" : "",
                defaultValue));
        }

        return sb.toString().trim();
    }
}
