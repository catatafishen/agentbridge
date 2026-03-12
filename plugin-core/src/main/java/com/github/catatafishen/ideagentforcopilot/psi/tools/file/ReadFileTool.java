package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a file via IntelliJ's editor buffer.
 */
@SuppressWarnings("java:S112")
public final class ReadFileTool extends FileTool {

    public ReadFileTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "intellij_read_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Read File";
    }

    @Override
    public @NotNull String description() {
        return "Read a file via IntelliJ's editor buffer -- always returns the current in-memory content";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to read"},
            {"start_line", TYPE_INTEGER, "Optional: first line to read (1-based, inclusive)"},
            {"end_line", TYPE_INTEGER, "Optional: last line to read (1-based, inclusive). Use with start_line to read a range"}
        }, "path");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.readFile(args);
    }
}
