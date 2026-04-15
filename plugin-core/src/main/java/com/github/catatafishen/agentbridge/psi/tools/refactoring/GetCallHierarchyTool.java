package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

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
        return "Find all callers of a function, method, or named element with file paths and line numbers";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Function, method, or named element to find callers for"),
            Param.required("file", TYPE_STRING, "Path to the file containing the definition"),
            Param.required("line", TYPE_INTEGER, "Line number where the definition is located")
        );
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
        String elementName = args.get(PARAM_SYMBOL).getAsString();
        String filePath = args.get("file").getAsString();
        int line = args.get("line").getAsInt();

        String result = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> com.github.catatafishen.agentbridge.psi.CallHierarchySupport
                .getCallHierarchy(project, elementName, filePath, line)
        );
        return ToolUtils.truncateOutput(result);
    }
}
