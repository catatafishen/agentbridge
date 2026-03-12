package com.github.catatafishen.ideagentforcopilot.psi.tools.testing;

import com.github.catatafishen.ideagentforcopilot.psi.TestTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retrieves code coverage data, optionally filtered by file or class.
 */
@SuppressWarnings("java:S112")
public final class GetCoverageTool extends TestingTool {

    public GetCoverageTool(Project project, TestTools testTools) {
        super(project, testTools);
    }

    @Override
    public @NotNull String id() {
        return "get_coverage";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Coverage";
    }

    @Override
    public @NotNull String description() {
        return "Retrieve code coverage data, optionally filtered by file or class";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Optional file or class name to filter coverage results", ""}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return testTools.getCoverage(args);
    }
}
