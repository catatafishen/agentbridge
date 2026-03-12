package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Renames a file in place without moving it to a different directory.
 */
@SuppressWarnings("java:S112")
public final class RenameFileTool extends FileTool {

    public RenameFileTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "rename_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Rename File";
    }

    @Override
    public @NotNull String description() {
        return "Rename a file in place without moving it to a different directory";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Rename {path} → {new_name}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to rename (absolute or project-relative)"},
            {"new_name", TYPE_STRING, "New file name (just the filename, not a full path)"}
        }, "path", "new_name");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.renameFile(args);
    }
}
