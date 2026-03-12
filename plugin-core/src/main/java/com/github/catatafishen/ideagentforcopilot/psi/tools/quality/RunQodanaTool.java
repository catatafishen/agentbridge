package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.QodanaAnalyzer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs Qodana static analysis and returns findings.
 */
public final class RunQodanaTool extends QualityTool {

    private final QodanaAnalyzer qodanaAnalyzer;

    public RunQodanaTool(Project project, QodanaAnalyzer qodanaAnalyzer) {
        super(project);
        this.qodanaAnalyzer = qodanaAnalyzer;
    }

    @Override
    public @NotNull String id() {
        return "run_qodana";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Qodana";
    }

    @Override
    public @NotNull String description() {
        return "Run Qodana static analysis and return findings";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_LIMIT, TYPE_INTEGER, "Maximum number of problems to return (default: 100)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return qodanaAnalyzer.runQodana(args);
    }
}
