package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.ui.renderers.TypeHierarchyRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

/**
 * Shows supertypes and/or subtypes of a class or interface.
 */
@SuppressWarnings("java:S112")
public final class GetTypeHierarchyTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_DIRECTION = "direction";

    public GetTypeHierarchyTool(Project project) {
        super(project);
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
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Fully qualified or simple class/interface name"},
            {PARAM_DIRECTION, TYPE_STRING, "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both"}
        }, PARAM_SYMBOL);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TypeHierarchyRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String direction = args.has(PARAM_DIRECTION) ? args.get(PARAM_DIRECTION).getAsString() : "both";

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.agentbridge.psi.java.RefactoringJavaSupport
                .getTypeHierarchy(project, symbolName, direction)
        );
    }
}
