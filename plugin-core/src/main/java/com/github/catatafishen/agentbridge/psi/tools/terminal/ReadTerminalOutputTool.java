package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.renderers.TerminalOutputRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads output from an integrated terminal tab.
 */
public final class ReadTerminalOutputTool extends TerminalTool {

    private static final String PARAM_MAX_LINES = "max_lines";

    public ReadTerminalOutputTool(Project project) {
        super(project);
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
        return "Read recent output from an integrated terminal tab. Returns the last N lines from the terminal buffer. " +
                "Use list_terminals to see available tab names.";
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
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"tab_name", TYPE_STRING, "Name of the terminal tab to read from. If omitted, reads from the currently selected terminal tab."},
            {PARAM_MAX_LINES, TYPE_INTEGER, "Maximum number of lines to return from the end of the terminal buffer (default: 50). Use 0 for the full buffer."}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;
        int maxLines = args.has(PARAM_MAX_LINES) ? args.get(PARAM_MAX_LINES).getAsInt() : DEFAULT_MAX_LINES;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                Content targetContent = resolveTerminalContent(tabName);
                if (targetContent == null) {
                    resultFuture.complete(tabName != null
                        ? "No terminal tab found matching '" + tabName + "'. Use list_terminals to see available tabs."
                        : "No terminal tab is open. Use run_in_terminal to start one.");
                    return;
                }
                readTerminalText(resultFuture, targetContent, maxLines);
            } catch (Exception e) {
                LOG.warn("Failed to read terminal", e);
                resultFuture.complete("Failed to read terminal: " + e.getMessage());
            }
        });

        try {
            return resultFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Terminal read timed out.";
        } catch (Exception e) {
            return "Terminal read timed out.";
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TerminalOutputRenderer.INSTANCE;
    }
}
