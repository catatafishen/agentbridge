package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Deletes a file from the project via IntelliJ.
 */
@SuppressWarnings("java:S112")
public final class DeleteFileTool extends FileTool {

    public DeleteFileTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "delete_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Delete File";
    }

    @Override
    public @NotNull String description() {
        return "Delete a file from the project via IntelliJ";
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Delete {path}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to delete (absolute or project-relative)"}
        }, "path");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.deleteFile(args);
    }
}
