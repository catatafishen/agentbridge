package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.CodeNavigationTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gets the structure of a file — classes, methods, and fields with line numbers.
 */
@SuppressWarnings("java:S112")
public final class GetFileOutlineTool extends NavigationTool {

    public GetFileOutlineTool(Project project, CodeNavigationTools navTools) {
        super(project, navTools);
    }

    @Override
    public @NotNull String id() {
        return "get_file_outline";
    }

    @Override
    public @NotNull String displayName() {
        return "Get File Outline";
    }

    @Override
    public @NotNull String description() {
        return "Get the structure of a file -- classes, methods, and fields with line numbers";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to outline"}
        }, "path");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return navTools.getFileOutline(args);
    }
}
