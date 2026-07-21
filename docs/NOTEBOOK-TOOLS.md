# Jupyter Notebook Tools

AgentBridge is a bridge to the IntelliJ IDE. Every tool should proxy something IntelliJ already does
well — it should not invent its own implementations.

Jupyter support in JetBrains IDEs (DataSpell, PyCharm Professional, IntelliJ IDEA Ultimate with the
Python plugin) ships in the Pro-only **`intellij.jupyter`** plugin. Its kernel/execution engine is
closed-source — there is **no public API to run a cell and await its result**. Even JetBrains' own UI
runs a cell by invoking an action and then polling execution status. AgentBridge follows the same
model.

The `.ipynb` file on disk (the [nbformat](https://nbformat.readthedocs.io/) JSON) is the source of
truth for cell outputs: the in-editor notebook document is a transformed `#%%`-delimited script view
that omits outputs and stable cell ids. AgentBridge therefore **reads and edits the raw nbformat
JSON** and **triggers execution through IntelliJ actions**.

---

## Tools

| Tool                        | Kind    | What it does                                                             |
|-----------------------------|---------|-------------------------------------------------------------------------|
| `notebook_list_cells`       | read    | Compact index of every cell: index, id, type, execution count, output tag, source preview |
| `notebook_read_cell`        | read    | Full source and rendered outputs of one cell (by index or id); images summarized |
| `notebook_edit_cell`        | edit    | Replace a cell's source text                                            |
| `notebook_add_cell`         | edit    | Insert a new code/markdown/raw cell at a position                       |
| `notebook_delete_cell`      | edit    | Delete a cell by index or id                                            |
| `notebook_move_cell`        | edit    | Move a cell to a new position                                           |
| `notebook_change_cell_type` | edit    | Convert a cell between code / markdown / raw                            |
| `notebook_run_cell`         | execute | Run one cell and return its new output (polls the kernel to completion) |
| `notebook_run_all`          | execute | Run every cell in the notebook                                          |
| `notebook_restart_kernel`   | execute | Restart the notebook's kernel                                           |
| `notebook_interrupt_kernel` | execute | Interrupt the currently running cell                                    |
| `notebook_kernel_status`    | read    | Report inferred kernel/run state from cell execution status             |

Read and edit tools work wherever an `.ipynb` file exists — they need no kernel and no Jupyter
plugin. Execution tools require the `intellij.jupyter` plugin and register only when it is present;
they disable gracefully otherwise.

---

## How it works

### Reading and editing (nbformat JSON)

`NotebookModel` (in `psi/tools/notebook/`) parses the `.ipynb` JSON, exposes cell accessors, and
performs structural mutations in place — preserving every field the tools do not understand (kernel
metadata, `ExecuteTime`, unknown output MIME bundles). `NotebookJson` serializes back with the same
conventions Jupyter/DataSpell write (one-space indent, `null`s kept, non-ASCII verbatim, single-line
`source` as a string / multi-line as a line array, the file's original LF/CRLF line ending, trailing
newline) so a one-cell edit produces a one-cell git diff.

The tools read the raw VF bytes rather than the editor `Document` (which is the transformed `#%%`
view). Before reading, an unsaved notebook document is flushed to disk so in-editor edits are not
missed; after writing, the file is reloaded so an open notebook editor reflects the change.
`NotebookOutputFormatter` renders outputs (`stream`, `execute_result`, `display_data`, `error`) to
compact text — `text/plain` verbatim, images and other rich MIME bundles summarized with their size.

### Execution (IntelliJ actions + polling)

There is no public kernel API, so execution tools:

1. open the notebook, position the caret in the target cell, and invoke the confirmed action
   (`NotebookRunCellAction`, `NotebookRunAllAction`, `JupyterInterruptKernelAction`; the restart
   action id is discovered at runtime via `ActionManager.getActionIdList("Jupyter")`);
2. poll the `.ipynb` JSON until the cell's `execution_count` advances past its pre-run value (with a
   timeout), then return the fresh outputs.

This uses only public platform APIs (`ActionManager`/`ActionUtil`, `Editor`, VFS), so the tools live
in the **main plugin** with no internal-API dependency — matching AGENTS.md's "bridge, not inventor"
principle. Completion is inferred from the nbformat `execution_count`, which is version-independent.

**No compile-time dependency on the Jupyter plugin:** action ids are plain strings resolved through
`ActionManager` at runtime, and cell state is read from the nbformat JSON — so `plugin-core` compiles
and verifies against IDEs (WebStorm, GoLand) that do not ship `intellij.jupyter`.

---

## Availability

Read and edit tools: any IDE. Execution tools: IDEs with the `intellij.jupyter` plugin (DataSpell,
PyCharm Professional, IntelliJ IDEA Ultimate + Python). When the plugin is absent, the execution
tools are not registered.
