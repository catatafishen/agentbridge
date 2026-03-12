package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.CodeNavigationTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Searches for text or regex patterns across project files using IntelliJ's editor buffers.
 */
@SuppressWarnings("java:S112")
public final class SearchTextTool extends NavigationTool {

    public SearchTextTool(Project project, CodeNavigationTools navTools) {
        super(project, navTools);
    }

    @Override
    public @NotNull String id() {
        return "search_text";
    }

    @Override
    public @NotNull String displayName() {
        return "Search Text";
    }

    @Override
    public @NotNull String description() {
        return "Search for text or regex patterns across project files using IntelliJ's editor buffers";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"query", TYPE_STRING, "Text or regex pattern to search for"},
            {"file_pattern", TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.kt', '*.java')", ""},
            {"regex", TYPE_BOOLEAN, "If true, treat query as regex. Default: false (literal match)"},
            {"case_sensitive", TYPE_BOOLEAN, "Case-sensitive search. Default: true"},
            {"max_results", TYPE_INTEGER, "Maximum results to return (default: 100)"}
        }, "query");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return navTools.searchText(args);
    }
}
