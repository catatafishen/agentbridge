package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.RefactoringTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds all callers of a method with file paths and line numbers.
 */
@SuppressWarnings("java:S112")
public final class GetCallHierarchyTool extends RefactoringTool {

    public GetCallHierarchyTool(Project project, RefactoringTools refactoringTools) {
        super(project, refactoringTools);
    }

    @Override
    public @NotNull String id() {
        return "get_call_hierarchy";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Call Hierarchy";
    }

    @Override
    public @NotNull String description() {
        return "Find all callers of a method with file paths and line numbers";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"symbol", TYPE_STRING, "Method name to find callers for"},
            {"file", TYPE_STRING, "Path to the file containing the method definition"},
            {"line", TYPE_INTEGER, "Line number where the method is defined"}
        }, "symbol", "file", "line");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return refactoringTools.getCallHierarchyWrapper(args);
    }
}
