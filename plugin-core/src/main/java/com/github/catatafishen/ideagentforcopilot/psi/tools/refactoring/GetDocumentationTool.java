package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.RefactoringTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets Javadoc or KDoc for a symbol by fully-qualified name.
 */
@SuppressWarnings("java:S112")
public final class GetDocumentationTool extends RefactoringTool {

    public GetDocumentationTool(Project project, RefactoringTools refactoringTools) {
        super(project, refactoringTools);
    }

    @Override
    public @NotNull String id() {
        return "get_documentation";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Documentation";
    }

    @Override
    public @NotNull String description() {
        return "Get Javadoc or KDoc for a symbol by fully-qualified name";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"symbol", TYPE_STRING, "Fully qualified symbol name (e.g. java.util.List)"}
        }, "symbol");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return refactoringTools.getDocumentation(args);
    }
}
