package com.github.catatafishen.agentbridge.psi.tools.database.proxy;

import com.github.catatafishen.agentbridge.psi.tools.database.DatabaseTool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Thin proxy for a single JetBrains built-in MCP tool.
 * <p>
 * Delegates {@link #execute(JsonObject)} to {@link JetBrainsMcpProxy#callTool} which calls
 * the JetBrains tool in-process (no HTTP round-trip). Activates the Database tool window
 * via follow-agent before delegating so the user can monitor query results.
 * <p>
 * Instances are created by the static factory methods; one instance per JetBrains tool.
 */
public final class JetBrainsProxyTool extends DatabaseTool {

    private static final String DESC_CONNECTION_ID =
        "Unique connection ID from list_database_connections";
    private static final String PARAM_CONNECTION_ID = "connectionId";
    private static final String PARAM_DATABASE_NAME = "databaseName";
    private static final String PARAM_SCHEMA_NAME = "schemaName";

    private final String toolId;
    private final String displayName;
    private final String description;
    private final ToolDefinition.Kind kind;
    private final JsonObject inputSchema;
    private final boolean readOnly;

    private JetBrainsProxyTool(
        Project project,
        String toolId,
        String displayName,
        String description,
        ToolDefinition.Kind kind,
        boolean readOnly,
        JsonObject inputSchema
    ) {
        super(project);
        this.toolId = toolId;
        this.displayName = displayName;
        this.description = description;
        this.kind = kind;
        this.readOnly = readOnly;
        this.inputSchema = inputSchema;
    }

    @Override
    public @NotNull String id() {
        return toolId;
    }

    @Override
    public @NotNull String displayName() {
        return displayName;
    }

    @Override
    public @NotNull String description() {
        return description;
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return kind;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return inputSchema;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        activateDatabaseToolWindow();
        try {
            return JetBrainsMcpProxy.callTool(project, toolId, args.toString());
        } catch (ReflectiveOperationException e) {
            return "Error: JetBrains MCP proxy failed for '" + toolId + "': " + e.getMessage();
        }
    }

    // ── Factory methods ─────────────────────────────────────────────────────

    private static JetBrainsProxyTool listConnections(Project project) {
        return new JetBrainsProxyTool(project,
            "list_database_connections",
            "List Database Connections",
            "Retrieves all configured database connections (data sources) in the project. " +
                "For each connection returns its unique ID, name, DBMS, and driver name. " +
                "Use the returned ID with other database tools. " +
                "Requires the Database Tools and SQL plugin and the JetBrains AI Assistant plugin.",
            ToolDefinition.Kind.READ, true,
            schema());
    }

    private static JetBrainsProxyTool testConnection(Project project) {
        return new JetBrainsProxyTool(project,
            "test_database_connection",
            "Test Database Connection",
            "Tests a database connection and returns diagnostic info: whether the connection " +
                "is problematic (yes/no/unknown), DBMS type and version, JDBC driver info, and " +
                "a summary of the connection attempt. Use list_database_connections to get the ID.",
            ToolDefinition.Kind.READ, true,
            schema(Param.required("id", TYPE_STRING, DESC_CONNECTION_ID)));
    }

    private static JetBrainsProxyTool listSchemas(Project project) {
        return new JetBrainsProxyTool(project,
            "list_database_schemas",
            "List Database Schemas",
            "Retrieves a list of database schemas in the specified connection. " +
                "For each schema returns its name and the database name (empty if not applicable). " +
                "Set selectedOnly to true to list only schemas visible in the database tree.",
            ToolDefinition.Kind.READ, true,
            schema(
                Param.required(PARAM_CONNECTION_ID, TYPE_STRING, DESC_CONNECTION_ID),
                Param.optional("selectedOnly", TYPE_BOOLEAN,
                    "True to list only schemas selected in the database tree; false (default) to list all")));
    }

    private static JetBrainsProxyTool listSchemaObjectKinds(Project project) {
        return new JetBrainsProxyTool(project,
            "list_schema_object_kinds",
            "List Schema Object Kinds",
            "Retrieves supported schema object kinds for the given connection (e.g. TABLE, VIEW, " +
                "PROCEDURE). Returns kind codes and human-readable names. Use the code with " +
                "list_schema_objects to filter by type.",
            ToolDefinition.Kind.READ, true,
            schema(Param.required(PARAM_CONNECTION_ID, TYPE_STRING, DESC_CONNECTION_ID)));
    }

    private static JetBrainsProxyTool listSchemaObjects(Project project) {
        return new JetBrainsProxyTool(project,
            "list_schema_objects",
            "List Schema Objects",
            "Retrieves database objects (tables, views, procedures, etc.) within a schema. " +
                "Returns object names and their kinds. Use list_schema_object_kinds to discover " +
                "valid kind codes. Pass null for kind to retrieve all objects.",
            ToolDefinition.Kind.READ, true,
            schema(
                Param.required(PARAM_CONNECTION_ID, TYPE_STRING, DESC_CONNECTION_ID),
                Param.required(PARAM_SCHEMA_NAME, TYPE_STRING, "Name of the schema"),
                Param.required(PARAM_DATABASE_NAME, TYPE_STRING,
                    "Name of the database the schema belongs to. Empty string if the DBMS has no databases."),
                Param.optional("kind", TYPE_STRING,
                    "Object kind code (from list_schema_object_kinds) to filter by; omit to retrieve all")));
    }

    private static JetBrainsProxyTool executeQuery(Project project) {
        return new JetBrainsProxyTool(project,
            "execute_sql_query",
            "Execute SQL Query",
            "Executes a SQL query against the given database connection. " +
                "Reports execution status (success or error). For SELECT queries, returns data " +
                "in CSV format. For INSERT/UPDATE/DELETE, returns row-count info. " +
                "Use list_database_connections to get the connectionId. " +
                "Warning: write operations are not guarded — use a read-only database user for safety.",
            ToolDefinition.Kind.EDIT, false,
            schema(
                Param.required(PARAM_CONNECTION_ID, TYPE_STRING, DESC_CONNECTION_ID),
                Param.required("queryText", TYPE_STRING, "SQL query to execute")));
    }

    private static JetBrainsProxyTool previewTableData(Project project) {
        return new JetBrainsProxyTool(project,
            "preview_table_data",
            "Preview Table Data",
            "Returns preview data for a table, view, or other table-like object as CSV. " +
                "Use list_schema_objects to discover table names.",
            ToolDefinition.Kind.READ, true,
            schema(
                Param.required(PARAM_CONNECTION_ID, TYPE_STRING, DESC_CONNECTION_ID),
                Param.required(PARAM_SCHEMA_NAME, TYPE_STRING, "Name of the schema"),
                Param.required(PARAM_DATABASE_NAME, TYPE_STRING,
                    "Name of the database the schema belongs to. Empty string if not applicable."),
                Param.required("tableName", TYPE_STRING, "Name of the table or view"),
                Param.optional("maxRowCount", TYPE_INTEGER, "Maximum rows to return (default: 100)")));
    }

    private static JetBrainsProxyTool cancelQuery(Project project) {
        return new JetBrainsProxyTool(project,
            "cancel_sql_query",
            "Cancel SQL Query",
            "Cancels a running database query by its session ID. " +
                "Use list_recent_sql_queries to find the session ID of the running query.",
            ToolDefinition.Kind.EDIT, false,
            schema(Param.required("sessionId", TYPE_STRING, "Query session ID from list_recent_sql_queries")));
    }

    private static JetBrainsProxyTool listRecentQueries(Project project) {
        return new JetBrainsProxyTool(project,
            "list_recent_sql_queries",
            "List Recent SQL Queries",
            "Retrieves recent and currently running queries for the given connection. " +
                "Returns session ID, elapsed time (ms), state (running/finished/cancelled), " +
                "completion status, and query text. Use cancel_sql_query with the session ID " +
                "to cancel a running query. Requires a paid JetBrains AI subscription.",
            ToolDefinition.Kind.READ, true,
            schema(Param.required(PARAM_CONNECTION_ID, TYPE_STRING, DESC_CONNECTION_ID)));
    }

    /**
     * Creates proxy tools for all JetBrains MCP tools that are actually registered in the
     * running IDE. Tools not present in the live MCP server (e.g. because the AI Assistant
     * plugin is not installed) are silently omitted.
     */
    public static List<JetBrainsProxyTool> createAll(Project project) {
        List<String> registeredNames = JetBrainsMcpProxy.getRegisteredToolNames();
        Map<String, JetBrainsProxyTool> candidates = Map.ofEntries(
            Map.entry("list_database_connections", listConnections(project)),
            Map.entry("test_database_connection", testConnection(project)),
            Map.entry("list_database_schemas", listSchemas(project)),
            Map.entry("list_schema_object_kinds", listSchemaObjectKinds(project)),
            Map.entry("list_schema_objects", listSchemaObjects(project)),
            Map.entry("execute_sql_query", executeQuery(project)),
            Map.entry("preview_table_data", previewTableData(project)),
            Map.entry("cancel_sql_query", cancelQuery(project)),
            Map.entry("list_recent_sql_queries", listRecentQueries(project))
        );
        return registeredNames.stream()
            .filter(candidates::containsKey)
            .map(candidates::get)
            .toList();
    }
}
