package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.CodeQualityTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Formats a file using IntelliJ's configured code style.
 */
@SuppressWarnings("java:S112")
public final class FormatCodeTool extends QualityTool {

    public FormatCodeTool(Project project, CodeQualityTools qualityTools) {
        super(project, qualityTools);
    }

    @Override
    public @NotNull String id() {
        return "format_code";
    }

    @Override
    public @NotNull String displayName() {
        return "Format Code";
    }

    @Override
    public @NotNull String description() {
        return "Manually format a file using IntelliJ's configured code style";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to format"}
        }, "path");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return qualityTools.formatCode(args);
    }
}
