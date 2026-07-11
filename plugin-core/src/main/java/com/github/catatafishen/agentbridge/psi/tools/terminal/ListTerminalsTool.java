package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Lists active terminal tabs.
 */
public final class ListTerminalsTool extends TerminalTool {

    public ListTerminalsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_terminals";
    }

    @Override
    public @NotNull String displayName() {
        return "List Terminals";
    }

    @Override
    public @NotNull String description() {
        return "List active terminal tabs with their names. Use tab names with read_terminal_output or write_terminal_input.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        StringBuilder result = new StringBuilder();

        appendOpenTerminalTabs(result);
        appendDefaultShell(result);

        result.append("\n\nTip: Reuse AgentBridge tabs with run_in_terminal, interact with read_terminal_output/write_terminal_input, and close unused tabs with close_terminal.");
        return result.toString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }
}
