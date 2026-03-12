package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.psi.InfrastructureTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads output from a tab in the Build tool window.
 */
@SuppressWarnings("java:S112")
public final class ReadBuildOutputTool extends InfrastructureTool {

    public ReadBuildOutputTool(Project project, InfrastructureTools infraTools) {
        super(project, infraTools);
    }

    @Override
    public @NotNull String id() {
        return "read_build_output";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Build Output";
    }

    @Override
    public @NotNull String description() {
        return "Read output from a tab in the Build tool window (Gradle/Maven/compiler output)";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"tab_name", TYPE_STRING, "Name of the Build tab to read (default: currently selected or most recent). Use tab names shown in IntelliJ's Build tool window."},
            {"max_chars", TYPE_INTEGER, "Maximum characters to return (default: 8000)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return infraTools.readBuildOutput(args);
    }
}
