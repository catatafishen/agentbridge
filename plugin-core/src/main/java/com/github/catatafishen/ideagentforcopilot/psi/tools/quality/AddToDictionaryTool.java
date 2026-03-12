package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"word", TYPE_STRING, "The word to add to the project dictionary"}
        }, "word");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        String word = args.get("word").getAsString().trim().toLowerCase();
        if (word.isEmpty()) {
            return "Error: word cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var spellChecker = com.intellij.spellchecker.SpellCheckerManager.getInstance(project);
                spellChecker.acceptWordAsCorrect(word, project);
                resultFuture.complete("Added '" + word + "' to project dictionary. " +
                    "It will no longer be flagged as a typo in future inspections.");
            } catch (Exception e) {
                LOG.error("Error adding word to dictionary", e);
                resultFuture.complete(ToolUtils.ERROR_PREFIX + "adding word to dictionary: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }
}
