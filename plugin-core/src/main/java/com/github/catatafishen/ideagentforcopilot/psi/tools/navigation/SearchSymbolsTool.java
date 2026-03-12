package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.CodeNavigationTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Searches for classes, methods, or fields by name using IntelliJ's symbol index.
 */
@SuppressWarnings("java:S112")
public final class SearchSymbolsTool extends NavigationTool {

    public SearchSymbolsTool(Project project, CodeNavigationTools navTools) {
        super(project, navTools);
    }

    @Override
    public @NotNull String id() {
        return "search_symbols";
    }

    @Override
    public @NotNull String displayName() {
        return "Search Symbols";
    }

    @Override
    public @NotNull String description() {
        return "Search for classes, methods, or fields by name using IntelliJ's symbol index";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"query", TYPE_STRING, "Symbol name to search for, or '*' to list all symbols in the project"},
            {"type", TYPE_STRING, "Optional: filter by type (class, method, field, property). Default: all types", ""}
        }, "query");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return navTools.searchSymbols(args);
    }
}
