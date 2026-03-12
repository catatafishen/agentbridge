package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Redoes the last N undone actions on a file using IntelliJ's UndoManager.
 */
@SuppressWarnings("java:S112")
public final class RedoTool extends FileTool {

    public RedoTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "redo";
    }

    @Override
    public @NotNull String displayName() {
        return "Redo";
    }

    @Override
    public @NotNull String description() {
        return "Redo the last N undone actions on a file using IntelliJ's UndoManager";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to redo changes on"},
            {"count", TYPE_INTEGER, "Number of redo steps (default: 1)"}
        }, "path");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.redo(args);
    }
}
