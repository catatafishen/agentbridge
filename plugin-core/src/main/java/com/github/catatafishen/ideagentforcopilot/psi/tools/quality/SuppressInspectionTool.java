package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Inserts a suppress annotation or comment for a specific inspection at a given line.
 */
@SuppressWarnings("java:S112")
public final class SuppressInspectionTool extends QualityTool {

    public SuppressInspectionTool(Project project, CodeQualityTools qualityTools) {
        super(project, qualityTools);
    }

    @Override
    public @NotNull String id() {
        return "suppress_inspection";
    }

    @Override
    public @NotNull String displayName() {
        return "Suppress Inspection";
    }

    @Override
    public @NotNull String description() {
        return "Insert a suppress annotation or comment for a specific inspection at a given line";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file containing the code to suppress"},
            {"line", TYPE_INTEGER, "Line number where the inspection finding is located"},
            {"inspection_id", TYPE_STRING, "The inspection ID to suppress (e.g., 'SpellCheckingInspection')"}
        }, "path", "line", "inspection_id");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return qualityTools.suppressInspection(args);
    }
}
