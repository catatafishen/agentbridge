package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Forces IntelliJ to refresh a file or directory from disk,
 * picking up changes made by external tools.
 */
@SuppressWarnings("java:S112")
public final class ReloadFromDiskTool extends FileTool {

    public ReloadFromDiskTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "reload_from_disk";
    }

    @Override
    public @NotNull String displayName() {
        return "Reload from Disk";
    }

    @Override
    public @NotNull String description() {
        return "Force IntelliJ to refresh a file or directory from disk, picking up changes made by external tools";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "File or directory path to reload (absolute or project-relative). Omit to reload the entire project root."}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.reloadFromDisk(args);
    }
}
