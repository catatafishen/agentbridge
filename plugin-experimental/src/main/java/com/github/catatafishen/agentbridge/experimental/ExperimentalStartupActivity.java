package com.github.catatafishen.agentbridge.experimental;

import com.github.catatafishen.agentbridge.experimental.psi.tools.database.AddDataSourceTool;
import com.github.catatafishen.agentbridge.experimental.psi.tools.database.ExecuteQueryTool;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.tools.quality.RunInspectionsTool;
import com.github.catatafishen.agentbridge.services.MacroToolRegistrar;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity for the experimental plugin variant.
 * <p>
 * Registers tools that require {@code @ApiStatus.Internal} or impl-JAR APIs:
 * <ul>
 *   <li>{@code RunInspectionsTool} — uses internal inspection runner APIs</li>
 *   <li>{@code ExecuteQueryTool} — overwrites the main plugin's SQLite-only version with
 *       a full version using {@code DatabaseConnectionManager} (supports all databases)</li>
 *   <li>{@code AddDataSourceTool} — uses {@code LocalDataSource} + {@code DataSourceManager}
 *       (both {@code @ApiStatus.Internal}) to create new data sources programmatically</li>
 * </ul>
 */
public final class ExperimentalStartupActivity implements ProjectActivity {

    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        MacroToolRegistrar.getInstance(project).syncRegistrations();
        PsiBridgeService.getInstance(project).registerTool(new RunInspectionsTool(project));
        if (PlatformApiCompat.isPluginInstalled(DATABASE_PLUGIN_ID)) {
            PsiBridgeService.getInstance(project).registerTool(new ExecuteQueryTool(project));
            PsiBridgeService.getInstance(project).registerTool(new AddDataSourceTool(project));
        }
        return Unit.INSTANCE;
    }
}
