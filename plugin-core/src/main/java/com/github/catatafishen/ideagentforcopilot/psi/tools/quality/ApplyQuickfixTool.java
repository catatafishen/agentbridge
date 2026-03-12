package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies an IntelliJ quick-fix at a specific file and line.
 */
@SuppressWarnings("java:S112")
public final class ApplyQuickfixTool extends QualityTool {

    public ApplyQuickfixTool(Project project, CodeQualityTools qualityTools) {
        super(project, qualityTools);
    }

    @Override
    public @NotNull String id() {
        return "apply_quickfix";
    }

    @Override
    public @NotNull String displayName() {
        return "Apply Quickfix";
    }

    @Override
    public @NotNull String description() {
        return "Apply an IntelliJ quick-fix at a specific file and line";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the file containing the problem"},
            {"line", TYPE_INTEGER, "Line number where the problem is located"},
            {"inspection_id", TYPE_STRING, "The inspection ID from run_inspections output (e.g., 'unused')"},
            {"fix_index", TYPE_INTEGER, "Which fix to apply if multiple are available (default: 0)"}
        }, "file", "line", "inspection_id");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return qualityTools.applyQuickfix(args);
    }
}
