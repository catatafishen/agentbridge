package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Downloads library sources to enable source navigation and debugging.
 */
@SuppressWarnings("java:S112")
public final class DownloadSourcesTool extends ProjectTool {

    public DownloadSourcesTool(Project project, ProjectTools projectTools) {
        super(project, projectTools);
    }

    @Override
    public @NotNull String id() {
        return "download_sources";
    }

    @Override
    public @NotNull String displayName() {
        return "Download Sources";
    }

    @Override
    public @NotNull String description() {
        return "Download library sources to enable source navigation and debugging";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"library", TYPE_STRING, "Optional library name filter (e.g. 'junit')"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return projectTools.downloadSources(args);
    }
}
