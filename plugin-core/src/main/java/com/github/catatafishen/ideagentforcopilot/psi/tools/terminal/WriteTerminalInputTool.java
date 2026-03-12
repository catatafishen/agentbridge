package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.psi.TerminalTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Sends raw text or keystrokes to a running terminal.
 */
@SuppressWarnings("java:S112")
public final class WriteTerminalInputTool extends TerminalTool {

    public WriteTerminalInputTool(Project project, TerminalTools terminalTools) {
        super(project, terminalTools);
    }

    @Override
    public @NotNull String id() {
        return "write_terminal_input";
    }

    @Override
    public @NotNull String displayName() {
        return "Write Terminal Input";
    }

    @Override
    public @NotNull String description() {
        return "Send raw text or keystrokes to a running terminal (e.g. answer prompts, send Ctrl-C)";
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"input", TYPE_STRING, "Text or keystrokes to send. Supports escape sequences: {enter}, {tab}, {ctrl-c}, {ctrl-d}, {ctrl-z}, {escape}, {up}, {down}, {left}, {right}, {backspace}, \\n, \\t"},
            {"tab_name", TYPE_STRING, "Name of the terminal tab to write to. If omitted, writes to the currently selected tab"}
        }, "input");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return terminalTools.writeTerminalInput(args);
    }
}
