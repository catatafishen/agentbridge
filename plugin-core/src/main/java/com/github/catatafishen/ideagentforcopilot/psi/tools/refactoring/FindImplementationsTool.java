package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SearchResultRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds all implementations of a class/interface or overrides of a method.
 */
@SuppressWarnings("java:S112")
public final class FindImplementationsTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";

    public FindImplementationsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "find_implementations";
    }

    @Override
    public @NotNull String displayName() {
        return "Find Implementations";
    }

    @Override
    public @NotNull String description() {
        return "Find all implementations of a class/interface or overrides of a method";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Class, interface, or method name to find implementations for"},
            {"file", TYPE_STRING, "Optional: file path for method context (required when searching for method overrides)"},
            {"line", TYPE_INTEGER, "Optional: line number to disambiguate the method (required when searching for method overrides)"}
        }, PARAM_SYMBOL);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String filePath = args.has("file") ? args.get("file").getAsString() : null;
        int line = args.has("line") ? args.get("line").getAsInt() : 0;

        String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.ideagentforcopilot.psi.java.RefactoringJavaSupport
                .findImplementations(project, symbolName, filePath, line)
        );
        return ToolUtils.truncateOutput(result);
    }
}
