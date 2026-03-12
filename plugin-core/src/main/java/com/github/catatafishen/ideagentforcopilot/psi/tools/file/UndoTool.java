package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Undoes the last N edit actions on a file using IntelliJ's UndoManager.
 */
@SuppressWarnings("java:S112")
public final class UndoTool extends FileTool {

    public UndoTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "undo";
    }

    @Override
    public @NotNull String displayName() {
        return "Undo";
    }

    @Override
    public @NotNull String description() {
        return "Undo the last N edit actions on a file using IntelliJ's UndoManager";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to undo changes on"},
            {"count", TYPE_INTEGER, "Number of undo steps (default: 1). Each write + auto-format counts as 2 steps"}
        }, "path");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.undo(args);
    }
}
