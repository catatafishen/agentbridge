package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Adds a word to the project spell-check dictionary.
 */
public final class AddToDictionaryTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(AddToDictionaryTool.class);

    public AddToDictionaryTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "add_to_dictionary";
    }

    @Override
    public @NotNull String displayName() {
        return "Add to Dictionary";
    }

    @Override
    public @NotNull String description() {
        return "Add a word to the project spell-check dictionary";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("word", TYPE_STRING, "The word to add to the project dictionary")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String word = args.get("word").getAsString().trim().toLowerCase();
        if (word.isEmpty()) {
            return "Error: word cannot be empty";
        }
        try {
            // SpellCheckerManager is a bundled plugin class not available at Gradle compile time,
            // so we use reflection to avoid a hard compile-time dependency.
            Class<?> managerClass = Class.forName("com.intellij.spellchecker.SpellCheckerManager");
            Object spellChecker = managerClass.getMethod("getInstance", Project.class).invoke(null, project);
            managerClass.getMethod("acceptWordAsCorrect", String.class, Project.class)
                .invoke(spellChecker, word, project);
            return "Added '" + word + "' to project dictionary. " +
                "It will no longer be flagged as a typo in future inspections.";
        } catch (ClassNotFoundException e) {
            return "Spellchecker plugin is not available in this IDE build.";
        } catch (Exception e) {
            LOG.warn("Spellchecker unavailable: " + e.getMessage());
            return "Spellchecker plugin is not functional in this IDE build.";
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }
}
