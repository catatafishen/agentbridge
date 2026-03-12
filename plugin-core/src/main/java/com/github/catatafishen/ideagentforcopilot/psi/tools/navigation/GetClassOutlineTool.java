package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.CodeNavigationTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets the full API of any class by fully-qualified name, including library and JDK classes.
 */
@SuppressWarnings("java:S112")
public final class GetClassOutlineTool extends NavigationTool {

    public GetClassOutlineTool(Project project, CodeNavigationTools navTools) {
        super(project, navTools);
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
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return navTools.getClassOutline(args);
    }
}
