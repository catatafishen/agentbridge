package com.github.catatafishen.agentbridge.experimental;

import com.github.catatafishen.agentbridge.experimental.psi.tools.database.AddDataSourceTool;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.tools.quality.RunInspectionsTool;
import com.github.catatafishen.agentbridge.services.MacroToolRegistrar;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

public final class ExperimentalStartupActivity implements ProjectActivity {

    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";

    @NotNull
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        MacroToolRegistrar.getInstance(project).syncRegistrations();
        PsiBridgeService.getInstance(project).registerTool(new RunInspectionsTool(project));
        if (PlatformApiCompat.isPluginInstalled(DATABASE_PLUGIN_ID)) {
            PsiBridgeService.getInstance(project).registerTool(new AddDataSourceTool(project));
        }
        return Unit.INSTANCE;
    }
}
