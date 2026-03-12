package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ClassOutlineRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets the full API of any class by fully-qualified name, including library and JDK classes.
 */
public final class GetClassOutlineTool extends NavigationTool {

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
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"class_name", TYPE_STRING, "Fully qualified class name (e.g. 'java.util.ArrayList', 'com.intellij.openapi.project.Project')"},
            {"include_inherited", TYPE_BOOLEAN, "If true, include inherited methods and fields from superclasses. Default: false (own members only)"}
        }, "class_name");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ClassOutlineRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) {
        String className = args.has("class_name") ? args.get("class_name").getAsString() : "";
        if (className.isEmpty()) return "Error: 'class_name' parameter is required";
        boolean includeInherited = args.has("include_inherited")
            && args.get("include_inherited").getAsBoolean();

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.ideagentforcopilot.psi.java.CodeNavigationJavaSupport.computeClassOutline(project, className, includeInherited)
        );
    }
}
