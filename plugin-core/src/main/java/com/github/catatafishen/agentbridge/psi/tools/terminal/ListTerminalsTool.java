package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Lists terminal resources owned by the current MCP session.
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
        return "List terminal resources owned by this MCP session, including stable terminal_id values. "
            + "Other agents' terminals and user-created tabs are intentionally hidden.";
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
        appendOwnedTerminalTabs(result);
        appendDefaultShell(result);
        result.append(
            "\n\nTip: Reuse terminals with run_in_terminal. Use terminal_id with "
                + "read_terminal_output, write_terminal_input, and close_terminal.");
        return result.toString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }
}
