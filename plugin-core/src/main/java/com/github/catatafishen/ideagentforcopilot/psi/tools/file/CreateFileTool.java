package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates a new file and registers it in IntelliJ's VFS.
 */
@SuppressWarnings("java:S112")
public final class CreateFileTool extends FileTool {

    public CreateFileTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "create_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Create File";
    }

    @Override
    public @NotNull String description() {
        return "Create a new file and register it in IntelliJ's VFS";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Create {path}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path for the new file (absolute or project-relative). File must not already exist"},
            {"content", TYPE_STRING, "Content to write to the file"}
        }, "path", "content");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.createFile(args);
    }
}
