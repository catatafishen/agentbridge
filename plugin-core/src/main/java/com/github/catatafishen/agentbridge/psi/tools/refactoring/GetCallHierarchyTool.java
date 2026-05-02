package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.CallHierarchySupport;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
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
    private static final String PARAM_DEPTH = "depth";

    public GetCallHierarchyTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_call_hierarchy";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Get Call Hierarchy";
    }

    @Override
    public @NotNull String description() {
        return "Find all callers of a function, method, or named element with file paths and line numbers. "
            + "Accepts a fully-qualified name (e.g. 'com.example.MyClass.myMethod') as the 'symbol' parameter "
            + "— when an FQN is provided, 'file' and 'line' are optional. "
            + "Use 'depth' to traverse multiple levels (e.g., depth=2 finds callers of callers).";
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
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Function, method, or named element to find callers for. "
                + "Can be a simple name (requires file+line) or a fully-qualified name "
                + "(e.g. 'com.example.MyClass.myMethod') to resolve without file+line"),
            Param.optional("file", TYPE_STRING, "Path to the file containing the definition. "
                + "Optional when 'symbol' is a fully-qualified name"),
            Param.optional("line", TYPE_INTEGER, "Line number where the definition is located. "
                + "Optional when 'symbol' is a fully-qualified name"),
            Param.optional(PARAM_DEPTH, TYPE_INTEGER, "How many levels of callers to traverse (default: 1, max: 5). "
                + "depth=1 finds direct callers, depth=2 also finds callers of those callers, etc.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL)) {
            return "Error: 'symbol' parameter is required";
        }
        String elementName = args.get(PARAM_SYMBOL).getAsString();
        String filePath = args.has("file") ? args.get("file").getAsString() : null;
        int line = args.has("line") ? args.get("line").getAsInt() : -1;
        int depth = args.has(PARAM_DEPTH) ? Math.min(args.get(PARAM_DEPTH).getAsInt(), 5) : 1;
        if (depth < 1) depth = 1;

        // FQN mode: resolve by fully-qualified name
        if (FqnResolver.looksLikeFqn(elementName) && filePath == null) {
            int finalDepth = depth;
            String result = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () ->
                    CallHierarchySupport.getCallHierarchyByFqn(project, elementName, finalDepth));
            return ToolUtils.truncateOutput(result);
        }

        // Standard mode: resolve from file:line
        if (filePath == null || line < 1) {
            return "Error: 'file' and 'line' are required when 'symbol' is not a fully-qualified name. "
                + "Use a fully-qualified name (e.g. 'com.example.MyClass.myMethod') to resolve without file+line.";
        }

        int finalDepth = depth;
        String result = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> CallHierarchySupport
                .getCallHierarchy(project, elementName, filePath, line, finalDepth)
        );
        return ToolUtils.truncateOutput(result);
    }
}
