package com.github.catatafishen.ideagentforcopilot.custommcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity for the custom-MCP plugin variant.
 * Connects to all configured external MCP servers and registers their tools.
 * Runs on a background thread (standard {@link ProjectActivity} behaviour).
 */
public final class CustomMcpStartupActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        CustomMcpRegistrar.getInstance(project).syncRegistrations();
        return Unit.INSTANCE;
    }
}
