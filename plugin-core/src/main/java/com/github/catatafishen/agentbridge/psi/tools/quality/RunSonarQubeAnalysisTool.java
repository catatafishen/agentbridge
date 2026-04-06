package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.SonarQubeIntegration;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Runs SonarQube for IDE (SonarLint) analysis on the full project or changed files.
 */
public final class RunSonarQubeAnalysisTool extends QualityTool {

    public RunSonarQubeAnalysisTool(Project project) {
        super(project);
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
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_SCOPE, TYPE_STRING, "Analysis scope: 'all' (full project) or 'changed' (VCS changed files only). Default: 'all'"},
            {PARAM_LIMIT, TYPE_INTEGER, "Maximum number of findings to return. Default: 100"},
            {PARAM_OFFSET, TYPE_INTEGER, "Pagination offset. Default: 0"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String scope = args.has(PARAM_SCOPE) ? args.get(PARAM_SCOPE).getAsString() : "all";
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;

        SonarQubeIntegration sonar = new SonarQubeIntegration(project);
        return sonar.runAnalysis(scope, limit, offset);
    }
}
