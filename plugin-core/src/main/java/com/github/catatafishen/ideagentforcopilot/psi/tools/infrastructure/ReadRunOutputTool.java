package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.psi.InfrastructureTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads output from a recent Run panel tab by name.
 */
@SuppressWarnings("java:S112")
public final class ReadRunOutputTool extends InfrastructureTool {

    public ReadRunOutputTool(Project project, InfrastructureTools infraTools) {
        super(project, infraTools);
    }

    @Override
    public @NotNull String id() {
        return "read_run_output";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Run Output";
    }

    @Override
    public @NotNull String description() {
        return "Read output from a recent Run panel tab by name";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"tab_name", TYPE_STRING, "Name of the Run tab to read (default: most recent)"},
            {"max_chars", TYPE_INTEGER, "Maximum characters to return (default: 8000)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return infraTools.readRunOutput(args);
    }
}
