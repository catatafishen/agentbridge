package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.RefactoringTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Navigates to the declaration of a symbol at a given file and line.
 */
@SuppressWarnings("java:S112")
public final class GoToDeclarationTool extends RefactoringTool {

    public GoToDeclarationTool(Project project, RefactoringTools refactoringTools) {
        super(project, refactoringTools);
    }

    @Override
    public @NotNull String id() {
        return "go_to_declaration";
    }

    @Override
    public @NotNull String displayName() {
        return "Go to Declaration";
    }

    @Override
    public @NotNull String description() {
        return "Navigate to the declaration of a symbol at a given file and line";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the file containing the symbol usage"},
            {"symbol", TYPE_STRING, "Name of the symbol to look up"},
            {"line", TYPE_INTEGER, "Line number where the symbol appears"}
        }, "file", "symbol", "line");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return refactoringTools.goToDeclaration(args);
    }
}
