package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.RefactoringTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows supertypes and/or subtypes of a class or interface.
 */
@SuppressWarnings("java:S112")
public final class GetTypeHierarchyTool extends RefactoringTool {

    public GetTypeHierarchyTool(Project project, RefactoringTools refactoringTools) {
        super(project, refactoringTools);
    }

    @Override
    public @NotNull String id() {
        return "get_type_hierarchy";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Type Hierarchy";
    }

    @Override
    public @NotNull String description() {
        return "Show supertypes and/or subtypes of a class or interface";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"symbol", TYPE_STRING, "Fully qualified or simple class/interface name"},
            {"direction", TYPE_STRING, "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both"}
        }, "symbol");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return refactoringTools.getTypeHierarchyWrapper(args);
    }
}
