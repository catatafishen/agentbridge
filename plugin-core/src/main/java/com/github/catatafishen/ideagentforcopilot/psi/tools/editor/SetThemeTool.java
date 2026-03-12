package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Changes the IDE theme by name.
 */
@SuppressWarnings("java:S112")
public final class SetThemeTool extends EditorTool {

    public SetThemeTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override
    public @NotNull String id() {
        return "set_theme";
    }

    @Override
    public @NotNull String displayName() {
        return "Set Theme";
    }

    @Override
    public @NotNull String description() {
        return "Change the IDE theme by name (e.g., 'Darcula', 'Light')";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Set theme: {theme}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"theme", TYPE_STRING, "Theme name or partial name (e.g., 'Darcula', 'Light')"}
        }, "theme");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.setTheme(args);
    }
}
