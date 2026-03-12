package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.psi.TerminalTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads output from an integrated terminal tab.
 */
@SuppressWarnings("java:S112")
public final class ReadTerminalOutputTool extends TerminalTool {

    public ReadTerminalOutputTool(Project project, TerminalTools terminalTools) {
        super(project, terminalTools);
    }

    @Override
    public @NotNull String id() {
        return "read_terminal_output";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Terminal Output";
    }

    @Override
    public @NotNull String description() {
        return "Read output from an integrated terminal tab";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"tab_name", TYPE_STRING, "Name of the terminal tab to read from"},
            {"max_lines", TYPE_INTEGER, "Maximum number of lines to return from the end of the terminal buffer (default: 50). Use 0 for the full buffer."}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return terminalTools.readTerminalOutput(args);
    }
}
