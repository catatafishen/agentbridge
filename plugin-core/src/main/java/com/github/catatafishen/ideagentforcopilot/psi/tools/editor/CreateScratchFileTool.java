package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates a temporary scratch file with the given name and content.
 */
@SuppressWarnings("java:S112")
public final class CreateScratchFileTool extends EditorTool {

    public CreateScratchFileTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override
    public @NotNull String id() {
        return "create_scratch_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Create Scratch File";
    }

    @Override
    public @NotNull String description() {
        return "Create a temporary scratch file with the given name and content";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Scratch file name with extension (e.g., 'test.py', 'notes.md')"},
            {"content", TYPE_STRING, "The content to write to the scratch file"}
        }, "name", "content");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.createScratchFile(args);
    }
}
