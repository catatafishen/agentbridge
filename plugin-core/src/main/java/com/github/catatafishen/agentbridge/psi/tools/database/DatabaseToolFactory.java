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
 * Only read-only tools (list sources, list tables, get schema) are registered here
 * because they use the public DAS model API ({@code database-plugin-frontend.jar}).
 * <p>
 * The {@code database_execute_query} tool requires internal Database plugin APIs
 * ({@code DatabaseConnectionManager}, {@code RemoteConnection}) and is registered
 * separately in the experimental plugin variant.
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
                new GetSchemaTool(project)
            );
        } catch (NoClassDefFoundError e) {
            LOG.warn("Database plugin is installed but its classes are not accessible from AgentBridge's classloader; " +
                "database tools will be unavailable. Cause: " + e.getMessage());
            return List.of();
        }
    }
}
