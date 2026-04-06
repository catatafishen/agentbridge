package com.github.catatafishen.agentbridge.psi.tools;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
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
    protected String argumentsHash;

    protected Tool(Project project) {
        this.project = project;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args, @Nullable String argumentsHash) throws Exception {
        this.argumentsHash = argumentsHash;
        return execute(args);
    }

    // category() is inherited from ToolDefinition — subclasses must implement it

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{});
    }

    @Override
    public boolean hasExecutionHandler() {
        return true;
    }

    // ── Shared utilities ─────────────────────────────────────

    // ── Schema builder helpers ─────────────────────────────────

    protected static final String TYPE_STRING = "string";
    protected static final String TYPE_BOOLEAN = "boolean";
    protected static final String TYPE_INTEGER = "integer";
    protected static final String TYPE_ARRAY = "array";

    private static final String KEY_TYPE = "type";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_DEFAULT = "default";

    protected static com.google.gson.JsonObject schema(Object[][] params, String... required) {
        com.google.gson.JsonObject s = new com.google.gson.JsonObject();
        s.addProperty(KEY_TYPE, "object");
        com.google.gson.JsonObject props = new com.google.gson.JsonObject();
        for (Object[] p : params) {
            com.google.gson.JsonObject prop = new com.google.gson.JsonObject();
            prop.addProperty(KEY_TYPE, (String) p[1]);
            prop.addProperty(KEY_DESCRIPTION, (String) p[2]);
            if (p.length > 3 && p[3] != null) {
                prop.addProperty(KEY_DEFAULT, (String) p[3]);
            }
            props.add((String) p[0], prop);
        }
        s.add(KEY_PROPERTIES, props);
        com.google.gson.JsonArray req = new com.google.gson.JsonArray();
        for (String r : required) req.add(r);
        s.add(KEY_REQUIRED, req);
        return s;
    }

    protected static void addArrayItems(com.google.gson.JsonObject schema, String propName) {
        com.google.gson.JsonObject prop = schema.getAsJsonObject(KEY_PROPERTIES).getAsJsonObject(propName);
        com.google.gson.JsonObject items = new com.google.gson.JsonObject();
        items.addProperty(KEY_TYPE, TYPE_STRING);
        prop.add("items", items);
    }

    protected static void addDictProperty(com.google.gson.JsonObject schema, String name, String description) {
        com.google.gson.JsonObject prop = new com.google.gson.JsonObject();
        prop.addProperty(KEY_TYPE, "object");
        prop.addProperty(KEY_DESCRIPTION, description);
        prop.add(KEY_PROPERTIES, new com.google.gson.JsonObject());
        com.google.gson.JsonObject additionalProps = new com.google.gson.JsonObject();
        additionalProps.addProperty(KEY_TYPE, TYPE_STRING);
        prop.add("additionalProperties", additionalProps);
        schema.getAsJsonObject(KEY_PROPERTIES).add(name, prop);
    }

    protected VirtualFile resolveVirtualFile(String path) {
        return ToolUtils.resolveVirtualFile(project, path);
    }

    /**
     * Resolves a VirtualFile by path, falling back to a synchronous VFS refresh when
     * {@code findFileByPath} returns null.
     * This handles the case where IntelliJ's VFS cache is stale (e.g. a file was just
     * created by another tool and the file-watcher event hasn't fired yet).
     * <p>
     * Must be called from a background thread (not the EDT) and outside any ReadAction,
     * because {@link com.intellij.openapi.vfs.LocalFileSystem#refreshAndFindFileByPath} emits VFS events that require a write lock.
     */
    protected VirtualFile refreshAndFindVirtualFile(String path) {
        return ToolUtils.refreshAndFindVirtualFile(project, path);
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

        AgentTabTracker.getInstance(project).trackTab("Run", title);

        try {
            int exitCode = exitFuture.get(timeoutSec, TimeUnit.SECONDS);
            return new ProcessResult(exitCode, output.toString(), false);
        } catch (TimeoutException e) {
            processHandler.destroyProcess();
            return new ProcessResult(-1, output.toString(), true);
        }
    }

}
