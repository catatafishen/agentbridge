package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ClassOutlineRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class GetClassOutlineTool extends NavigationTool {

    private static final String PARAM_CLASS_NAME = "class_name";
    private static final String PARAM_INCLUDE_INHERITED = "include_inherited";

    public GetClassOutlineTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_class_outline";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Class Outline";
    }

    @Override
    public @NotNull String description() {
        return "Get the full API of any class by fully-qualified name, including library and JDK classes";
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
            {PARAM_CLASS_NAME, TYPE_STRING, "Fully qualified class name (e.g. 'java.util.ArrayList', 'com.intellij.openapi.project.Project')"},
            {PARAM_INCLUDE_INHERITED, TYPE_BOOLEAN, "If true, include inherited methods and fields from superclasses. Default: false (own members only)"}
        }, PARAM_CLASS_NAME);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ClassOutlineRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String className = args.has(PARAM_CLASS_NAME) ? args.get(PARAM_CLASS_NAME).getAsString() : "";
        if (className.isEmpty()) return "Error: 'class_name' parameter is required";
        boolean includeInherited = args.has(PARAM_INCLUDE_INHERITED)
            && args.get(PARAM_INCLUDE_INHERITED).getAsBoolean();

        return ReadAction.compute(() ->
            com.github.catatafishen.ideagentforcopilot.psi.java.CodeNavigationJavaSupport.computeClassOutline(project, className, includeInherited)
        );
    }
}
