package com.github.catatafishen.agentbridge.experimental;

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
 * Registers experimental tools (RunInspectionsTool) and syncs macro tool registrations.
 */
public final class ExperimentalStartupActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        MacroToolRegistrar.getInstance(project).syncRegistrations();
        PsiBridgeService.getInstance(project).registerTool(new RunInspectionsTool(project));
        return Unit.INSTANCE;
    }
}
