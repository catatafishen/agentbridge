package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.psi.InfrastructureTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads recent IntelliJ IDE log entries, optionally filtered by level or text.
 */
@SuppressWarnings("java:S112")
public final class ReadIdeLogTool extends InfrastructureTool {

    public ReadIdeLogTool(Project project, InfrastructureTools infraTools) {
        super(project, infraTools);
    }

    @Override
    public @NotNull String id() {
        return "read_ide_log";
    }

    @Override
    public @NotNull String displayName() {
        return "Read IDE Log";
    }

    @Override
    public @NotNull String description() {
        return "Read recent IntelliJ IDE log entries, optionally filtered by level or text";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"lines", TYPE_INTEGER, "Number of recent lines to return (default: 50)"},
            {"filter", TYPE_STRING, "Only return lines containing this text"},
            {"level", TYPE_STRING, "Filter by log level: INFO, WARN, ERROR"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return infraTools.readIdeLog(args);
    }
}
