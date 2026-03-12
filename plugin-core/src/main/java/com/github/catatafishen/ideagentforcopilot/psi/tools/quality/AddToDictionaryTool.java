package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adds a word to the project spell-check dictionary.
 */
@SuppressWarnings("java:S112")
public final class AddToDictionaryTool extends QualityTool {

    public AddToDictionaryTool(Project project, CodeQualityTools qualityTools) {
        super(project, qualityTools);
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
        return qualityTools.addToDictionary(args);
    }
}
