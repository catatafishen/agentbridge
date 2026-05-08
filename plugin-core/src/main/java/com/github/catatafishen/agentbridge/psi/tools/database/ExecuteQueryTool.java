package com.github.catatafishen.agentbridge.psi.tools.database;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.database.psi.DbDataSource;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes a SQL query against a named data source and returns results as a text table.
 * <p>
 * Uses direct JDBC via {@code DriverManager.getConnection(url)} with the bundled
 * {@code org.xerial:sqlite-jdbc} driver. This works for SQLite data sources
 * (including the conversation database at {@code .agentbridge/conversation.db})
 * without requiring an active IntelliJ connection.
 * <p>
 * <b>Why SQLite only:</b> IntelliJ runs non-SQLite JDBC drivers in a separate remote JVM
 * via its {@code DatabaseConnectionManager} (marked {@code @ApiStatus.Internal}). That API
 * is not accessible from the main plugin. The Experimental plugin variant overrides this
 * tool with a version that uses {@code DatabaseConnectionManager} and supports any
 * actively connected database.
 */
public final class ExecuteQueryTool extends DatabaseTool {

    private static final String PARAM_DATA_SOURCE = "data_source";
    private static final String PARAM_QUERY = "query";
    private static final String PARAM_MAX_ROWS = "max_rows";
    private static final String ERROR_DATA_SOURCE_PREFIX = "Error: data source '";
    private static final int DEFAULT_MAX_ROWS = 100;
    private static final int MAX_CELL_WIDTH = 50;

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
        return """
            Execute a SQL query against a named data source and return results as a text table. \
            Opens the Database tool window so the human can follow along. \
            Supports SQLite data sources directly via the bundled JDBC driver — \
            no active IntelliJ connection required. \
            For non-SQLite databases, connect to the data source in the Database tool window \
            first (or use the Experimental plugin variant which supports all connected databases). \
            Use database_list_sources to find available data source names.""";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.EXECUTE;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_DATA_SOURCE, TYPE_STRING,
                "Name of the data source to execute the query against"),
            Param.required(PARAM_QUERY, TYPE_STRING, "SQL query to execute"),
            Param.optional(PARAM_MAX_ROWS, TYPE_INTEGER,
                "Maximum number of rows to return (default: 100, 0 = no limit)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        activateDatabaseToolWindow();
        String dataSourceName = args.get(PARAM_DATA_SOURCE).getAsString();
        String query = args.get(PARAM_QUERY).getAsString().trim();
        int maxRows = args.has(PARAM_MAX_ROWS) && !args.get(PARAM_MAX_ROWS).isJsonNull()
            ? args.get(PARAM_MAX_ROWS).getAsInt() : DEFAULT_MAX_ROWS;

        if (query.isEmpty()) {
            return "Error: 'query' parameter cannot be empty";
        }

        DbDataSource dataSource = resolveDataSource(dataSourceName);
        if (dataSource == null) {
            return ERROR_DATA_SOURCE_PREFIX + dataSourceName + "' not found. " + availableDataSourceNames();
        }

        // noinspection RedundantCast — cast is needed to disambiguate Computable vs ThrowableComputable overloads
        String jdbcUrl = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> resolveJdbcUrl(dataSource));

        if (jdbcUrl.isEmpty()) {
            return ERROR_DATA_SOURCE_PREFIX + dataSourceName + "' has no JDBC URL configured.";
        }

        if (!jdbcUrl.startsWith("jdbc:sqlite:")) {
            String scheme = jdbcUrl.contains(":") ? jdbcUrl.split(":")[1] : "unknown";
            return ERROR_DATA_SOURCE_PREFIX + dataSourceName + "' uses '" + scheme + "' driver. "
                + "Direct JDBC execution is only supported for SQLite in this plugin variant. "
                + "Connect to the data source in IntelliJ's Database tool window and use the "
                + "Experimental plugin variant for full multi-database query support.";
        }

        return executeViaJdbc(jdbcUrl, query, dataSourceName, maxRows);
    }

    /**
     * Extracts the JDBC URL from a data source. Returns empty string if unavailable.
     * Must be called inside a read action.
     */
    private static @NotNull String resolveJdbcUrl(@NotNull DbDataSource dataSource) {
        var config = dataSource.getConnectionConfig();
        if (config == null) return "";
        String url = config.getUrl();
        return url != null ? url : "";
    }

    private static @NotNull String executeViaJdbc(
        @NotNull String jdbcUrl,
        @NotNull String query,
        @NotNull String dataSourceName,
        int maxRows) {
        try (Connection conn = java.sql.DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            if (maxRows > 0) {
                stmt.setMaxRows(maxRows);
            }
            boolean hasResultSet = stmt.execute(query);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    return formatResultSet(rs, dataSourceName, maxRows);
                }
            }
            return "Query executed successfully. " + stmt.getUpdateCount() + " row(s) affected.";
        } catch (SQLException e) {
            return "Error executing query: " + e.getMessage();
        }
    }

    private static @NotNull String formatResultSet(
        @NotNull ResultSet rs,
        @NotNull String dataSourceName,
        int maxRows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        String[] colNames = new String[colCount];
        int[] colWidths = new int[colCount];
        for (int i = 0; i < colCount; i++) {
            colNames[i] = meta.getColumnLabel(i + 1);
            colWidths[i] = colNames[i].length();
        }

        List<String[]> rows = collectRows(rs, colCount, colWidths);
        return buildTable(colNames, colWidths, rows, dataSourceName, maxRows);
    }

    private static @NotNull List<String[]> collectRows(
        @NotNull ResultSet rs, int colCount, int[] colWidths) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        while (rs.next()) {
            String[] row = new String[colCount];
            for (int i = 0; i < colCount; i++) {
                String val = rs.getString(i + 1);
                row[i] = val != null ? val : "NULL";
                colWidths[i] = Math.clamp(row[i].length(), colWidths[i], MAX_CELL_WIDTH);
            }
            rows.add(row);
        }
        return rows;
    }

    private static @NotNull String buildTable(
        String[] colNames, int[] colWidths, List<String[]> rows,
        String dataSourceName, int maxRows) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, rows.size(), colNames.length, maxRows, dataSourceName);
        appendColumnHeaders(sb, colNames, colWidths);
        appendSeparatorLine(sb, colWidths);
        appendRows(sb, rows, colWidths);
        return sb.toString().trim();
    }

    private static void appendHeader(
        StringBuilder sb, int rowCount, int colCount, int maxRows, String dataSourceName) {
        sb.append(rowCount).append(" row(s), ").append(colCount).append(" column(s)");
        if (maxRows > 0 && rowCount >= maxRows) {
            sb.append(" (limited to ").append(maxRows).append(")");
        }
        sb.append(" from '").append(dataSourceName).append("':\n\n");
    }

    private static void appendColumnHeaders(StringBuilder sb, String[] colNames, int[] colWidths) {
        for (int i = 0; i < colNames.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(padRight(colNames[i], colWidths[i]));
        }
        sb.append("\n");
    }

    private static void appendSeparatorLine(StringBuilder sb, int[] colWidths) {
        for (int i = 0; i < colWidths.length; i++) {
            if (i > 0) sb.append("-+-");
            sb.repeat("-", colWidths[i]);
        }
        sb.append("\n");
    }

    private static void appendRows(StringBuilder sb, List<String[]> rows, int[] colWidths) {
        for (String[] row : rows) {
            appendRow(sb, row, colWidths);
        }
    }

    private static void appendRow(StringBuilder sb, String[] row, int[] colWidths) {
        for (int i = 0; i < row.length; i++) {
            if (i > 0) sb.append(" | ");
            String val = row[i].length() > MAX_CELL_WIDTH
                ? row[i].substring(0, MAX_CELL_WIDTH - 3) + "..."
                : row[i];
            sb.append(padRight(val, colWidths[i]));
        }
        sb.append("\n");
    }

    private static @NotNull String padRight(@NotNull String value, int width) {
        if (value.length() >= width) return value;
        return value + " ".repeat(width - value.length());
    }
}
