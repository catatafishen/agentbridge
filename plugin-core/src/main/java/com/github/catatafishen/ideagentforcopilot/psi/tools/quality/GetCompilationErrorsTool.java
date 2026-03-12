package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fast compilation error check using cached daemon results.
 */
@SuppressWarnings("java:S112")
public final class GetCompilationErrorsTool extends QualityTool {

    public GetCompilationErrorsTool(Project project, CodeQualityTools qualityTools) {
        super(project, qualityTools);
    }

    @Override
    public @NotNull String id() {
        return "get_compilation_errors";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Compilation Errors";
    }

    @Override
    public @NotNull String description() {
        return "Fast compilation error check using cached daemon results";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Optional: specific file to check. If omitted, checks all open source files", ""}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return qualityTools.getCompilationErrors(args);
    }
}
