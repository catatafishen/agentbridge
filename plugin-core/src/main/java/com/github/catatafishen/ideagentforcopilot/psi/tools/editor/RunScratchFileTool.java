package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EditorTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a scratch file using an appropriate run configuration.
 */
@SuppressWarnings("java:S112")
public final class RunScratchFileTool extends EditorTool {

    public RunScratchFileTool(Project project, EditorTools editorTools) {
        super(project, editorTools);
    }

    @Override
    public @NotNull String id() {
        return "run_scratch_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Scratch File";
    }

    @Override
    public @NotNull String description() {
        return "Run a scratch file using an appropriate run configuration";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Scratch file name with extension (e.g., 'test.kts', 'MyApp.java', 'hello.js')"},
            {"module", TYPE_STRING, "Optional: module name for classpath (e.g., 'plugin-core')"},
            {"interactive", TYPE_BOOLEAN, "Optional: enable interactive/REPL mode (Kotlin scripts)"}
        }, "name");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editorTools.runScratchFile(args);
    }
}
