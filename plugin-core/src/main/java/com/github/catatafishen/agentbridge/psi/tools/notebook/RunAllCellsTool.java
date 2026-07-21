package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every code cell of a notebook on the kernel, in order.
 */
public final class RunAllCellsTool extends NotebookExecutionTool {

    private static final int DEFAULT_TIMEOUT_SEC = 180;
    private static final long POLL_INTERVAL_MS = 1000;
    /** Consecutive no-progress polls after which execution is treated as stalled (error / stopped). */
    private static final int STABLE_POLLS = 3;

    public RunAllCellsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "notebook_run_all";
    }

    @Override
    public @NotNull String displayName() {
        return "Run All Notebook Cells";
    }

    @Override
    public @NotNull String description() {
        return "Run every code cell of a notebook on its Jupyter kernel, in order, and report how many "
            + "finished and which produced errors. Waits up to 'timeout_sec' (default " + DEFAULT_TIMEOUT_SEC
            + "s), detecting completion via each cell's execution_count. Execution stops at the first "
            + "cell that errors. Requires the Jupyter plugin (DataSpell / PyCharm Professional).";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run all cells in {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING, "Absolute or project-relative path to the .ipynb notebook"),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER, "Seconds to wait for all cells to finish (default "
                + DEFAULT_TIMEOUT_SEC + ", max " + MAX_TIMEOUT_SEC + ").")
        );
    }

    @Override
    protected @NotNull String run(@NotNull JsonObject args) throws IOException {
        String path = readPath(args);
        VirtualFile vf = resolveNotebookFile(path);
        if (vf == null) {
            return notebookNotFound(path);
        }
        NotebookModel nb = NotebookModel.parse(readNotebookText(vf));
        Map<Integer, Integer> before = codeCellExecutionCounts(nb);
        if (before.isEmpty()) {
            return "Notebook " + relativize(project.getBasePath(), vf.getPath()) + " has no code cells to run.";
        }

        String invokeError = executor.invokeAction(vf, NotebookExecutor.ACTION_RUN_ALL, null, nb.cellCount());
        if (invokeError != null) {
            return invokeError;
        }
        return pollAll(vf, before, timeoutSec(args, DEFAULT_TIMEOUT_SEC));
    }

    private @NotNull String pollAll(@NotNull VirtualFile vf, @NotNull Map<Integer, Integer> before,
                                    int timeoutSec) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        int lastAdvanced = -1;
        int stable = 0;
        NotebookModel latest = null;
        while (System.currentTimeMillis() < deadline) {
            if (!sleep(POLL_INTERVAL_MS)) {
                return "Error: interrupted while waiting for the notebook to finish running";
            }
            latest = NotebookModel.parse(readNotebookText(vf));
            int advanced = countAdvanced(latest, before);
            if (advanced == before.size()) {
                return summarize(latest, before, false, timeoutSec);
            }
            if (advanced == lastAdvanced) {
                if (++stable >= STABLE_POLLS && advanced > 0) {
                    return summarize(latest, before, true, timeoutSec);
                }
            } else {
                stable = 0;
                lastAdvanced = advanced;
            }
        }
        return summarize(latest, before, true, timeoutSec);
    }

    private static @NotNull Map<Integer, Integer> codeCellExecutionCounts(@NotNull NotebookModel nb) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < nb.cellCount(); i++) {
            JsonObject cell = nb.cellAt(i);
            if (NotebookModel.CODE.equals(NotebookModel.cellType(cell))) {
                counts.put(i, NotebookModel.executionCount(cell));
            }
        }
        return counts;
    }

    /** Counts code cells whose execution_count advanced past its pre-run value. */
    private static int countAdvanced(@NotNull NotebookModel current, @NotNull Map<Integer, Integer> before) {
        int advanced = 0;
        for (Map.Entry<Integer, Integer> entry : before.entrySet()) {
            int index = entry.getKey();
            if (index >= current.cellCount()) {
                continue;
            }
            Integer now = NotebookModel.executionCount(current.cellAt(index));
            if (now != null && !now.equals(entry.getValue())) {
                advanced++;
            }
        }
        return advanced;
    }

    private @NotNull String summarize(NotebookModel current, @NotNull Map<Integer, Integer> before,
                                      boolean partial, int timeoutSec) {
        int total = before.size();
        int advanced = current != null ? countAdvanced(current, before) : 0;
        List<String> errors = new ArrayList<>();
        Integer maxExec = null;
        if (current != null) {
            for (Integer index : before.keySet()) {
                if (index >= current.cellCount()) {
                    continue;
                }
                JsonObject cell = current.cellAt(index);
                Integer exec = NotebookModel.executionCount(cell);
                if (exec != null && (maxExec == null || exec > maxExec)) {
                    maxExec = exec;
                }
                String outSummary = NotebookOutputFormatter.shortOutputSummary(NotebookModel.outputsOf(cell));
                if (outSummary.startsWith("ERROR:")) {
                    errors.add("[" + index + "] " + outSummary.substring("ERROR:".length()));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Ran ").append(advanced).append('/').append(total).append(" code cells");
        if (partial) {
            sb.append(advanced < total
                ? " — execution stopped early or timed out after " + timeoutSec + "s"
                : "");
        }
        sb.append('.');
        if (!errors.isEmpty()) {
            sb.append("\nErrors in cells: ").append(String.join(", ", errors));
            sb.append("\nUse notebook_read_cell to see a failing cell's full traceback.");
        }
        if (maxExec != null) {
            sb.append("\nHighest execution_count: ").append(maxExec).append('.');
        }
        return sb.toString();
    }
}
