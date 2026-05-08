package com.github.catatafishen.agentbridge.experimental.psi.tools.database;

import com.github.catatafishen.agentbridge.psi.tools.database.DatabaseTool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.psi.DataSourceManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Adds a new data source to the IntelliJ Database tool window.
 * <p>
 * Uses {@code @ApiStatus.Internal} APIs ({@code LocalDataSource}, {@code DataSourceManager}) —
 * requires plugin-experimental to avoid Marketplace restrictions.
 * <p>
 * After adding, opens the Database tool window so the user can review and connect.
 */
public final class AddDataSourceTool extends DatabaseTool {

    private static final String PARAM_NAME = "name";
    private static final String PARAM_URL = "url";
    private static final String PARAM_USERNAME = "username";
    private static final String PARAM_DRIVER_CLASS = "driver_class";

    /**
     * Maps JDBC URL scheme prefixes to their default driver class names.
     * Covers the most common databases shipped with IntelliJ's bundled drivers.
     */
    private static final Map<String, String> SCHEME_TO_DRIVER = Map.of(
        "jdbc:postgresql:", "org.postgresql.Driver",
        "jdbc:mysql:", "com.mysql.cj.jdbc.Driver",
        "jdbc:mariadb:", "org.mariadb.jdbc.Driver",
        "jdbc:sqlite:", "org.sqlite.JDBC",
        "jdbc:h2:", "org.h2.Driver",
        "jdbc:oracle:", "oracle.jdbc.OracleDriver",
        "jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "jdbc:db2:", "com.ibm.db2.jcc.DB2Driver"
    );

    public AddDataSourceTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "database_add_source";
    }

    @Override
    public @NotNull String displayName() {
        return "Add Data Source";
    }

    @Override
    public @NotNull String description() {
        return "Add a new database data source to IntelliJ's Database tool window. Accepts a JDBC " +
            "connection URL; auto-detects the driver class from the URL scheme (postgresql, mysql, " +
            "mariadb, sqlite, h2, oracle, sqlserver, db2). Provide driver_class explicitly for " +
            "non-standard URLs. Opens the Database tool window on success so the user can review " +
            "and enter credentials. Note: password is not stored — the user will be prompted " +
            "when connecting for the first time.";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_NAME, TYPE_STRING, "Display name for the data source (e.g. 'my-postgres')"),
            Param.required(PARAM_URL, TYPE_STRING, "JDBC connection URL (e.g. 'jdbc:postgresql://localhost:5432/mydb')"),
            Param.optional(PARAM_USERNAME, TYPE_STRING, "Database username"),
            Param.optional(PARAM_DRIVER_CLASS, TYPE_STRING,
                "JDBC driver class name. Auto-detected from URL scheme if omitted.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String name = args.get(PARAM_NAME).getAsString().trim();
        String url = args.get(PARAM_URL).getAsString().trim();
        String username = args.has(PARAM_USERNAME) && !args.get(PARAM_USERNAME).isJsonNull()
            ? args.get(PARAM_USERNAME).getAsString().trim() : null;
        String driverClass = args.has(PARAM_DRIVER_CLASS) && !args.get(PARAM_DRIVER_CLASS).isJsonNull()
            ? args.get(PARAM_DRIVER_CLASS).getAsString().trim() : null;

        if (name.isEmpty()) {
            return "Error: 'name' cannot be empty";
        }
        if (url.isEmpty()) {
            return "Error: 'url' cannot be empty";
        }

        if (driverClass == null) {
            driverClass = detectDriverClass(url);
            if (driverClass == null) {
                return "Error: Could not detect JDBC driver from URL scheme '" + extractScheme(url) + "'. "
                    + "Provide 'driver_class' explicitly. Supported schemes: " + supportedSchemes();
            }
        }

        String resolvedDriverClass = driverClass;
        // Explicit Computable<String> variable avoids ambiguity between runReadAction overloads in javac
        Computable<String> action = () -> addDataSource(name, url, username, resolvedDriverClass);
        String result = ApplicationManager.getApplication().runReadAction(action);

        activateDatabaseToolWindow();
        return result;
    }

    private @NotNull String addDataSource(
        @NotNull String name,
        @NotNull String url,
        @Nullable String username,
        @NotNull String driverClass
    ) {
        DataSourceManager<LocalDataSource> manager = DataSourceManager.byDataSource(project, LocalDataSource.class);
        if (manager == null) {
            return "Error: Could not obtain DataSourceManager for LocalDataSource. "
                + "Ensure the Database plugin is installed and a project is open.";
        }

        LocalDataSource ds = manager.createEmpty();
        ds.setName(name);
        ds.setUrl(url);
        ds.setDriverClass(driverClass);
        if (username != null && !username.isEmpty()) {
            ds.setUsername(username);
        }

        manager.addDataSource(ds);

        return "Data source '" + name + "' added successfully.\n"
            + "  URL:    " + url + "\n"
            + "  Driver: " + driverClass + "\n"
            + (username != null ? "  User:   " + username + "\n" : "")
            + "\nThe Database tool window has been opened. "
            + "Right-click the data source and choose 'Connect' to enter credentials and connect.";
    }

    private @Nullable String detectDriverClass(@NotNull String url) {
        for (Map.Entry<String, String> entry : SCHEME_TO_DRIVER.entrySet()) {
            if (url.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static @NotNull String extractScheme(@NotNull String url) {
        int lastColon = url.lastIndexOf(':');
        return lastColon > 0 ? url.substring(0, lastColon + 1) : url;
    }

    private static @NotNull String supportedSchemes() {
        return String.join(", ", SCHEME_TO_DRIVER.keySet()
            .stream()
            .map(s -> s.replace("jdbc:", "").replace(":", ""))
            .sorted()
            .toList());
    }
}
