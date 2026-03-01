package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for tool handler groups. Each subclass registers its tools
 * in the constructor via {@link #register(String, ToolHandler)}.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
abstract class AbstractToolHandler {
    protected final Project project;
    private final Map<String, ToolHandler> tools = new LinkedHashMap<>();

    protected AbstractToolHandler(Project project) {
        this.project = project;
    }

    protected final void register(String name, ToolHandler handler) {
        tools.put(name, handler);
    }

    /**
     * Check whether an optional plugin is installed.
     * Use this to skip registering tools that depend on plugins the user doesn't have.
     */
    protected static boolean isPluginInstalled(String pluginId) {
        return PluginManagerCore.isPluginInstalled(PluginId.getId(pluginId));
    }

    /**
     * Returns the tool registry for this handler group.
     */
    Map<String, ToolHandler> getTools() {
        return tools;
    }

    // Convenience delegates to ToolUtils
    protected VirtualFile resolveVirtualFile(String path) {
        return ToolUtils.resolveVirtualFile(project, path);
    }

    protected String relativize(String basePath, String filePath) {
        return ToolUtils.relativize(basePath, filePath);
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {
    }

    /**
     * Executes a command in the IntelliJ Run panel, capturing output and waiting for exit.
     */
    protected ProcessResult executeInRunPanel(
        com.intellij.execution.configurations.GeneralCommandLine cmd,
        String title, int timeoutSec) throws Exception {
        java.util.concurrent.CompletableFuture<Integer> exitFuture = new java.util.concurrent.CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        // Use Process-based constructor to avoid cross-JAR @NotNull annotation resolution issue
        Process process = cmd.createProcess();
        com.intellij.execution.process.OSProcessHandler processHandler =
            new com.intellij.execution.process.OSProcessHandler(process, cmd.getCommandLineString());
        processHandler.addProcessListener(new com.intellij.execution.process.ProcessListener() {
            @Override
            public void onTextAvailable(
                @org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessEvent event,
                @org.jetbrains.annotations.NotNull com.intellij.openapi.util.Key outputType) {
                output.append(event.getText());
            }

            @Override
            public void processTerminated(
                @org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessEvent event) {
                exitFuture.complete(event.getExitCode());
            }
        });

        EdtUtil.invokeLater(() -> {
            try {
                new com.intellij.execution.RunContentExecutor(project, processHandler)
                    .withTitle(title)
                    .withActivateToolWindow(true)
                    .run();
            } catch (Exception e) {
                processHandler.startNotify();
            }
        });

        try {
            int exitCode = exitFuture.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
            return new ProcessResult(exitCode, output.toString(), false);
        } catch (java.util.concurrent.TimeoutException e) {
            processHandler.destroyProcess();
            return new ProcessResult(-1, output.toString(), true);
        }
    }
}
