package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Surgical find-and-replace edit within a file.
 */
@SuppressWarnings("java:S112")
public final class EditTextTool extends FileTool {

    public EditTextTool(Project project, FileTools fileTools) {
        super(project, fileTools);
    }

    @Override
    public @NotNull String id() {
        return "edit_text";
    }

    @Override
    public @NotNull String displayName() {
        return "Edit Text";
    }

    @Override
    public @NotNull String description() {
        return "Surgical find-and-replace edit within a file -- for small changes inside methods, "
            + "imports, or config. Auto-format and import optimization is deferred until turn end "
            + "(controlled by auto_format_and_optimize_imports param)";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Edit {path}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to edit"},
            {"old_str", TYPE_STRING, "Exact string to find and replace. Must match exactly one location in the file"},
            {"new_str", TYPE_STRING, "Replacement string"},
            {"auto_format_and_optimize_imports", TYPE_BOOLEAN, "Auto-format code AND optimize imports after editing (default: true). Formatting is DEFERRED until the end of the current turn or before git commit — safe for multi-step edits within a single turn. ⚠\uFE0F Import optimization REMOVES imports it considers unused — if you add imports in one edit and reference them in a later edit, set this to false or combine both changes in one edit"}
        }, "path", "old_str", "new_str");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return fileTools.writeFile(args);
    }
}
