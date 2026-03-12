package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs SonarQube for IDE (SonarLint) analysis on the full project or changed files.
 */
@SuppressWarnings("java:S112")
public final class RunSonarQubeAnalysisTool extends QualityTool {

    public RunSonarQubeAnalysisTool(Project project, CodeQualityTools qualityTools) {
        super(project, qualityTools);
    }

    @Override
    public @NotNull String id() {
        return "run_sonarqube_analysis";
    }

    @Override
    public @NotNull String displayName() {
        return "Run SonarQube Analysis";
    }

    @Override
    public @NotNull String description() {
        return "Run SonarQube for IDE (SonarLint) analysis on the full project or changed files";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"scope", TYPE_STRING, "Analysis scope: 'all' (full project) or 'changed' (VCS changed files only). Default: 'all'"},
            {"limit", TYPE_INTEGER, "Maximum number of findings to return. Default: 100"},
            {"offset", TYPE_INTEGER, "Pagination offset. Default: 0"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return qualityTools.runSonarQubeAnalysis(args);
    }
}
