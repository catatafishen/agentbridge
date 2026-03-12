package com.github.catatafishen.ideagentforcopilot.psi.tools;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.services.ToolSchemas;
import com.google.gson.JsonObject;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract base class for all individual tool implementations.
 * Each concrete tool subclass defines its identity, behavior flags,
 * and execution logic in a single self-contained class.
 *
 * @see ToolDefinition
 */
public abstract class Tool implements ToolDefinition {

    protected final Project project;

    protected Tool(Project project) {
        this.project = project;
    }

    /**
     * The tool's functional category. Subclasses override this.
     * Mapped to the legacy {@link ToolRegistry.Category} via {@link #category()}.
     */
    public abstract @NotNull ToolCategory toolCategory();

    @Override
    public @NotNull ToolRegistry.Category category() {
        return mapCategory(toolCategory());
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return ToolSchemas.getInputSchema(id());
    }

    @Override
    public boolean hasExecutionHandler() {
        return true;
    }

    // ── Shared utilities ─────────────────────────────────────

    protected VirtualFile resolveVirtualFile(String path) {
        return ToolUtils.resolveVirtualFile(project, path);
    }

    protected String relativize(String basePath, String filePath) {
        return ToolUtils.relativize(basePath, filePath);
    }

    protected record ProcessResult(int exitCode, String output, boolean timedOut) {
    }

    @SuppressWarnings("java:S112") // generic exception caught at JSON-RPC dispatch level
    protected ProcessResult executeInRunPanel(
        com.intellij.execution.configurations.GeneralCommandLine cmd,
        String title, int timeoutSec) throws Exception {
        CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        Process process = cmd.createProcess();
        OSProcessHandler processHandler = new OSProcessHandler(process, cmd.getCommandLineString());
        processHandler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                output.append(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                exitFuture.complete(event.getExitCode());
            }
        });

        EdtUtil.invokeLater(() -> {
            try {
                new RunContentExecutor(project, processHandler)
                    .withTitle(title)
                    .withActivateToolWindow(true)
                    .run();
            } catch (Exception e) {
                processHandler.startNotify();
            }
        });

        try {
            int exitCode = exitFuture.get(timeoutSec, TimeUnit.SECONDS);
            return new ProcessResult(exitCode, output.toString(), false);
        } catch (TimeoutException e) {
            processHandler.destroyProcess();
            return new ProcessResult(-1, output.toString(), true);
        }
    }

    // ── Category mapping ─────────────────────────────────────

    private static ToolRegistry.Category mapCategory(ToolCategory cat) {
        return switch (cat) {
            case FILE -> ToolRegistry.Category.FILE;
            case GIT -> ToolRegistry.Category.GIT;
            case NAVIGATION -> ToolRegistry.Category.SEARCH;
            case QUALITY -> ToolRegistry.Category.CODE_QUALITY;
            case REFACTORING, EDITING -> ToolRegistry.Category.REFACTOR;
            case TESTING -> ToolRegistry.Category.TESTING;
            case PROJECT -> ToolRegistry.Category.PROJECT;
            case INFRASTRUCTURE -> ToolRegistry.Category.INFRASTRUCTURE;
            case TERMINAL -> ToolRegistry.Category.TERMINAL;
            case EDITOR -> ToolRegistry.Category.EDITOR;
            case OTHER -> ToolRegistry.Category.OTHER;
        };
    }
}
