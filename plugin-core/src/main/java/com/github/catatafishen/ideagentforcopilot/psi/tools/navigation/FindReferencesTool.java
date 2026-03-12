package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.CodeNavigationTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds all usages of a symbol throughout the project.
 */
@SuppressWarnings("java:S112")
public final class FindReferencesTool extends NavigationTool {

    public FindReferencesTool(Project project, CodeNavigationTools navTools) {
        super(project, navTools);
    }

    @Override
    public @NotNull String id() {
        return "find_references";
    }

    @Override
    public @NotNull String displayName() {
        return "Find References";
    }

    @Override
    public @NotNull String description() {
        return "Find all usages of a symbol throughout the project";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"symbol", TYPE_STRING, "The exact symbol name to search for"},
            {"file_pattern", TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.java')", ""}
        }, "symbol");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return navTools.findReferences(args);
    }
}
