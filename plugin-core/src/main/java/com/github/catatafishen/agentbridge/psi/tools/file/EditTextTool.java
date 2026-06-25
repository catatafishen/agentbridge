package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.services.PermissionTemplateUtil;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Surgical find-and-replace edit within a file.
 */
@SuppressWarnings("java:S112")
public final class EditTextTool extends WriteFileTool {

    public EditTextTool(Project project) {
        super(project);
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
            + "(controlled by auto_format_and_optimize_imports param). "
            + "Use show_diff first to preview a proposed change to the user before applying.";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Edit {path}";
    }

    @Override
    protected boolean allowActiveFileFallback() {
        return true;
    }

    @Override
    public @Nullable String resolvePermissionQuestion(@Nullable JsonObject args) {
        JsonObject enriched = args != null ? args.deepCopy() : new JsonObject();
        if (!enriched.has("path") || enriched.get("path").isJsonNull()) {
            if (enriched.has("file") && !enriched.get("file").isJsonNull()) {
                enriched.addProperty("path", enriched.get("file").getAsString());
            } else {
                enriched.addProperty("path", "(active file)");
            }
        }
        return PermissionTemplateUtil.stripPlaceholders(
            PermissionTemplateUtil.substituteArgs(permissionTemplate(), enriched));
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional("path", TYPE_STRING, "Path to the file to edit (absolute or project-relative). "
                + "Also accepts 'file' as an alias. If omitted, edits the currently active file in the IDE editor."),
            Param.required("old_str", TYPE_STRING, "Exact string to find and replace. Must match exactly one location in the file"),
            Param.required("new_str", TYPE_STRING, "Replacement string"),
            Param.optional("replace_all", TYPE_BOOLEAN,
                "If true, replace every occurrence of old_str instead of failing when multiple matches exist (default: false)"),
            Param.optional("case_sensitive", TYPE_BOOLEAN,
                "If false, match old_str case-insensitively (default: true). The replacement is inserted as-is."),
            Param.optional("auto_format_and_optimize_imports", TYPE_BOOLEAN,
                "Auto-format code AND optimize imports after editing (default: true). "
                    + "Formatting is DEFERRED until the end of the current turn or before git commit — "
                    + "safe for multi-step edits within a single turn. "
                    + "⚠️ Import optimization REMOVES imports it considers unused — "
                    + "if you add imports in one edit and reference them in a later edit, "
                    + "set this to false or combine both changes in one edit")
        );
    }

}
