package com.github.catatafishen.ideagentforcopilot.psi.tools.terminal;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        return "List active terminal tabs";
    }

    

    @Override
    public @NotNull String kind() {
        return "read";
    }
@Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        StringBuilder result = new StringBuilder();

        appendOpenTerminalTabs(result);
        appendAvailableShells(result);
        appendDefaultShell(result);

        result.append("\n\nTip: Use run_in_terminal with tab_name to reuse an existing tab, or new_tab=true to force a new one.");
        return result.toString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }
}
