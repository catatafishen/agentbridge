package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SetThemeTool extends EditorTool {

    private static final Logger LOG = Logger.getInstance(SetThemeTool.class);
    private static final String PARAM_THEME = "theme";

    public SetThemeTool(Project project) {
        super(project);
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
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Set theme: {theme}";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_THEME, TYPE_STRING, "Theme name or partial name (e.g., 'Darcula', 'Light')")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_THEME)) {
            return "Missing required parameter: 'theme' (theme name or partial name)";
        }
        String themeQuery = args.get(PARAM_THEME).getAsString();
        String queryLower = themeQuery.toLowerCase();

        var lafManager = LafManager.getInstance();
        var themes = PlatformApiCompat.getInstalledThemes(lafManager);

        UIThemeLookAndFeelInfo target = null;
        for (var theme : themes) {
            if (theme.getName().equals(themeQuery)) {
                target = theme;
                break;
            }
            if (target == null && theme.getName().toLowerCase().contains(queryLower)) {
                target = theme;
            }
        }

        if (target == null) {
            return "Theme not found: '" + themeQuery + "'. Use list_themes to see available themes.";
        }

        var finalTarget = target;
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                lafManager.setCurrentLookAndFeel(finalTarget, false);
                lafManager.updateUI();
                resultFuture.complete("Theme changed to '" + finalTarget.getName() + "'.");
            } catch (Exception e) {
                LOG.warn("Failed to set theme", e);
                resultFuture.complete("Failed to set theme: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
