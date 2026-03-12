package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets cached editor problems (errors/warnings) for open files.
 */
@SuppressWarnings("java:S112")
public final class GetProblemsTool extends QualityTool {

    public GetProblemsTool(Project project, CodeQualityTools qualityTools) {
        super(project, qualityTools);
    }

    @Override
    public @NotNull String id() {
        return "get_problems";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Problems";
    }

    @Override
    public @NotNull String description() {
        return "Get cached editor problems (errors/warnings) for open files";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", ""}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return qualityTools.getProblems(args);
    }
}
