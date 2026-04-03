package com.github.catatafishen.ideagentforcopilot.psi.tools.database;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.database.dataSource.DatabaseConnection;
import com.intellij.database.dataSource.DatabaseConnectionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.remote.jdbc.RemoteResultSet;
import com.intellij.database.remote.jdbc.RemoteResultSetMetaData;
import com.intellij.database.remote.jdbc.RemoteStatement;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a SQL query against a named data source and returns results as a text table.
 */
public final class ExecuteQueryTool extends DatabaseTool {

    private static final int DEFAULT_MAX_ROWS = 100;

    public ExecuteQueryTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "database_execute_query";
    }

    @Override
    public @NotNull String displayName() {
        return "Execute SQL Query";
    }

    @Override
    public @NotNull String description() {
        return "Execute a SQL query against a named data source; return results as text table";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.EXECUTE;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"data_source", TYPE_STRING, "Name of the data source to execute the query against"},
            {"query", TYPE_STRING, "SQL query to execute"},
            {"max_rows", TYPE_INTEGER, "Maximum number of rows to return (default: 100, 0 = no limit)"},
        }, "data_source", "query");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String dataSourceName = args.get("data_source").getAsString();
        String query = args.get("query").getAsString().trim();
        int maxRows = args.has("max_rows") && !args.get("max_rows").isJsonNull()
            ? args.get("max_rows").getAsInt() : DEFAULT_MAX_ROWS;

        if (query.isEmpty()) {
            return "Error: 'query' parameter cannot be empty";
        }

        DbDataSource dataSource = resolveDataSource(dataSourceName);
        if (dataSource == null) {
            return "Error: data source '" + dataSourceName + "' not found. " + availableDataSourceNames();
        }

        var delegate = ReadAction.compute(dataSource::getDelegate);
        if (!(delegate instanceof LocalDataSource localDataSource)) {
            return "Error: data source '" + dataSourceName + "' is not a local data source";
        }

        DatabaseConnection connection = findActiveConnection(localDataSource);
        if (connection == null) {
            return "Error: no active connection for '" + dataSourceName + "'. "
                + "Please connect to the data source first via the Database tool window.";
        }

        try {
            var remoteConnection = connection.getRemoteConnection();
            RemoteStatement stmt = remoteConnection.createStatement();
            try {
                if (maxRows > 0) {
                    stmt.setMaxRows(maxRows);
                }
                boolean hasResultSet = stmt.execute(query);
                if (hasResultSet) {
                    RemoteResultSet rs = stmt.getResultSet();
                    try {
                        return formatResultSet(rs, dataSourceName, maxRows);
                    } finally {
                        rs.close();
                    }
                } else {
                    int updateCount = stmt.getUpdateCount();
                    return "Query executed successfully. " + updateCount + " row(s) affected.";
                }
            } finally {
                stmt.close();
            }
        } catch (Exception e) {
            return "Error executing query: " + e.getMessage();
        }
    }

    private static DatabaseConnection findActiveConnection(LocalDataSource localDataSource) {
        var allConnections = DatabaseConnectionManager.getInstance().getActiveConnections();
        for (var conn : allConnections) {
            if (localDataSource.equals(conn.getConnectionPoint().getDataSource())) {
                return conn;
            }
        }
        return null;
    }

    private static @NotNull String formatResultSet(
            @NotNull RemoteResultSet rs,
            @NotNull String dataSourceName,
            int maxRows) throws Exception {
        RemoteResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        String[] colNames = new String[colCount];
        int[] colWidths = new int[colCount];
        for (int i = 0; i < colCount; i++) {
            colNames[i] = meta.getColumnLabel(i + 1);
            colWidths[i] = colNames[i].length();
        }

        List<String[]> rows = new ArrayList<>();
        while (rs.next()) {
            String[] row = new String[colCount];
            for (int i = 0; i < colCount; i++) {
                String val = rs.getString(i + 1);
                row[i] = val != null ? val : "NULL";
                colWidths[i] = Math.max(colWidths[i], Math.min(row[i].length(), 50));
            }
            rows.add(row);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" row(s), ").append(colCount).append(" column(s)");
        if (maxRows > 0 && rows.size() >= maxRows) {
            sb.append(" (limited to ").append(maxRows).append(")");
        }
        sb.append(" from '").append(dataSourceName).append("':\n\n");

        for (int i = 0; i < colCount; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(String.format("%-" + colWidths[i] + "s", colNames[i]));
        }
        sb.append("\n");

        for (int i = 0; i < colCount; i++) {
            if (i > 0) sb.append("-+-");
            sb.append("-".repeat(colWidths[i]));
        }
        sb.append("\n");

        for (String[] row : rows) {
            for (int i = 0; i < colCount; i++) {
                if (i > 0) sb.append(" | ");
                String val = row[i].length() > 50 ? row[i].substring(0, 47) + "..." : row[i];
                sb.append(String.format("%-" + colWidths[i] + "s", val));
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }
}
