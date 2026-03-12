package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.RefactoringTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Renames, extracts method, inlines, or safe-deletes a symbol using IntelliJ's refactoring engine.
 */
@SuppressWarnings("java:S112")
public final class RefactorTool extends RefactoringTool {

    public RefactorTool(Project project, RefactoringTools refactoringTools) {
        super(project, refactoringTools);
    }

    @Override
    public @NotNull String id() {
        return "refactor";
    }

    @Override
    public @NotNull String displayName() {
        return "Refactor";
    }

    @Override
    public @NotNull String description() {
        return "Rename, extract method, inline, or safe-delete a symbol using IntelliJ's refactoring engine";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{operation} {symbol}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"operation", TYPE_STRING, "Refactoring type: 'rename', 'extract_method', 'inline', or 'safe_delete'"},
            {"file", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"},
            {"symbol", TYPE_STRING, "Name of the symbol to refactor (class, method, field, or variable)"},
            {"line", TYPE_INTEGER, "Line number to disambiguate if multiple symbols share the same name"},
            {"new_name", TYPE_STRING, "New name for 'rename' operation. Required when operation is 'rename'"}
        }, "operation", "file", "symbol");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return refactoringTools.refactor(args);
    }
}
