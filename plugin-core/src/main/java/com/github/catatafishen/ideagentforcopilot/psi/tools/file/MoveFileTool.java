package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Moves a file to a different directory.
 */
@SuppressWarnings("java:S112")
public final class MoveFileTool extends FileTool {

    public MoveFileTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "move_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Move File";
    }

    @Override
    public @NotNull String description() {
        return "Move a file to a different directory";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Move {path} → {destination}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to move (absolute or project-relative)"},
            {"destination", TYPE_STRING, "Destination directory path (absolute or project-relative)"}
        }, "path", "destination");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.moveFile(args);
    }
}
