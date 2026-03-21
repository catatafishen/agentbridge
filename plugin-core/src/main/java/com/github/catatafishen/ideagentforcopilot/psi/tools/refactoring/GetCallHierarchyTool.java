package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SearchResultRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds all callers of a method with file paths and line numbers.
 */
@SuppressWarnings("java:S112")
public final class GetCallHierarchyTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";

    public GetCallHierarchyTool(Project project) {
        super(project);
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
    public @NotNull String kind() {
        return "read";
    }
@Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Method name to find callers for"},
            {"file", TYPE_STRING, "Path to the file containing the method definition"},
            {"line", TYPE_INTEGER, "Line number where the method is defined"}
        }, PARAM_SYMBOL, "file", "line");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL) || !args.has("file") || !args.has("line")) {
            return "Error: 'symbol', 'file', and 'line' parameters are required";
        }
        String methodName = args.get(PARAM_SYMBOL).getAsString();
        String filePath = args.get("file").getAsString();
        int line = args.get("line").getAsInt();

        String result = ReadAction.compute(() ->
            com.github.catatafishen.ideagentforcopilot.psi.java.RefactoringJavaSupport
                .getCallHierarchy(project, methodName, filePath, line)
        );
        return ToolUtils.truncateOutput(result);
    }
}
