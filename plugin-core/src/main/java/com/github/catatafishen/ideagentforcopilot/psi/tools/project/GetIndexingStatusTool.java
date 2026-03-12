package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Checks whether IntelliJ indexing is in progress; optionally waits until it finishes.
 */
@SuppressWarnings("java:S112")
public final class GetIndexingStatusTool extends ProjectTool {

    public GetIndexingStatusTool(Project project, ProjectTools projectTools) {
        super(project, projectTools);
    }

    @Override
    public @NotNull String id() {
        return "get_indexing_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Indexing Status";
    }

    @Override
    public @NotNull String description() {
        return "Check whether IntelliJ indexing is in progress; optionally wait until it finishes";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"wait", TYPE_BOOLEAN, "If true, blocks until indexing finishes"},
            {"timeout", TYPE_INTEGER, "Max seconds to wait when wait=true (default: 30)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return projectTools.getIndexingStatus(args);
    }
}
