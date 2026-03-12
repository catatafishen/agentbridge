package com.github.catatafishen.ideagentforcopilot.psi.tools.testing;

import com.github.catatafishen.ideagentforcopilot.psi.TestTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs tests by class, method, or wildcard pattern via Gradle.
 */
@SuppressWarnings("java:S112")
public final class RunTestsTool extends TestingTool {

    public RunTestsTool(Project project, TestTools testTools) {
        super(project, testTools);
    }

    @Override
    public @NotNull String id() {
        return "run_tests";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Tests";
    }

    @Override
    public @NotNull String description() {
        return "Run tests by class, method, or wildcard pattern via Gradle";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run tests: {target}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"target", TYPE_STRING, "Test target: fully qualified class class.method (e.g., 'MyTest.testFoo'), or pattern with wildcards (e.g., '*Test')"},
            {"module", TYPE_STRING, "Optional Gradle module name (e.g., 'plugin-core')", ""}
        }, "target");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return testTools.runTests(args);
    }
}
