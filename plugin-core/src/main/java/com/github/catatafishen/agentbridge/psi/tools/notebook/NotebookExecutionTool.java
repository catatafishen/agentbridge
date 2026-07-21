package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Base for notebook execution tools (run cell / run all / restart / interrupt). Configures the
 * common MCP behaviour flags and provides cell-completion polling driven by the nbformat
 * {@code execution_count}.
 */
public abstract class NotebookExecutionTool extends NotebookTool {

    protected static final String PARAM_TIMEOUT = "timeout_sec";
    protected static final int MAX_TIMEOUT_SEC = 600;
    private static final long POLL_INTERVAL_MS = 400;

    protected final NotebookExecutor executor;

    protected NotebookExecutionTool(Project project) {
        super(project);
        this.executor = new NotebookExecutor(project);
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    /** Execution can run for minutes; do not hold the global write semaphore for its duration. */
    @Override
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public boolean requiresInteractiveEdt() {
        return true;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    protected int timeoutSec(@NotNull JsonObject args, int fallback) {
        if (args.has(PARAM_TIMEOUT) && !args.get(PARAM_TIMEOUT).isJsonNull()) {
            return Math.max(1, Math.min(args.get(PARAM_TIMEOUT).getAsInt(), MAX_TIMEOUT_SEC));
        }
        return fallback;
    }

    /**
     * Polls the notebook until the target cell's {@code execution_count} advances past
     * {@code before}, or the timeout elapses. Returns the rendered outputs, or a timeout note.
     */
    protected @NotNull String pollCellCompletion(@NotNull VirtualFile vf, int index,
                                                 @Nullable Integer before, int timeoutSec) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!sleep(POLL_INTERVAL_MS)) {
                return "Error: interrupted while waiting for cell [" + index + "] to finish";
            }
            NotebookModel current = NotebookModel.parse(readNotebookText(vf));
            if (index >= current.cellCount()) {
                return "Cell [" + index + "] no longer exists after execution (the notebook changed).";
            }
            JsonObject cell = current.cellAt(index);
            Integer after = NotebookModel.executionCount(cell);
            if (after != null && !after.equals(before)) {
                return formatRanCell(index, before, after, cell);
            }
        }
        return "Cell [" + index + "] was submitted but has not finished within " + timeoutSec + "s "
            + "(execution_count still " + (before == null ? "unset" : before) + "). The kernel may be busy "
            + "or the cell is long-running — call notebook_read_cell later, or retry with a larger timeout_sec.";
    }

    private static @NotNull String formatRanCell(int index, @Nullable Integer before, Integer after,
                                                 @NotNull JsonObject cell) {
        JsonArray outputs = NotebookModel.outputsOf(cell);
        StringBuilder sb = new StringBuilder();
        sb.append("Ran cell [").append(index).append("] — execution_count ")
            .append(before == null ? "unset" : before).append(" → ").append(after);
        if (outputs.isEmpty()) {
            sb.append("\n(no output)");
        } else {
            sb.append("\n\n").append(NotebookOutputFormatter.renderOutputs(
                outputs, NotebookOutputFormatter.DEFAULT_MAX_OUTPUT_CHARS));
        }
        return sb.toString();
    }

    /** Sleeps, returning {@code false} if interrupted (so callers can bail out cleanly). */
    protected static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
