package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a diff viewer comparing a file to proposed content or another file.
 */
@SuppressWarnings("java:S112")
public final class ShowDiffTool extends EditorTool {

    public ShowDiffTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override
    public @NotNull String id() {
        return "show_diff";
    }

    @Override
    public @NotNull String displayName() {
        return "Show Diff";
    }

    @Override
    public @NotNull String description() {
        return "Show a diff viewer comparing a file to proposed content or another file";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the first file"},
            {"file2", TYPE_STRING, "Optional: path to second file for two-file comparison"},
            {"content", TYPE_STRING, "Optional: proposed new content to diff against the current file"},
            {"title", TYPE_STRING, "Optional: title for the diff viewer tab"}
        }, "file");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.showDiff(args);
    }
}
