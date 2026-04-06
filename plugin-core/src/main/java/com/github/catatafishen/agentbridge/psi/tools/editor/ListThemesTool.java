package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.ide.ui.LafManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Lists all available IDE themes with their dark/light type.
 */
public final class ListThemesTool extends EditorTool {

    public ListThemesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_themes";
    }

    @Override
    public @NotNull String displayName() {
        return "List Themes";
    }

    @Override
    public @NotNull String description() {
        return "List all available IDE themes with their dark/light type";
    }



    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }
@Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        var lafManager = LafManager.getInstance();
        var current = lafManager.getCurrentUIThemeLookAndFeel();
        String currentName = current != null ? current.getName() : "";

        var themes = kotlin.sequences.SequencesKt.toList(lafManager.getInstalledThemes());
        var sb = new StringBuilder("Available themes:\n\n");
        for (var theme : themes) {
            boolean active = theme.getName().equals(currentName);
            sb.append(active ? "  ▸ " : "  • ").append(theme.getName())
                .append(theme.isDark() ? " (dark)" : " (light)");
            if (active) sb.append(" ← current");
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
