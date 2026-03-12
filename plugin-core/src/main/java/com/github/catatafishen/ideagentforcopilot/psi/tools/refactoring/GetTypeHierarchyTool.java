package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.TypeHierarchyRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows supertypes and/or subtypes of a class or interface.
 */
@SuppressWarnings("java:S112")
public final class GetTypeHierarchyTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";

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
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_SYMBOL, TYPE_STRING, "Fully qualified or simple class/interface name"},
            {"direction", TYPE_STRING, "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both"}
        }, PARAM_SYMBOL);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TypeHierarchyRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String direction = args.has("direction") ? args.get("direction").getAsString() : "both";

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.ideagentforcopilot.psi.java.RefactoringJavaSupport
                .getTypeHierarchy(project, symbolName, direction)
        );
    }
}
