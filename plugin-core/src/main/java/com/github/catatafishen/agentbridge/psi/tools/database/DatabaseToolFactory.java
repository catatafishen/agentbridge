package com.github.catatafishen.agentbridge.psi.tools.database;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Creates database tools when the Database plugin is installed and its classes are accessible.
 * <p>
 * Only tools using the public DAS model API ({@code database-plugin-frontend.jar}) are
 * registered here: list sources, list tables, get schema, and execute query (SQLite only
 * via bundled {@code org.xerial:sqlite-jdbc}).
 * <p>
 * <b>Why {@code database_execute_query} is SQLite-only here:</b>
 * Non-SQLite JDBC drivers run in a separate remote JVM managed by IntelliJ's
 * {@code DatabaseConnectionManager} ({@code @ApiStatus.Internal}). That API is not
 * accessible from the main plugin (the classes exist in implementation JARs that cause
 * classloader conflicts when referenced from this module). The Experimental plugin
 * variant overrides {@code database_execute_query} with a version that uses
 * {@code DatabaseConnectionManager} and supports all actively connected databases.
 * <p>
 * <b>Why {@code database_add_source} is absent here:</b>
 * Creating a new data source requires {@code LocalDataSource} and
 * {@code LocalDataSourceManager} which are both {@code @ApiStatus.Internal}.
 * That tool is registered by {@code ExperimentalStartupActivity} in the Experimental
 * plugin variant.
 */
public final class DatabaseToolFactory {

    private static final Logger LOG = Logger.getInstance(DatabaseToolFactory.class);
    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";

    private DatabaseToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        if (!PlatformApiCompat.isPluginInstalled(DATABASE_PLUGIN_ID)) {
            return List.of();
        }
        // The database plugin may be registered but its classes unreachable from this classloader
        // (e.g. in Rider where DasDataSource lives in a module not visible to AgentBridge).
        // Catching NoClassDefFoundError here is the correct fix: the plugin-installed check
        // confirms the plugin exists, but class accessibility requires a runtime probe.
        try {
            return List.of(
                new ListDataSourcesTool(project),
                new ListTablesTool(project),
                new GetSchemaTool(project),
                new ExecuteQueryTool(project)
            );
        } catch (NoClassDefFoundError e) {
            LOG.warn("Database plugin is installed but its classes are not accessible from AgentBridge's classloader; " +
                "database tools will be unavailable. Cause: " + e.getMessage());
            return List.of();
        }
    }
}
