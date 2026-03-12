package com.github.catatafishen.ideagentforcopilot.psi.tools.testing;

import com.github.catatafishen.ideagentforcopilot.psi.TestTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists test classes and methods in the project.
 */
@SuppressWarnings("java:S112")
public final class ListTestsTool extends TestingTool {

    public ListTestsTool(Project project, TestTools testTools) {
        super(project, testTools);
    }

    @Override
    public @NotNull String id() {
        return "list_tests";
    }

    @Override
    public @NotNull String displayName() {
        return "List Tests";
    }

    @Override
    public @NotNull String description() {
        return "List test classes and methods in the project";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file_pattern", TYPE_STRING, "Optional glob pattern to filter test files (e.g., '*IntegrationTest*')", ""}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return testTools.listTests(args);
    }
}
